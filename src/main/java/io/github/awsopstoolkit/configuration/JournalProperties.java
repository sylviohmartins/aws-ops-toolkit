package io.github.awsopstoolkit.configuration;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.journal", ignoreUnknownFields = false)
public record JournalProperties(
        @NotNull @DefaultValue("5s") Duration busyTimeout,
        @DefaultValue("100") @Min(1) @Max(10000) int apiPageSize,
        @DefaultValue("500") @Min(1) @Max(10000) int reportWindowSize) {
    private static final Duration MAX_BUSY_TIMEOUT = Duration.ofMinutes(1);

    public JournalProperties {
        if (busyTimeout == null
                || busyTimeout.isNegative()
                || busyTimeout.compareTo(MAX_BUSY_TIMEOUT) > 0) {
            throw new IllegalArgumentException("busy-timeout must be within [0, 1m]");
        }
    }
}
