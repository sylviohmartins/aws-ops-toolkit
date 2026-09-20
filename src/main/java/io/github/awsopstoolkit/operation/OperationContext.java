package io.github.awsopstoolkit.operation;

import java.io.IOException;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public record OperationContext(
        UUID operationId,
        long startCursor,
        int pageSize,
        BooleanSupplier stopRequested,
        PageCommit commit) {
    public static final ScopedValue<UUID> CURRENT_OPERATION = ScopedValue.newInstance();

    @FunctionalInterface
    public interface PageCommit {
        void accept(long nextCursor, String csvRows) throws IOException;
    }
}
