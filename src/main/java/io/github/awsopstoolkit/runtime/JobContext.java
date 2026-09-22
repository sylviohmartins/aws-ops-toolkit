package io.github.awsopstoolkit.runtime;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import tools.jackson.databind.ObjectMapper;

public final class JobContext {
    private final String id;
    private final JobRequest request;
    private final SqliteJournal journal;
    private final ExecutionPolicy policy;
    private final DispatchLimiter limiter;
    private final AtomicReference<JobState> stop;
    private final long started = System.nanoTime();
    private final long previousElapsed;
    private final Set<String> resources;
    public final ObjectMapper json;
    public final RuntimeProperties settings;

    public JobContext(
            String id,
            JobRequest request,
            SqliteJournal journal,
            ExecutionPolicy policy,
            DispatchLimiter limiter,
            AtomicReference<JobState> stop,
            Set<String> resources,
            ObjectMapper json,
            RuntimeProperties settings)
            throws SQLException {
        this.id = id;
        this.request = request;
        this.journal = journal;
        this.policy = policy;
        this.limiter = limiter;
        this.stop = stop;
        this.resources = resources;
        this.json = json;
        this.settings = settings;
        previousElapsed = journal.job(id).elapsedMillis();
    }

    public String id() {
        return id;
    }

    public JobRequest request() {
        return request;
    }

    public void readUsage(
            int scanned, software.amazon.awssdk.services.dynamodb.model.ConsumedCapacity capacity)
            throws SQLException {
        double units =
                capacity == null || capacity.capacityUnits() == null ? 0 : capacity.capacityUnits();
        journal.recordReadUsage(
                id, scanned, units, request.maxScannedRecords(), request.maxReadCapacity());
        io.micrometer.core.instrument.Metrics.counter("toolkit.records.scanned").increment(scanned);
        io.micrometer.core.instrument.Metrics.counter("toolkit.dynamodb.read.capacity")
                .increment(units);
    }

    public long elapsed() {
        return (System.nanoTime() - started) / 1_000_000;
    }

    void externalFailure(boolean throttle) throws SQLException {
        limiter.failure(throttle);
        io.micrometer.core.instrument.Metrics.counter(
                        "toolkit.external.failures", "throttle", Boolean.toString(throttle))
                .increment();
        if (throttle) {
            journal.recordThrottle(id);
            io.micrometer.core.instrument.Metrics.counter("toolkit.external.throttled").increment();
        }
    }

    void externalRetry() throws SQLException {
        journal.recordRetry(id);
    }

