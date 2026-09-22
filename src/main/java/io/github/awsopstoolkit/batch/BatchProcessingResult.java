package io.github.awsopstoolkit.batch;

public record BatchProcessingResult(
        long batches, long items, long failedBatches, long failedItems) {
    public BatchProcessingResult {
        if (batches < 0 || items < 0 || failedBatches < 0 || failedItems < 0)
            throw new IllegalArgumentException("Batch counters cannot be negative");
        if (failedBatches > batches || failedItems > items)
            throw new IllegalArgumentException("Failure counters cannot exceed totals");
    }

    public BatchProcessingResult plus(long batchItems, boolean failed) {
        return new BatchProcessingResult(
                Math.addExact(batches, 1),
                Math.addExact(items, batchItems),
                Math.addExact(failedBatches, failed ? 1 : 0),
                Math.addExact(failedItems, failed ? batchItems : 0));
    }
}
