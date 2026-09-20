package io.github.awsopstoolkit.operation;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record OperationSnapshot(
        int schemaVersion,
        UUID operationId,
        String operationType,
        String definitionVersion,
        OperationMode mode,
        JsonNode parameters,
        OperationStatus status,
        long cursor,
        long total,
        int pageSize,
        Instant createdAt,
        Instant updatedAt,
        String errorCode) {
    public OperationSnapshot with(OperationStatus next, long nextCursor, String code) {
        return new OperationSnapshot(
                schemaVersion,
                operationId,
                operationType,
                definitionVersion,
                mode,
                parameters,
                next,
                nextCursor,
                total,
                pageSize,
                createdAt,
                Instant.now(),
                code);
    }
}
