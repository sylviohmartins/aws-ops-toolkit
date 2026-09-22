package io.github.awsopstoolkit.runtime;

import java.util.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Conditional data compensation is a new approved job; emitted events are not undone. */
public final class CompensationWorkflow extends DynamoWorkflow {
    public CompensationWorkflow(DynamoDbClient dynamo) {
        super(dynamo);
    }

    @Override
    public String type() {
        return "payment-compensate";
    }

    @Override
    public boolean writes() {
        return true;
    }

    @Override
    public void validate(JobRequest request) {
        required(request.parameters(), "table");
        UUID.fromString(required(request.parameters(), "sourceJob"));
    }

    @Override
    public Page plan(JobContext c, int segment, String cursor) throws Exception {
        var source = super.plan(c, segment, cursor);
        String prefix = c.request().parameters().path("sourceJob").asText() + ":";
        var candidates =
                source.records().stream()
                        .filter(
                                record ->
                                        c.json.readTree(record.payload())
                                                .path("opsMarker")
                                                .asText()
                                                .startsWith(prefix))
                        .toList();
        return new Page(candidates, source.cursor(), source.complete());
    }

    @Override
    public String execute(JobContext c, SqliteJournal.Task task) throws Exception {
        var item = c.json.readTree(task.payload());
        String table = c.request().parameters().path("table").asText();
        String oldMarker = required(item, "opsMarker"),
                oldStatus = required(item, "opsPreviousStatus");
        long version = item.path("version").asLong(-1);
        if (!oldStatus.equals("PENDING") || version < 1)
            throw new IllegalArgumentException("Unsupported pre-image");
        String marker = c.id() + ":" + task.key();
        var key = Map.of("id", s(task.key()));
        try {
            c.effect(
                    task,
                    "compensate",
                    table,
                    () -> {
                        dynamo.updateItem(
                                b ->
                                        b.tableName(table)
                                                .key(key)
                                                .conditionExpression(
                                                        "#v=:v AND opsMarker=:old AND #s=:settled")
                                                .updateExpression(
                                                        "SET #s=:previous,#v=:next,opsMarker=:marker REMOVE opsPreviousStatus")
                                                .expressionAttributeNames(
                                                        Map.of("#s", "status", "#v", "version"))
                                                .expressionAttributeValues(
                                                        Map.of(
                                                                ":v",
                                                                n(version),
                                                                ":next",
                                                                n(version + 1),
                                                                ":old",
                                                                s(oldMarker),
                                                                ":marker",
                                                                s(marker),
                                                                ":settled",
                                                                s("SETTLED"),
                                                                ":previous",
                                                                s(oldStatus))));
                        return "COMPENSATED_DATA_ONLY";
                    },
                    () -> {
                        var actual =
                                c.read(
                                                table,
                                                () ->
                                                        dynamo.getItem(
                                                                b ->
                                                                        b.tableName(table)
                                                                                .key(key)
                                                                                .consistentRead(
                                                                                        true)))
                                        .item();
                        return marker.equals(actual.getOrDefault("opsMarker", s("")).s())
                                ? Optional.of("RECONCILED")
                                : Optional.empty();
                    });
            return "COMPENSATED_DATA_ONLY";
        } catch (ConditionalCheckFailedException e) {
            return "CONFLICT";
        }
    }
}
