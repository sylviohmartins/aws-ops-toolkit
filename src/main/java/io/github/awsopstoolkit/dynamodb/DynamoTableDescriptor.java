package io.github.awsopstoolkit.dynamodb;

import java.util.Objects;
import java.util.Set;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/**
 * Structural metadata for a recurring typed DynamoDB table. Physical names stay in configuration.
 */
public record DynamoTableDescriptor<T>(
        String logicalName,
        TableSchema<T> schema,
        String partitionKeyAttribute,
        Set<String> indexes) {
    public DynamoTableDescriptor {
        if (logicalName == null || logicalName.isBlank())
            throw new IllegalArgumentException("logicalName cannot be blank");
        Objects.requireNonNull(schema, "schema");
        if (partitionKeyAttribute == null || partitionKeyAttribute.isBlank())
            throw new IllegalArgumentException("partitionKeyAttribute cannot be blank");
        indexes = indexes == null ? Set.of() : Set.copyOf(indexes);
    }
}
