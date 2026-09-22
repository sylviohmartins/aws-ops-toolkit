package io.github.awsopstoolkit.batch;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bounded batch processor. At most maxConcurrency batches are submitted at once, so huge sources
 * are consumed incrementally instead of becoming an unbounded future/list graph.
 */
public final class BatchProcessor {
    private BatchProcessor() {}

    public static <T> BatchProcessingResult process(
            Iterable<T> source,
            BatchOptions options,
            Consumer<List<T>> consumer,
            BiConsumer<List<T>, Exception> errorConsumer)
            throws InterruptedException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(options);
        Objects.requireNonNull(consumer);
        Objects.requireNonNull(errorConsumer);

        if (options.maxConcurrency() == 1) {
            var result = new BatchProcessingResult(0, 0, 0, 0);
            var buffer = new ArrayList<T>(options.batchSize());
            for (T item : source) {
                buffer.add(item);
                if (buffer.size() == options.batchSize()) {
                    result = execute(List.copyOf(buffer), consumer, errorConsumer, result);
                    buffer.clear();
                }
            }
            return buffer.isEmpty()
                    ? result
                    : execute(List.copyOf(buffer), consumer, errorConsumer, result);
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = new ExecutorCompletionService<BatchOutcome>(executor);
            int inFlight = 0;
            var result = new BatchProcessingResult(0, 0, 0, 0);
            var buffer = new ArrayList<T>(options.batchSize());

            for (T item : source) {
                buffer.add(item);
                if (buffer.size() == options.batchSize()) {
                    completion.submit(task(List.copyOf(buffer), consumer, errorConsumer));
                    buffer.clear();
                    inFlight++;
                    if (inFlight == options.maxConcurrency()) {
                        result = result(result, completion.take());
                        inFlight--;
                    }
                }
            }
            if (!buffer.isEmpty()) {
                completion.submit(task(List.copyOf(buffer), consumer, errorConsumer));
                inFlight++;
            }
            while (inFlight-- > 0) result = result(result, completion.take());
            return result;
        }
    }

    private static <T> Callable<BatchOutcome> task(
            List<T> batch,
            Consumer<List<T>> consumer,
            BiConsumer<List<T>, Exception> errorConsumer) {
        return () -> {
            try {
                consumer.accept(batch);
                return new BatchOutcome(batch.size(), false);
            } catch (RuntimeException failure) {
                errorConsumer.accept(batch, failure);
                return new BatchOutcome(batch.size(), true);
            }
        };
    }

    private static BatchProcessingResult result(
            BatchProcessingResult current, Future<BatchOutcome> completed)
            throws InterruptedException {
        try {
            var outcome = completed.get();
            return current.plus(outcome.items(), outcome.failed());
        } catch (ExecutionException impossible) {
            throw new IllegalStateException("Batch task escaped its failure boundary", impossible);
        }
    }

    private static <T> BatchProcessingResult execute(
            List<T> batch,
            Consumer<List<T>> consumer,
            BiConsumer<List<T>, Exception> errorConsumer,
            BatchProcessingResult result) {
        try {
            consumer.accept(batch);
            return result.plus(batch.size(), false);
        } catch (RuntimeException failure) {
            errorConsumer.accept(batch, failure);
            return result.plus(batch.size(), true);
        }
    }

    private record BatchOutcome(int items, boolean failed) {}
}
