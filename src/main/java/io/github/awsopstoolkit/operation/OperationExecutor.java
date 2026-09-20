package io.github.awsopstoolkit.operation;

import io.github.awsopstoolkit.checkpoint.FileCheckpointStore;
import io.github.awsopstoolkit.configuration.ToolkitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public final class OperationExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(OperationExecutor.class);
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Control> active = new HashMap<>();
    private final OperationRegistry registry;
    private final FileCheckpointStore store;
    private final PreflightCheckService preflight;
    private final ToolkitProperties properties;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final MeterRegistry meters;
    private boolean stopping;

    private static final class Control {
        private volatile OperationSnapshot snapshot;
        private volatile OperationStatus requested;

        private Control(OperationSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    public OperationExecutor(
            OperationRegistry registry,
            FileCheckpointStore store,
            PreflightCheckService preflight,
            ToolkitProperties properties,
            ObjectMapper mapper,
            Validator validator,
            MeterRegistry meters) {
        this.registry = registry;
        this.store = store;
        this.preflight = preflight;
        this.properties = properties;
        this.mapper = mapper;
        this.validator = validator;
        this.meters = meters;
    }

    public synchronized OperationSnapshot start(String type, OperationRequest request)
            throws IOException {
        var definition = registry.require(type);
        long total = validateAndTotal(definition, request.parameters());
        preflight.check(request.mode(), total, reservedBytes());
        requireCapacity();
        var now = Instant.now();
        var snapshot =
                new OperationSnapshot(
                        1,
                        UUID.randomUUID(),
                        type,
                        definition.version(),
                        request.mode(),
                        request.parameters(),
                        OperationStatus.CREATED,
                        0,
                        total,
                        properties.pageSize(),
                        now,
                        now,
                        null);
        schedule(snapshot);
        return snapshot;
    }

    public synchronized OperationSnapshot status(UUID id) throws IOException {
        Control control = active.get(id);
        if (control != null) return control.snapshot;
        var snapshot = store.load(id);
        if (snapshot.status().active()) {
            snapshot =
                    snapshot.with(
                            OperationStatus.INTERRUPTED, snapshot.cursor(), "PROCESS_INTERRUPTED");
            store.save(snapshot);
        }
        return snapshot;
    }

    public synchronized OperationSnapshot resume(UUID id) throws IOException {
        var snapshot = status(id);
        if (active.containsKey(id))
            throw new IllegalStateException("Operation still has an active worker");
        if (!snapshot.status().resumable())
            throw new IllegalStateException("Operation cannot resume from this state");
        var definition = registry.require(snapshot.operationType());
        long total = validateAndTotal(definition, snapshot.parameters());
        if (total != snapshot.total() || !definition.version().equals(snapshot.definitionVersion()))
            throw new IllegalStateException("Operation definition changed");
        preflight.check(snapshot.mode(), total, reservedBytes());
        requireCapacity();
        var resumed = snapshot.with(OperationStatus.CREATED, snapshot.cursor(), null);
        schedule(resumed);
        return resumed;
    }

    public synchronized OperationSnapshot requestStop(UUID id, boolean cancel) throws IOException {
        var snapshot = status(id);
        var control = active.get(id);
        if (control == null) {
            if (cancel && snapshot.status().resumable()) {
                snapshot = snapshot.with(OperationStatus.CANCELLED, snapshot.cursor(), null);
                store.save(snapshot);
                return snapshot;
            }
            throw new IllegalStateException("Operation is not active");
        }
        if (!snapshot.status().active())
            throw new IllegalStateException("Operation is no longer active");
        if (control.requested == OperationStatus.CANCELLED && !cancel) {
            throw new IllegalStateException("Cancellation already requested");
        }
        var next =
                snapshot.with(
                        cancel ? OperationStatus.CANCELLING : OperationStatus.PAUSING,
                        snapshot.cursor(),
                        null);
        store.save(next);
        control.snapshot = next;
        control.requested = cancel ? OperationStatus.CANCELLED : OperationStatus.PAUSED;
        return next;
    }

    private long reservedBytes() {
        return active.values().stream()
                .mapToLong(c -> preflight.estimate(c.snapshot.total()))
                .sum();
    }

    private void requireCapacity() {
        if (stopping || active.size() >= properties.maxConcurrentOperations()) {
            throw new IllegalStateException("Operation capacity unavailable");
        }
    }

    private <I> long validateAndTotal(OperationDefinition<I> definition, JsonNode parameters) {
        I input = mapper.treeToValue(parameters, definition.inputType());
        if (input == null || !validator.validate(input).isEmpty())
            throw new IllegalArgumentException("Invalid operation parameters");
        return definition.total(input);
    }

    private void schedule(OperationSnapshot snapshot) throws IOException {
        store.save(snapshot);
        var control = new Control(snapshot);
        active.put(snapshot.operationId(), control);
        try {
            workers.submit(() -> run(control));
        } catch (RuntimeException failure) {
            active.remove(snapshot.operationId());
            throw failure;
        }
    }

    private void run(Control control) {
        try {
            synchronized (this) {
                if (control.requested == null) update(control, OperationStatus.RUNNING, null);
            }
            var snapshot = control.snapshot;
            var context =
                    new OperationContext(
                            snapshot.operationId(),
                            snapshot.cursor(),
                            snapshot.pageSize(),
                            () ->
                                    control.requested != null
                                            || Thread.currentThread().isInterrupted(),
                            (cursor, rows) -> commit(control, cursor, rows));
            ScopedValue.where(OperationContext.CURRENT_OPERATION, snapshot.operationId())
                    .call(
                            () -> {
                                execute(
                                        registry.require(snapshot.operationType()),
                                        snapshot.parameters(),
                                        context);
                                return null;
                            });
            synchronized (this) {
                if (control.requested == null
                        && control.snapshot.cursor() != control.snapshot.total()) {
                    throw new IllegalStateException(
                            "Operation returned before committing all records");
                }
                update(
                        control,
                        control.requested != null ? control.requested : OperationStatus.COMPLETED,
                        null);
                // Publish a resumable state and release its worker ownership atomically.
                active.remove(control.snapshot.operationId(), control);
            }
        } catch (Exception failure) {
            LOG.error(
                    "Operation failed operationId={} category={}",
                    control.snapshot.operationId(),
                    failure.getClass().getSimpleName());
            synchronized (this) {
                try {
                    update(control, OperationStatus.FAILED, "EXECUTION_OR_STORAGE_FAILURE");
                } catch (IOException persistenceFailure) {
                    LOG.error(
                            "Checkpoint unavailable operationId={}; restart requires inspection",
                            control.snapshot.operationId());
                }
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            synchronized (this) {
                active.remove(control.snapshot.operationId(), control);
            }
        }
    }

    private <I> void execute(
            OperationDefinition<I> definition, JsonNode parameters, OperationContext context)
            throws Exception {
        definition.execute(mapper.treeToValue(parameters, definition.inputType()), context);
    }

    private synchronized void commit(Control control, long cursor, String rows) throws IOException {
        var snapshot = control.snapshot;
        long expected = Math.min(snapshot.total(), snapshot.cursor() + snapshot.pageSize());
        if (cursor != expected) throw new IOException("Non-contiguous checkpoint");
        preflight.checkDisk();
        store.saveChunk(snapshot.operationId(), snapshot.cursor(), rows);
        var next = snapshot.with(snapshot.status(), cursor, null);
        store.save(next);
        control.snapshot = next;
        meters.counter("toolkit.records.processed", "type", snapshot.operationType())
                .increment(cursor - snapshot.cursor());
    }

    private void update(Control control, OperationStatus status, String code) throws IOException {
        var next = control.snapshot.with(status, control.snapshot.cursor(), code);
        store.save(next);
        control.snapshot = next;
        LOG.info(
                "Operation state operationId={} state={} cursor={}",
                next.operationId(),
                status,
                next.cursor());
    }

    @PreDestroy
    public void shutdown() {
        synchronized (this) {
            stopping = true;
            active.values()
                    .forEach(
                            c -> {
                                if (c.requested == null) c.requested = OperationStatus.INTERRUPTED;
                            });
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(25, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException failure) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
