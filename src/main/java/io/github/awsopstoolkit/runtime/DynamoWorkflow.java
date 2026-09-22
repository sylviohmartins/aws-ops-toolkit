package io.github.awsopstoolkit.runtime;

import java.util.*;
import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import tools.jackson.databind.JsonNode;

/** Paginated inventory and payment-rule source. Only projected fields enter the local plan. */
public class DynamoWorkflow implements Workflow {
    protected final DynamoDbClient dynamo;

    public DynamoWorkflow(DynamoDbClient dynamo) {
        this.dynamo = dynamo;
    }

    @Override
    public String type() {
        return "dynamodb-inventory";
    }

    @Override
    public boolean writes() {
        return false;
    }

    @Override
    public Set<String> resources(JsonNode parameters) {
        return Set.of(required(parameters, "table"));
    }

    @Override
    public void validate(JobRequest request) {
        required(request.parameters(), "table");
        var allowed = Set.of("table", "queryId");
        request.parameters()
                .propertyNames()
                .forEach(
                        n -> {
                            if (!allowed.contains(n))
                                throw new IllegalArgumentException("Unknown parameter");
                        });
        if (request.parameters().has("queryId") && request.segments() != 1)
            throw new IllegalArgumentException("Query uses one cursor");
    }

    @Override
    public void preflight(JobContext context) throws Exception {
        String table = required(context.request().parameters(), "table");
        context.read(table, () -> dynamo.describeTable(b -> b.tableName(table)));
    }

    @Override
    public Page plan(JobContext context, int segment, String cursor) throws Exception {
        String table = required(context.request().parameters(), "table");
        Map<String, AttributeValue> start =
                cursor.isEmpty() ? Map.of() : EnhancedDocument.fromJson(cursor).toMap();
        List<Map<String, AttributeValue>> items;
        Map<String, AttributeValue> next;
        var p = context.request().parameters();
        if (p.has("queryId")) {
            var response =
                    context.read(
                            table,
                            () ->
                                    dynamo.query(
                                            QueryRequest.builder()
                                                    .tableName(table)
                                                    .keyConditionExpression("id = :id")
                                                    .projectionExpression(
                                                            "id,#s,#v,opsMarker,opsPreviousStatus")
                                                    .expressionAttributeNames(
                                                            Map.of("#s", "status", "#v", "version"))
                                                    .expressionAttributeValues(
                                                            Map.of(
                                                                    ":id",
                                                                    s(p.path("queryId").asText())))
                                                    .exclusiveStartKey(
                                                            start.isEmpty() ? null : start)
                                                    .limit(context.settings.pageSize())
                                                    .returnConsumedCapacity(
                                                            ReturnConsumedCapacity.TOTAL)
                                                    .build()));
            context.readUsage(response.scannedCount(), response.consumedCapacity());
            items = response.items();
            next = response.lastEvaluatedKey();
        } else {
            var response =
                    context.read(
                            table,
                            () ->
                                    dynamo.scan(
                                            ScanRequest.builder()
                                                    .tableName(table)
                                                    .segment(segment)
                                                    .totalSegments(context.request().segments())
                                                    .exclusiveStartKey(
                                                            start.isEmpty() ? null : start)
                                                    .limit(context.settings.pageSize())
                                                    .projectionExpression(
                                                            "id,#s,#v,opsMarker,opsPreviousStatus")
                                                    .expressionAttributeNames(
                                                            Map.of("#s", "status", "#v", "version"))
                                                    .returnConsumedCapacity(
                                                            ReturnConsumedCapacity.TOTAL)
                                                    .build()));
            context.readUsage(response.scannedCount(), response.consumedCapacity());
            items = response.items();
            next = response.lastEvaluatedKey();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (var item : items) {
            String id = item.getOrDefault("id", s("")).s();
            if (id == null || id.isBlank())
                throw new IllegalArgumentException("The reference schema requires string id");
            candidates.add(
                    new Candidate(id, EnhancedDocument.fromAttributeValueMap(item).toJson()));
        }
        return new Page(
                List.copyOf(candidates),
                next.isEmpty() ? "" : EnhancedDocument.fromAttributeValueMap(next).toJson(),
                next.isEmpty());
    }

    @Override
    public String execute(JobContext context, SqliteJournal.Task task) throws Exception {
        return "OBSERVED";
    }

    @Override
    public Proposal proposal(JsonNode parameters, SqliteJournal.Task task) {
        var item = EnhancedDocument.fromJson(task.payload()).toMap();
        var before = new LinkedHashMap<String, Object>();
        if (item.containsKey("status") && item.get("status").s() != null)
            before.put("status", item.get("status").s());
        if (item.containsKey("version") && item.get("version").n() != null)
            before.put("version", item.get("version").n());
        return new Proposal("READ_ONLY", Map.copyOf(before), Map.copyOf(before));
    }

    @Override
    public List<String> risks() {
        return List.of(
                "Scan is not a transactional snapshot",
                "Filter expressions do not reduce read capacity already consumed");
    }

    public static String required(JsonNode node, String key) {
        var value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 2048)
            throw new IllegalArgumentException("Missing/invalid " + key);
        return value.asText();
    }

    protected static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    protected static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }
}
