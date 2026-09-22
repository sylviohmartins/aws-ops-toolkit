package io.github.awsopstoolkit.configuration;

import io.github.awsopstoolkit.dynamodb.DynamoLimits;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.dynamodb", ignoreUnknownFields = false)
public record DynamoProperties(
        @DefaultValue("100") @Min(1) @Max(DynamoLimits.MAX_PAGE_ITEMS) int pageSize,
        @DefaultValue Map<String, String> tables) {
    public DynamoProperties {
        tables = tables == null ? Map.of() : Map.copyOf(tables);
        tables.forEach(
                (logical, physical) -> {
                    if (logical == null
                            || logical.isBlank()
                            || physical == null
                            || physical.isBlank()) {
                        throw new IllegalArgumentException(
                                "DynamoDB table mappings require non-blank logical and physical names");
                    }
                });
    }

    public String requireTable(String logicalName) {
        String table = tables.get(logicalName);
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Missing DynamoDB table mapping: " + logicalName);
        }
        return table;
    }
}
