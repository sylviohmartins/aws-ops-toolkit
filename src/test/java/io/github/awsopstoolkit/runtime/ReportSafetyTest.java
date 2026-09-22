package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.validation.Validation;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ReportSafetyTest {
    @TempDir Path directory;

    @Test
    void reportColumnsAreExplicitAllowlistedAndCannotExposePayload() {
        assertEquals(List.of("record", "outcome"), JobController.reportColumns("record,outcome"));
        assertThrows(
                IllegalArgumentException.class,
                () -> JobController.reportColumns("record,payload"));
        assertThrows(
                IllegalArgumentException.class, () -> JobController.reportColumns("record,record"));
    }

    @Test
    void onlyOneXlsxProjectionCanConsumeTemporaryDiskAtATime() throws Exception {
        var settings =
                RuntimeTestFixtures.runtime(
                        true, null, Set.of("table"), Set.of("principal"), 10, 1, 10);
        try (var journal = RuntimeTestFixtures.journal(directory);
                var validation = Validation.buildDefaultValidatorFactory();
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var coordinator =
                    new JobCoordinator(
                            journal,
                            settings,
                            null,
                            List.of(),
                            JsonMapper.builder().build(),
                            validation.getValidator());
            try {
                String id = UUID.randomUUID().toString();
                journal.create(id, "{}", "1", "identity", 10);
                journal.page(
                        id,
                        0,
                        new Workflow.Page(List.of(new Workflow.Candidate("id", "{}")), "", true),
                        1);
                journal.seal(id);
                var controller =
                        new JobController(
                                coordinator,
                                journal,
                                RuntimeTestFixtures.toolkit(
                                        io.github.awsopstoolkit.configuration.ToolkitProperties
                                                .Environment.LOCAL,
                                        directory,
                                        false),
                                RuntimeTestFixtures.report(),
                                RuntimeTestFixtures.csvFactory());
                var blocked = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var body =
                        controller
                                .xlsx(UUID.fromString(id), "sequence,record,state,outcome")
                                .getBody();
                var running =
                        threads.submit(
                                () -> {
                                    body.writeTo(
                                            new OutputStream() {
                                                @Override
                                                public void write(int b) throws IOException {
                                                    blocked.countDown();
                                                    try {
                                                        if (!release.await(10, TimeUnit.SECONDS))
                                                            throw new IOException("Timed out");
                                                    } catch (InterruptedException e) {
                                                        Thread.currentThread().interrupt();
                                                        throw new IOException(e);
                                                    }
                                                }
                                            });
                                    return null;
                                });
                try {
                    assertTrue(blocked.await(10, TimeUnit.SECONDS));
                    var second =
                            controller
                                    .xlsx(UUID.fromString(id), "sequence,record,state,outcome")
                                    .getBody();
                    assertThrows(
                            IOException.class,
                            () -> second.writeTo(OutputStream.nullOutputStream()));
                } finally {
                    release.countDown();
                    running.get(15, TimeUnit.SECONDS);
                }
            } finally {
                coordinator.close();
            }
        }
    }
}
