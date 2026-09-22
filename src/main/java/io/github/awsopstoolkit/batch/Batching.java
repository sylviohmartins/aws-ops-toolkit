package io.github.awsopstoolkit.batch;

import java.util.*;
import java.util.function.Consumer;

/** Bounded batching for sources that may be much larger than memory. */
public final class Batching {
    private Batching() {}

    public static <T> BatchResult forEachBatch(
            Iterable<T> source, int batchSize, Consumer<List<T>> consumer) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(consumer, "consumer");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");

        var buffer = new ArrayList<T>(batchSize);
        var result = new BatchResult(0, 0);
        for (T item : source) {
            buffer.add(item);
            if (buffer.size() == batchSize) result = emit(buffer, consumer, result);
        }
        return buffer.isEmpty() ? result : emit(buffer, consumer, result);
    }

    private static <T> BatchResult emit(
            ArrayList<T> buffer, Consumer<List<T>> consumer, BatchResult result) {
        List<T> batch = List.copyOf(buffer);
        consumer.accept(batch);
        buffer.clear();
        return result.plus(batch.size());
    }
}
