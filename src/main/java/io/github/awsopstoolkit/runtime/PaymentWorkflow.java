package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.aws.DynamoDbService;
import io.github.awsopstoolkit.configuration.HttpProperties;
import java.nio.charset.StandardCharsets;
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
        var resources = new LinkedHashSet<String>();
        resources.add(required(p, "table"));
        resources.add(required(p, "queue"));
        resources.add(required(p, "topic"));
        resources.add(required(p, "function"));
        resources.add(required(p, "evidenceBucket"));
        resources.add(settings.paymentEndpoint().toString());
        String outboxTable = optionalOutboxTable(p);
        if (outboxTable != null) resources.add(outboxTable);
        return Set.copyOf(resources);
    }

    @Override
    public void validate(JobRequest request) {
        var p = request.parameters();
        resources(p);
        ServiceWorkflow.requireQualifiedFunction(required(p, "function"));
        Set<String> fields =
                Set.of("table", "queue", "topic", "function", "evidenceBucket", "outboxTable");
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
        var target = ServiceWorkflow.lambdaTarget(function);
        String bucket = required(p, "evidenceBucket");
        String outboxTable = optionalOutboxTable(p);
        if (outboxTable != null) {
            c.read(outboxTable, () -> dynamo.describeTable(b -> b.tableName(outboxTable)));
        }
        c.read(queue, () -> sqs.getQueueAttributes(b -> b.queueUrl(queue)));
        c.read(topic, () -> sns.getTopicAttributes(b -> b.topicArn(topic)));
        c.read(
                function,
                () -> lambda.getFunctionConfiguration(b -> b.functionName(target.functionName())));
        if (target.qualifier().matches("\\d+")) {
            c.read(
                    function,
                    () ->
                            lambda.getFunctionConfiguration(
                                    b ->
                                            b.functionName(target.functionName())
                                                    .qualifier(target.qualifier())));
        } else {
            c.read(
                    function,
                    () ->
                            lambda.getAlias(
                                    b ->
                                            b.functionName(target.functionName())
                                                    .name(target.qualifier())));
        }
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
        return 9;
    }

    @Override
    public List<String> risks() {
        return List.of(
                "Concurrent updates may produce conditional conflicts",
                "Without outboxTable, cross-service effects are reconciled but are not transactionally atomic",
                "With outboxTable, payment update and event creation are atomic but broker delivery remains at-least-once",
                "Credential expiry or ambiguous transport failures may require operator reconciliation");
    }

    @Override
    public String execute(JobContext c, SqliteJournal.Task task) throws Exception {
        var p = c.request().parameters();
        var item = c.json.readTree(task.payload());
        if (!"PENDING".equals(item.path("status").asText())) return "SKIPPED";
        String table = p.path("table").asText();
        String outboxTable = optionalOutboxTable(p);
        long version = item.path("version").asLong(-1);
        if (version < 0) throw new IllegalArgumentException("Missing optimistic version");
        String eventId = c.id() + ":" + task.key();
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
        Map<String, AttributeValue> key = Map.of("id", s(task.key()));
        String updateStep = outboxTable == null ? "dynamodb-update" : "dynamodb-update-outbox";
        var priorUpdate = c.effectState(task, updateStep);
        if (priorUpdate == null || priorUpdate.state() == EffectState.NOT_SENT) {
            var remote = http.get(c, task.key());
            if (!"SETTLED".equals(remote.path("status").asText())) return "SKIPPED";
        }
        try {
            c.effect(
                    task,
                    updateStep,
                    table,
                    () -> {
                        if (outboxTable == null) {
                            dynamo.updateItem(paymentUpdate(table, key, version, eventId));
                            return "UPDATED";
                        }
                        var transaction =
                                TransactWriteItemsRequest.builder()
                                        .clientRequestToken(transactionToken(eventId))
                                        .transactItems(
                                                TransactWriteItem.builder()
                                                        .update(
                                                                paymentTransactionUpdate(
                                                                        table,
                                                                        key,
                                                                        version,
                                                                        eventId))
                                                        .build(),
                                                TransactWriteItem.builder()
                                                        .put(
                                                                Put.builder()
                                                                        .tableName(outboxTable)
                                                                        .item(
                                                                                Map.of(
                                                                                        "eventId",
                                                                                        s(eventId),
                                                                                        "operationId",
                                                                                        s(c.id()),
                                                                                        "paymentId",
                                                                                        s(task.key()),
                                                                                        "payload",
                                                                                        s(event),
                                                                                        "deliveryState",
                                                                                        s("PENDING"),
                                                                                        "version",
                                                                                        n(version + 1)))
                                                                        .conditionExpression(
                                                                                "attribute_not_exists(eventId)")
                                                                        .build())
                                                        .build())
                                        .build();
                        DynamoDbService.validateConditionalTransaction(transaction);
                        dynamo.transactWriteItems(transaction);
                        return "UPDATED_WITH_OUTBOX";
                    },
                    () ->
                            reconcileUpdate(
                                    c,
                                    table,
                                    outboxTable,
                                    key,
                                    eventId,
                                    task.key(),
                                    event));
        } catch (ConditionalCheckFailedException e) {
            return "CONFLICT";
        } catch (TransactionCanceledException e) {
            if (e.cancellationReasons().stream()
                    .anyMatch(reason -> "ConditionalCheckFailed".equals(reason.code()))) {
                return "CONFLICT";
            }
            throw e;
        }

        String queue = p.path("queue").asText();
        String topic = p.path("topic").asText();
        String function = p.path("function").asText();
        var functionTarget = ServiceWorkflow.lambdaTarget(function);
        String sqsMessageId =
                c.effect(
                        task,
                        "sqs-delivery",
                        queue,
                        () -> sqs.sendMessage(b -> b.queueUrl(queue).messageBody(event)).messageId(),
                        Optional::empty);
        if (outboxTable != null) {
            markOutboxField(
                    c,
                    task,
                    outboxTable,
                    eventId,
                    "outbox-sqs-accepted",
                    "sqsMessageId",
                    sqsMessageId);
        }
        String snsMessageId =
                c.effect(
                        task,
                        "sns-delivery",
                        topic,
                        () -> sns.publish(b -> b.topicArn(topic).message(event)).messageId(),
                        Optional::empty);
        if (outboxTable != null) {
            markOutboxField(
                    c,
                    task,
                    outboxTable,
                    eventId,
                    "outbox-sns-accepted",
                    "snsMessageId",
                    snsMessageId);
            markOutboxDelivered(c, task, outboxTable, eventId);
        }
        String functionResult =
                c.effect(
                        task,
                        "lambda-validation",
                        function,
                        () -> {
                            var response =
                                    lambda.invoke(
                                            InvokeRequest.builder()
                                                    .functionName(functionTarget.functionName())
                                                    .qualifier(functionTarget.qualifier())
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

    private static String optionalOutboxTable(JsonNode parameters) {
        return parameters.has("outboxTable") ? required(parameters, "outboxTable") : null;
    }

    private static UpdateItemRequest paymentUpdate(
            String table, Map<String, AttributeValue> key, long version, String eventId) {
        return UpdateItemRequest.builder()
                .tableName(table)
                .key(key)
                .conditionExpression("#v=:v AND #s=:pending")
                .updateExpression(
                        "SET #s=:settled,#v=:next,opsMarker=:marker,opsPreviousStatus=:pending")
                .expressionAttributeNames(Map.of("#s", "status", "#v", "version"))
                .expressionAttributeValues(
                        paymentUpdateValues(version, eventId))
                .build();
    }

    private static Update paymentTransactionUpdate(
            String table, Map<String, AttributeValue> key, long version, String eventId) {
        return Update.builder()
                .tableName(table)
                .key(key)
                .conditionExpression("#v=:v AND #s=:pending")
                .updateExpression(
                        "SET #s=:settled,#v=:next,opsMarker=:marker,opsPreviousStatus=:pending")
                .expressionAttributeNames(Map.of("#s", "status", "#v", "version"))
                .expressionAttributeValues(paymentUpdateValues(version, eventId))
                .build();
    }

    private static Map<String, AttributeValue> paymentUpdateValues(long version, String eventId) {
        return Map.of(
                ":v",
                n(version),
                ":next",
                n(version + 1),
                ":pending",
                s("PENDING"),
                ":settled",
                s("SETTLED"),
                ":marker",
                s(eventId));
    }

    private static String transactionToken(String eventId) {
        return UUID.nameUUIDFromBytes(eventId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private Optional<String> reconcileUpdate(
            JobContext c,
            String table,
            String outboxTable,
            Map<String, AttributeValue> key,
            String eventId,
            String paymentId,
            String event)
            throws Exception {
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
        if (!eventId.equals(actual.getOrDefault("opsMarker", s("")).s())) {
            return Optional.empty();
        }
        if (outboxTable == null) return Optional.of("RECONCILED");
        var outbox =
                c.read(
                                outboxTable,
                                () ->
                                        dynamo.getItem(
                                                GetItemRequest.builder()
                                                        .tableName(outboxTable)
                                                        .key(Map.of("eventId", s(eventId)))
                                                        .consistentRead(true)
                                                        .build()))
                        .item();
        return eventId.equals(outbox.getOrDefault("eventId", s("")).s())
                        && paymentId.equals(outbox.getOrDefault("paymentId", s("")).s())
                        && event.equals(outbox.getOrDefault("payload", s("")).s())
                ? Optional.of("RECONCILED_WITH_OUTBOX")
                : Optional.empty();
    }

    private void markOutboxField(
            JobContext c,
            SqliteJournal.Task task,
            String outboxTable,
            String eventId,
            String step,
            String field,
            String value)
            throws Exception {
        c.effect(
                task,
                step,
                outboxTable,
                () -> {
                    dynamo.updateItem(
                            UpdateItemRequest.builder()
                                    .tableName(outboxTable)
                                    .key(Map.of("eventId", s(eventId)))
                                    .conditionExpression("attribute_exists(eventId)")
                                    .updateExpression("SET #field=:value")
                                    .expressionAttributeNames(Map.of("#field", field))
                                    .expressionAttributeValues(Map.of(":value", s(value)))
                                    .build());
                    return value;
                },
                () -> {
                    var actual =
                            c.read(
                                            outboxTable,
                                            () ->
                                                    dynamo.getItem(
                                                            GetItemRequest.builder()
                                                                    .tableName(outboxTable)
                                                                    .key(
                                                                            Map.of(
                                                                                    "eventId",
                                                                                    s(eventId)))
                                                                    .consistentRead(true)
                                                                    .build()))
                                    .item();
                    return value.equals(actual.getOrDefault(field, s("")).s())
                            ? Optional.of(value)
                            : Optional.empty();
                });
    }

    private void markOutboxDelivered(
            JobContext c, SqliteJournal.Task task, String outboxTable, String eventId)
            throws Exception {
        c.effect(
                task,
                "outbox-delivered",
                outboxTable,
                () -> {
                    dynamo.updateItem(
                            UpdateItemRequest.builder()
                                    .tableName(outboxTable)
                                    .key(Map.of("eventId", s(eventId)))
                                    .conditionExpression(
                                            "attribute_exists(sqsMessageId) AND attribute_exists(snsMessageId)")
                                    .updateExpression("SET deliveryState=:delivered")
                                    .expressionAttributeValues(
                                            Map.of(":delivered", s("DELIVERED")))
                                    .build());
                    return "DELIVERED";
                },
                () -> {
                    var actual =
                            c.read(
                                            outboxTable,
                                            () ->
                                                    dynamo.getItem(
                                                            GetItemRequest.builder()
                                                                    .tableName(outboxTable)
                                                                    .key(
                                                                            Map.of(
                                                                                    "eventId",
                                                                                    s(eventId)))
                                                                    .consistentRead(true)
                                                                    .build()))
                                    .item();
                    return "DELIVERED".equals(
                                    actual.getOrDefault("deliveryState", s("")).s())
                            ? Optional.of("DELIVERED")
                            : Optional.empty();
                });
    }

}
