package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.aws.S3Limits;
import io.github.awsopstoolkit.configuration.S3Properties;
import io.github.awsopstoolkit.configuration.SqsProperties;
import io.github.awsopstoolkit.security.Hashing;
import java.io.*;
import java.util.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.JsonNode;

/** Reviewable single-command plans for explicit messaging and object operations. */
public final class ServiceWorkflow implements Workflow {
    private static final String REFRESH_PREFIX = "receive-refresh/";
    private static final int MAX_COMMAND_MESSAGES = 1_000;
    private static final int MAX_POISON_RECEIVE_COUNT = 1_000;
    private static final int MAX_INLINE_PAYLOAD_CHARS = 65_536;

    public enum Kind {
        SQS_INSPECT,
        SQS_CONSUME,
        DLQ_REPLAY,
        SNS_PUBLISH,
        LAMBDA_INVOKE,
        S3_COPY,
        S3_DELETE,
        S3_ABORT_MULTIPART
    }

    private final Kind kind;
    private final SqsClient sqs;
    private final SnsClient sns;
    private final LambdaClient lambda;
    private final S3Client s3;
    private final software.amazon.awssdk.services.dynamodb.DynamoDbClient dynamo;
    private final int visibilityTimeoutSeconds;
    private final int receiveWaitTimeSeconds;
    private final int receiptReacquireAttempts;
    private final int poisonReceiveCount;
    private final int acknowledgementLeaseReserveSeconds;
    private final int receiptReuseLeaseReserveSeconds;
    private final long multipartPartSizeBytes;

    public ServiceWorkflow(
            Kind kind,
            SqsClient sqs,
            SnsClient sns,
            LambdaClient lambda,
            S3Client s3,
            software.amazon.awssdk.services.dynamodb.DynamoDbClient dynamo,
            SqsProperties sqsProperties,
            S3Properties s3Properties) {
        this.kind = kind;
        this.sqs = sqs;
        this.sns = sns;
        this.lambda = lambda;
        this.s3 = s3;
        this.dynamo = dynamo;
        this.visibilityTimeoutSeconds = sqsProperties.visibilityTimeoutSeconds();
        this.receiveWaitTimeSeconds = sqsProperties.receiveWaitTimeSeconds();
        this.receiptReacquireAttempts = sqsProperties.receiptReacquireAttempts();
        this.poisonReceiveCount = sqsProperties.poisonReceiveCount();
        this.acknowledgementLeaseReserveSeconds =
                sqsProperties.acknowledgementLeaseReserveSeconds();
        this.receiptReuseLeaseReserveSeconds = sqsProperties.receiptReuseLeaseReserveSeconds();
        this.multipartPartSizeBytes = s3Properties.multipartPartBytes();
    }

