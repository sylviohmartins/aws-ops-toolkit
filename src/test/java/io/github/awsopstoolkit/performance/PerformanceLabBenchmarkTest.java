package io.github.awsopstoolkit.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bounded local performance laboratory. It measures JVM/pipeline mechanics only and deliberately
 * does not claim DynamoDB, network, RCU/WCU or downstream throughput.
 */
@EnabledIfSystemProperty(named = "toolkit.performance.lab", matches = "true")
class PerformanceLabBenchmarkTest {
    private static final long HUNDRED_MILLION = 100_000_000L;
    private static final int DEFAULT_ITEM_BYTES =
            Integer.getInteger("toolkit.performance.item-bytes", 1024);
    private static final int DEFAULT_PAGE_SIZE =
            Integer.getInteger("toolkit.performance.page-size", 1000);
    private static final int DEFAULT_CONCURRENCY =
            Integer.getInteger("toolkit.performance.concurrency", 8);
    private static final int DEFAULT_SELECTIVITY_BP =
            Integer.getInteger("toolkit.performance.selectivity-bp", 1000);
    private static final int SAMPLE_BYTES = 64;
    private static final int MAX_WORK_ROUNDS = 128;
    private static final int MAX_LATENCY_SAMPLES = 20_000;
    private static final int LATENCY_SAMPLE_EVERY_PAGES = 32;

    @Test
    @org.junit.jupiter.api.Timeout(900)
    void measuresBoundedPipelineThroughOneHundredMillion() throws Exception {
        validateConfiguration();
        warmUp();

        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", 2);
        document.put("scope", "LOCAL_SYNTHETIC_NOT_AWS");
        document.put("generatedAt", java.time.Instant.now().toString());
        document.put("environment", environment());
        document.put("methodology", methodology());
        document.put("volumeSweep", volumeSweep());
        document.put("concurrencySweep", concurrencySweep());
        document.put("pageSizeSweep", pageSizeSweep());
        document.put("selectivitySweep", selectivitySweep());
        document.put("eightTableReference", eightTableReference());
        document.put("limitations", limitations());

        Path output = Path.of("target", "performance-lab", "performance-lab.json");
        Files.createDirectories(output.getParent());
        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValue(output, document);
        assertTrue(Files.size(output) > 0);
    }

    private static void validateConfiguration() {
        assertTrue(DEFAULT_ITEM_BYTES >= 1 && DEFAULT_ITEM_BYTES <= 400 * 1024);
        assertTrue(DEFAULT_PAGE_SIZE >= 1 && DEFAULT_PAGE_SIZE <= 10_000);
        assertTrue(DEFAULT_CONCURRENCY >= 1 && DEFAULT_CONCURRENCY <= 256);
        assertTrue(DEFAULT_SELECTIVITY_BP >= 0 && DEFAULT_SELECTIVITY_BP <= 10_000);
    }

    private static void warmUp() throws Exception {
        RunResult ignored =
                run(
                        new RunConfig(
                                "warmup",
                                2_000_000L,
                                DEFAULT_PAGE_SIZE,
                                DEFAULT_CONCURRENCY,
                                DEFAULT_SELECTIVITY_BP,
                                DEFAULT_ITEM_BYTES));
        assertEquals(2_000_000L, ignored.recordsScanned());
    }

    private static List<RunResult> volumeSweep() throws Exception {
        long[] volumes = {
            1_000_000L, 10_000_000L, 25_000_000L, 50_000_000L, 75_000_000L, HUNDRED_MILLION
        };
        var results = new ArrayList<RunResult>();
        for (long records : volumes) {
            results.add(
                    runRepeated(
                            new RunConfig(
                                    "volume-" + records,
                                    records,
                                    DEFAULT_PAGE_SIZE,
                                    DEFAULT_CONCURRENCY,
                                    DEFAULT_SELECTIVITY_BP,
                                    DEFAULT_ITEM_BYTES),
                            3));
        }
        assertEquals(HUNDRED_MILLION, results.getLast().recordsScanned());
        return List.copyOf(results);
    }

