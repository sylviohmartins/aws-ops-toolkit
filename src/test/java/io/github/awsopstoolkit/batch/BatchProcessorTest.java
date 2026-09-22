package io.github.awsopstoolkit.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BatchProcessorTest {
    @Test
    void processesLargeIterableIncrementallyWithBoundedConcurrency() throws Exception {
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var seen = new AtomicInteger();

        var result =
                BatchProcessor.process(
                        () -> java.util.stream.IntStream.range(0, 10_000).boxed().iterator(),
                        new BatchOptions(125, 4),
                        batch -> {
                            int now = active.incrementAndGet();
                            peak.accumulateAndGet(now, Math::max);
                            try {
                                seen.addAndGet(batch.size());
                            } finally {
                                active.decrementAndGet();
                            }
                        },
                        (batch, failure) -> fail(failure));

        assertEquals(10_000, result.items());
        assertEquals(80, result.batches());
        assertEquals(0, result.failedBatches());
        assertEquals(10_000, seen.get());
        assertTrue(peak.get() <= 4);
    }

    @Test
    void countsPartialFailuresWithoutLosingFollowingBatches() throws Exception {
        var result =
                BatchProcessor.process(
                        List.of(1, 2, 3, 4, 5, 6),
                        new BatchOptions(2, 2),
                        batch -> {
                            if (batch.contains(3)) throw new IllegalStateException("expected");
                        },
                        (batch, failure) -> assertEquals("expected", failure.getMessage()));

        assertEquals(3, result.batches());
        assertEquals(6, result.items());
        assertEquals(1, result.failedBatches());
        assertEquals(2, result.failedItems());
    }
}
