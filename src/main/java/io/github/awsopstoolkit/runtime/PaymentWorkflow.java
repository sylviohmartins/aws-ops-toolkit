package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.configuration.HttpProperties;
import java.util.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.JsonNode;

/** Versioned reference rule: HTTP confirmation -> conditional update -> durable delivery steps. */
public final class PaymentWorkflow extends DynamoWorkflow {
    private final SqsClient sqs;
    private final SnsClient sns;
    private final LambdaClient lambda;
    private final S3Client s3;
    private final PaymentGateway http;
    private final HttpProperties settings;

    public PaymentWorkflow(
            DynamoDbClient dynamo,
            SqsClient sqs,
            SnsClient sns,
            LambdaClient lambda,
            S3Client s3,
            PaymentGateway http,
            HttpProperties settings) {
        super(dynamo);
        this.sqs = sqs;
        this.sns = sns;
        this.lambda = lambda;
        this.s3 = s3;
        this.http = http;
        this.settings = settings;
    }

    @Override
    public String type() {
        return "payment-repair";
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public Set<String> resources(JsonNode p) {
        return Set.of(
                required(p, "table"),
                required(p, "queue"),
                required(p, "topic"),
                required(p, "function"),
                required(p, "evidenceBucket"),
                settings.paymentEndpoint().toString());
    }

    @Override
    public void validate(JobRequest request) {
        var p = request.parameters();
        resources(p);
        ServiceWorkflow.requireQualifiedFunction(required(p, "function"));
        Set<String> fields = Set.of("table", "queue", "topic", "function", "evidenceBucket");
        p.propertyNames()
                .forEach(
                        n -> {
                            if (!fields.contains(n))
                                throw new IllegalArgumentException("Unknown parameter");
                        });
    }

    @Override
    public void preflight(JobContext c) throws Exception {
        super.preflight(c);
        var p = c.request().parameters();
        String queue = required(p, "queue");
        String topic = required(p, "topic");
        String function = required(p, "function");
        String bucket = required(p, "evidenceBucket");
        c.read(queue, () -> sqs.getQueueAttributes(b -> b.queueUrl(queue)));
        c.read(topic, () -> sns.getTopicAttributes(b -> b.topicArn(topic)));
        c.read(function, () -> lambda.getFunctionConfiguration(b -> b.functionName(function)));
        c.read(bucket, () -> s3.headBucket(b -> b.bucket(bucket)));
    }

    @Override
    public Page plan(JobContext context, int segment, String cursor) throws Exception {
        var page = super.plan(context, segment, cursor);
        var selected = new ArrayList<Candidate>();
        for (var candidate : page.records()) {
            if ("PENDING"
                    .equals(context.json.readTree(candidate.payload()).path("status").asText())) {
                selected.add(candidate);
            }
        }
        return new Page(List.copyOf(selected), page.cursor(), page.complete());
    }

    @Override
    public Proposal proposal(JsonNode parameters, SqliteJournal.Task task) {
        var item =
                software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument.fromJson(
                                task.payload())
                        .toMap();
        String status = item.getOrDefault("status", s("")).s();
        long version = Long.parseLong(item.getOrDefault("version", n(0)).n());
        return new Proposal(
                "PAYMENT_REPAIR",
                Map.of("status", status, "version", version),
                Map.of("status", "SETTLED", "version", version + 1));
    }

    @Override
    public int estimatedCallsPerCandidate() {
        return 6;
    }

    @Override
    public List<String> risks() {
        return List.of(
                "Concurrent updates may produce conditional conflicts",
                "Cross-service effects are reconciled but are not transactionally atomic",
                "Credential expiry or ambiguous transport failures may require operator reconciliation");
    }

    @Override
    public String execute(JobContext c, SqliteJournal.Task task) throws Exception {
        var p = c.request().parameters();
        var item = c.json.readTree(task.payload());
        if (!"PENDING".equals(item.path("status").asText())) return "SKIPPED";
        String table = p.path("table").asText();
        long version = item.path("version").asLong(-1);
        if (version < 0) throw new IllegalArgumentException("Missing optimistic version");
        String eventId = c.id() + ":" + task.key();
        Map<String, AttributeValue> key = Map.of("id", s(task.key()));
        // Once a write may have happened, complete/reconcile its delivery chain from the ledger.
        var priorUpdate = c.effectState(task, "dynamodb-update");
        if (priorUpdate == null || priorUpdate.state() == EffectState.NOT_SENT) {
            var remote = http.get(c, task.key());
            if (!"SETTLED".equals(remote.path("status").asText())) return "SKIPPED";
        }
        try {
            c.effect(
                    task,
                    "dynamodb-update",
                    table,
                    () -> {
                        dynamo.updateItem(
                                UpdateItemRequest.builder()
                                        .tableName(table)
                                        .key(key)
                                        .conditionExpression("#v=:v AND #s=:pending")
                                        .updateExpression(
                                                "SET #s=:settled,#v=:next,opsMarker=:marker,opsPreviousStatus=:pending")
                                        .expressionAttributeNames(
                                                Map.of("#s", "status", "#v", "version"))
                                        .expressionAttributeValues(
                                                Map.of(
                                                        ":v",
                                                        n(version),
                                                        ":next",
                                                        n(version + 1),
                                                        ":pending",
                                                        s("PENDING"),
                                                        ":settled",
                                                        s("SETTLED"),
                                                        ":marker",
                                                        s(eventId)))
                                        .build());
                        return "UPDATED";
                    },
                    () -> {
                        var actual =
                                c.read(
                                                table,
                                                () ->
                                                        dynamo.getItem(
                                                                GetItemRequest.builder()
                                                                        .tableName(table)
                                                                        .key(key)
                                                                        .consistentRead(true)
                                                                        .build()))
                                        .item();
                        return eventId.equals(actual.getOrDefault("opsMarker", s("")).s())
                                ? Optional.of("RECONCILED")
                                : Optional.empty();
                    });
        } catch (ConditionalCheckFailedException e) {
            return "CONFLICT";
        }
        String event =
                c.json.writeValueAsString(
                        Map.of(
                                "eventId",
                                eventId,
                                "paymentId",
                                task.key(),
                                "status",
                                "SETTLED",
                                "version",
                                version + 1));
        String queue = p.path("queue").asText();
        String topic = p.path("topic").asText();
        String function = p.path("function").asText();
        c.effect(
                task,
                "sqs-delivery",
                queue,
                () -> sqs.sendMessage(b -> b.queueUrl(queue).messageBody(event)).messageId(),
                Optional::empty);
        c.effect(
                task,
                "sns-delivery",
                topic,
                () -> sns.publish(b -> b.topicArn(topic).message(event)).messageId(),
                Optional::empty);
        String functionResult =
                c.effect(
                        task,
                        "lambda-validation",
                        function,
                        () -> {
                            var response =
                                    lambda.invoke(
                                            InvokeRequest.builder()
                                                    .functionName(function)
                                                    .invocationType(InvocationType.REQUEST_RESPONSE)
                                                    .payload(SdkBytes.fromUtf8String(event))
                                                    .build());
                            if (response.statusCode() != 200 || response.functionError() != null)
                                return "FUNCTION_ERROR";
                            var payload = c.json.readTree(response.payload().asUtf8String());
                            return payload.path("accepted").asBoolean(false)
                                    ? "ACCEPTED"
                                    : "BUSINESS_REJECTED";
                        },
                        Optional::empty);
        if (!"ACCEPTED".equals(functionResult)) return functionResult;
        String bucket = p.path("evidenceBucket").asText();
        String objectKey = "operations/" + c.id() + "/" + task.sequence() + ".json";
        c.effect(
                task,
                "s3-evidence",
                bucket,
                () ->
                        s3.putObject(
                                        b ->
                                                b.bucket(bucket)
                                                        .key(objectKey)
                                                        .metadata(Map.of("event-id", eventId)),
                                        RequestBody.fromString(event))
                                .eTag(),
                () -> {
                    try {
                        var head =
                                c.read(
                                        bucket,
                                        () -> s3.headObject(b -> b.bucket(bucket).key(objectKey)));
                        return eventId.equals(head.metadata().get("event-id"))
                                ? Optional.of(head.eTag())
                                : Optional.empty();
                    } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
                        return Optional.empty();
                    }
                });
        return "REPAIRED";
    }
}
