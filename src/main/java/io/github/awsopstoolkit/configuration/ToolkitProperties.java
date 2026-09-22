package io.github.awsopstoolkit.configuration;

import jakarta.validation.constraints.*;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.core", ignoreUnknownFields = false)
public record ToolkitProperties(
        @NotNull @DefaultValue("LOCAL") Environment environment,
        @NotNull @DefaultValue(".aws-ops-toolkit/data") Path dataDirectory,
        @NotNull @DefaultValue("1GB") DataSize minimumFreeSpace,
        @DefaultValue("2") @Min(1) @Max(4) int maxConcurrentOperations,
        @DefaultValue("100") @Min(1) @Max(1000) int pageSize,
        @DefaultValue("false") boolean writeEnabled,
        @NotBlank @Size(min = 32, max = 256) String localToken) {
    public enum Environment {
        LOCAL,
        DEV,
        HML,
        PROD
    }

    public long minimumFreeBytes() {
        return minimumFreeSpace.toBytes();
    }

    @Override
    public String toString() {
        return "ToolkitProperties[redacted]";
    }
}