    private static List<RunResult> concurrencySweep() throws Exception {
        int[] levels = {1, 2, 4, 8, 16, 32, 64, 128};
        var results = new ArrayList<RunResult>();
        for (int concurrency : levels) {
            results.add(
                    runRepeated(
                            new RunConfig(
                                    "concurrency-" + concurrency,
                                    20_000_000L,
                                    DEFAULT_PAGE_SIZE,
                                    concurrency,
                                    DEFAULT_SELECTIVITY_BP,
                                    DEFAULT_ITEM_BYTES),
                            3));
        }
        return withComparisons(results);
    }

    private static List<RunResult> pageSizeSweep() throws Exception {
        int[] pageSizes = {100, 500, 1000, 5000};
        var results = new ArrayList<RunResult>();
        for (int pageSize : pageSizes) {
            results.add(
                    runRepeated(
                            new RunConfig(
                                    "page-" + pageSize,
                                    10_000_000L,
                                    pageSize,
                                    DEFAULT_CONCURRENCY,
                                    DEFAULT_SELECTIVITY_BP,
                                    DEFAULT_ITEM_BYTES),
                            3));
        }
        return withComparisons(results);
    }

    private static List<RunResult> selectivitySweep() throws Exception {
        int[] basisPoints = {10_000, 5_000, 1_000, 100, 10, 1};
        var results = new ArrayList<RunResult>();
        for (int selectivity : basisPoints) {
            results.add(
                    run(
                            new RunConfig(
                                    "selectivity-" + selectivity,
                                    5_000_000L,
                                    DEFAULT_PAGE_SIZE,
                                    DEFAULT_CONCURRENCY,
                                    selectivity,
                                    DEFAULT_ITEM_BYTES)));
        }
        return List.copyOf(results);
    }

    private static List<RunResult> eightTableReference() throws Exception {
        var results = new ArrayList<RunResult>();
        for (int table = 1; table <= 8; table++) {
            results.add(
                    runRepeated(
                            new RunConfig(
                                    "synthetic-table-" + table,
                                    5_000_000L,
                                    DEFAULT_PAGE_SIZE,
                                    DEFAULT_CONCURRENCY,
                                    DEFAULT_SELECTIVITY_BP,
                                    DEFAULT_ITEM_BYTES),
                            3));
        }
        return List.copyOf(results);
    }

    private static RunResult runRepeated(RunConfig config, int repetitions) throws Exception {
        var runs = new ArrayList<RunResult>(repetitions);
        for (int iteration = 0; iteration < repetitions; iteration++) runs.add(run(config));
        runs.sort(Comparator.comparingDouble(RunResult::recordsPerSecond));
        RunResult median = runs.get(runs.size() / 2);
        double min = runs.getFirst().recordsPerSecond();
        double max = runs.getLast().recordsPerSecond();
        double spread = ((max - min) / median.recordsPerSecond()) * 100.0;
        return median.withRepeatability(repetitions, spread);
    }

