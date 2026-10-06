package io.github.awsopstoolkit.performance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStackDynamoPerformanceCheckpointTest {
    @TempDir Path tempDir;

    @Test
    void createsAndRevalidatesCheckpointIdentity() throws Exception {
        Files.createDirectories(tempDir);

        LocalStackDynamoPerformanceTest.ensureProjectionCheckpointIdentity(
                tempDir, "lab-perf-v3-01-rich-uniform", 512);

        assertTrue(Files.exists(tempDir.resolve("checkpoint-config.json")));
        assertDoesNotThrow(
                () ->
                        LocalStackDynamoPerformanceTest.ensureProjectionCheckpointIdentity(
                                tempDir, "lab-perf-v3-01-rich-uniform", 512));
    }

    @Test
    void rejectsLegacyStateWithoutIdentityManifest() throws Exception {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("segment-0000.json"), "{}", StandardCharsets.UTF_8);

        assertThrows(
                IllegalStateException.class,
                () ->
                        LocalStackDynamoPerformanceTest.ensureProjectionCheckpointIdentity(
                                tempDir, "lab-perf-v3-01-rich-uniform", 512));
    }

    @Test
    void aggregatesScanCallLatencyPercentiles() {
        var metrics =
                LocalStackDynamoPerformanceTest.scanCallLatencyMetrics(
                        java.util.List.of(
                                new LocalStackDynamoPerformanceTest.ScanCallSample(
                                        1, 10_000_000, 100, 100),
                                new LocalStackDynamoPerformanceTest.ScanCallSample(
                                        2, 20_000_000, 100, 100),
                                new LocalStackDynamoPerformanceTest.ScanCallSample(
                                        3, 30_000_000, 100, 100),
                                new LocalStackDynamoPerformanceTest.ScanCallSample(
                                        4, 40_000_000, 100, 100)));

        assertEquals(4, metrics.get("samples"));
        assertEquals(25.0, (double) metrics.get("averageMillis"), 0.0001);
        assertEquals(20.0, (double) metrics.get("p50Millis"), 0.0001);
        assertEquals(40.0, (double) metrics.get("p95Millis"), 0.0001);
        assertEquals(40.0, (double) metrics.get("p99Millis"), 0.0001);
        assertEquals(40.0, (double) metrics.get("maxMillis"), 0.0001);
        assertEquals(4, metrics.get("slowestSegment"));
    }

    @Test
    void evaluatesLowDiskReserveSafely() {
        long tenGiB = 10L * 1024 * 1024 * 1024;

        assertTrue(LocalStackDynamoPerformanceTest.shouldCancelForLowDisk(tenGiB - 1, tenGiB));
        assertFalse(LocalStackDynamoPerformanceTest.shouldCancelForLowDisk(tenGiB, tenGiB));
        assertFalse(LocalStackDynamoPerformanceTest.shouldCancelForLowDisk(1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> LocalStackDynamoPerformanceTest.shouldCancelForLowDisk(-1, tenGiB));
    }

    @Test
    void rejectsIncompatibleIdentityManifest() throws Exception {
        Files.createDirectories(tempDir);
        LocalStackDynamoPerformanceTest.ensureProjectionCheckpointIdentity(
                tempDir, "lab-perf-v3-01-rich-uniform", 512);

        Path manifest = tempDir.resolve("checkpoint-config.json");
        String content = Files.readString(manifest, StandardCharsets.UTF_8);
        Files.writeString(
                manifest,
                content.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                StandardCharsets.UTF_8);

        assertThrows(
                IllegalStateException.class,
                () ->
                        LocalStackDynamoPerformanceTest.ensureProjectionCheckpointIdentity(
                                tempDir, "lab-perf-v3-01-rich-uniform", 512));
    }
}
