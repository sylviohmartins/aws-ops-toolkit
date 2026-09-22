package io.github.awsopstoolkit.configuration;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.aws", ignoreUnknownFields = false)
public record AwsProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("us-east-1") String region,
        String profile,
        String expectedAccount,
        @DefaultValue("8") @Min(1) @Max(64) int maxConnections,
        @NotNull @DefaultValue("3s") Duration connectionTimeout,
        @NotNull @DefaultValue("2s") Duration acquisitionTimeout,
        @NotNull @DefaultValue("25s") Duration socketTimeout,
        @NotNull @DefaultValue("30s") Duration maxIdle,
        @NotNull @DefaultValue("30s") Duration apiCallAttemptTimeout,
        @NotNull @DefaultValue("35s") Duration apiCallTimeout) {
    public AwsProperties {
        requirePositive(connectionTimeout, "connection-timeout");
        requirePositive(acquisitionTimeout, "acquisition-timeout");
        requirePositive(socketTimeout, "socket-timeout");
        requirePositive(maxIdle, "max-idle");
        requirePositive(apiCallAttemptTimeout, "api-call-attempt-timeout");
        requirePositive(apiCallTimeout, "api-call-timeout");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
