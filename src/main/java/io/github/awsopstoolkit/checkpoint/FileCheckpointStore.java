package io.github.awsopstoolkit.checkpoint;

import io.github.awsopstoolkit.configuration.ToolkitProperties;
import io.github.awsopstoolkit.operation.OperationSnapshot;
import io.github.awsopstoolkit.operation.SyntheticOperationLimits;
import io.github.awsopstoolkit.security.Hashing;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
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

    public IdempotencyReservation findIdempotency(String key, String fingerprint)
            throws IOException {
        validateIdempotencyKey(key);
        validateFingerprint(fingerprint);
        String keyHash = Hashing.sha256Hex(key);
        Path path = idempotencyPath(keyHash);
        if (!Files.exists(path)) return null;
        return readIdempotencyReservation(path, keyHash, fingerprint);
    }

    public IdempotencyReservation reserveIdempotency(
            String key, String fingerprint, UUID proposedOperationId) throws IOException {
        validateIdempotencyKey(key);
        validateFingerprint(fingerprint);
        Objects.requireNonNull(proposedOperationId, "proposedOperationId");

        String keyHash = Hashing.sha256Hex(key);
        Path path = idempotencyPath(keyHash);
        if (Files.exists(path)) return readIdempotencyReservation(path, keyHash, fingerprint);

        var reservation = new IdempotencyReservation(1, keyHash, fingerprint, proposedOperationId);
        atomicWrite(path, mapper.writeValueAsBytes(reservation));
        return reservation;
    }

    public boolean checkpointExists(UUID id) {
        return Files.isRegularFile(directory(id).resolve("checkpoint.json"));
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

    private Path idempotencyPath(String keyHash) {
        return root.resolve("idempotency").resolve(keyHash + ".json");
    }

    private IdempotencyReservation readIdempotencyReservation(
            Path path, String keyHash, String fingerprint) throws IOException {
        var saved = mapper.readValue(Files.readAllBytes(path), IdempotencyReservation.class);
        if (saved.schemaVersion() != 1
                || !keyHash.equals(saved.keyHash())
                || saved.fingerprint() == null
                || saved.fingerprint().isBlank()
                || saved.operationId() == null) {
            throw new IOException("Unsupported or corrupt idempotency reservation");
        }
        if (!fingerprint.equals(saved.fingerprint()))
            throw new IllegalStateException(
                    "Idempotency-Key is already bound to a different request");
        return saved;
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128)
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain between 1 and 128 characters");
        if (!key.equals(key.trim()))
            throw new IllegalArgumentException(
                    "Idempotency-Key cannot have surrounding whitespace");
        for (int i = 0; i < key.length(); i++) {
            char value = key.charAt(i);
            if (Character.isISOControl(value))
                throw new IllegalArgumentException(
                        "Idempotency-Key cannot contain control characters");
        }
    }

    private static void validateFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank())
            throw new IllegalArgumentException("Idempotency fingerprint is required");
    }

    public record IdempotencyReservation(
            int schemaVersion, String keyHash, String fingerprint, UUID operationId) {}

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
