package io.github.awsopstoolkit.runtime;

/** Structural API safety bounds; runtime tuning remains in configuration properties. */
public final class JobRequestLimits {
    public static final int MAX_REFERENCE_CHARS = 80;
    public static final int MAX_REASON_CHARS = 256;
    public static final long MAX_RECORDS = 10_000_000L;
    public static final int MAX_CALLS = 10_000_000;
    public static final int MAX_SECONDS = 86_400;
    public static final int MAX_CANARY_RECORDS = 1_000;
    public static final int MAX_CONFLICTS = 10_000;
    public static final int MAX_ERRORS = 10_000_000;
    public static final int MAX_ERROR_SAMPLE = 10_000_000;
    public static final int DEFAULT_MIN_ERROR_SAMPLE = 100;
    public static final int MAX_SEGMENTS = 1_024;
    public static final long MAX_SCANNED_RECORDS = 100_000_000L;

    private JobRequestLimits() {}
}
