package io.github.awsopstoolkit.configuration;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.http", ignoreUnknownFields = false)
public record HttpProperties(
        URI paymentEndpoint,
        @DefaultValue Set<String> paymentHosts,
        @NotNull @DefaultValue("3s") Duration connectTimeout,
        @NotNull @DefaultValue("10s") Duration responseTimeout,
        @DefaultValue("3") @Min(1) @Max(5) int maxAttempts,
        @NotNull @DefaultValue("10s") Duration maxRetryAfter,
        @NotNull @DefaultValue("64KB") DataSize maxResponseBody) {
    private static final Duration MAX_RESPONSE_TIMEOUT = Duration.ofMinutes(1);
    private static final DataSize MAX_RESPONSE_BODY = DataSize.ofMegabytes(10);

    public HttpProperties {
        paymentHosts = paymentHosts == null ? Set.of() : Set.copyOf(paymentHosts);
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connect-timeout must be positive");
        }
        if (responseTimeout == null
                || responseTimeout.isZero()
                || responseTimeout.isNegative()
                || responseTimeout.compareTo(MAX_RESPONSE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("response-timeout must be within (0, 1m]");
        }
        if (maxRetryAfter == null || maxRetryAfter.isNegative()) {
            throw new IllegalArgumentException("max-retry-after cannot be negative");
        }
        if (maxResponseBody == null
                || maxResponseBody.toBytes() < 1
                || maxResponseBody.toBytes() > MAX_RESPONSE_BODY.toBytes()) {
            throw new IllegalArgumentException("max-response-body must be within (0, 10MB]");
        }
    }
}