    public void checkpoint() throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (stop.get() != null) throw new JobStopped(stop.get());
        if (previousElapsed + elapsed() > request.maxSeconds() * 1000L)
            throw new JobStopped(JobState.BUDGET_EXCEEDED);
        policy.disk(journal);
    }

    private void before(String resource, boolean write) throws Exception {
        checkpoint();
        if (!resources.contains(resource))
            throw new IllegalArgumentException("Rule accessed an unplanned resource");
        var job = journal.job(id);
        // Reserve both STS and the resource dispatch before either reaches AWS.
        journal.reserveCalls(id, request.maxCalls(), 2);
        policy.authorize(job, write);
        checkpoint();
        io.micrometer.core.instrument.Metrics.counter(
                        "toolkit.runtime.dispatches", "kind", write ? "effect" : "read")
                .increment();
    }

    public <T> T read(String resource, Supplier<T> call) throws Exception {
        for (int attempt = 0; ; attempt++) {
            limiter.acquire(journal.job(id).rate());
            try {
                before(resource, false);
                io.micrometer.core.instrument.Metrics.counter(
                                "toolkit.aws.requests", "kind", "read")
                        .increment();
                long callStarted = System.nanoTime();
                T value = call.get();
                io.micrometer.core.instrument.Metrics.timer("toolkit.aws.latency", "kind", "read")
                        .record(
                                System.nanoTime() - callStarted,
                                java.util.concurrent.TimeUnit.NANOSECONDS);
                limiter.healthy();
                return value;
            } catch (AwsServiceException e) {
                if (authentication(e)) throw new JobStopped(JobState.AUTHENTICATION_REQUIRED);
                if (authorization(e)) throw new JobStopped(JobState.AUTHORIZATION_REQUIRED);
                boolean transientError = e.isThrottlingException() || e.statusCode() >= 500;
                io.micrometer.core.instrument.Metrics.counter(
                                "toolkit.aws.failures", "kind", "read")
                        .increment();
                if (e.isThrottlingException()) {
                    journal.recordThrottle(id);
                    io.micrometer.core.instrument.Metrics.counter("toolkit.aws.throttled")
                            .increment();
                }
                limiter.failure(e.isThrottlingException());
                if (!transientError || attempt >= 2) throw e;
                journal.recordRetry(id);
            } catch (SdkClientException e) {
                io.micrometer.core.instrument.Metrics.counter(
                                "toolkit.aws.failures", "kind", "read")
                        .increment();
                limiter.failure(false);
                if (attempt >= 2) throw e;
                journal.recordRetry(id);
            } finally {
                limiter.release();
            }
            io.micrometer.core.instrument.Metrics.counter("toolkit.aws.retries", "kind", "read")
                    .increment();
            DispatchLimiter.backoff(attempt);
        }
    }

    /** Intention is durable before dispatch. Unknown writes are never replayed without evidence. */
    public String effect(
            SqliteJournal.Task task,
            String step,
            String resource,
            Supplier<String> call,
            CheckedReconcile reconcile)
            throws Exception {
        if (request.mode() != JobMode.EXECUTE)
            throw new IllegalStateException("Remote effects are forbidden outside EXECUTE mode");
        var previous = journal.effect(id, task.sequence(), step);
        if (previous != null && previous.state().equals("SUCCEEDED")) return previous.result();
        if (previous != null && !previous.state().equals("NOT_SENT")) {
            Optional<String> result = reconcile.get();
            if (result.isPresent()) {
                journal.effect(id, task.sequence(), step, "SUCCEEDED", result.get());
                return result.get();
            }
            throw new JobStopped(JobState.RECONCILIATION_REQUIRED);
        }
        limiter.acquire(journal.job(id).rate());
        try {
            before(resource, true);
            journal.effect(id, task.sequence(), step, "INTENT", "");
            try {
                io.micrometer.core.instrument.Metrics.counter(
                                "toolkit.aws.requests", "kind", "effect")
                        .increment();
                long callStarted = System.nanoTime();
                String result = call.get();
                io.micrometer.core.instrument.Metrics.timer("toolkit.aws.latency", "kind", "effect")
                        .record(
                                System.nanoTime() - callStarted,
                                java.util.concurrent.TimeUnit.NANOSECONDS);
                journal.effect(id, task.sequence(), step, "SUCCEEDED", result);
                limiter.healthy();
                return result;
            } catch (EffectNotDispatched e) {
                journal.effect(id, task.sequence(), step, "NOT_SENT", "");
                throw new JobStopped(e.state());
            } catch (AwsServiceException e) {
                boolean rejected = e.statusCode() >= 400 && e.statusCode() < 500;
                journal.effect(id, task.sequence(), step, rejected ? "NOT_SENT" : "UNKNOWN", "");
                io.micrometer.core.instrument.Metrics.counter(
                                "toolkit.aws.failures", "kind", "effect")
                        .increment();
                if (e.isThrottlingException()) {
                    journal.recordThrottle(id);
                    io.micrometer.core.instrument.Metrics.counter("toolkit.aws.throttled")
                            .increment();
                }
                if (authentication(e)) throw new JobStopped(JobState.AUTHENTICATION_REQUIRED);
                if (authorization(e)) throw new JobStopped(JobState.AUTHORIZATION_REQUIRED);
                if (!rejected) throw new JobStopped(JobState.RECONCILIATION_REQUIRED);
                limiter.failure(e.isThrottlingException());
                throw e;
            } catch (RuntimeException e) {
                journal.effect(id, task.sequence(), step, "UNKNOWN", "");
                throw new JobStopped(JobState.RECONCILIATION_REQUIRED);
            }
        } finally {
            limiter.release();
        }
    }

    public void audit(String event, String detail) throws SQLException {
        journal.audit(id, event, detail);
    }

    public String delivery(
            SqliteJournal.Task task, String step, String resource, Supplier<String> call)
            throws Exception {
        long owner = journal.priorDelivery(id, step);
        return effect(
                owner == 0 ? task : new SqliteJournal.Task(owner, task.key(), task.payload()),
                step,
                resource,
                call,
                Optional::empty);
    }

    public SqliteJournal.NamedEffect latestEffect(SqliteJournal.Task task, String prefix)
            throws SQLException {
        return journal.latestEffect(id, task.sequence(), prefix);
    }

    public SqliteJournal.Effect effectState(SqliteJournal.Task task, String step)
            throws SQLException {
        return journal.effect(id, task.sequence(), step);
    }

    private static boolean authentication(AwsServiceException e) {
        String code = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
        return e.statusCode() == 401
                || Set.of(
                                "ExpiredToken",
                                "ExpiredTokenException",
                                "InvalidClientTokenId",
                                "UnrecognizedClientException")
                        .contains(code == null ? "" : code);
    }

    private static boolean authorization(AwsServiceException e) {
        String code = e.awsErrorDetails() == null ? "" : e.awsErrorDetails().errorCode();
        String normalized = code == null ? "" : code;
        return e.statusCode() == 403
                || normalized.startsWith("AccessDenied")
                || normalized.startsWith("UnauthorizedOperation");
    }

    @FunctionalInterface
    public interface CheckedReconcile {
        Optional<String> get() throws Exception;
    }
}
