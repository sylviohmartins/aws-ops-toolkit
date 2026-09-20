package io.github.awsopstoolkit.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("toolkit")
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
            @Min(1) @Max(64) int maxConnections) {}

    // Record's generated toString would expose the bearer token.
    @Override
    public String toString() {
        return "ToolkitProperties[redacted]";
    }
}