    private static RunResult run(RunConfig config) throws Exception {
        var runtime = Runtime.getRuntime();
        var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        var next = new AtomicLong();
        var scanned = new LongAdder();
        var matched = new LongAdder();
        var pages = new LongAdder();
        var checksum = new LongAdder();
        var heapPeak = new AtomicLong(usedHeap(runtime));
        var latencies = new LatencySamples(MAX_LATENCY_SAMPLES);
        var gcBefore = gcSample();
        long heapBefore = usedHeap(runtime);
        long cpuBefore = os.getProcessCpuTime();
        long started = System.nanoTime();

        try (var sampler = Executors.newSingleThreadScheduledExecutor();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            sampler.scheduleAtFixedRate(
                    () -> heapPeak.accumulateAndGet(usedHeap(runtime), Math::max),
                    0,
                    25,
                    TimeUnit.MILLISECONDS);
            var futures = new ArrayList<Future<?>>(config.concurrency());
            for (int worker = 0; worker < config.concurrency(); worker++) {
                futures.add(
                        executor.submit(
                                () ->
                                        consume(
                                                config, next, scanned, matched, pages, checksum,
                                                latencies)));
            }
            for (var future : futures) future.get();
            sampler.shutdownNow();
        }

        long elapsed = System.nanoTime() - started;
        long cpuNanos = Math.max(0, os.getProcessCpuTime() - cpuBefore);
        long heapAfter = usedHeap(runtime);
        heapPeak.accumulateAndGet(heapAfter, Math::max);
        var gcAfter = gcSample();
        long scannedCount = scanned.sum();
        long matchedCount = matched.sum();
        double seconds = elapsed / 1_000_000_000.0;
        double recordsPerSecond = scannedCount / seconds;
        long estimatedBytes = scannedCount * (long) config.itemBytes();
        double mbPerSecond = estimatedBytes / 1024.0 / 1024.0 / seconds;
        double cpuPercent =
                cpuNanos
                        * 100.0
                        / (Math.max(1L, elapsed) * Math.max(1, runtime.availableProcessors()));
        double projectionSeconds = HUNDRED_MILLION / recordsPerSecond;
        var latency = latencies.snapshotMillis();

        assertEquals(config.records(), scannedCount);
        assertNotEquals(0L, checksum.sum(), "synthetic work must remain observable");
        return new RunResult(
                config.name(),
                config.records(),
                scannedCount,
                matchedCount,
                pages.sum(),
                config.pageSize(),
                config.concurrency(),
                config.selectivityBasisPoints(),
                config.itemBytes(),
                workRounds(config.itemBytes()),
                elapsed / 1_000_000.0,
                recordsPerSecond,
                mbPerSecond,
                estimatedBytes,
                cpuPercent,
                heapBefore,
                heapPeak.get(),
                heapAfter,
                gcAfter.collections() - gcBefore.collections(),
                gcAfter.millis() - gcBefore.millis(),
                latency.samples(),
                latency.p50(),
                latency.p95(),
                latency.p99(),
                projectionSeconds,
                checksum.sum(),
                1,
                0.0,
                "MEASURED_LOCAL_SYNTHETIC",
                bottleneckHint(cpuPercent),
                null,
                null);
    }

    private static void consume(
            RunConfig config,
            AtomicLong next,
            LongAdder scanned,
            LongAdder matched,
            LongAdder pages,
            LongAdder checksum,
            LatencySamples latencies) {
        long localScanned = 0;
        long localMatched = 0;
        long localPages = 0;
        long localChecksum = 0x6A09E667F3BCC909L;

        while (true) {
            long pageStarted = System.nanoTime();
            long start = next.getAndAdd(config.pageSize());
            if (start >= config.records()) break;
            long end = Math.min(config.records(), start + config.pageSize());

            for (long record = start; record < end; record++) {
                long mapped = syntheticMap(record, config.itemBytes());
                localChecksum = Long.rotateLeft(localChecksum ^ mapped, 11) + record;
                localScanned++;
                if (matches(mapped, config.selectivityBasisPoints())) localMatched++;
            }

            localPages++;
            if (localPages % LATENCY_SAMPLE_EVERY_PAGES == 0) {
                latencies.record(System.nanoTime() - pageStarted);
            }
        }
        scanned.add(localScanned);
        matched.add(localMatched);
        pages.add(localPages);
        checksum.add(localChecksum);
    }

    private static long syntheticMap(long record, int itemBytes) {
        long value = record ^ 0x9E3779B97F4A7C15L ^ itemBytes;
        int rounds = workRounds(itemBytes);
        for (int round = 0; round < rounds; round++) {
            value ^= value >>> 30;
            value *= 0xBF58476D1CE4E5B9L;
            value ^= value >>> 27;
            value *= 0x94D049BB133111EBL;
            value ^= ((long) round << 32) ^ itemBytes;
            value = Long.rotateLeft(value, 17);
        }
        return value;
    }

