package io.github.awsopstoolkit.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Reference adapter, deliberately independent from Spring and the operation engine. Callbacks must
 * durably finish a page before returning; checkpoints are published afterwards. Retries of SDK
 * requests are owned by the configured client, not repeated here.
 */
public final class DynamoDbService {
    private final DynamoDbClient client;
    private final AwsCallGate gate;
    private final WriteAuthorization authorization;
    private final int maxPageItems;

    public DynamoDbService(
            DynamoDbClient client,
            AwsCallGate gate,
            WriteAuthorization authorization,
            int maxPageItems) {
        this.client = Objects.requireNonNull(client);
        this.gate = Objects.requireNonNull(gate);
        this.authorization = Objects.requireNonNull(authorization);
        if (maxPageItems < 1 || maxPageItems > 10_000) {
            throw new IllegalArgumentException("maxPageItems must be between 1 and 10000");
        }
        this.maxPageItems = maxPageItems;
    }

    public GetItemResponse get(GetItemRequest request) throws InterruptedException {
        return gate.call(() -> client.getItem(request));
    }

    /**
     * Returns partial successes and UnprocessedKeys unchanged; the caller owns their retry budget.
     */
    public BatchGetItemResponse batchGet(BatchGetItemRequest request) throws InterruptedException {
        int keys =
                request.requestItems().values().stream()
                        .mapToInt(value -> value.keys().size())
                        .sum();
        if (keys < 1 || keys > 100) {
            throw new IllegalArgumentException("BatchGet requires 1 to 100 keys");
        }
        return gate.call(() -> client.batchGetItem(request));
    }

    /** Paginates Query, including empty filtered pages. Return value is the next durable cursor. */
    public Map<String, AttributeValue> queryPages(
            QueryRequest template,
            long maxPages,
            BooleanSupplier cancelled,
            QueryPageConsumer pageConsumer,
            QueryCheckpointConsumer checkpointConsumer)
            throws Exception {
        requirePages(maxPages);
        Map<String, AttributeValue> cursor = template.exclusiveStartKey();
        for (long page = 0; page < maxPages; page++) {
            checkCancelled(cancelled);
            QueryRequest request =
                    template.toBuilder()
                            .exclusiveStartKey(cursor.isEmpty() ? null : cursor)
                            .limit(pageLimit(template.limit()))
                            .returnConsumedCapacity(ReturnConsumedCapacity.INDEXES)
                            .build();
            QueryResponse response = gate.call(() -> client.query(request));
            checkCancelled(cancelled);
            pageConsumer.accept(response);
            cursor = Map.copyOf(response.lastEvaluatedKey());
            checkpointConsumer.save(cursor, cursor.isEmpty());
            if (cursor.isEmpty()) {
                break;
            }
        }
        return cursor;
    }

