package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteJournalProcessLockTest {
    @TempDir Path directory;

    @Test
    void rejectsSecondInstanceAndAllowsReopenAfterRelease() throws Exception {
        try (var first = RuntimeTestFixtures.journal(directory)) {
            first.create("job", "{}", "v1", "identity", 1);

            IOException failure =
                    assertThrows(IOException.class, () -> RuntimeTestFixtures.journal(directory));

            assertTrue(failure.getMessage().contains("already in use"));
            assertEquals(JobState.PLANNING, first.job("job").state());
        }

        try (var reopened = RuntimeTestFixtures.journal(directory)) {
            assertEquals(JobState.INTERRUPTED, reopened.job("job").state());
        }
    }
}
