package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CoordinatorReconciliationTest {
    @TempDir Path directory;
    private final RuntimeProperties settings =
            RuntimeTestFixtures.runtime(
                    true, null, Set.of("table"), Set.of("principal"), 1000, 2, 10);

    @Test
    void unknownValueProducingEffectCannotBeConfirmedWithFabricatedResult() throws Exception {
        try (var journal = RuntimeTestFixtures.journal(directory);
                var validation = Validation.buildDefaultValidatorFactory()) {
            long task = unknown(journal);
            var coordinator =
                    new JobCoordinator(
                            journal,
                            settings,
                            null,
                            List.of(),
                            JsonMapper.builder().build(),
                            validation.getValidator());
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                coordinator.reconcile(
                                        "job",
                                        task,
                                        "multipart-create",
                                        true,
                                        "LAB operator evidence"));
                coordinator.reconcile(
                        "job",
                        task,
                        "multipart-create",
                        true,
                        "LAB observed upload ID",
                        "actual-upload-id");
                assertEquals(
                        "actual-upload-id",
                        journal.effect("job", task, "multipart-create").result());
            } finally {
                coordinator.close();
            }
        }
    }

    @Test
    void cancellationStillAllowsResolvingUnknownEffectWithoutRestartingJob() throws Exception {
        try (var journal = RuntimeTestFixtures.journal(directory);
                var validation = Validation.buildDefaultValidatorFactory()) {
            long task = unknown(journal);
            var coordinator =
                    new JobCoordinator(
                            journal,
                            settings,
                            null,
                            List.of(),
                            JsonMapper.builder().build(),
                            validation.getValidator());
            try {
                coordinator.stop("job", true);
                coordinator.reconcile(
                        "job", task, "multipart-create", false, "LAB confirmed no upload exists");
                assertEquals(JobState.CANCELLED, journal.job("job").state());
                assertEquals(
                        EffectState.NOT_SENT,
                        journal.effect("job", task, "multipart-create").state());
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                coordinator.approve(
                                        "job",
                                        journal.job("job").hash(),
                                        "LAB do not restart",
                                        false));
            } finally {
                coordinator.close();
            }
        }
    }

    private long unknown(SqliteJournal journal) throws Exception {
        journal.create("job", "{}", "1", "principal", 10);
        journal.page(
                "job",
                0,
                new Workflow.Page(List.of(new Workflow.Candidate("id", "{}")), "", true),
                1);
        journal.seal("job");
        long task = journal.pending("job", 1).getFirst().sequence();
        journal.effect("job", task, "multipart-create", EffectState.UNKNOWN, "");
        journal.state("job", JobState.RECONCILIATION_REQUIRED);
        return task;
    }
}
