package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfSystemProperty(named = "toolkit.benchmark", matches = "true")
class LedgerBenchmarkTest {
    private static final int RECORDS = Integer.getInteger("toolkit.benchmark.records", 1_000_000);
    private static final int PAGE_SIZE = Integer.getInteger("toolkit.benchmark.page-size", 1_000);
    private static final long MAX_HEAP_GROWTH_BYTES =
            Long.getLong("toolkit.benchmark.max-heap-growth-bytes", 192L * 1024 * 1024);

    @TempDir Path directory;

    @Test
    @org.junit.jupiter.api.Timeout(1800)
    void recordsConfiguredCandidateLedgerMeasurements() throws Exception {
        assertTrue(RECORDS >= 1 && RECORDS <= 30_000_000);
        assertTrue(PAGE_SIZE >= 1 && PAGE_SIZE <= 10_000);
        var json = JsonMapper.builder().build();
        var runtime = Runtime.getRuntime();
        System.gc();
        long heapBefore = usedHeap(runtime);
        long peakHeap = heapBefore;
        var gcBefore = gcSample();
        long started = System.nanoTime();
        long inserted;
        long reported;
        long insertNanos;
        long sealNanos;
        long reportNanos;
        long diskBytesWhileOpen;
        String planHash;

        try (var journal = new SqliteJournal(directory)) {
            journal.create("benchmark", "{}", "1", "benchmark-identity", 1000);

            long insertStarted = System.nanoTime();
            for (int offset = 0; offset < RECORDS; offset += PAGE_SIZE) {
                var candidates = new ArrayList<Workflow.Candidate>(PAGE_SIZE);
                int end = Math.min(RECORDS, offset + PAGE_SIZE);
                for (int index = offset; index < end; index++) {
                    candidates.add(new Workflow.Candidate("candidate-" + index, "{}"));
                }
                journal.page(
                        "benchmark",
                        0,
                        new Workflow.Page(candidates, Integer.toString(end), end == RECORDS),
                        RECORDS);
                peakHeap = Math.max(peakHeap, usedHeap(runtime));
            }
            insertNanos = System.nanoTime() - insertStarted;
            inserted = journal.count("benchmark", null);

            long sealStarted = System.nanoTime();
            planHash = journal.seal("benchmark");
            sealNanos = System.nanoTime() - sealStarted;
            peakHeap = Math.max(peakHeap, usedHeap(runtime));

            var reportCount = new AtomicLong();
            long reportStarted = System.nanoTime();
            journal.report("benchmark", row -> reportCount.incrementAndGet());
            reportNanos = System.nanoTime() - reportStarted;
            reported = reportCount.get();
            peakHeap = Math.max(peakHeap, usedHeap(runtime));
            diskBytesWhileOpen = diskBytes(directory);
        }

        long totalNanos = System.nanoTime() - started;
        long diskBytesAfterClose = diskBytes(directory);
        var gcAfter = gcSample();
        long heapAfter = usedHeap(runtime);
        long heapGrowth = Math.max(0, peakHeap - heapBefore);

        var benchmark = new LinkedHashMap<String, Object>();
        benchmark.put("name", "sqlite-journal-configured-candidates");
        benchmark.put("recordsTarget", RECORDS);
        benchmark.put("pageSize", PAGE_SIZE);
        benchmark.put("pageCount", (RECORDS + PAGE_SIZE - 1L) / PAGE_SIZE);
        benchmark.put("maxHeapGrowthBytes", MAX_HEAP_GROWTH_BYTES);

        var result = new LinkedHashMap<String, Object>();
        result.put("recordsInserted", inserted);
        result.put("recordsReported", reported);
        result.put("planHash", planHash);
        result.put("insertNanos", insertNanos);
        result.put("sealNanos", sealNanos);
        result.put("reportNanos", reportNanos);
        result.put("totalNanos", totalNanos);
        result.put("gcCollections", gcAfter.collections() - gcBefore.collections());
        result.put("gcCollectionMillis", gcAfter.millis() - gcBefore.millis());
        result.put("gcCollectorsReporting", gcAfter.collectorsReporting());
        result.put("heapBeforeBytes", heapBefore);
        result.put("heapPeakBytes", peakHeap);
        result.put("heapAfterBytes", heapAfter);
        result.put("heapGrowthBytes", heapGrowth);
        result.put("diskBytesWhileOpen", diskBytesWhileOpen);
        result.put("diskBytesAfterClose", diskBytesAfterClose);

        var environment = new LinkedHashMap<String, Object>();
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("javaVendor", System.getProperty("java.vendor"));
        environment.put("osName", System.getProperty("os.name"));
        environment.put("availableProcessors", runtime.availableProcessors());
        environment.put("maxHeapBytes", runtime.maxMemory());

        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", 1);
        document.put("benchmark", benchmark);
        document.put("result", result);
        document.put("environment", environment);

        Path output = Path.of("target", "benchmark", "ledger-" + RECORDS + ".json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output, document);
        json.writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("target", "benchmark", "result.json"), document);

        assertEquals(RECORDS, inserted);
        assertEquals(RECORDS, reported);
        assertFalse(planHash.isBlank());
        assertTrue(heapGrowth <= MAX_HEAP_GROWTH_BYTES, "heap growth exceeded benchmark bound");
        assertTrue(Files.size(output) > 0);
    }

    private static long usedHeap(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long diskBytes(Path path) throws Exception {
        long bytes = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) bytes += Files.size(file);
            }
        }
        return bytes;
    }

    private static GcSample gcSample() {
        long collections = 0;
        long millis = 0;
        int reporting = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionCount() >= 0 && collector.getCollectionTime() >= 0) {
                collections += collector.getCollectionCount();
                millis += collector.getCollectionTime();
                reporting++;
            }
        }
        return new GcSample(collections, millis, reporting);
    }

    private record GcSample(long collections, long millis, int collectorsReporting) {}
}
