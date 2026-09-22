package io.github.awsopstoolkit.dynamodb;

import java.util.Objects;
import java.util.function.Consumer;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

/**
 * Typed, lazy DynamoDB read gateway. Mutating operations intentionally remain in the guarded
 * operational adapter so this extension point cannot bypass write authorization.
 */
public final class DynamoTableGateway<T> {
    private final DynamoTableDescriptor<T> descriptor;
    private final DynamoDbTable<T> table;

    DynamoTableGateway(DynamoTableDescriptor<T> descriptor, DynamoDbTable<T> table) {
        this.descriptor = Objects.requireNonNull(descriptor);
        this.table = Objects.requireNonNull(table);
    }

    public DynamoTableDescriptor<T> descriptor() {
        return descriptor;
    }

    public String physicalTableName() {
        return table.tableName();
    }

    public T get(Key key) {
        return table.getItem(Objects.requireNonNull(key));
    }

    public void scan(ScanEnhancedRequest request, Consumer<Page<T>> pageConsumer) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(pageConsumer);
        table.scan(request).stream().forEach(pageConsumer);
    }

    public void query(QueryEnhancedRequest request, Consumer<Page<T>> pageConsumer) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(pageConsumer);
        table.query(request).stream().forEach(pageConsumer);
    }
}
