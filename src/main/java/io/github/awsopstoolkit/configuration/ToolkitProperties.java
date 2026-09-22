package io.github.awsopstoolkit.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit", ignoreUnknownFields = false)
public record ToolkitProperties(
        @NotNull Environment environment,
        @NotNull Path dataDirectory,
        @Min(1048576) long minimumFreeBytes,
        @Min(1) @Max(4) int maxConcurrentOperations,
        @Min(1) @Max(1000) int pageSize,
        boolean writeEnabled,
        @NotBlank @Size(min = 32, max = 256) String localToken,
        @Valid @NotNull Aws aws) {
    public enum Environment {
        LOCAL,
        DEV,
        HML,
        PROD
    }

    public record Aws(
            boolean enabled,
            @NotBlank String region,
            String profile,
            String expectedAccount,
            @Min(1) @Max(64) int maxConnections,
            @DefaultValue("3000") @Min(100) @Max(30000) int connectionTimeoutMillis,
            @DefaultValue("2000") @Min(100) @Max(30000) int acquisitionTimeoutMillis,
            @DefaultValue("25000") @Min(100) @Max(120000) int socketTimeoutMillis,
            @DefaultValue("30000") @Min(1000) @Max(300000) int maxIdleMillis,
            @DefaultValue("30000") @Min(1000) @Max(120000) int apiCallAttemptTimeoutMillis,
            @DefaultValue("35000") @Min(1000) @Max(180000) int apiCallTimeoutMillis) {}

    // Record's generated toString would expose the bearer token.
    @Override
    public String toString() {
        return "ToolkitProperties[redacted]";
    }
}
