package io.github.awsopstoolkit.configuration;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.sqs", ignoreUnknownFields = false)
public record SqsProperties(
        @NotNull @DefaultValue("120s") Duration visibilityTimeout,
        @NotNull @DefaultValue("1s") Duration receiveWaitTime,
        @DefaultValue("3") @Min(1) @Max(20) int receiptReacquireAttempts,
        @DefaultValue("5") @Min(1) @Max(1000) int poisonReceiveCount,
        @NotNull @DefaultValue("5s") Duration acknowledgementLeaseReserve,
        @NotNull @DefaultValue("40s") Duration receiptReuseLeaseReserve) {
    private static final Duration MAX_VISIBILITY_TIMEOUT = Duration.ofHours(12);
    private static final Duration MAX_RECEIVE_WAIT_TIME = Duration.ofSeconds(20);

    public SqsProperties {
        requireWithin(
                visibilityTimeout,
                Duration.ofSeconds(1),
                MAX_VISIBILITY_TIMEOUT,
                "visibility-timeout");
        requireWithin(receiveWaitTime, Duration.ZERO, MAX_RECEIVE_WAIT_TIME, "receive-wait-time");
        requireWithin(
                acknowledgementLeaseReserve,
                Duration.ZERO,
                visibilityTimeout,
                "acknowledgement-lease-reserve");
        requireWithin(
                receiptReuseLeaseReserve,
                Duration.ZERO,
                visibilityTimeout,
                "receipt-reuse-lease-reserve");
        requireRange(receiptReacquireAttempts, 1, 20, "receipt-reacquire-attempts");
        requireRange(poisonReceiveCount, 1, 1_000, "poison-receive-count");
        requireWholeSeconds(visibilityTimeout, "visibility-timeout");
        requireWholeSeconds(receiveWaitTime, "receive-wait-time");
        requireWholeSeconds(acknowledgementLeaseReserve, "acknowledgement-lease-reserve");
        requireWholeSeconds(receiptReuseLeaseReserve, "receipt-reuse-lease-reserve");
    }

    private static void requireWithin(Duration value, Duration min, Duration max, String name) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
    }

    private static void requireRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
    }

    private static void requireWholeSeconds(Duration value, String name) {
        if (value.getNano() != 0) {
            throw new IllegalArgumentException(name + " must use whole-second precision");
        }
    }

    public int visibilityTimeoutSeconds() {
        return Math.toIntExact(visibilityTimeout.toSeconds());
    }

    public int receiveWaitTimeSeconds() {
        return Math.toIntExact(receiveWaitTime.toSeconds());
    }

    public int acknowledgementLeaseReserveSeconds() {
        return Math.toIntExact(acknowledgementLeaseReserve.toSeconds());
    }

    public int receiptReuseLeaseReserveSeconds() {
        return Math.toIntExact(receiptReuseLeaseReserve.toSeconds());
    }
}
