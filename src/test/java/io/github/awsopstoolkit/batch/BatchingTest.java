package io.github.awsopstoolkit.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BatchingTest {
    @Test
    void consumesIterableIncrementallyAndKeepsOnlyBoundedBatches() {
        var sizes = new ArrayList<Integer>();
        var result =
                Batching.forEachBatch(
                        () -> java.util.stream.IntStream.range(0, 10_003).boxed().iterator(),
                        128,
                        batch -> {
                            sizes.add(batch.size());
                            assertTrue(batch.size() <= 128);
                        });

        assertEquals(10_003, result.items());
        assertEquals(79, result.batches());
        assertEquals(79, sizes.size());
        assertEquals(19, sizes.getLast());
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Batching.forEachBatch(List.of(1), 0, ignored -> {}));
    }
}
