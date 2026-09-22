package io.github.awsopstoolkit.runtime;

import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

/** Typed operational request. Omitted mode fails safe to DRY_RUN. */
public record JobRequest(
        @NotBlank String operation,
        @NotNull JsonNode parameters,
        @NotNull JobMode mode,
        @Size(max = JobRequestLimits.MAX_REFERENCE_CHARS) String incidentId,
        @Size(max = JobRequestLimits.MAX_REFERENCE_CHARS) String changeId,
        @Size(max = JobRequestLimits.MAX_REASON_CHARS) String reason,
        Boolean explicitConfirmation,
        @Min(1) @Max(JobRequestLimits.MAX_RECORDS) long maxRecords,
        @Min(1) @Max(JobRequestLimits.MAX_CALLS) int maxCalls,
        @Min(1) @Max(JobRequestLimits.MAX_SECONDS) int maxSeconds,
        @Min(1) @Max(JobRequestLimits.MAX_CANARY_RECORDS) int canaryRecords,
        @Min(0) @Max(JobRequestLimits.MAX_CONFLICTS) int maxConflicts,
        @Min(0) @Max(JobRequestLimits.MAX_ERRORS) Integer maxErrors,
        @DecimalMin("0.0") @DecimalMax("1.0") Double maxErrorRate,
        @Min(1) @Max(JobRequestLimits.MAX_ERROR_SAMPLE) Integer minErrorSample,
        @Min(1) @Max(JobRequestLimits.MAX_SEGMENTS) int segments,
        Boolean visibilityImpactAccepted,
        Boolean sharedConsumerImpactAccepted,
        @Min(1) @Max(JobRequestLimits.MAX_SCANNED_RECORDS) Long maxScannedRecords,
        @DecimalMin("0.5") Double maxReadCapacity) {
    public JobRequest {
        if (mode == null) mode = JobMode.DRY_RUN;
        explicitConfirmation = Boolean.TRUE.equals(explicitConfirmation);
        visibilityImpactAccepted = Boolean.TRUE.equals(visibilityImpactAccepted);
        sharedConsumerImpactAccepted = Boolean.TRUE.equals(sharedConsumerImpactAccepted);
        if (maxErrors == null) maxErrors = 0;
        if (maxErrorRate == null) maxErrorRate = 0.0;
        if (minErrorSample == null) minErrorSample = JobRequestLimits.DEFAULT_MIN_ERROR_SAMPLE;
        if (maxScannedRecords == null) maxScannedRecords = maxRecords;
        if (maxReadCapacity == null) maxReadCapacity = (double) maxRecords;
        if (!Double.isFinite(maxReadCapacity) || !Double.isFinite(maxErrorRate)) {
            throw new IllegalArgumentException("Budgets must be finite");
        }
        incidentId = normalized(incidentId);
        changeId = normalized(changeId);
        reason = normalized(reason);
    }

    public JobRequest(
            String operation,
            JsonNode parameters,
            JobMode mode,
            String incidentId,
            String changeId,
            String reason,
            boolean explicitConfirmation,
            long maxRecords,
            int maxCalls,
            int maxSeconds,
            int canaryRecords,
            int maxConflicts,
            int segments,
            boolean visibilityImpactAccepted,
            boolean sharedConsumerImpactAccepted) {
        this(
                operation,
                parameters,
                mode,
                incidentId,
                changeId,
                reason,
                explicitConfirmation,
                maxRecords,
                maxCalls,
                maxSeconds,
                canaryRecords,
                maxConflicts,
                0,
                0.0,
                JobRequestLimits.DEFAULT_MIN_ERROR_SAMPLE,
                segments,
                visibilityImpactAccepted,
                sharedConsumerImpactAccepted,
                null,
                null);
    }

    /** Backward-compatible Java call sites are planning-only by default. */
    public JobRequest(
            String operation,
            JsonNode parameters,
            long maxRecords,
            int maxCalls,
            int maxSeconds,
            int canaryRecords,
            int maxConflicts,
            int segments,
            boolean visibilityImpactAccepted,
            boolean sharedConsumerImpactAccepted) {
        this(
                operation,
                parameters,
                JobMode.DRY_RUN,
                null,
                null,
                null,
                false,
                maxRecords,
                maxCalls,
                maxSeconds,
                canaryRecords,
                maxConflicts,
                0,
                0.0,
                JobRequestLimits.DEFAULT_MIN_ERROR_SAMPLE,
                segments,
                visibilityImpactAccepted,
                sharedConsumerImpactAccepted,
                null,
                null);
    }

    public boolean hasOperationalReference() {
        return incidentId != null || changeId != null;
    }

    private static String normalized(String value) {
        if (value == null) return null;
        String result = value.strip();
        if (result.isEmpty()) return null;
        if (result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Operational metadata contains control characters");
        }
        return result;
    }
}
