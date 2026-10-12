package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteStorageFailureRecoveryTest {
    @TempDir Path directory;

    @Test
    void sqliteFullRollsBackPageAndReopensConsistently() throws Exception {
        Path database = directory.resolve("operations.sqlite");

        try (var journal = RuntimeTestFixtures.journal(directory)) {
            journal.create("job", "{}", "v1", "identity", 10);

            Connection journalConnection = journalConnection(journal);
            long pageCount;
            try (var statement = journalConnection.createStatement();
                    var result = statement.executeQuery("PRAGMA page_count")) {
                assertTrue(result.next());
                pageCount = result.getLong(1);
            }
            try (var statement = journalConnection.createStatement()) {
                statement.execute("PRAGMA max_page_count=" + pageCount);
            }

            String payload = "{\"blob\":\"" + "x".repeat(128 * 1024) + "\"}";
            List<Workflow.Candidate> records = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                records.add(new Workflow.Candidate("record-" + index, payload));
            }

            assertThrows(
                    SQLException.class,
                    () ->
                            journal.page(
                                    "job",
                                    0,
                                    new Workflow.Page(List.copyOf(records), "next", false),
                                    100));
            assertEquals(0, journal.count("job", null));
            assertEquals(new SqliteJournal.Cursor("", false), journal.cursor("job", 0));
            assertEquals(JobState.PLANNING, journal.job("job").state());
            assertEquals(
                    List.of("CREATED"),
                    journal.auditPage("job", 0).stream()
                            .map(row -> row.get("event").toString())
                            .toList());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA max_page_count=2147483646");
        }

        try (var reopened = RuntimeTestFixtures.journal(directory)) {
            assertEquals(JobState.INTERRUPTED, reopened.job("job").state());
            assertEquals(0, reopened.count("job", null));
            assertEquals(new SqliteJournal.Cursor("", false), reopened.cursor("job", 0));
        }
    }

    private static Connection journalConnection(SqliteJournal journal) throws Exception {
        Field field = SqliteJournal.class.getDeclaredField("db");
        field.setAccessible(true);
        return (Connection) field.get(journal);
    }
}
