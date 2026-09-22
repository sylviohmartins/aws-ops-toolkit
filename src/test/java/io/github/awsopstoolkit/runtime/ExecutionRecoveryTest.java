package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import tools.jackson.databind.json.JsonMapper;

class ExecutionRecoveryTest {
    @TempDir Path directory;
    final JsonMapper json = JsonMapper.builder().build();
    final RuntimeProperties settings =
            RuntimeTestFixtures.runtime(
                    true, null, Set.of("table"), Set.of("principal"), 1000, 2, 10);
    final AtomicBoolean validIdentity = new AtomicBoolean(true);
    final AtomicInteger identityCalls = new AtomicInteger();

    ExecutionPolicy policy() {
        var sts =
                (StsClient)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {StsClient.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getCallerIdentity")) {
                                        identityCalls.incrementAndGet();
                                        return GetCallerIdentityResponse.builder()
                                                .account(
                                                        validIdentity.get()
                                                                ? "123456789012"
                                                                : "999999999999")
                                                .arn("principal")
                                                .build();
                                    }
                                    return null;
                                });
        return new ExecutionPolicy(
                settings,
                RuntimeTestFixtures.toolkit(ToolkitProperties.Environment.LOCAL, directory, false),
                RuntimeTestFixtures.aws("123456789012"),
                sts);
    }

    JobContext context(
            SqliteJournal journal, ExecutionPolicy policy, AtomicReference<JobState> stop)
            throws Exception {
        return new JobContext(
                "job",
                new JobRequest(
                        "test",
                        json.readTree("{}"),
                        JobMode.EXECUTE,
                        "INC-TEST",
                        "CHG-TEST",
                        "test effect execution",
                        true,
                        10,
                        100,
                        60,
                        1,
                        0,
                        1,
                        false,
                        false),
                journal,
                policy,
                RuntimeTestFixtures.limiter(2, 1000),
                stop,
                Set.of("table"),
                json,
                settings);
    }

    SqliteJournal.Task prepare(SqliteJournal journal, ExecutionPolicy policy) throws Exception {
        journal.create("job", "{}", "1", policy.identity(), 1000);
        journal.page(
                "job",
                0,
                new Workflow.Page(List.of(new Workflow.Candidate("id", "{}")), "", true),
                1);
        journal.seal("job");
        journal.approve("job", SqliteJournal.now() + 60, "LAB test approval", false);
        return journal.pending("job", 1).getFirst();
    }

    @Test
    void responseLostAfterWriteReconcilesWithoutRepeatingEffect() throws Exception {
        var policy = policy();
        var remoteWrites = new AtomicInteger();
        try (var journal = new SqliteJournal(directory)) {
            var task = prepare(journal, policy);
            var context = context(journal, policy, new AtomicReference<>());
            var stopped =
                    assertThrows(
                            JobStopped.class,
                            () ->
                                    context.effect(
                                            task,
                                            "update",
                                            "table",
                                            () -> {
                                                remoteWrites.incrementAndGet();
                                                throw new IllegalStateException("lost response");
                                            },
                                            Optional::empty));
            assertEquals(JobState.RECONCILIATION_REQUIRED, stopped.state());
            assertEquals("UNKNOWN", journal.effect("job", task.sequence(), "update").state());
            assertEquals(
                    "confirmed",
                    context.effect(
                            task,
                            "update",
                            "table",
                            () -> {
                                remoteWrites.incrementAndGet();
                                return "duplicated";
                            },
                            () -> Optional.of("confirmed")));
            assertEquals(1, remoteWrites.get());
            assertEquals(
                    "confirmed",
                    context.effect(
                            task,
                            "update",
                            "table",
                            () -> {
                                throw new AssertionError("must not dispatch");
                            },
                            Optional::empty));
        }
    }

    @Test
    void uncertainNonIdempotentWriteCannotBeBlindlyRetried() throws Exception {
        var policy = policy();
        try (var journal = new SqliteJournal(directory)) {
            var task = prepare(journal, policy);
            journal.effect("job", task.sequence(), "publish", "INTENT", "");
            var context = context(journal, policy, new AtomicReference<>());
            assertEquals(
                    JobState.RECONCILIATION_REQUIRED,
                    assertThrows(
                                    JobStopped.class,
                                    () ->
                                            context.effect(
                                                    task,
                                                    "publish",
                                                    "table",
                                                    () -> {
                                                        throw new AssertionError("duplicate");
                                                    },
                                                    Optional::empty))
                            .state());
        }
    }

    @Test
    void expiredApprovalWrongIdentityAndCancellationBlockBeforeIntent() throws Exception {
        var policy = policy();
        try (var journal = new SqliteJournal(directory)) {
            var task = prepare(journal, policy);
            var stop = new AtomicReference<JobState>();
            var context = context(journal, policy, stop);
            journal.approve("job", SqliteJournal.now() - 1, "expired test", false);
            assertEquals(
                    JobState.AUTHORIZATION_REQUIRED,
                    assertThrows(
                                    JobStopped.class,
                                    () ->
                                            context.effect(
                                                    task,
                                                    "write",
                                                    "table",
                                                    () -> "not allowed",
                                                    Optional::empty))
                            .state());
            assertNull(journal.effect("job", task.sequence(), "write"));
            journal.approve("job", SqliteJournal.now() + 60, "renewed", false);
            validIdentity.set(false);
            assertThrows(
                    JobStopped.class,
                    () ->
                            context.effect(
                                    task, "write", "table", () -> "not allowed", Optional::empty));
            validIdentity.set(true);
            stop.set(JobState.CANCELLED);
            assertEquals(
                    JobState.CANCELLED,
                    assertThrows(JobStopped.class, () -> context.read("table", () -> "not allowed"))
                            .state());
            assertNull(journal.effect("job", task.sequence(), "write"));
        }
    }

    @Test
    void expiredAssumeRoleSessionCanResumeWithNewSessionOfSameRole() throws Exception {
        String stableRole = "arn:aws:iam::123456789012:role/team/war-room";
        var expired = new AtomicBoolean(false);
        var session = new AtomicInteger(1);
        var sts =
                (StsClient)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {StsClient.class},
                                (proxy, method, args) -> {
                                    if (!method.getName().equals("getCallerIdentity")) return null;
                                    if (expired.get())
                                        throw software.amazon.awssdk.services.sts.model.StsException
                                                .builder()
                                                .statusCode(403)
                                                .awsErrorDetails(
                                                        software.amazon.awssdk.awscore.exception
                                                                .AwsErrorDetails.builder()
                                                                .errorCode("ExpiredToken")
                                                                .build())
                                                .build();
                                    return GetCallerIdentityResponse.builder()
                                            .account("123456789012")
                                            .arn(
                                                    "arn:aws:sts::123456789012:assumed-role/team/war-room/session-"
                                                            + session.get())
                                            .build();
                                });
        var runtime =
                RuntimeTestFixtures.runtime(
                        false, null, Set.of("table"), Set.of(stableRole), 1000, 2, 10);
        var policy =
                new ExecutionPolicy(
                        runtime,
                        RuntimeTestFixtures.toolkit(
                                ToolkitProperties.Environment.LOCAL, directory, false),
                        RuntimeTestFixtures.aws("123456789012"),
                        sts);
        try (var journal = new SqliteJournal(directory)) {
            journal.create("job", "{}", "1", policy.identity(), 1000);
            var request =
                    new JobRequest("test", json.readTree("{}"), 10, 100, 60, 1, 0, 1, false, false);
            var context =
                    new JobContext(
                            "job",
                            request,
                            journal,
                            policy,
                            RuntimeTestFixtures.limiter(2, 1000),
                            new AtomicReference<>(),
                            Set.of("table"),
                            json,
                            runtime);
            expired.set(true);
            assertEquals(
                    JobState.AUTHENTICATION_REQUIRED,
                    assertThrows(JobStopped.class, () -> context.read("table", () -> "blocked"))
                            .state());

            expired.set(false);
            session.set(2);
            assertEquals("resumed", context.read("table", () -> "resumed"));
            assertTrue(journal.job("job").calls() >= 4);
        }
    }

    @Test
    void resourceOutsidePlanCannotBeDispatched() throws Exception {
        var policy = policy();
        try (var journal = new SqliteJournal(directory)) {
            var task = prepare(journal, policy);
            var context = context(journal, policy, new AtomicReference<>());
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            context.effect(
                                    task,
                                    "write",
                                    "other-table",
                                    () -> "not allowed",
                                    Optional::empty));
            assertNull(journal.effect("job", task.sequence(), "write"));
        }
    }

    @Test
    void exhaustedBudgetDoesNotDispatchUncountedIdentityLookup() throws Exception {
        var policy = policy();
        try (var journal = new SqliteJournal(directory)) {
            prepare(journal, policy);
            identityCalls.set(0);
            var actualCalls = new AtomicInteger();
            var bounded =
                    new JobContext(
                            "job",
                            new JobRequest(
                                    "test", json.readTree("{}"), 10, 1, 60, 1, 0, 1, false, false),
                            journal,
                            policy,
                            RuntimeTestFixtures.limiter(2, 1000),
                            new AtomicReference<>(),
                            Set.of("table"),
                            json,
                            settings);
            assertEquals(
                    JobState.BUDGET_EXCEEDED,
                    assertThrows(
                                    JobStopped.class,
                                    () ->
                                            bounded.read(
                                                    "table", () -> actualCalls.incrementAndGet()))
                            .state());
            assertEquals(0, identityCalls.get() + actualCalls.get());
        }
    }

    @Test
    void emptyFilteredScanStillConsumesTheExaminedRecordBudget() throws Exception {
        assertScanBudget(11, 1);
    }

    @Test
    void consumedCapacityBudgetIsEnforcedEvenForSmallPages() throws Exception {
        assertScanBudget(1, 11);
    }

    private void assertScanBudget(int examined, double capacity) throws Exception {
        var policy = policy();
        try (var journal = new SqliteJournal(directory)) {
            prepare(journal, policy);
            var request =
                    new JobRequest(
                            "dynamodb-inventory",
                            json.readTree("{\"table\":\"table\"}"),
                            10,
                            100,
                            60,
                            1,
                            0,
                            1,
                            false,
                            false);
            var c =
                    new JobContext(
                            "job",
                            request,
                            journal,
                            policy,
                            RuntimeTestFixtures.limiter(2, 1000),
                            new AtomicReference<>(),
                            Set.of("table"),
                            json,
                            settings);
            var client =
                    (software.amazon.awssdk.services.dynamodb.DynamoDbClient)
                            Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {
                                        software.amazon.awssdk.services.dynamodb.DynamoDbClient
                                                .class
                                    },
                                    (proxy, method, args) ->
                                            software.amazon.awssdk.services.dynamodb.model
                                                    .ScanResponse.builder()
                                                    .count(0)
                                                    .scannedCount(examined)
                                                    .consumedCapacity(
                                                            software.amazon.awssdk.services.dynamodb
                                                                    .model.ConsumedCapacity
                                                                    .builder()
                                                                    .capacityUnits(capacity)
                                                                    .build())
                                                    .build());
            assertEquals(
                    JobState.BUDGET_EXCEEDED,
                    assertThrows(JobStopped.class, () -> new DynamoWorkflow(client).plan(c, 0, ""))
                            .state());
        }
    }
}
