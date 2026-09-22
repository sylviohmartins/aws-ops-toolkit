package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkflowValidationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void unqualifiedLambdaArnIsRejectedBeforePlanning() {
        var rule =
                new ServiceWorkflow(
                        ServiceWorkflow.Kind.LAMBDA_INVOKE, null, null, null, null, null);
        for (String function :
                java.util.List.of(
                        "arn:aws:lambda:us-east-1:123456789012:function:target",
                        "target:$LATEST")) {
            var request =
                    new JobRequest(
                            "lambda-invoke",
                            json.valueToTree(
                                    Map.of(
                                            "function",
                                            function,
                                            "invocationType",
                                            "RequestResponse",
                                            "payload",
                                            Map.of())),
                            1,
                            10,
                            60,
                            1,
                            0,
                            1,
                            false,
                            false);
            assertThrows(IllegalArgumentException.class, () -> rule.validate(request));
        }
    }

    @Test
    void legacyJavaConstructorFailsSafeToDryRun() {
        var request =
                new JobRequest(
                        "dynamodb-inventory",
                        json.valueToTree(Map.of("table", "table")),
                        1,
                        10,
                        60,
                        1,
                        0,
                        1,
                        false,
                        false);
        assertEquals(JobMode.DRY_RUN, request.mode());
        assertFalse(request.explicitConfirmation());
    }

    @Test
    void executeWriteRequiresOperationalReferenceReasonAndConfirmation() {
        var policy = policy(ToolkitProperties.Environment.HML, true);
        var unsafe =
                new JobRequest(
                        "write",
                        json.createObjectNode(),
                        JobMode.EXECUTE,
                        null,
                        null,
                        null,
                        false,
                        1,
                        10,
                        60,
                        1,
                        0,
                        1,
                        false,
                        false);
        assertThrows(IllegalArgumentException.class, () -> policy.validateIntent(unsafe, true));

        var safe =
                new JobRequest(
                        "write",
                        json.createObjectNode(),
                        JobMode.EXECUTE,
                        "INC-TEST",
                        null,
                        "validated test execution",
                        true,
                        1,
                        10,
                        60,
                        1,
                        0,
                        1,
                        false,
                        false);
        assertDoesNotThrow(() -> policy.validateIntent(safe, true));
    }

    @Test
    void productionWriteRequiresIncidentAndChange() {
        var policy = policy(ToolkitProperties.Environment.PROD, true);
        var request =
                new JobRequest(
                        "write",
                        json.createObjectNode(),
                        JobMode.EXECUTE,
                        "INC-TEST",
                        null,
                        "validated production test",
                        true,
                        1,
                        10,
                        60,
                        1,
                        0,
                        1,
                        false,
                        false);
        assertThrows(IllegalArgumentException.class, () -> policy.validateIntent(request, true));
    }

    @Test
    void assumedRoleSessionNormalizesToStableIamRole() {
        assertEquals(
                "arn:aws:iam::123456789012:role/team/war-room",
                ExecutionPolicy.normalizePrincipal(
                        "arn:aws:sts::123456789012:assumed-role/team/war-room/session-123"));
        assertEquals(
                "arn:aws:iam::123456789012:user/operator",
                ExecutionPolicy.normalizePrincipal("arn:aws:iam::123456789012:user/operator"));
    }

    @Test
    void nonLocalWriteRequiresBothWriteGates() {
        var policy = policy(ToolkitProperties.Environment.HML, false);
        var request =
                new JobRequest(
                        "write",
                        json.createObjectNode(),
                        JobMode.EXECUTE,
                        "INC-TEST",
                        "CHG-TEST",
                        "validated gated write",
                        true,
                        1,
                        10,
                        60,
                        1,
                        0,
                        1,
                        false,
                        false);
        assertThrows(IllegalStateException.class, () -> policy.validateIntent(request, true));
    }

    @Test
    void sqsEnvelopeValidatesBodyMd5AndPreservesReceiveCount() {
        var message =
                software.amazon.awssdk.services.sqs.model.Message.builder()
                        .messageId("message-1")
                        .receiptHandle("receipt-1")
                        .body("hello")
                        .md5OfBody("5d41402abc4b2a76b9719d911017c592")
                        .attributes(
                                Map.of(
                                        software.amazon.awssdk.services.sqs.model
                                                .MessageSystemAttributeName
                                                .APPROXIMATE_RECEIVE_COUNT,
                                        "7"))
                        .build();
        var envelope = json.readTree(MessageEnvelope.encode(message, json, 100, 120));
        assertEquals(7, envelope.path("receiveCount").asInt());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MessageEnvelope.encode(
                                message.toBuilder()
                                        .md5OfBody("00000000000000000000000000000000")
                                        .build(),
                                json,
                                100,
                                120));
    }

    @Test
    void sqsDryRunDoesNotRequireVisibilityImpactConsent() {
        var rule =
                new ServiceWorkflow(ServiceWorkflow.Kind.DLQ_REPLAY, null, null, null, null, null);
        var request =
                new JobRequest(
                        "dlq-replay",
                        json.valueToTree(
                                Map.of(
                                        "queue", "source",
                                        "destinationQueue", "destination",
                                        "messages", 10)),
                        10,
                        100,
                        60,
                        1,
                        0,
                        1,
                        false,
                        false);
        assertDoesNotThrow(() -> rule.validate(request));
    }

    @Test
    void omittedOptionalBooleansDeserializeFailSafeToFalse() throws Exception {
        var request =
                json.readValue(
                        """
                        {
                          "operation":"dynamodb-inventory",
                          "parameters":{"table":"table"},
                          "maxRecords":10,
                          "maxCalls":100,
                          "maxSeconds":60,
                          "canaryRecords":1,
                          "maxConflicts":0,
                          "segments":1
                        }
                        """,
                        JobRequest.class);
        assertEquals(JobMode.DRY_RUN, request.mode());
        assertFalse(request.explicitConfirmation());
        assertFalse(request.visibilityImpactAccepted());
        assertFalse(request.sharedConsumerImpactAccepted());
    }

    private static ExecutionPolicy policy(
            ToolkitProperties.Environment environment, boolean writeEnabled) {
        var runtime =
                new RuntimeProperties(
                        true,
                        true,
                        null,
                        Set.of("table"),
                        Set.of("principal"),
                        10,
                        1,
                        10,
                        86400,
                        900,
                        30,
                        null,
                        Set.of(),
                        3000,
                        10000,
                        3,
                        10);
        var toolkit =
                new ToolkitProperties(
                        environment,
                        Path.of("."),
                        1_048_576,
                        1,
                        10,
                        writeEnabled,
                        "local-test-token-not-for-real-use-123",
                        new ToolkitProperties.Aws(
                                false,
                                "us-east-1",
                                "",
                                "123456789012",
                                2,
                                3000,
                                2000,
                                25000,
                                30000,
                                30000,
                                35000));
        return new ExecutionPolicy(runtime, toolkit, null);
    }
}
