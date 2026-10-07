package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.github.awsopstoolkit.configuration.JournalProperties;
import jakarta.validation.Validator;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JobApiReadModelTest {
    @TempDir Path directory;

    @Test
    void operationCatalogIsDeterministicAndDescribesWorkflowSafety() throws Exception {
        var coordinator =
                new JobCoordinator(
                        mock(SqliteJournal.class),
                        RuntimeTestFixtures.runtime(
                                false, null, Set.of("resource"), Set.of("principal"), 10, 2, 100),
                        mock(ExecutionPolicy.class),
                        List.of(
                                new CatalogWorkflow(
                                        "z-write", "3", true, 2, List.of("conditional-write")),
                                new CatalogWorkflow(
                                        "a-read", "7", false, 0, List.of("eventual-consistency"))),
                        JsonMapper.builder().build(),
                        mock(Validator.class));
        try {
            var catalog = coordinator.operationCatalog();

            assertEquals(
                    List.of("a-read", "z-write"),
                    catalog.stream().map(JobCoordinator.OperationCatalogEntry::type).toList());
            assertEquals("7", catalog.getFirst().version());
            assertFalse(catalog.getFirst().writes());
            assertEquals(0, catalog.getFirst().estimatedCallsPerCandidate());
            assertEquals(List.of("eventual-consistency"), catalog.getFirst().risks());
            assertTrue(catalog.getLast().writes());
            assertEquals(2, catalog.getLast().estimatedCallsPerCandidate());
        } finally {
            coordinator.close();
        }
    }

    @Test
    void errorPageUsesStableCursorWithoutMixingConflictAndErrorBudgets() throws Exception {
        var properties = new JournalProperties(Duration.ofSeconds(5), 2, 10);
        try (var journal = new SqliteJournal(directory, properties)) {
            journal.create("job", "{}", "1", "principal", 10);
            journal.page(
                    "job",
                    0,
                    new Workflow.Page(
                            List.of(
                                    new Workflow.Candidate("record-1", "{}"),
                                    new Workflow.Candidate("record-2", "{}"),
                                    new Workflow.Candidate("record-3", "{}"),
                                    new Workflow.Candidate("record-4", "{}")),
                            "",
                            true),
                    10);

            var tasks = journal.pending("job", 10);
            journal.finish("job", tasks.get(0).sequence(), "FAILED");
            journal.finish("job", tasks.get(1).sequence(), "CONFLICT");
            journal.finish("job", tasks.get(2).sequence(), "OK");
            journal.finish("job", tasks.get(3).sequence(), "ERROR");

            assertEquals(2, journal.errors("job"));
            assertEquals(1, journal.conflicts("job"));

            var first = journal.errorPageInfo("job", 0);
            assertEquals(2, first.items().size());
            assertTrue(first.hasMore());
            assertEquals(2, first.pageSize());
            assertEquals(0, first.after());
            assertEquals(first.items().getLast().sequence(), first.nextAfter());
            assertEquals(first.items(), journal.errorPage("job", 0));

            var firstView = JobController.errorView(first.items().getFirst());
            assertEquals("FAILED", firstView.get("outcome"));
            assertNotEquals("record-1", firstView.get("record"));

            var second = journal.errorPageInfo("job", first.nextAfter());
            assertEquals(1, second.items().size());
            assertFalse(second.hasMore());
            assertEquals("ERROR", second.items().getFirst().outcome());
            assertEquals(second.items().getFirst().sequence(), second.nextAfter());

            var empty = journal.errorPageInfo("job", second.nextAfter());
            assertTrue(empty.items().isEmpty());
            assertFalse(empty.hasMore());
            assertEquals(second.nextAfter(), empty.nextAfter());

            assertThrows(IllegalArgumentException.class, () -> journal.errorPageInfo("job", -1));
        }
    }

    private record CatalogWorkflow(
            String type,
            String version,
            boolean writes,
            int estimatedCallsPerCandidate,
            List<String> risks)
            implements Workflow {
        @Override
        public Set<String> resources(JsonNode parameters) {
            return Set.of("resource");
        }

        @Override
        public void validate(JobRequest request) {}

        @Override
        public Page plan(JobContext context, int segment, String cursor) {
            return new Page(List.of(), "", true);
        }

        @Override
        public String execute(JobContext context, SqliteJournal.Task task) {
            return "NOOP";
        }
    }
}
