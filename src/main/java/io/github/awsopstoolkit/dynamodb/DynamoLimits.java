package io.github.awsopstoolkit.dynamodb;

/** DynamoDB/API structural limits shared by configuration and adapters. */
public final class DynamoLimits {
    public static final int MAX_PAGE_ITEMS = 10_000;
    public static final int MAX_ACTIVE_SCAN_WORKERS = 256;
    public static final int MAX_TOTAL_SCAN_SEGMENTS = 1_000_000;
    public static final int MAX_BATCH_GET_KEYS = 100;
    public static final int MAX_BATCH_WRITE_REQUESTS = 25;
    public static final int MAX_TRANSACTION_ACTIONS = 100;

    private DynamoLimits() {}
}