    private static int workRounds(int itemBytes) {
        return Math.min(
                MAX_WORK_ROUNDS, Math.max(1, (itemBytes + SAMPLE_BYTES - 1) / SAMPLE_BYTES));
    }

    private static boolean matches(long value, int basisPoints) {
        if (basisPoints == 10_000) return true;
        if (basisPoints == 0) return false;
        return Long.remainderUnsigned(value, 10_000) < basisPoints;
    }

    private static List<RunResult> withComparisons(List<RunResult> rows) {
        if (rows.isEmpty()) return List.of();
        var compared = new ArrayList<RunResult>(rows.size());
        RunResult previous = null;
        for (RunResult row : rows) {
            if (previous == null) {
                compared.add(row.withComparison(0.0, "BASELINE"));
            } else {
                double delta = (row.recordsPerSecond() / previous.recordsPerSecond() - 1.0) * 100.0;
                String classification =
                        delta > 5.0 ? "IMPROVEMENT" : delta < -5.0 ? "REGRESSION" : "NEUTRAL";
                compared.add(row.withComparison(delta, classification));
            }
            previous = row;
        }
        return List.copyOf(compared);
    }

    private static String bottleneckHint(double cpuPercent) {
        if (cpuPercent >= 75.0) return "LOCAL_CPU_OR_MAPPING";
        return "LOCAL_PIPELINE_OR_SCHEDULING";
    }

    private static Map<String, Object> methodology() {
        var values = new LinkedHashMap<String, Object>();
        values.put("warmupRecords", 2_000_000);
        values.put("payloadModel", "dependent checksum work, one round per 64 modeled bytes");
        values.put("bounded", true);
        values.put("materializesAllRecords", false);
        values.put("latencySamplingEveryPages", LATENCY_SAMPLE_EVERY_PAGES);
        values.put("maxLatencySamples", MAX_LATENCY_SAMPLES);
        values.put("repetitionsForVolumeConcurrencyPageAndEightTables", 3);
        values.put("comparisonBaseline", "previous row of each progressive sweep");
        return Collections.unmodifiableMap(values);
    }

    private static Map<String, Object> environment() {
        var runtime = Runtime.getRuntime();
        var values = new LinkedHashMap<String, Object>();
        values.put("commit", System.getProperty("toolkit.performance.commit", "UNKNOWN"));
        values.put("javaVersion", System.getProperty("java.version"));
        values.put("javaVendor", System.getProperty("java.vendor"));
        values.put("osName", System.getProperty("os.name"));
        values.put("osVersion", System.getProperty("os.version"));
        values.put("availableProcessors", runtime.availableProcessors());
        values.put("maxHeapBytes", runtime.maxMemory());
        values.put(
                "physicalMemoryBytes",
                ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                        .getTotalMemorySize());
        return Collections.unmodifiableMap(values);
    }

    private static List<String> limitations() {
        return List.of(
                "No AWS request is issued by this benchmark.",
                "Modeled bytes drive deterministic CPU work; they are not measured network bytes.",
                "Synthetic mapping/checksum is intentionally more substantial than an empty loop but is not a real DTO/Jackson/AWS SDK decode.",
                "Synthetic table slots validate repeatability and boundedness; they are not measurements of eight real tables.",
                "DynamoDB partitions, RCU/WCU, throttling, retries, indexes, connection pools and network latency require DEV/HML.",
                "Projection100mSeconds extrapolates only the measured local synthetic rate.");
    }

