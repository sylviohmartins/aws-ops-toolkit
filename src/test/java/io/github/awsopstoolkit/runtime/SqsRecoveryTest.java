package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import tools.jackson.databind.json.JsonMapper;

class SqsRecoveryTest {
    @TempDir Path directory;
    final JsonMapper json = JsonMapper.builder().build();
    final AtomicInteger receives = new AtomicInteger();
    final AtomicInteger sends = new AtomicInteger();
    final List<String> deleted = new ArrayList<>();
    final List<String> released = new ArrayList<>();
    final Deque<Message> incoming = new ArrayDeque<>();

    @Test
    void expiredReceiptIsReacquiredWithoutRepeatingSuccessfulDelivery() throws Exception {
        try (var fixture = fixture()) {
            incoming.add(message("original", "fresh-receipt"));
            assertEquals("REPLAYED", fixture.workflow.execute(fixture.context, fixture.task));
            assertEquals(1, receives.get());
            assertEquals(0, sends.get());
            assertEquals(List.of("fresh-receipt"), deleted);
        }
    }

    @Test
    void missingTargetReleasesUnrelatedMessagesAndStopsBeforeAcknowledging() throws Exception {
        try (var fixture = fixture()) {
            incoming.add(message("unrelated", "other-receipt"));
            var stopped =
                    assertThrows(
                            JobStopped.class,
                            () -> fixture.workflow.execute(fixture.context, fixture.task));
            assertEquals(JobState.RECONCILIATION_REQUIRED, stopped.state());
            assertTrue(receives.get() > 0 && receives.get() <= 3);
            assertEquals(List.of("other-receipt"), released);
            assertTrue(deleted.isEmpty());
            assertEquals(0, sends.get());
            incoming.add(message("original", "later-receipt"));
            assertEquals("REPLAYED", fixture.workflow.execute(fixture.context, fixture.task));
            assertEquals(List.of("later-receipt"), deleted);
        }
    }

    @Test
    void unknownRefreshNeverTriggersAnotherReceive() throws Exception {
        try (var fixture = fixture()) {
            fixture.journal.effect(
                    "job",
                    fixture.task.sequence(),
                    "receive-refresh/0000000001",
                    EffectState.UNKNOWN,
                    "");
            incoming.add(message("original", "must-not-receive"));
            assertEquals(
                    JobState.RECONCILIATION_REQUIRED,
                    assertThrows(
                                    JobStopped.class,
                                    () -> fixture.workflow.execute(fixture.context, fixture.task))
                            .state());
            assertEquals(0, receives.get());
            assertTrue(deleted.isEmpty());
        }
    }

    @Test
    void latestDurableFreshReceiptSurvivesRestartWithoutReceivingAgain() throws Exception {
        try (var fixture = fixture()) {
            fixture.journal.effect(
                    "job",
                    fixture.task.sequence(),
                    "receive-refresh/0000000001",
                    EffectState.SUCCEEDED,
                    envelope("fresh-receipt", SqliteJournal.now()));
            assertEquals("REPLAYED", fixture.workflow.execute(fixture.context, fixture.task));
            assertEquals(0, receives.get());
            assertEquals(List.of("fresh-receipt"), deleted);
            assertEquals(0, sends.get());
        }
    }

    @Test
    void acknowledgedMessageDoesNotNeedToBeReacquiredAfterRestart() throws Exception {
        try (var fixture = fixture()) {
            fixture.journal.effect(
                    "job", fixture.task.sequence(), "ack", EffectState.SUCCEEDED, "ACKNOWLEDGED");
            assertEquals("REPLAYED", fixture.workflow.execute(fixture.context, fixture.task));
            assertEquals(0, receives.get());
            assertTrue(deleted.isEmpty());
        }
    }

    String envelope(String receipt, long receivedAt) {
        return json.writeValueAsString(
                Map.of(
                        "id",
                        "original",
                        "receipt",
                        receipt,
                        "body",
                        "{}",
                        "attributes",
                        Map.of(),
                        "receivedAt",
                        receivedAt,
                        "leaseUntil",
                        receivedAt + 120));
    }

    Message message(String id, String receipt) {
        return Message.builder().messageId(id).receiptHandle(receipt).body("{}").build();
    }

