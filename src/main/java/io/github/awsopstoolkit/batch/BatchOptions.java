package io.github.awsopstoolkit.batch;

public record BatchOptions(int batchSize, int maxConcurrency) {
    public static final int MAX_SUPPORTED_CONCURRENCY = 256;

    public BatchOptions {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        if (maxConcurrency < 1 || maxConcurrency > MAX_SUPPORTED_CONCURRENCY)
            throw new IllegalArgumentException(
                    "maxConcurrency must be between 1 and " + MAX_SUPPORTED_CONCURRENCY);
    }

    public static BatchOptions sequential(int batchSize) {
        return new BatchOptions(batchSize, 1);
    }
}
