package io.github.awsopstoolkit.runtime;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "operations", ignoreUnknownFields = false)
public record RuntimeProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean writes,
        URI labEndpoint,
        @DefaultValue Set<String> resources,
        @DefaultValue Set<String> principals,
        @DefaultValue("10") @Min(1) @Max(1000) int requestsPerSecond,
        @DefaultValue("2") @Min(1) @Max(32) int workers,
        @DefaultValue("100") @Min(1) @Max(1000) int pageSize,
        @DefaultValue("86400") @Min(60) @Max(86400) int planLifetimeSeconds,
        @DefaultValue("900") @Min(30) @Max(3600) int approvalLifetimeSeconds,
        @DefaultValue("30") @Min(1) int retentionDays,
        URI paymentEndpoint,
        @DefaultValue Set<String> paymentHosts,
        @DefaultValue("3000") @Min(100) @Max(30000) int httpConnectTimeoutMillis,
        @DefaultValue("10000") @Min(100) @Max(60000) int httpResponseTimeoutMillis,
        @DefaultValue("3") @Min(1) @Max(5) int httpMaxAttempts,
        @DefaultValue("10") @Min(0) @Max(60) int httpMaxRetryAfterSeconds) {}
