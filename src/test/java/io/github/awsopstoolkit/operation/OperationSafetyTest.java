package io.github.awsopstoolkit.operation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.awsopstoolkit.checkpoint.FileCheckpointStore;
import io.github.awsopstoolkit.configuration.ToolkitProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class OperationSafetyTest {
    @TempDir Path directory;
    private final JsonMapper mapper = JsonMapper.builder().build();

    private ToolkitProperties properties(
            ToolkitProperties.Environment environment, boolean writes) {
        return new ToolkitProperties(
                environment,
                directory,
                1048576,
                1,
                10,
                writes,
                "test-only-local-token-not-for-deployment",
                new ToolkitProperties.Aws(
                        false, "us-east-1", "", "", 2, 3000, 2000, 25000, 30000, 30000, 35000));
    }

    @Test
    void executeModeAndProductionAreRejectedBeforeScheduling() throws Exception {
        var properties = properties(ToolkitProperties.Environment.LOCAL, false);
        try (var store = new FileCheckpointStore(properties, mapper)) {
            var preflight = new PreflightCheckService(properties, store);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> preflight.check(OperationMode.EXECUTE, 10, 0));
            var production =
                    new PreflightCheckService(
                            properties(ToolkitProperties.Environment.PROD, false), store);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> production.check(OperationMode.DRY_RUN, 10, 0));
            var writeGateEnabled =
                    new PreflightCheckService(
                            properties(ToolkitProperties.Environment.LOCAL, true), store);
            assertDoesNotThrow(() -> writeGateEnabled.check(OperationMode.DRY_RUN, 10, 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> writeGateEnabled.check(OperationMode.EXECUTE, 10, 0));
        }
    }

    @Test
    void resumeReplacesUncommittedChunkAndNeverDuplicatesReportRows() throws Exception {
        var properties = properties(ToolkitProperties.Environment.LOCAL, false);
        try (var store = new FileCheckpointStore(properties, mapper);
                var validation = Validation.buildDefaultValidatorFactory()) {
            var meters = new SimpleMeterRegistry();
            var id = UUID.randomUUID();
            var now = Instant.now();
            var parameters = mapper.valueToTree(new SyntheticInventoryOperation.Input(25, 0));
            // Simulate a process dying after report write, before checkpoint publication.
            store.saveChunk(id, 0, "uncommitted data must disappear\r\n");
            store.save(
                    new OperationSnapshot(
                            1,
                            id,
                            "synthetic-inventory",
                            "1",
                            OperationMode.DRY_RUN,
                            parameters,
                            OperationStatus.RUNNING,
                            0,
                            25,
                            10,
                            now,
                            now,
                            null));
            var executor =
                    new OperationExecutor(
                            new OperationRegistry(List.of(new SyntheticInventoryOperation())),
                            store,
                            new PreflightCheckService(properties, store),
                            properties,
                            mapper,
                            validation.getValidator(),
                            meters);
            try {
                assertEquals(OperationStatus.INTERRUPTED, executor.status(id).status());
                executor.resume(id);
                OperationSnapshot result = awaitTerminal(executor, id);
                assertEquals(OperationStatus.COMPLETED, result.status());
                assertEquals(25, result.cursor());
                var output = new ByteArrayOutputStream();
                store.report(result, output);
                String csv = output.toString(java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(26, csv.lines().count());
                assertEquals(
                        1, csv.lines().filter(line -> line.startsWith("\"synthetic-0\"")).count());
                assertFalse(csv.contains("uncommitted"));
                assertThrows(IllegalStateException.class, () -> executor.resume(id));
            } finally {
                executor.shutdown();
                meters.close();
            }
        }
    }

    @Test
    void diskBudgetFailsEarly() throws Exception {
        var properties = properties(ToolkitProperties.Environment.LOCAL, false);
        try (var store = new FileCheckpointStore(properties, mapper)) {
            var preflight = new PreflightCheckService(properties, store);
            assertThrows(
                    IllegalStateException.class,
                    () -> preflight.check(OperationMode.DRY_RUN, 10, Long.MAX_VALUE / 2));
        }
    }

    @Test
    void unknownOrInvalidParametersAreRejected() throws Exception {
        var properties = properties(ToolkitProperties.Environment.LOCAL, false);
        try (var store = new FileCheckpointStore(properties, mapper);
                var validation = Validation.buildDefaultValidatorFactory()) {
            var meters = new SimpleMeterRegistry();
            var executor =
                    new OperationExecutor(
                            new OperationRegistry(List.of(new SyntheticInventoryOperation())),
                            store,
                            new PreflightCheckService(properties, store),
                            properties,
                            mapper,
                            validation.getValidator(),
                            meters);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                executor.start(
                                        "unknown",
                                        new OperationRequest(
                                                OperationMode.DRY_RUN, mapper.createObjectNode())));
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                executor.start(
                                        "synthetic-inventory",
                                        new OperationRequest(
                                                OperationMode.DRY_RUN,
                                                mapper.valueToTree(
                                                        new SyntheticInventoryOperation.Input(
                                                                -1, 0)))));
            } finally {
                executor.shutdown();
                meters.close();
            }
        }
    }

    private OperationSnapshot awaitTerminal(OperationExecutor executor, UUID id) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        OperationSnapshot snapshot;
        do {
            snapshot = executor.status(id);
            if (!snapshot.status().active()) return snapshot;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("Operation did not complete before test deadline");
        return snapshot;
    }

    @Test
    void rejectsResumeWithChangedRuleVersion() throws Exception {
        var properties = properties(ToolkitProperties.Environment.LOCAL, false);
        try (var store = new FileCheckpointStore(properties, mapper);
                var validation = Validation.buildDefaultValidatorFactory()) {
            var meters = new SimpleMeterRegistry();
            var executor =
                    new OperationExecutor(
                            new OperationRegistry(List.of(new SyntheticInventoryOperation())),
                            store,
                            new PreflightCheckService(properties, store),
                            properties,
                            mapper,
                            validation.getValidator(),
                            meters);
            var now = Instant.now();
            var id = UUID.randomUUID();
            var parameters = mapper.valueToTree(new SyntheticInventoryOperation.Input(25, 0));
            store.save(
                    new OperationSnapshot(
                            1,
                            id,
                            "synthetic-inventory",
                            "obsolete-rule",
                            OperationMode.DRY_RUN,
                            parameters,
                            OperationStatus.PAUSED,
                            0,
                            25,
                            10,
                            now,
                            now,
                            null));
            try {
                assertThrows(IllegalStateException.class, () -> executor.resume(id));
                assertEquals(OperationStatus.PAUSED, store.load(id).status());
            } finally {
                executor.shutdown();
                meters.close();
            }
        }
    }

    @Test
    void incompleteRuleCannotReportCompleted() throws Exception {
        var properties = properties(ToolkitProperties.Environment.LOCAL, false);
        OperationDefinition<SyntheticInventoryOperation.Input> incomplete =
                new OperationDefinition<>() {
                    public String type() {
                        return "incomplete";
                    }

                    public String version() {
                        return "1";
                    }

                    public Class<SyntheticInventoryOperation.Input> inputType() {
                        return SyntheticInventoryOperation.Input.class;
                    }

                    public long total(SyntheticInventoryOperation.Input input) {
                        return input.records();
                    }

                    public void execute(
                            SyntheticInventoryOperation.Input input, OperationContext context) {}
                };
        try (var store = new FileCheckpointStore(properties, mapper);
                var validation = Validation.buildDefaultValidatorFactory()) {
            var meters = new SimpleMeterRegistry();
            var executor =
                    new OperationExecutor(
                            new OperationRegistry(List.of(incomplete)),
                            store,
                            new PreflightCheckService(properties, store),
                            properties,
                            mapper,
                            validation.getValidator(),
                            meters);
            try {
                var job =
                        executor.start(
                                "incomplete",
                                new OperationRequest(
                                        OperationMode.DRY_RUN,
                                        mapper.valueToTree(
                                                new SyntheticInventoryOperation.Input(10, 0))));
                assertEquals(
                        OperationStatus.FAILED,
                        awaitTerminal(executor, job.operationId()).status());
                assertEquals(0, store.load(job.operationId()).cursor());
            } finally {
                executor.shutdown();
                meters.close();
            }
        }
    }

    @Test
    void immediatePauseResumeNeverLosesWorkerOwnership() throws Exception {
        var base = properties(ToolkitProperties.Environment.LOCAL, false);
        var properties =
                new ToolkitProperties(
                        base.environment(),
                        directory,
                        base.minimumFreeBytes(),
                        2,
                        10,
                        false,
                        base.localToken(),
                        base.aws());
        try (var store = new FileCheckpointStore(properties, mapper);
                var validation = Validation.buildDefaultValidatorFactory()) {
            var meters = new SimpleMeterRegistry();
            var executor =
                    new OperationExecutor(
                            new OperationRegistry(List.of(new SyntheticInventoryOperation())),
                            store,
                            new PreflightCheckService(properties, store),
                            properties,
                            mapper,
                            validation.getValidator(),
                            meters);
            try {
                var job =
                        executor.start(
                                "synthetic-inventory",
                                new OperationRequest(
                                        OperationMode.DRY_RUN,
                                        mapper.valueToTree(
                                                new SyntheticInventoryOperation.Input(100000, 5))));
                for (int cycle = 0; cycle < 20; cycle++) {
                    executor.requestStop(job.operationId(), false);
                    assertEquals(
                            OperationStatus.PAUSED,
                            awaitTerminal(executor, job.operationId()).status());
                    executor.resume(job.operationId());
                    assertTrue(executor.status(job.operationId()).status().active());
                }
                executor.requestStop(job.operationId(), true);
                assertEquals(
                        OperationStatus.CANCELLED,
                        awaitTerminal(executor, job.operationId()).status());
            } finally {
                executor.shutdown();
                meters.close();
            }
        }
    }
}
