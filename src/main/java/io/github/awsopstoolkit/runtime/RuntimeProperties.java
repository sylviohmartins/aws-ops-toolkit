package io.github.awsopstoolkit.runtime;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.operations", ignoreUnknownFields = false)
public record RuntimeProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean writes,
        URI labEndpoint,
        @DefaultValue Set<String> resources,
        @DefaultValue Set<String> principals,
        @DefaultValue("10") @Min(1) @Max(1000) int requestsPerSecond,
        @DefaultValue("2") @Min(1) @Max(32) int workers,
        @DefaultValue("100") @Min(1) @Max(1000) int pageSize,
        @NotNull @DefaultValue("24h") Duration planLifetime,
        @NotNull @DefaultValue("15m") Duration approvalLifetime,
        @NotNull @DefaultValue("30d") Duration retention,
        @DefaultValue("10") @Min(1) @Max(100) int dryRunSampleSize,
        @NotNull @DefaultValue("25s") Duration shutdownTimeout,
        @NotNull @DefaultValue("8KB") DataSize estimatedBytesPerRecord,
        @DefaultValue("20") @Min(1) @Max(10000) int healthyResponsesBeforeIncrease,
        @DefaultValue("5") @Min(1) @Max(100) int circuitFailuresBeforeOpen,
        @NotNull @DefaultValue("10s") Duration circuitOpenDuration,
        @NotNull @DefaultValue("100ms") Duration retryBaseBackoff,
        @DefaultValue("3") @Min(1) @Max(5) int readMaxAttempts) {
    private static final Duration MAX_CIRCUIT_OPEN_DURATION = Duration.ofMinutes(5);
    private static final Duration MAX_RETRY_BASE_BACKOFF = Duration.ofSeconds(10);

    public RuntimeProperties {
        resources = resources == null ? Set.of() : Set.copyOf(resources);
        principals = principals == null ? Set.of() : Set.copyOf(principals);
        requirePositive(planLifetime, "plan-lifetime");
        requirePositive(approvalLifetime, "approval-lifetime");
        requirePositive(retention, "retention");
        requirePositive(shutdownTimeout, "shutdown-timeout");
        requirePositive(circuitOpenDuration, "circuit-open-duration");
        requirePositive(retryBaseBackoff, "retry-base-backoff");
        if (circuitOpenDuration.compareTo(MAX_CIRCUIT_OPEN_DURATION) > 0)
            throw new IllegalArgumentException("circuit-open-duration must not exceed 5m");
        if (retryBaseBackoff.compareTo(MAX_RETRY_BASE_BACKOFF) > 0)
            throw new IllegalArgumentException("retry-base-backoff must not exceed 10s");
        if (estimatedBytesPerRecord == null || estimatedBytesPerRecord.toBytes() < 1)
            throw new IllegalArgumentException("estimated-bytes-per-record must be positive");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
