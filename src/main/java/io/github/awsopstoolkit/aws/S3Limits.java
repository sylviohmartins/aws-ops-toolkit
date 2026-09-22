package io.github.awsopstoolkit.aws;

/** S3 protocol/format bounds shared by the adapter and operational workflows. */
public final class S3Limits {
    public static final int MAX_LIST_KEYS = 1_000;
    public static final long MAX_OBJECT_BYTES = 5L * 1024 * 1024 * 1024 * 1024;
    public static final long SINGLE_COPY_MAX_BYTES = 5L * 1024 * 1024 * 1024;

    private S3Limits() {}
}