    @Override
    public String type() {
        return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Override
    public Set<String> resources(JsonNode p) {
        var result = new HashSet<String>();
        for (String key :
                switch (kind) {
                    case SQS_INSPECT -> List.of("queue");
                    case SQS_CONSUME -> List.of("queue", "table");
                    case DLQ_REPLAY -> List.of("queue", "destinationQueue");
                    case SNS_PUBLISH -> List.of("topic");
                    case LAMBDA_INVOKE -> List.of("function");
                    case S3_COPY -> List.of("bucket", "destinationBucket");
                    case S3_DELETE, S3_ABORT_MULTIPART -> List.of("bucket");
                }) result.add(DynamoWorkflow.required(p, key));
        return Set.copyOf(result);
    }

    @Override
    public void validate(JobRequest request) {
        var p = request.parameters();
        resources(p);
        var allowed = allowedFields();
        p.propertyNames()
                .forEach(
                        name -> {
                            if (!allowed.contains(name))
                                throw new IllegalArgumentException("Unknown parameter: " + name);
                        });
        if (request.segments() != 1)
            throw new IllegalArgumentException("This workflow uses one source");
        if (kind == Kind.SQS_INSPECT || kind == Kind.DLQ_REPLAY || kind == Kind.SQS_CONSUME) {
            if (request.mode() == JobMode.EXECUTE
                    && (!request.visibilityImpactAccepted()
                            || !request.sharedConsumerImpactAccepted()))
                throw new IllegalArgumentException(
                        "Explicit receive and consumer impact consent required for EXECUTE");
            if (p.path("messages").asInt(0) < 1
                    || p.path("messages").asInt() > MAX_COMMAND_MESSAGES)
                throw new IllegalArgumentException("messages must be 1.." + MAX_COMMAND_MESSAGES);
            int poisonReceiveCount = p.path("poisonReceiveCount").asInt(this.poisonReceiveCount);
            if (poisonReceiveCount < 1 || poisonReceiveCount > MAX_POISON_RECEIVE_COUNT)
                throw new IllegalArgumentException(
                        "poisonReceiveCount must be 1.." + MAX_POISON_RECEIVE_COUNT);
        }
        if (kind == Kind.SNS_PUBLISH || kind == Kind.LAMBDA_INVOKE) {
            if (!p.has("payload")
                    || p.path("payload").toString().length() > MAX_INLINE_PAYLOAD_CHARS)
                throw new IllegalArgumentException("A bounded payload is required");
        }
        if (kind == Kind.LAMBDA_INVOKE) {
            requireQualifiedFunction(p.path("function").asText());
            if (!Set.of("RequestResponse", "Event", "DryRun")
                    .contains(p.path("invocationType").asText()))
                throw new IllegalArgumentException("Explicit invocation type required");
        }
        if (kind == Kind.S3_COPY || kind == Kind.S3_DELETE) {
            DynamoWorkflow.required(p, "key");
            DynamoWorkflow.required(p, "versionId");
            if ("null".equals(p.path("versionId").asText()))
                throw new IllegalArgumentException("An immutable version is required");
            if (kind == Kind.S3_COPY) DynamoWorkflow.required(p, "destinationKey");
            if (p.path("maxBytes").asLong(0) < 1
                    || p.path("maxBytes").asLong() > S3Limits.MAX_OBJECT_BYTES)
                throw new IllegalArgumentException("Explicit object byte budget required");
        }
        if (kind == Kind.S3_ABORT_MULTIPART) {
            DynamoWorkflow.required(p, "key");
            DynamoWorkflow.required(p, "uploadId");
        }
    }

    private Set<String> allowedFields() {
        return switch (kind) {
            case SQS_INSPECT -> Set.of("queue", "messages", "poisonReceiveCount");
            case SQS_CONSUME -> Set.of("queue", "table", "messages", "poisonReceiveCount");
            case DLQ_REPLAY ->
                    Set.of(
                            "queue",
                            "destinationQueue",
                            "messages",
                            "groupId",
                            "poisonReceiveCount");
            case SNS_PUBLISH -> Set.of("topic", "payload", "groupId");
            case LAMBDA_INVOKE -> Set.of("function", "invocationType", "payload");
            case S3_COPY ->
                    Set.of(
                            "bucket",
                            "key",
                            "versionId",
                            "destinationBucket",
                            "destinationKey",
                            "maxBytes",
                            "multipart");
            case S3_DELETE -> Set.of("bucket", "key", "versionId", "maxBytes");
            case S3_ABORT_MULTIPART -> Set.of("bucket", "key", "uploadId");
        };
    }

    static void requireQualifiedFunction(String function) {
        if (!function.matches(
                "(?:arn:aws(?:-[a-z]+)*:lambda:[a-z0-9-]+:\\d{12}:function:)?[A-Za-z0-9_-]+:[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException(
                    "A numeric Lambda version or named alias is required");
    }

    @Override
    public void preflight(JobContext c) throws Exception {
        var p = c.request().parameters();
        switch (kind) {
            case SQS_INSPECT, DLQ_REPLAY, SQS_CONSUME -> {
                String queue = DynamoWorkflow.required(p, "queue");
                c.read(queue, () -> sqs.getQueueAttributes(b -> b.queueUrl(queue)));
                if (kind == Kind.DLQ_REPLAY) {
                    String destination = DynamoWorkflow.required(p, "destinationQueue");
                    c.read(destination, () -> sqs.getQueueAttributes(b -> b.queueUrl(destination)));
                } else if (kind == Kind.SQS_CONSUME) {
                    String table = DynamoWorkflow.required(p, "table");
                    c.read(table, () -> dynamo.describeTable(b -> b.tableName(table)));
                }
            }
            case SNS_PUBLISH -> {
                String topic = DynamoWorkflow.required(p, "topic");
                c.read(topic, () -> sns.getTopicAttributes(b -> b.topicArn(topic)));
            }
            case LAMBDA_INVOKE -> {
                String function = DynamoWorkflow.required(p, "function");
                c.read(
                        function,
                        () -> lambda.getFunctionConfiguration(b -> b.functionName(function)));
            }
            case S3_COPY -> {
                String source = DynamoWorkflow.required(p, "bucket");
                String destination = DynamoWorkflow.required(p, "destinationBucket");
                c.read(source, () -> s3.headBucket(b -> b.bucket(source)));
                c.read(destination, () -> s3.headBucket(b -> b.bucket(destination)));
            }
            case S3_DELETE, S3_ABORT_MULTIPART -> {
                String bucket = DynamoWorkflow.required(p, "bucket");
                c.read(bucket, () -> s3.headBucket(b -> b.bucket(bucket)));
            }
        }
    }

    @Override
    public Proposal proposal(JsonNode parameters, SqliteJournal.Task task) {
        var before = new LinkedHashMap<String, Object>();
        var after = new LinkedHashMap<String, Object>();
        String action;
        switch (kind) {
            case SQS_INSPECT -> {
                action = "SQS_INSPECT";
                before.put("messagesRequested", parameters.path("messages").asInt());
                after.put("acknowledge", false);
            }
            case SQS_CONSUME -> {
                action = "SQS_CONSUME";
                before.put("messagesRequested", parameters.path("messages").asInt());
                after.put("ackAfterDurableProcessing", true);
            }
            case DLQ_REPLAY -> {
                action = "DLQ_REPLAY";
                before.put("messagesRequested", parameters.path("messages").asInt());
                after.put("ackSourceAfterDestination", true);
            }
            case SNS_PUBLISH -> action = "SNS_PUBLISH";
            case LAMBDA_INVOKE -> {
                action = "LAMBDA_INVOKE";
                after.put("invocationType", parameters.path("invocationType").asText());
            }
            case S3_COPY -> {
                action = "S3_COPY";
                before.put("maxBytes", parameters.path("maxBytes").asLong());
                after.put("multipart", parameters.path("multipart").asBoolean(false));
            }
            case S3_DELETE -> {
                action = "S3_DELETE_VERSION";
                before.put("maxBytes", parameters.path("maxBytes").asLong());
            }
            case S3_ABORT_MULTIPART -> action = "S3_ABORT_MULTIPART";
            default -> throw new IllegalStateException("Unsupported workflow");
        }
        return new Proposal(action, Map.copyOf(before), Map.copyOf(after));
    }

    @Override
    public int estimatedCallsPerCandidate() {
        return switch (kind) {
            case SQS_INSPECT -> 1;
            case SQS_CONSUME, DLQ_REPLAY -> 3;
            case SNS_PUBLISH, LAMBDA_INVOKE, S3_ABORT_MULTIPART -> 1;
            case S3_COPY -> 2;
            case S3_DELETE -> 2;
        };
    }

    @Override
    public List<String> risks() {
        return switch (kind) {
            case SQS_INSPECT, SQS_CONSUME, DLQ_REPLAY ->
                    List.of(
                            "ReceiveMessage changes message visibility and receive count",
                            "Shared queues may affect concurrent consumers",
                            "Acknowledgement is allowed only after durable downstream outcome");
            case SNS_PUBLISH ->
                    List.of(
                            "Publish acknowledgement does not prove downstream business processing",
                            "Ambiguous network outcomes require reconciliation before replay");
            case LAMBDA_INVOKE ->
                    List.of(
                            "Transport acceptance is distinct from business success",
                            "Async invocation may complete after the local request returns");
            case S3_COPY, S3_DELETE, S3_ABORT_MULTIPART ->
                    List.of(
                            "Version and retention semantics must remain stable",
                            "Ambiguous remote outcomes require evidence before retry");
        };
    }

    @Override
    public Page plan(JobContext c, int segment, String cursor) throws Exception {
        var p = c.request().parameters();
        if (kind == Kind.S3_COPY || kind == Kind.S3_DELETE) {
            var head =
                    c.read(
                            p.path("bucket").asText(),
                            () ->
                                    s3.headObject(
                                            b ->
                                                    b.bucket(p.path("bucket").asText())
                                                            .key(p.path("key").asText())
                                                            .versionId(
                                                                    p.path("versionId").asText())));
            if (head.contentLength() > p.path("maxBytes").asLong())
                throw new JobStopped(JobState.BUDGET_EXCEEDED);
            if (kind == Kind.S3_DELETE
                    && (head.objectLockLegalHoldStatus() == ObjectLockLegalHoldStatus.ON
                            || head.objectLockRetainUntilDate() != null
                                    && head.objectLockRetainUntilDate()
                                            .isAfter(java.time.Instant.now())))
                throw new IllegalArgumentException("Object retention prohibits deletion");
        }
        int total =
                (kind == Kind.SQS_INSPECT || kind == Kind.DLQ_REPLAY || kind == Kind.SQS_CONSUME)
                        ? p.path("messages").asInt()
                        : 1;
        int start = cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
        int end = Math.min(total, start + c.settings.pageSize());
        List<Candidate> records = new ArrayList<>();
        for (int i = start; i < end; i++) records.add(new Candidate("command-" + i, p.toString()));
        return new Page(List.copyOf(records), Integer.toString(end), end == total);
    }

    @Override
    public String execute(JobContext c, SqliteJournal.Task task) throws Exception {
        var p = c.json.readTree(task.payload());
        return switch (kind) {
            case SQS_INSPECT, DLQ_REPLAY, SQS_CONSUME -> receive(c, task, p);
            case S3_ABORT_MULTIPART -> {
                String bucket = p.path("bucket").asText();
                c.effect(
                        task,
                        "abort-multipart",
                        bucket,
                        () -> {
                            s3.abortMultipartUpload(
                                    b ->
                                            b.bucket(bucket)
                                                    .key(p.path("key").asText())
                                                    .uploadId(p.path("uploadId").asText()));
                            return "ABORTED";
                        },
                        Optional::empty);
                yield "ABORTED";
            }
            case SNS_PUBLISH -> {
                String topic = p.path("topic").asText();
                c.effect(
                        task,
                        "publish",
                        topic,
                        () -> {
                            var request =
                                    software.amazon.awssdk.services.sns.model.PublishRequest
                                            .builder()
                                            .topicArn(topic)
                                            .message(p.path("payload").toString());
                            if (topic.endsWith(".fifo"))
                                request.messageGroupId(DynamoWorkflow.required(p, "groupId"))
                                        .messageDeduplicationId(c.id() + "-" + task.sequence());
                            return sns.publish(request.build()).messageId();
                        },
                        Optional::empty);
                yield "PUBLISHED";
            }
            case LAMBDA_INVOKE -> {
                String function = p.path("function").asText();
                String result =
                        c.effect(
                                task,
                                "invoke",
                                function,
                                () -> {
                                    var mode =
                                            InvocationType.fromValue(
                                                    p.path("invocationType").asText());
                                    var response =
                                            lambda.invoke(
                                                    b ->
                                                            b.functionName(function)
                                                                    .invocationType(mode)
                                                                    .payload(
                                                                            SdkBytes.fromUtf8String(
                                                                                    p.path(
                                                                                                    "payload")
                                                                                            .toString())));
                                    if (response.functionError() != null) return "FUNCTION_ERROR";
                                    return switch (mode) {
                                        case EVENT ->
                                                response.statusCode() == 202
                                                        ? "ACCEPTED"
                                                        : "FAILED";
                                        case DRY_RUN ->
                                                response.statusCode() == 204
                                                        ? "PERMISSION_CHECKED"
                                                        : "FAILED";
                                        default ->
                                                response.statusCode() == 200
                                                        ? "EXECUTED"
                                                        : "FAILED";
                                    };
                                },
                                Optional::empty);
                yield result;
            }
            case S3_COPY -> copy(c, task, p);
            case S3_DELETE -> {
                String bucket = p.path("bucket").asText();
                c.effect(
                        task,
                        "delete-version",
                        bucket,
                        () -> {
                            s3.deleteObject(
                                    b ->
                                            b.bucket(bucket)
                                                    .key(p.path("key").asText())
                                                    .versionId(p.path("versionId").asText()));
                            return "DELETED";
                        },
                        () -> {
                            try {
                                c.read(
                                        bucket,
                                        () ->
                                                s3.headObject(
                                                        b ->
                                                                b.bucket(bucket)
                                                                        .key(p.path("key").asText())
                                                                        .versionId(
                                                                                p.path("versionId")
                                                                                        .asText())));
                                return Optional.empty();
                            } catch (S3Exception e) {
                                if (e.statusCode() == 404) return Optional.of("ABSENT");
                                throw e;
                            }
                        });
                yield "DELETED";
            }
        };
    }

    private String receive(JobContext c, SqliteJournal.Task task, JsonNode p) throws Exception {
        String queue = p.path("queue").asText();
        // Receive itself is an approved effect. Dry-run only records the command and its impact.
        var original = receiveEnvelope(c, task, queue, "receive");
        if (!original.has("id")) return "EMPTY";
        if (original.path("receiveCount").asInt(1)
                >= p.path("poisonReceiveCount").asInt(poisonReceiveCount)) {
            c.audit("POISON_MESSAGE_NO_ACK", Long.toString(task.sequence()));
            return "POISON_NO_ACK";
        }
        if (kind == Kind.SQS_INSPECT) {
            c.audit("MESSAGE_INSPECTED", original.path("id").asText());
            return "INSPECTED_NO_ACK";
        }
        var acknowledgement = c.latestEffect(task, "ack");
        if (acknowledgement != null) {
            if (acknowledgement.state() == EffectState.SUCCEEDED)
                return kind == Kind.SQS_CONSUME ? "CONSUMED" : "REPLAYED";
            if (acknowledgement.state() != EffectState.NOT_SENT)
                throw new JobStopped(JobState.RECONCILIATION_REQUIRED);
        }
        var message = freshReceipt(c, task, queue, original);
        if (kind == Kind.SQS_CONSUME) {
            var body = c.json.readTree(message.path("body").asText());
            String eventId = DynamoWorkflow.required(body, "eventId");
            String table = p.path("table").asText();
            String digest = Hashing.sha256Hex(message.path("body").asText());
            var values =
                    Map.of(
                            "id",
                            DynamoWorkflow.s(eventId),
                            "payloadHash",
                            DynamoWorkflow.s(digest));
            try {
                c.effect(
                        task,
                        "consume",
                        table,
                        () -> {
                            dynamo.putItem(
                                    b ->
                                            b.tableName(table)
                                                    .item(values)
                                                    .conditionExpression(
                                                            "attribute_not_exists(id)"));
                            return "PROCESSED";
                        },
                        () -> {
                            var actual =
                                    c.read(
                                                    table,
                                                    () ->
                                                            dynamo.getItem(
                                                                    b ->
                                                                            b.tableName(table)
                                                                                    .key(
                                                                                            Map.of(
                                                                                                    "id",
                                                                                                    DynamoWorkflow
                                                                                                            .s(
                                                                                                                    eventId)))
                                                                                    .consistentRead(
                                                                                            true)))
                                            .item();
                            return digest.equals(
                                            actual.getOrDefault("payloadHash", DynamoWorkflow.s(""))
                                                    .s())
                                    ? Optional.of("DEDUPLICATED")
                                    : Optional.empty();
                        });
            } catch (
                    software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
                            e) {
                var actual =
                        c.read(
                                        table,
                                        () ->
                                                dynamo.getItem(
                                                        b ->
                                                                b.tableName(table)
                                                                        .key(
                                                                                Map.of(
                                                                                        "id",
                                                                                        DynamoWorkflow
                                                                                                .s(
                                                                                                        eventId)))
                                                                        .consistentRead(true)))
                                .item();
                if (!digest.equals(actual.getOrDefault("payloadHash", DynamoWorkflow.s("")).s()))
                    return "CONFLICT";
            }
            acknowledge(c, task, queue, message);
            return "CONSUMED";
        }
        String destination = p.path("destinationQueue").asText();
        c.delivery(
                task,
                "send/" + message.path("id").asText(),
                destination,
                () -> {
                    var send =
                            software.amazon.awssdk.services.sqs.model.SendMessageRequest.builder()
                                    .queueUrl(destination)
                                    .messageAttributes(MessageEnvelope.attributes(message))
                                    .messageBody(message.path("body").asText());
                    if (destination.endsWith(".fifo"))
                        send.messageGroupId(DynamoWorkflow.required(p, "groupId"))
                                .messageDeduplicationId(message.path("id").asText());
                    return sqs.sendMessage(send.build()).messageId();
                });
        acknowledge(c, task, queue, message);
        return "REPLAYED";
    }

    private void acknowledge(JobContext c, SqliteJournal.Task task, String queue, JsonNode message)
            throws Exception {
        var current = freshReceipt(c, task, queue, message);
        c.effect(
                task,
                "ack",
                queue,
                () -> {
                    // Authorization and rate waiting happen before this supplier runs.
                    if (!leaseValid(current, acknowledgementLeaseReserveSeconds))
                        throw new EffectNotDispatched(JobState.PAUSED);
                    sqs.deleteMessage(
                            b -> b.queueUrl(queue).receiptHandle(current.path("receipt").asText()));
                    return "ACKNOWLEDGED";
                },
                Optional::empty);
    }

    private JsonNode receiveEnvelope(
            JobContext c, SqliteJournal.Task task, String queue, String step) throws Exception {
        String result =
                c.effect(
                        task,
                        step,
                        queue,
                        () -> {
                            // Start before dispatch so transport latency never extends the recorded
                            // lease.
                            long receivedAt = SqliteJournal.now();
                            var response =
                                    sqs.receiveMessage(
                                            b ->
                                                    b.queueUrl(queue)
                                                            .maxNumberOfMessages(1)
                                                            .waitTimeSeconds(receiveWaitTimeSeconds)
                                                            .visibilityTimeout(
                                                                    visibilityTimeoutSeconds)
                                                            .messageAttributeNames("All")
                                                            .messageSystemAttributeNames(
                                                                    software.amazon.awssdk.services
                                                                            .sqs.model
                                                                            .MessageSystemAttributeName
                                                                            .APPROXIMATE_RECEIVE_COUNT));
                            return response.messages().isEmpty()
                                    ? "{}"
                                    : MessageEnvelope.encode(
                                            response.messages().getFirst(),
                                            c.json,
                                            receivedAt,
                                            visibilityTimeoutSeconds);
                        },
                        Optional::empty);
        return c.json.readTree(result);
    }

    /** Bounded receipt reacquisition; durable sequence numbers allow a later bounded retry. */
    private JsonNode freshReceipt(
            JobContext c, SqliteJournal.Task task, String queue, JsonNode original)
            throws Exception {
        var latest = c.latestEffect(task, REFRESH_PREFIX);
        long next = 1;
        if (latest == null) {
            if (leaseValid(original, receiptReuseLeaseReserveSeconds)) return original;
        } else {
            next = Long.parseLong(latest.step().substring(REFRESH_PREFIX.length()));
            if (latest.state() == EffectState.SUCCEEDED) {
                var cached = c.json.readTree(latest.result());
                if (sameMessage(original, cached)
                        && leaseValid(cached, receiptReuseLeaseReserveSeconds)) return cached;
                if (cached.has("id") && !sameMessage(original, cached))
                    releaseUnrelated(c, task, queue, latest.step(), cached);
                next++;
            } else if (latest.state() != EffectState.NOT_SENT) {
                throw new JobStopped(JobState.RECONCILIATION_REQUIRED);
            }
        }
        for (int attempt = 0; attempt < receiptReacquireAttempts; attempt++, next++) {
            c.checkpoint();
            String step = REFRESH_PREFIX + String.format(Locale.ROOT, "%010d", next);
            var received = receiveEnvelope(c, task, queue, step);
            if (!received.has("id")) continue;
            if (sameMessage(original, received)) {
                if (leaseValid(received, receiptReuseLeaseReserveSeconds)) return received;
            } else releaseUnrelated(c, task, queue, step, received);
        }
        c.audit("RECEIPT_REACQUISITION_REQUIRED", Long.toString(task.sequence()));
        throw new JobStopped(JobState.RECONCILIATION_REQUIRED);
    }

    private void releaseUnrelated(
            JobContext c,
            SqliteJournal.Task task,
            String queue,
            String receiveStep,
            JsonNode message)
            throws Exception {
        c.effect(
                task,
                "release/" + receiveStep,
                queue,
                () -> {
                    if (!leaseValid(message, 0)) return "LEASE_EXPIRED";
                    sqs.changeMessageVisibility(
                            b ->
                                    b.queueUrl(queue)
                                            .receiptHandle(message.path("receipt").asText())
                                            .visibilityTimeout(0));
                    return "RELEASED";
                },
                () -> leaseValid(message, 0) ? Optional.empty() : Optional.of("LEASE_EXPIRED"));
    }

    private static boolean sameMessage(JsonNode original, JsonNode candidate) {
        return original.path("id").asText().equals(candidate.path("id").asText());
    }

    private static boolean leaseValid(JsonNode message, int reserveSeconds) {
        return !message.path("receipt").asText().isBlank()
                && message.path("leaseUntil").asLong(0) > SqliteJournal.now() + reserveSeconds;
    }

    private String copy(JobContext c, SqliteJournal.Task task, JsonNode p) throws Exception {
        String source = p.path("bucket").asText(),
                target = p.path("destinationBucket").asText(),
                key = p.path("destinationKey").asText();
        String marker = c.id() + ":" + task.sequence();
        var head =
                c.read(
                        source,
                        () ->
                                s3.headObject(
                                        b ->
                                                b.bucket(source)
                                                        .key(p.path("key").asText())
                                                        .versionId(p.path("versionId").asText())));
        if (head.contentLength() > p.path("maxBytes").asLong())
            throw new JobStopped(JobState.BUDGET_EXCEEDED);
        if (head.contentLength() > S3Limits.SINGLE_COPY_MAX_BYTES
                || p.path("multipart").asBoolean(false)) {
            MultipartCopy.copy(
                    c,
                    task,
                    s3,
                    source,
                    p.path("key").asText(),
                    p.path("versionId").asText(),
                    target,
                    key,
                    head.contentLength(),
                    multipartPartSizeBytes);
            return "COPIED_MULTIPART";
        }
        c.effect(
                task,
                "copy",
                target,
                () -> {
                    // Server-side copy uses bounded client memory; version pins the immutable
                    // source.
                    return s3.copyObject(
                                    b ->
                                            b.sourceBucket(source)
                                                    .sourceKey(p.path("key").asText())
                                                    .sourceVersionId(p.path("versionId").asText())
                                                    .destinationBucket(target)
                                                    .destinationKey(key)
                                                    .metadataDirective(MetadataDirective.REPLACE)
                                                    .metadata(Map.of("operation-marker", marker)))
                            .copyObjectResult()
                            .eTag();
                },
                () -> {
                    try {
                        var actual =
                                c.read(target, () -> s3.headObject(b -> b.bucket(target).key(key)));
                        return marker.equals(actual.metadata().get("operation-marker"))
                                ? Optional.of(actual.eTag())
                                : Optional.empty();
                    } catch (S3Exception e) {
                        if (e.statusCode() == 404) return Optional.empty();
                        throw e;
                    }
                });
        return "COPIED";
    }
}
