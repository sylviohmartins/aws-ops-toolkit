package io.github.awsopstoolkit.batch;

public record BatchResult(long batches, long items) {
    public BatchResult plus(long batchItems) {
        if (batchItems < 0) throw new IllegalArgumentException("batchItems cannot be negative");
        return new BatchResult(batches + 1, Math.addExact(items, batchItems));
    }
}