    /**
     * A fixed number of virtual workers processes segments; no task is created per item/page.
     * totalSegments is part of resume identity and cannot change on resume. Each segment may stop
     * at maxPagesPerSegment; completion flags distinguish budget stops.
     */
    public ScanSummary parallelScan(
            ScanRequest template,
            int totalSegments,
            int maxConcurrentSegments,
            long maxPagesPerSegment,
            Map<Integer, SegmentCheckpoint> resume,
            BooleanSupplier cancelled,
            ScanPageConsumer pageConsumer,
            ScanCheckpointConsumer checkpointConsumer)
            throws Exception {
        requirePages(maxPagesPerSegment);
        if (totalSegments < 1
                || totalSegments > 1_000_000
                || maxConcurrentSegments < 1
                || maxConcurrentSegments > 256) {
            throw new IllegalArgumentException("Invalid segment or worker limit");
        }
        if (template.segment() != null
                || template.totalSegments() != null
                || !template.exclusiveStartKey().isEmpty()) {
            throw new IllegalArgumentException(
                    "Use resume checkpoints for segment IDs and cursors");
        }
        Map<Integer, SegmentCheckpoint> checkpoints = Map.copyOf(resume);
        checkpoints.forEach(
                (segment, checkpoint) -> {
                    if (segment != checkpoint.segment()
                            || checkpoint.totalSegments() != totalSegments) {
                        throw new IllegalArgumentException("Checkpoint segment identity mismatch");
                    }
                });
        AtomicInteger nextSegment = new AtomicInteger();
        int workers = Math.min(totalSegments, maxConcurrentSegments);
        List<Future<ScanSummary>> futures = new ArrayList<>(workers);
        try (ExecutorService executor =
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("dynamodb-scan-", 0).factory())) {
            CompletionService<ScanSummary> completions = new ExecutorCompletionService<>(executor);
            for (int worker = 0; worker < workers; worker++) {
                futures.add(
                        completions.submit(
                                () -> {
                                    ScanSummary summary = ScanSummary.empty();
                                    for (int segment;
                                            (segment = nextSegment.getAndIncrement())
                                                    < totalSegments; ) {
                                        checkCancelled(cancelled);
                                        SegmentCheckpoint checkpoint = checkpoints.get(segment);
                                        if (checkpoint != null && checkpoint.completed()) {
                                            summary = summary.plus(new ScanSummary(0, 0, 0, 0, 1));
                                            continue;
                                        }
                                        summary =
                                                summary.plus(
                                                        scanSegment(
                                                                template,
                                                                segment,
                                                                totalSegments,
                                                                maxPagesPerSegment,
                                                                checkpoint,
                                                                cancelled,
                                                                pageConsumer,
                                                                checkpointConsumer));
                                    }
                                    return summary;
                                }));
            }
            try {
                ScanSummary result = ScanSummary.empty();
                for (int worker = 0; worker < workers; worker++) {
                    result = result.plus(completions.take().get());
                }
                return result;
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw new IllegalStateException("Scan worker failed", failure.getCause());
            } finally {
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
            }
        }
    }

    private ScanSummary scanSegment(
            ScanRequest template,
            int segment,
            int totalSegments,
            long maxPages,
            SegmentCheckpoint resume,
            BooleanSupplier cancelled,
            ScanPageConsumer pageConsumer,
            ScanCheckpointConsumer checkpointConsumer)
            throws Exception {
        Map<String, AttributeValue> cursor = resume == null ? Map.of() : resume.nextStartKey();
        ScanSummary result = ScanSummary.empty();
        for (long page = 0; page < maxPages; page++) {
            checkCancelled(cancelled);
            ScanRequest request =
                    template.toBuilder()
                            .segment(segment)
                            .totalSegments(totalSegments)
                            .exclusiveStartKey(cursor.isEmpty() ? null : cursor)
                            .limit(pageLimit(template.limit()))
                            .returnConsumedCapacity(ReturnConsumedCapacity.INDEXES)
                            .build();
            ScanResponse response = gate.call(() -> client.scan(request));
            checkCancelled(cancelled);
            pageConsumer.accept(segment, response);
            cursor = Map.copyOf(response.lastEvaluatedKey());
            checkpointConsumer.save(
                    new SegmentCheckpoint(segment, totalSegments, cursor, cursor.isEmpty()));
            double capacity =
                    response.consumedCapacity() == null
                                    || response.consumedCapacity().capacityUnits() == null
                            ? 0
                            : response.consumedCapacity().capacityUnits();
            result =
                    result.plus(
                            new ScanSummary(
                                    1,
                                    response.scannedCount(),
                                    response.count(),
                                    capacity,
                                    cursor.isEmpty() ? 1 : 0));
            if (cursor.isEmpty()) {
                break;
            }
        }
        return result;
    }

    public PutItemResponse conditionalPut(PutItemRequest request) throws InterruptedException {
        requireCondition(request.conditionExpression());
        return gate.call(
                () -> {
                    authorization.requirePermission("dynamodb:PutItem", request.tableName());
                    return client.putItem(request);
                });
    }

    public UpdateItemResponse conditionalUpdate(UpdateItemRequest request)
            throws InterruptedException {
        requireCondition(request.conditionExpression());
        return gate.call(
                () -> {
                    authorization.requirePermission("dynamodb:UpdateItem", request.tableName());
                    return client.updateItem(request);
                });
    }

    public DeleteItemResponse conditionalDelete(DeleteItemRequest request)
            throws InterruptedException {
        requireCondition(request.conditionExpression());
        return gate.call(
                () -> {
                    authorization.requirePermission("dynamodb:DeleteItem", request.tableName());
                    return client.deleteItem(request);
                });
    }

    /** BatchWrite has neither updates nor conditions. Its partial failures are never hidden. */
    public BatchWriteItemResponse batchWrite(BatchWriteItemRequest request)
            throws InterruptedException {
        int count = request.requestItems().values().stream().mapToInt(List::size).sum();
        if (count < 1 || count > 25) {
            throw new IllegalArgumentException("BatchWrite requires 1 to 25 requests");
        }
        return gate.call(
                () -> {
                    request.requestItems()
                            .forEach(
                                    (table, writes) ->
                                            writes.forEach(
                                                    write -> {
                                                        if (write.putRequest() != null) {
                                                            authorization.requirePermission(
                                                                    "dynamodb:PutItem", table);
                                                        }
                                                        if (write.deleteRequest() != null) {
                                                            authorization.requirePermission(
                                                                    "dynamodb:DeleteItem", table);
                                                        }
                                                    }));
                    return client.batchWriteItem(request);
                });
    }

    public TransactWriteItemsResponse transactWrite(TransactWriteItemsRequest request)
            throws InterruptedException {
        if (request.transactItems().isEmpty()
                || request.transactItems().size() > 100
                || request.clientRequestToken() == null
                || request.clientRequestToken().isBlank()) {
            throw new IllegalArgumentException(
                    "Transaction requires 1 to 100 actions and a stable client request token");
        }
        return gate.call(
                () -> {
                    request.transactItems()
                            .forEach(
                                    item -> {
                                        if (item.put() != null)
                                            authorization.requirePermission(
                                                    "dynamodb:PutItem", item.put().tableName());
                                        if (item.update() != null)
                                            authorization.requirePermission(
                                                    "dynamodb:UpdateItem",
                                                    item.update().tableName());
                                        if (item.delete() != null)
                                            authorization.requirePermission(
                                                    "dynamodb:DeleteItem",
                                                    item.delete().tableName());
                                        if (item.conditionCheck() != null) {
                                            authorization.requirePermission(
                                                    "dynamodb:ConditionCheckItem",
                                                    item.conditionCheck().tableName());
                                        }
                                    });
                    return client.transactWriteItems(request);
                });
    }

    private int pageLimit(Integer requested) {
        if (requested != null && requested < 1)
            throw new IllegalArgumentException("Page limit must be positive");
        return requested == null ? maxPageItems : Math.min(requested, maxPageItems);
    }

    private static void requireCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("A reviewed conditional expression is required");
        }
    }

    private static void requirePages(long maxPages) {
        if (maxPages < 1) throw new IllegalArgumentException("Page budget must be positive");
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Scan interrupted");
        if (cancelled.getAsBoolean()) throw new CancellationException("Operation cancelled");
    }

    public record SegmentCheckpoint(
            int segment,
            int totalSegments,
            Map<String, AttributeValue> nextStartKey,
            boolean completed) {
        public SegmentCheckpoint {
            if (totalSegments < 1 || segment < 0 || segment >= totalSegments) {
                throw new IllegalArgumentException("Invalid checkpoint segment");
            }
            nextStartKey = Map.copyOf(nextStartKey);
            if (completed && !nextStartKey.isEmpty()) {
                throw new IllegalArgumentException("Completed checkpoint cannot have a cursor");
            }
        }
    }

    public record ScanSummary(
            long pages, long examined, long returned, double capacityUnits, int completedSegments) {
        static ScanSummary empty() {
            return new ScanSummary(0, 0, 0, 0, 0);
        }

        ScanSummary plus(ScanSummary other) {
            return new ScanSummary(
                    pages + other.pages,
                    examined + other.examined,
                    returned + other.returned,
                    capacityUnits + other.capacityUnits,
                    completedSegments + other.completedSegments);
        }
    }

    @FunctionalInterface
    public interface ScanPageConsumer {
        void accept(int segment, ScanResponse page) throws Exception;
    }

    @FunctionalInterface
    public interface ScanCheckpointConsumer {
        void save(SegmentCheckpoint checkpoint) throws Exception;
    }

    @FunctionalInterface
    public interface QueryPageConsumer {
        void accept(QueryResponse page) throws Exception;
    }

    @FunctionalInterface
    public interface QueryCheckpointConsumer {
        void save(Map<String, AttributeValue> nextStartKey, boolean completed) throws Exception;
    }
}
