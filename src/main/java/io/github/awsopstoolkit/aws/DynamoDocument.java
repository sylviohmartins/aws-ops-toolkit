package io.github.awsopstoolkit.aws;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Small dynamic view that preserves DynamoDB types and distinguishes absent attributes from NULL.
 */
public final class DynamoDocument {
    private final Map<String, AttributeValue> attributes;

    public DynamoDocument(Map<String, AttributeValue> attributes) {
        this.attributes = Map.copyOf(Objects.requireNonNull(attributes));
    }

    public Map<String, AttributeValue> attributes() {
        return attributes;
    }

    public Optional<AttributeValue> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    public boolean isNull(String name) {
        return attribute(name).map(value -> Boolean.TRUE.equals(value.nul())).orElse(false);
    }

    public Optional<String> string(String name) {
        return attribute(name)
                .map(
                        value -> {
                            if (value.s() == null) {
                                throw new IllegalArgumentException(
                                        "Expected string attribute: " + name);
                            }
                            return value.s();
                        });
    }

    public Optional<BigDecimal> number(String name) {
        return attribute(name)
                .map(
                        value -> {
                            if (value.n() == null) {
                                throw new IllegalArgumentException(
                                        "Expected number attribute: " + name);
                            }
                            return new BigDecimal(value.n());
                        });
    }

    @Override
    public String toString() {
        return "DynamoDocument[attributeCount=" + attributes.size() + "]";
    }
}