    private static long usedHeap(Runtime runtime) {
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static GcSample gcSample() {
        long collections = 0;
        long millis = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionCount() >= 0) collections += collector.getCollectionCount();
            if (collector.getCollectionTime() >= 0) millis += collector.getCollectionTime();
        }
        return new GcSample(collections, millis);
    }

    private static final class LatencySamples {
        private final long[] nanos;
        private final AtomicInteger cursor = new AtomicInteger();

        private LatencySamples(int capacity) {
            nanos = new long[capacity];
        }

        void record(long value) {
            int index = cursor.getAndIncrement();
            if (index < nanos.length) nanos[index] = value;
        }

        LatencySnapshot snapshotMillis() {
            int size = Math.min(cursor.get(), nanos.length);
            if (size == 0) return new LatencySnapshot(0, 0, 0, 0);
            long[] copy = Arrays.copyOf(nanos, size);
            Arrays.sort(copy);
            return new LatencySnapshot(
                    size,
                    percentileMillis(copy, 0.50),
                    percentileMillis(copy, 0.95),
                    percentileMillis(copy, 0.99));
        }

        private static double percentileMillis(long[] sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1);
            return sorted[index] / 1_000_000.0;
        }
    }

    private record LatencySnapshot(int samples, double p50, double p95, double p99) {}

    private record RunConfig(
            String name,
            long records,
            int pageSize,
            int concurrency,
            int selectivityBasisPoints,
            int itemBytes) {}

    private record GcSample(long collections, long millis) {}

    private record RunResult(
            String name,
            long recordsTarget,
            long recordsScanned,
            long recordsMatched,
            long pagesRead,
            int pageSize,
            int concurrency,
            int selectivityBasisPoints,
            int itemBytes,
            int syntheticWorkRounds,
            double elapsedMillis,
            double recordsPerSecond,
            double modeledMegabytesPerSecond,
            long modeledBytesRead,
            double estimatedMachineCpuPercent,
            long heapBeforeBytes,
            long heapObservedPeakBytes,
            long heapAfterBytes,
            long gcCollections,
            long gcCollectionMillis,
            int latencySamples,
            double p50PageMillis,
            double p95PageMillis,
            double p99PageMillis,
            double projection100mSeconds,
            long processingChecksum,
            int repetitions,
            double throughputSpreadPercent,
            String measurementKind,
            String bottleneckHint,
            Double throughputDeltaPercent,
            String comparison) {
        RunResult withComparison(double delta, String classification) {
            return new RunResult(
                    name,
                    recordsTarget,
                    recordsScanned,
                    recordsMatched,
                    pagesRead,
                    pageSize,
                    concurrency,
                    selectivityBasisPoints,
                    itemBytes,
                    syntheticWorkRounds,
                    elapsedMillis,
                    recordsPerSecond,
                    modeledMegabytesPerSecond,
                    modeledBytesRead,
                    estimatedMachineCpuPercent,
                    heapBeforeBytes,
                    heapObservedPeakBytes,
                    heapAfterBytes,
                    gcCollections,
                    gcCollectionMillis,
                    latencySamples,
                    p50PageMillis,
                    p95PageMillis,
                    p99PageMillis,
                    projection100mSeconds,
                    processingChecksum,
                    repetitions,
                    throughputSpreadPercent,
                    measurementKind,
                    bottleneckHint,
                    delta,
                    classification);
        }

        RunResult withRepeatability(int runCount, double spreadPercent) {
            return new RunResult(
                    name,
                    recordsTarget,
                    recordsScanned,
                    recordsMatched,
                    pagesRead,
                    pageSize,
                    concurrency,
                    selectivityBasisPoints,
                    itemBytes,
                    syntheticWorkRounds,
                    elapsedMillis,
                    recordsPerSecond,
                    modeledMegabytesPerSecond,
                    modeledBytesRead,
                    estimatedMachineCpuPercent,
                    heapBeforeBytes,
                    heapObservedPeakBytes,
                    heapAfterBytes,
                    gcCollections,
                    gcCollectionMillis,
                    latencySamples,
                    p50PageMillis,
                    p95PageMillis,
                    p99PageMillis,
                    projection100mSeconds,
                    processingChecksum,
                    runCount,
                    spreadPercent,
                    measurementKind,
                    bottleneckHint,
                    throughputDeltaPercent,
                    comparison);
        }
    }
}
