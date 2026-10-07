package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;
import tools.jackson.databind.json.JsonMapper;

class PaymentResumeTest {
    @TempDir Path directory;

    @Test
    void changedExternalStatusCannotAbandonPreviouslyCommittedRepair() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var lookups = new AtomicInteger();
        server.createContext(
                "/payments/p1",
                exchange -> {
                    lookups.incrementAndGet();
                    byte[] body =
                            "{\"reference\":\"p1\",\"status\":\"PENDING\",\"version\":1}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            var resources = Set.of("table", "queue", "topic", "function:1", "bucket", endpoint);
            var settings =
                    RuntimeTestFixtures.runtime(
                            true,
                            URI.create("http://127.0.0.1:4566"),
                            resources,
                            Set.of("principal"),
                            1000,
                            2,
                            10);
            var httpSettings = RuntimeTestFixtures.http(URI.create(endpoint), Set.of("127.0.0.1"));
            var json = JsonMapper.builder().build();
            var request =
                    new JobRequest(
                            "payment-repair",
                            json.valueToTree(
                                    Map.of(
                                            "table",
                                            "table",
                                            "queue",
                                            "queue",
                                            "topic",
                                            "topic",
                                            "function",
                                            "function:1",
                                            "evidenceBucket",
                                            "bucket")),
                            JobMode.EXECUTE,
                            "INC-TEST",
                            "CHG-TEST",
                            "payment resume validation",
                            true,
                            10,
                            100,
                            60,
                            1,
                            0,
                            1,
                            false,
                            false);
            var sts =
                    stub(
                            StsClient.class,
                            software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse
                                    .builder()
                                    .account("123456789012")
                                    .arn("principal")
                                    .build());
            var policy =
                    new ExecutionPolicy(
                            settings,
                            RuntimeTestFixtures.toolkit(
                                    ToolkitProperties.Environment.LOCAL, directory, false),
                            RuntimeTestFixtures.aws("123456789012"),
                            sts);
            try (var journal = RuntimeTestFixtures.journal(directory);
                    var http = new PaymentGateway(httpSettings, settings)) {
                journal.create(
                        "job", json.writeValueAsString(request), "1", policy.identity(), 1000);
                journal.page(
                        "job",
                        0,
                        new Workflow.Page(
                                List.of(
                                        new Workflow.Candidate(
                                                "p1",
                                                "{\"id\":\"p1\",\"status\":\"PENDING\",\"version\":1}")),
                                "",
                                true),
                        10);
                journal.seal("job");
                journal.approve("job", SqliteJournal.now() + 60, "LAB resume validation", false);
                var task = journal.pending("job", 1).getFirst();
                journal.effect(
                        "job",
                        task.sequence(),
                        "dynamodb-update",
                        EffectState.SUCCEEDED,
                        "UPDATED");
                var context =
                        new JobContext(
                                "job",
                                request,
                                journal,
                                policy,
                                RuntimeTestFixtures.limiter(2, 1000),
                                new AtomicReference<>(),
                                resources,
                                json,
                                settings);
                var rule =
                        new PaymentWorkflow(
                                stub(DynamoDbClient.class, null),
                                stub(
                                        SqsClient.class,
                                        software.amazon.awssdk.services.sqs.model
                                                .SendMessageResponse.builder()
                                                .messageId("sent")
                                                .build()),
                                stub(
                                        SnsClient.class,
                                        software.amazon.awssdk.services.sns.model.PublishResponse
                                                .builder()
                                                .messageId("published")
                                                .build()),
                                stub(
                                        LambdaClient.class,
                                        software.amazon.awssdk.services.lambda.model.InvokeResponse
                                                .builder()
                                                .statusCode(200)
                                                .payload(
                                                        SdkBytes.fromUtf8String(
                                                                "{\"accepted\":true}"))
                                                .build()),
                                stub(
                                        S3Client.class,
                                        software.amazon.awssdk.services.s3.model.PutObjectResponse
                                                .builder()
                                                .eTag("etag")
                                                .build()),
                                http,
                                httpSettings);
                assertEquals("REPAIRED", rule.execute(context, task));
                assertEquals(0, lookups.get());
                assertEquals(
                        "sent", journal.effect("job", task.sequence(), "sqs-delivery").result());
            }
        } finally {
            server.stop(0);
        }
    }

    private static <T> T stub(Class<T> type, Object result) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            if (result == null)
                                throw new AssertionError(
                                        "Unexpected remote call " + method.getName());
                            return result;
                        }));
    }
}
