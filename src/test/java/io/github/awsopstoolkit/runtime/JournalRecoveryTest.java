package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalRecoveryTest {
    @TempDir Path directory;

    @Test
    void pageCursorAndCandidatesCommitTogetherAndReplaysDeduplicate() throws Exception {
        try (var journal = new SqliteJournal(directory)) {
            journal.create("job", "{}", "1", "identity", 10);
            var page =
                    new Workflow.Page(
                            List.of(new Workflow.Candidate("a", "{\"version\":1}")),
                            "cursor",
                            false);
            journal.page("job", 0, page, 2);
            journal.page("job", 0, page, 2);
            assertEquals(1, journal.count("job", null));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            journal.page(
                                    "job",
                                    0,
                                    new Workflow.Page(
                                            List.of(
                                                    new Workflow.Candidate("b", "{}"),
                                                    new Workflow.Candidate("c", "{}")),
                                            "wrong",
                                            true),
                                    2));
            assertEquals(1, journal.count("job", null));
            assertEquals("cursor", journal.cursor("job", 0).value());
        }
    }

    @Test
    void crashPreservesIntentAndRevokesApproval() throws Exception {
        long task;
        try (var journal = new SqliteJournal(directory)) {
            journal.create("job", "{}", "1", "identity", 10);
            journal.page(
                    "job",
                    0,
                    new Workflow.Page(List.of(new Workflow.Candidate("a", "{}")), "", true),
                    10);
            journal.seal("job");
            journal.approve("job", SqliteJournal.now() + 100, "change-test", false);
            task = journal.pending("job", 1).getFirst().sequence();
            journal.effect("job", task, "remote-write", "INTENT", "");
            journal.state("job", JobState.RUNNING);
        }
        try (var journal = new SqliteJournal(directory)) {
            assertEquals(JobState.INTERRUPTED, journal.job("job").state());
            assertEquals(0, journal.job("job").approvedUntil());
            assertEquals("INTENT", journal.effect("job", task, "remote-write").state());
            assertEquals(0, journal.count("job", "DONE"));
        }
    }

    @Test
    void planHashBindsIdentityRuleAndPayloadAndCallBudgetPersists() throws Exception {
        try (var journal = new SqliteJournal(directory)) {
            journal.create("a", "{}", "1", "identity", 10);
            journal.create("b", "{}", "2", "identity", 10);
            for (String id : List.of("a", "b"))
                journal.page(
                        id,
                        0,
                        new Workflow.Page(List.of(new Workflow.Candidate("key", "{}")), "", true),
                        1);
            assertNotEquals(journal.seal("a"), journal.seal("b"));
            journal.reserveCall("a", 1);
            assertThrows(JobStopped.class, () -> journal.reserveCall("a", 1));
            assertEquals(1, journal.job("a").calls());
        }
    }
}
