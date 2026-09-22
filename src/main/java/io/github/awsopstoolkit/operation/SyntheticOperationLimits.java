package io.github.awsopstoolkit.operation;

/** Structural bounds for the deterministic local example, not operational tuning knobs. */
public final class SyntheticOperationLimits {
    public static final long MAX_RECORDS = 10_000_000L;
    public static final int MAX_DELAY_MILLIS = 100;
    public static final int ESTIMATED_BYTES_PER_RECORD = 128;
    public static final int ESTIMATED_BYTES_PER_PAGE = 4_096;
    public static final int ESTIMATED_CSV_CHARS_PER_RECORD = 40;

    private SyntheticOperationLimits() {}
}
