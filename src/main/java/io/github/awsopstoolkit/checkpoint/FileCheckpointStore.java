package io.github.awsopstoolkit.checkpoint;

import io.github.awsopstoolkit.configuration.ToolkitProperties;
import io.github.awsopstoolkit.operation.OperationSnapshot;
import io.github.awsopstoolkit.operation.SyntheticOperationLimits;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Atomic snapshots for the deterministic demo, NOT a production write ledger. */
@Component
public final class FileCheckpointStore implements AutoCloseable {
    private final Path root;
    private final ObjectMapper mapper;
    private final FileChannel lockChannel;
    private final FileLock lock;

    public FileCheckpointStore(ToolkitProperties properties, ObjectMapper mapper)
            throws IOException {
        this.root = properties.dataDirectory().toAbsolutePath().normalize();
        this.mapper = mapper;
        Files.createDirectories(root);
        lockChannel =
                FileChannel.open(
                        root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (RuntimeException | IOException failure) {
            lockChannel.close();
            throw failure;
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("Data directory is already in use");
        }
        lock = acquired;
    }

    public long freeBytes() throws IOException {
        return Files.getFileStore(root).getUsableSpace();
    }

    public void save(OperationSnapshot snapshot) throws IOException {
        atomicWrite(
                directory(snapshot.operationId()).resolve("checkpoint.json"),
                mapper.writeValueAsBytes(snapshot));
    }

    public OperationSnapshot load(UUID id) throws IOException {
        var result =
                mapper.readValue(
                        Files.readAllBytes(directory(id).resolve("checkpoint.json")),
                        OperationSnapshot.class);
        if (result.schemaVersion() != 1
                || result.definitionVersion() == null
                || result.definitionVersion().isBlank()
                || !id.equals(result.operationId())
                || result.pageSize() < 1
                || result.pageSize()
                        > io.github.awsopstoolkit.configuration.CoreLimits.MAX_PAGE_SIZE
                || result.total() < 1
                || result.total() > SyntheticOperationLimits.MAX_RECORDS
                || result.cursor() < 0
                || result.cursor() > result.total()
                || (result.cursor() != result.total()
                        && result.cursor() % result.pageSize() != 0)) {
            throw new IOException("Unsupported or corrupt checkpoint");
        }
        return result;
    }

    public void saveChunk(UUID id, long start, String rows) throws IOException {
        atomicWrite(
                directory(id).resolve("chunk-" + start + ".csv"),
                rows.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Only committed chunks are visible; deterministic replay replaces a possibly orphaned chunk.
     */
    public void report(OperationSnapshot snapshot, OutputStream out) throws IOException {
        out.write("\"recordId\",\"decision\"\r\n".getBytes(StandardCharsets.UTF_8));
        for (long start = 0; start < snapshot.cursor(); start += snapshot.pageSize()) {
            Files.copy(directory(snapshot.operationId()).resolve("chunk-" + start + ".csv"), out);
        }
    }

    private Path directory(UUID id) {
        return root.resolve(id.toString());
    }

    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".pending-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Fail closed on filesystems without atomic rename. Never silently downgrade
            // durability.
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            lockChannel.close();
        }
    }
}
