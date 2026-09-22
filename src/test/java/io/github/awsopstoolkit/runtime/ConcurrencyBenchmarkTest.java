package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

/** Local virtual-thread/limiter sweep. It is deliberately not an AWS service benchmark. */
@EnabledIfSystemProperty(named = "toolkit.benchmark", matches = "true")
class ConcurrencyBenchmarkTest {
    private static final int[] LEVELS = {1, 4, 8, 16, 32, 64, 128, 256};
    private static final int TASKS = Integer.getInteger("toolkit.benchmark.sweep.tasks", 4096);
    private static final int SERVICE_MILLIS =
            Integer.getInteger("toolkit.benchmark.sweep.service-millis", 2);

    @Test
    @org.junit.jupiter.api.Timeout(300)
    void recordsBoundedConcurrencySweep() throws Exception {
        assertTrue(TASKS >= 256 && TASKS <= 100_000);
        assertTrue(SERVICE_MILLIS >= 1 && SERVICE_MILLIS <= 1_000);
        var rows = new ArrayList<Map<String, Object>>();
        for (int concurrency : LEVELS) rows.add(run(concurrency));

        var document = new LinkedHashMap<String, Object>();
        document.put("schemaVersion", 1);
        document.put("name", "local-virtual-thread-dispatch-sweep");
        document.put(
                "scope", "local limiter + virtual threads + synthetic fixed-latency work; not AWS");
        document.put("tasksPerLevel", TASKS);
        document.put("serviceMillis", SERVICE_MILLIS);
        document.put("results", rows);
        document.put("javaVersion", System.getProperty("java.version"));
        document.put("availableProcessors", Runtime.getRuntime().availableProcessors());

        Path output = Path.of("target", "benchmark", "concurrency-sweep.json");
        Files.createDirectories(output.getParent());
        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValue(output, document);
        assertTrue(Files.size(output) > 0);
    }

    private static Map<String, Object> run(int concurrency) throws Exception {
        var limiter = new DispatchLimiter(concurrency, 1_000_000);
        long[] latency = new long[TASKS];
        var runtime = Runtime.getRuntime();
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        threads.resetPeakThreadCount();
        var os =
                (com.sun.management.OperatingSystemMXBean)
                        java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore = os.getProcessCpuTime();
        long heapBefore = runtime.totalMemory() - runtime.freeMemory();
        long started = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = new ExecutorCompletionService<Void>(executor);
            for (int index = 0; index < TASKS; index++) {
                final int slot = index;
                completion.submit(
                        () -> {
                            long taskStarted = System.nanoTime();
                            limiter.acquire(1_000_000);
                            try {
                                Thread.sleep(SERVICE_MILLIS);
                                limiter.healthy();
                            } finally {
                                limiter.release();
                            }
                            latency[slot] = System.nanoTime() - taskStarted;
                            return null;
                        });
            }
            for (int i = 0; i < TASKS; i++) completion.take().get();
        }
        long elapsed = System.nanoTime() - started;
        long cpuNanos = Math.max(0, os.getProcessCpuTime() - cpuBefore);
        long heapAfter = runtime.totalMemory() - runtime.freeMemory();
        int peakPlatformThreads = threads.getPeakThreadCount();
        Arrays.sort(latency);
        long p50 = latency[(int) Math.ceil(TASKS * 0.50) - 1];
        long p95 = latency[(int) Math.ceil(TASKS * 0.95) - 1];
        long p99 = latency[(int) Math.ceil(TASKS * 0.99) - 1];
        double seconds = elapsed / 1_000_000_000.0;

        var row = new LinkedHashMap<String, Object>();
        row.put("concurrency", concurrency);
        row.put("tasks", TASKS);
        row.put("elapsedMillis", elapsed / 1_000_000.0);
        row.put("tasksPerSecond", TASKS / seconds);
        row.put("p50Millis", p50 / 1_000_000.0);
        row.put("p95Millis", p95 / 1_000_000.0);
        row.put("p99Millis", p99 / 1_000_000.0);
        row.put("processCpuMillis", cpuNanos / 1_000_000.0);
        row.put(
                "estimatedMachineCpuPercent",
                elapsed == 0
                        ? 0.0
                        : cpuNanos
                                * 100.0
                                / (elapsed * Math.max(1, runtime.availableProcessors())));
        row.put("heapBeforeBytes", heapBefore);
        row.put("heapAfterBytes", heapAfter);
        row.put("peakPlatformThreadsObserved", peakPlatformThreads);
        row.put("virtualTasksSubmitted", TASKS);
        row.put("syntheticRetries", 0);
        row.put("syntheticThrottles", 0);
        row.put("finalAdaptiveConcurrency", limiter.currentConcurrency());
        row.put("finalAdaptiveRate", limiter.currentRate());
        assertEquals(concurrency, limiter.currentConcurrency());
        return Collections.unmodifiableMap(row);
    }
}