    @SuppressWarnings("unchecked")
    Fixture fixture() throws Exception {
        var settings =
                RuntimeTestFixtures.runtime(
                        true,
                        null,
                        Set.of("queue", "destination"),
                        Set.of("principal"),
                        1000,
                        2,
                        10);
        var sts =
                (StsClient)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {StsClient.class},
                                (proxy, method, args) ->
                                        method.getName().equals("getCallerIdentity")
                                                ? GetCallerIdentityResponse.builder()
                                                        .account("123456789012")
                                                        .arn("principal")
                                                        .build()
                                                : null);
        var policy =
                new ExecutionPolicy(
                        settings,
                        RuntimeTestFixtures.toolkit(
                                ToolkitProperties.Environment.LOCAL, directory, false),
                        RuntimeTestFixtures.aws("123456789012"),
                        sts);
        var sqs =
                (SqsClient)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {SqsClient.class},
                                (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "receiveMessage":
                                            receives.incrementAndGet();
                                            return ReceiveMessageResponse.builder()
                                                    .messages(
                                                            incoming.isEmpty()
                                                                    ? List.of()
                                                                    : List.of(
                                                                            incoming.removeFirst()))
                                                    .build();
                                        case "sendMessage":
                                            sends.incrementAndGet();
                                            return SendMessageResponse.builder()
                                                    .messageId("sent")
                                                    .build();
                                        case "deleteMessage":
                                            {
                                                var builder = DeleteMessageRequest.builder();
                                                if (args[0] instanceof Consumer<?> consumer)
                                                    ((Consumer<DeleteMessageRequest.Builder>)
                                                                    consumer)
                                                            .accept(builder);
                                                else
                                                    builder =
                                                            ((DeleteMessageRequest) args[0])
                                                                    .toBuilder();
                                                deleted.add(builder.build().receiptHandle());
                                                return DeleteMessageResponse.builder().build();
                                            }
                                        case "changeMessageVisibility":
                                            {
                                                var builder =
                                                        ChangeMessageVisibilityRequest.builder();
                                                if (args[0] instanceof Consumer<?> consumer)
                                                    ((Consumer<
                                                                            ChangeMessageVisibilityRequest
                                                                                    .Builder>)
                                                                    consumer)
                                                            .accept(builder);
                                                else
                                                    builder =
                                                            ((ChangeMessageVisibilityRequest)
                                                                            args[0])
                                                                    .toBuilder();
                                                assertEquals(
                                                        0, builder.build().visibilityTimeout());
                                                released.add(builder.build().receiptHandle());
                                                return ChangeMessageVisibilityResponse.builder()
                                                        .build();
                                            }
                                        default:
                                            return null;
                                    }
                                });
        var p =
                json.readTree(
                        "{\"queue\":\"queue\",\"destinationQueue\":\"destination\",\"messages\":1}");
        var request =
                new JobRequest(
                        "dlq-replay",
                        p,
                        JobMode.EXECUTE,
                        "INC-TEST",
                        "CHG-TEST",
                        "SQS recovery validation",
                        true,
                        10,
                        100,
                        60,
                        1,
                        0,
                        1,
                        true,
                        true);
        var journal = RuntimeTestFixtures.journal(directory);
        journal.create("job", "{}", "1", policy.identity(), 1000);
        journal.page(
                "job",
                0,
                new Workflow.Page(
                        List.of(new Workflow.Candidate("command-0", p.toString())), "", true),
                1);
        journal.seal("job");
        journal.approve("job", SqliteJournal.now() + 60, "LAB recovery approval", false);
        var task = journal.pending("job", 1).getFirst();
        journal.effect(
                "job",
                task.sequence(),
                "receive",
                EffectState.SUCCEEDED,
                envelope("expired-receipt", SqliteJournal.now() - 200));
        journal.effect(
                "job",
                task.sequence(),
                "send/original",
                EffectState.SUCCEEDED,
                "sent-before-crash");
        var context =
                new JobContext(
                        "job",
                        request,
                        journal,
                        policy,
                        RuntimeTestFixtures.limiter(2, 1000),
                        new AtomicReference<>(),
                        Set.of("queue", "destination"),
                        json,
                        settings);
        return new Fixture(
                journal,
                context,
                task,
                new ServiceWorkflow(
                        ServiceWorkflow.Kind.DLQ_REPLAY,
                        sqs,
                        null,
                        null,
                        null,
                        null,
                        RuntimeTestFixtures.sqs(),
                        RuntimeTestFixtures.s3()));
    }

    record Fixture(
            SqliteJournal journal,
            JobContext context,
            SqliteJournal.Task task,
            ServiceWorkflow workflow)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            journal.close();
        }
    }
}
