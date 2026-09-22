package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

@EnabledIfSystemProperty(named = "toolkit.lab", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("lab")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DockerLabTest {
    static final Path DATA =
            Path.of("target", "lab-test", UUID.randomUUID().toString()).toAbsolutePath();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("toolkit.data-directory", () -> DATA.toString());
        r.add("toolkit.local-token", () -> "local-lab-test-token-never-for-real-use-123");
        r.add("toolkit.minimum-free-bytes", () -> 1048576);
        r.add("operations.requests-per-second", () -> 100);
        r.add("operations.page-size", () -> 7);
    }

    @Autowired JobCoordinator coordinator;
    @Autowired SqliteJournal journal;
    @Autowired ObjectMapper json;
    @Autowired DynamoDbClient dynamo;
    @Autowired SqsClient sqs;
    @Autowired S3Client s3;

    JobRequest request(String operation, Map<String, Object> params, int segments, int canary) {
        return new JobRequest(
                operation,
                json.valueToTree(params),
                JobMode.EXECUTE,
                "INC-LAB",
                "CHG-LAB",
                "Docker laboratory execution",
                true,
                1000,
                10000,
                600,
                canary,
                0,
                segments,
                true,
                true);
    }

    SqliteJournal.Job waitFor(String id, JobState state) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos();
        SqliteJournal.Job job;
        do {
            job = coordinator.status(id);
            if (job.state() == state) return job;
            if (!Set.of(JobState.PLANNING, JobState.RUNNING, JobState.APPROVED)
                    .contains(job.state()))
                fail(
                        "Expected "
                                + state
                                + ", actual "
                                + job.state()
                                + "; audit="
                                + journal.auditPage(id, 0));
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Job deadline: " + job.state());
    }

    SqliteJournal.Job execute(String operation, Map<String, Object> params) throws Exception {
        var created = coordinator.create(request(operation, params, 1, 1000));
        var plan = waitFor(created.id(), JobState.READY);
        coordinator.approve(plan.id(), plan.hash(), "LAB-TEST authorized fixture", false);
        return waitFor(plan.id(), JobState.COMPLETED);
    }

    @Test
    @Order(1)
    void segmentedInventoryCanaryPromotionAndNoWriteDuringPlanning() throws Exception {
        assertEquals(1, dynamo.scan(b -> b.tableName("lab-payments").limit(1)).count());
        var job =
                coordinator.create(
                        request("dynamodb-inventory", Map.of("table", "lab-payments"), 4, 3));
        var plan = waitFor(job.id(), JobState.READY);
        assertEquals(200, journal.count(job.id(), null));
        assertEquals(
                "PENDING",
                dynamo.getItem(
                                b ->
                                        b.tableName("lab-payments")
                                                .key(
                                                        Map.of(
                                                                "id",
                                                                AttributeValue.builder()
                                                                        .s("payment-000000")
                                                                        .build())))
                        .item()
                        .get("status")
                        .s());
        assertThrows(
                IllegalArgumentException.class,
                () -> coordinator.approve(job.id(), "wrong", "LAB approval", false));
        coordinator.approve(job.id(), plan.hash(), "LAB inventory canary", false);
        waitFor(job.id(), JobState.CANARY_COMPLETE);
        assertEquals(3, journal.count(job.id(), "DONE"));
        coordinator.approve(job.id(), plan.hash(), "LAB promote reviewed canary", true);
        waitFor(job.id(), JobState.COMPLETED);
        assertEquals(200, journal.count(job.id(), "DONE"));
    }

    @Test
    @Order(2)
    void servicesExecuteAgainstDocker() throws Exception {
        var before =
                sqs.getQueueAttributes(
                                b ->
                                        b.queueUrl("http://127.0.0.1:4566/123456789012/lab-replay")
                                                .attributeNames(
                                                        software.amazon.awssdk.services.sqs.model
                                                                .QueueAttributeName
                                                                .APPROXIMATE_NUMBER_OF_MESSAGES))
                        .attributes()
                        .get(
                                software.amazon.awssdk.services.sqs.model.QueueAttributeName
                                        .APPROXIMATE_NUMBER_OF_MESSAGES);
        execute(
                "dlq-replay",
                Map.of(
                        "queue",
                        "http://127.0.0.1:4566/123456789012/lab-dlq",
                        "destinationQueue",
                        "http://127.0.0.1:4566/123456789012/lab-replay",
                        "messages",
                        2));
        var after =
                sqs.getQueueAttributes(
                                b ->
                                        b.queueUrl("http://127.0.0.1:4566/123456789012/lab-replay")
                                                .attributeNames(
                                                        software.amazon.awssdk.services.sqs.model
                                                                .QueueAttributeName
                                                                .APPROXIMATE_NUMBER_OF_MESSAGES))
                        .attributes()
                        .get(
                                software.amazon.awssdk.services.sqs.model.QueueAttributeName
                                        .APPROXIMATE_NUMBER_OF_MESSAGES);
        assertEquals(Integer.parseInt(before) + 2, Integer.parseInt(after));
        execute(
                "sqs-consume",
                Map.of(
                        "queue",
                        "http://127.0.0.1:4566/123456789012/lab-replay",
                        "table",
                        "lab-consumed",
                        "messages",
                        2));
        assertTrue(dynamo.scan(b -> b.tableName("lab-consumed")).count() >= 2);
        execute(
                "sns-publish",
                Map.of(
                        "topic",
                        "arn:aws:sns:us-east-1:123456789012:lab-events",
                        "payload",
                        Map.of("eventId", "lab-test")));
        String version =
                s3.headObject(b -> b.bucket("lab-evidence").key("input/evidence.txt")).versionId();
        execute(
                "s3-copy",
                Map.of(
                        "bucket",
                        "lab-evidence",
                        "key",
                        "input/evidence.txt",
                        "versionId",
                        version,
                        "destinationBucket",
                        "lab-archive",
                        "destinationKey",
                        "copied.txt",
                        "maxBytes",
                        1000));
        assertEquals(
                "synthetic-evidence\n",
                s3.getObjectAsBytes(b -> b.bucket("lab-archive").key("copied.txt")).asUtf8String());
        String copiedVersion =
                s3.headObject(b -> b.bucket("lab-archive").key("copied.txt")).versionId();
        execute(
                "s3-delete",
                Map.of(
                        "bucket",
                        "lab-archive",
                        "key",
                        "copied.txt",
                        "versionId",
                        copiedVersion,
                        "maxBytes",
                        1000));
        assertThrows(
                software.amazon.awssdk.services.s3.model.S3Exception.class,
                () ->
                        s3.headObject(
                                b ->
                                        b.bucket("lab-archive")
                                                .key("copied.txt")
                                                .versionId(copiedVersion)));
        byte[] big = new byte[12 * 1024 * 1024];
        Arrays.fill(big, (byte) 7);
        String bigVersion =
                s3.putObject(
                                b -> b.bucket("lab-evidence").key("input/multipart.bin"),
                                software.amazon.awssdk.core.sync.RequestBody.fromBytes(big))
                        .versionId();
        execute(
                "s3-copy",
                Map.of(
                        "bucket",
                        "lab-evidence",
                        "key",
                        "input/multipart.bin",
                        "versionId",
                        bigVersion,
                        "destinationBucket",
                        "lab-archive",
                        "destinationKey",
                        "multipart.bin",
                        "maxBytes",
                        big.length,
                        "multipart",
                        true));
        assertEquals(
                big.length,
                s3.headObject(b -> b.bucket("lab-archive").key("multipart.bin")).contentLength());
    }

    @Test
    @Order(4)
    void lambdaExecutesQualifiedFunctionAndDistinguishesFunctionError() throws Exception {
        var job =
                execute(
                        "lambda-invoke",
                        Map.of(
                                "function",
                                "lab-reconcile:approved",
                                "invocationType",
                                "RequestResponse",
                                "payload",
                                Map.of("eventId", "lab-test")));
        List<String> outcomes = new ArrayList<>();
        journal.report(job.id(), row -> outcomes.add(row.outcome()));
        assertEquals(List.of("EXECUTED"), outcomes);
        var failedRequest =
                request(
                        "lambda-invoke",
                        Map.of(
                                "function",
                                "lab-reconcile:approved",
                                "invocationType",
                                "RequestResponse",
                                "payload",
                                Map.of("fail", true)),
                        1,
                        1);
        var failed = coordinator.create(failedRequest);
        var failedPlan = waitFor(failed.id(), JobState.READY);
        coordinator.approve(failed.id(), failedPlan.hash(), "LAB function error validation", false);
        waitFor(failed.id(), JobState.BUDGET_EXCEEDED);
        outcomes.clear();
        journal.report(failed.id(), row -> outcomes.add(row.outcome()));
        assertEquals(List.of("FUNCTION_ERROR"), outcomes);
    }

    @Test
    @Order(3)
    void paymentCanaryTouchesAllServicesAndKeepsUnapprovedRemainder() throws Exception {
        var job =
                coordinator.create(
                        request(
                                "payment-repair",
                                Map.of(
                                        "table",
                                        "lab-payments",
                                        "queue",
                                        "http://127.0.0.1:4566/123456789012/lab-events",
                                        "topic",
                                        "arn:aws:sns:us-east-1:123456789012:lab-events",
                                        "function",
                                        "lab-reconcile:approved",
                                        "evidenceBucket",
                                        "lab-evidence"),
                                4,
                                2));
        var plan = waitFor(job.id(), JobState.READY);
        coordinator.approve(job.id(), plan.hash(), "LAB payment canary", false);
        waitFor(job.id(), JobState.CANARY_COMPLETE);
        List<String> outcomes = new ArrayList<>();
        journal.report(
                job.id(),
                row -> {
                    if (row.state().equals("DONE")) outcomes.add(row.outcome());
                });
        assertEquals(List.of("REPAIRED", "REPAIRED"), outcomes);
        assertEquals(
                2,
                s3.listObjectsV2(
                                b ->
                                        b.bucket("lab-evidence")
                                                .prefix("operations/" + job.id() + "/"))
                        .keyCount());
        coordinator.stop(job.id(), true);
        assertEquals(JobState.CANCELLED, coordinator.status(job.id()).state());
        var compensation =
                execute(
                        "payment-compensate",
                        Map.of("table", "lab-payments", "sourceJob", job.id()));
        assertEquals(2, journal.count(compensation.id(), "DONE"));
    }

    @Test
    @Order(5)
    void concurrentDynamoChangeIsDetectedBeforeDownstreamEffects() throws Exception {
        var job =
                coordinator.create(
                        request(
                                "payment-repair",
                                Map.of(
                                        "table",
                                        "lab-payments",
                                        "queue",
                                        "http://127.0.0.1:4566/123456789012/lab-events",
                                        "topic",
                                        "arn:aws:sns:us-east-1:123456789012:lab-events",
                                        "function",
                                        "lab-reconcile:approved",
                                        "evidenceBucket",
                                        "lab-evidence"),
                                1,
                                1));
        var plan = waitFor(job.id(), JobState.READY);
        var task = journal.pending(job.id(), 1).getFirst();
        var current =
                dynamo.getItem(
                                b ->
                                        b.tableName("lab-payments")
                                                .key(
                                                        Map.of(
                                                                "id",
                                                                AttributeValue.builder()
                                                                        .s(task.key())
                                                                        .build()))
                                                .consistentRead(true))
                        .item();
        long version = Long.parseLong(current.get("version").n());
        dynamo.updateItem(
                b ->
                        b.tableName("lab-payments")
                                .key(Map.of("id", AttributeValue.builder().s(task.key()).build()))
                                .updateExpression("SET #v=:next")
                                .expressionAttributeNames(Map.of("#v", "version"))
                                .expressionAttributeValues(
                                        Map.of(
                                                ":next",
                                                AttributeValue.builder()
                                                        .n(Long.toString(version + 1))
                                                        .build())));
        coordinator.approve(job.id(), plan.hash(), "LAB concurrent-write injection", false);
        waitFor(job.id(), JobState.BUDGET_EXCEEDED);
        assertEquals(1, journal.conflicts(job.id()));
        assertEquals(
                0,
                s3.listObjectsV2(
                                b ->
                                        b.bucket("lab-evidence")
                                                .prefix("operations/" + job.id() + "/"))
                        .keyCount());
    }

    @Test
    @Order(6)
    void duplicateSqsMessagesAreDeduplicatedBeforeAcknowledgement() throws Exception {
        String queue = "http://127.0.0.1:4566/123456789012/lab-replay";
        String body = "{\"eventId\":\"duplicate-lab-event\",\"value\":1}";
        sqs.sendMessage(b -> b.queueUrl(queue).messageBody(body));
        sqs.sendMessage(b -> b.queueUrl(queue).messageBody(body));

        var job =
                execute(
                        "sqs-consume",
                        Map.of("queue", queue, "table", "lab-consumed", "messages", 2));
        assertEquals(2, journal.count(job.id(), "DONE"));
        assertFalse(
                dynamo.getItem(
                                b ->
                                        b.tableName("lab-consumed")
                                                .key(
                                                        Map.of(
                                                                "id",
                                                                AttributeValue.builder()
                                                                        .s("duplicate-lab-event")
                                                                        .build()))
                                                .consistentRead(true))
                        .item()
                        .isEmpty());
    }
}
