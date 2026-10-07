package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.security.Masking;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Validator;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tools.jackson.databind.ObjectMapper;

public final class JobCoordinator {
    private static final int MIN_APPROVAL_REASON_CHARS = 8;
    private static final int MIN_RECONCILIATION_EVIDENCE_CHARS = 12;
    private static final int MAX_RECONCILIATION_EVIDENCE_CHARS = 512;
    private static final int MAX_RECOVERED_RESULT_CHARS = 1_500_000;
    private static final int MAX_UPLOAD_ID_CHARS = 2_048;

    private final SqliteJournal journal;
    private final RuntimeProperties settings;
    private final ExecutionPolicy policy;
    private final Map<String, Workflow> workflows;
    private final ObjectMapper json;
    private final Validator validator;
    private final DispatchLimiter limiter;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, AtomicReference<JobState>> active = new HashMap<>();
    private boolean closing;

    public JobCoordinator(
            SqliteJournal journal,
            RuntimeProperties settings,
            ExecutionPolicy policy,
            List<Workflow> workflows,
            ObjectMapper json,
            Validator validator) {
        this.journal = journal;
        this.settings = settings;
        this.policy = policy;
        this.json = json;
        this.validator = validator;
        this.workflows = new HashMap<>();
        for (var workflow : workflows)
            if (this.workflows.put(workflow.type(), workflow) != null)
                throw new IllegalArgumentException("Duplicate rule");
        limiter =
                new DispatchLimiter(
                        settings.workers(),
                        settings.requestsPerSecond(),
                        settings.healthyResponsesBeforeIncrease(),
                        settings.circuitFailuresBeforeOpen(),
                        settings.circuitOpenDuration(),
                        settings.retryBaseBackoff());
    }

    public synchronized SqliteJournal.Job create(JobRequest request) throws Exception {
        capacity();
        if (!validator.validate(request).isEmpty())
            throw new IllegalArgumentException("Invalid request budgets");
        Workflow workflow = workflow(request.operation());
        workflow.validate(request);
        policy.validateIntent(request, workflow.writes());
        policy.preflight(journal, request, workflow.resources(request.parameters()));
        String identity = policy.identity();
        String id = UUID.randomUUID().toString();
        journal.create(
                id,
                json.writeValueAsString(request),
                workflow.version(),
                identity,
                settings.requestsPerSecond());
        String contextAudit =
                "mode="
                        + request.mode()
                        + ";incident="
                        + Objects.toString(request.incidentId(), "")
                        + ";change="
                        + Objects.toString(request.changeId(), "")
                        + ";resources="
                        + String.join(",", workflow.resources(request.parameters()));
        journal.audit(id, "REQUEST_CONTEXT", contextAudit);
        journal.audit(
                id,
                "CONFIG_SNAPSHOT",
                "workers="
                        + settings.workers()
                        + ";pageSize="
                        + settings.pageSize()
                        + ";requestsPerSecond="
                        + settings.requestsPerSecond()
                        + ";planLifetime="
                        + settings.planLifetime()
                        + ";approvalLifetime="
                        + settings.approvalLifetime()
                        + ";healthyResponsesBeforeIncrease="
                        + settings.healthyResponsesBeforeIncrease()
                        + ";circuitFailuresBeforeOpen="
                        + settings.circuitFailuresBeforeOpen()
                        + ";circuitOpenDuration="
                        + settings.circuitOpenDuration()
                        + ";retryBaseBackoff="
                        + settings.retryBaseBackoff()
                        + ";readMaxAttempts="
                        + settings.readMaxAttempts()
                        + ";environment="
                        + policy.environment()
                        + ";region="
                        + policy.region());
        schedule(id, true);
        return journal.job(id);
    }

    public SqliteJournal.Job status(String id) throws SQLException {
        return journal.job(id);
    }

    public Map<String, Object> statusView(String id) throws Exception {
        var job = journal.job(id);
        var request = request(job);
        var rule = workflow(request.operation());
        var view = new LinkedHashMap<String, Object>();
        view.put("id", job.id());
        view.put("operation", request.operation());
        view.put("mode", request.mode().name());
        view.put("state", job.state().name());
        view.put("hash", job.hash());
        view.put("created", job.created());
        if (job.finished() > 0) view.put("finished", job.finished());
        view.put("environment", policy.environment());
        view.put("region", policy.region());
        view.put("account", policy.account());
        view.put("approvedUntil", job.approvedUntil());
        view.put("promoted", job.promoted());
        view.put("calls", job.calls());
        view.put("retries", job.retries());
        view.put("throttles", job.throttles());
        view.put("elapsedMillis", job.elapsedMillis());
        view.put("requestsPerSecond", job.rate());
        view.put("adaptiveRequestsPerSecond", limiter.currentRate());
        view.put("adaptiveConcurrency", limiter.currentConcurrency());
        view.put("concurrencyCeiling", limiter.currentConcurrencyCeiling());
        view.put("planned", job.planned());
        view.put("identity", Masking.stableIdentifier(job.identity()));
        view.put("resources", rule.resources(request.parameters()));
        var parameterNames = new TreeSet<String>();
        request.parameters().propertyNames().forEach(parameterNames::add);
        view.put("parameterNames", List.copyOf(parameterNames));
        view.put("records", journal.count(id, null));
        view.put("done", journal.count(id, "DONE"));
        view.put("errors", journal.errors(id));
        view.put("conflicts", journal.conflicts(id));
        view.put("usage", journal.usage(id));
        if (request.incidentId() != null) view.put("incidentId", request.incidentId());
        if (request.changeId() != null) view.put("changeId", request.changeId());
        return Collections.unmodifiableMap(view);
    }

    public List<Map<String, Object>> planPageView(String id, long after) throws Exception {
        var job = journal.job(id);
        var request = request(job);
        var rule = workflow(request.operation());
        var result = new ArrayList<Map<String, Object>>();
        for (var row : journal.planPage(id, after)) {
            long sequence = ((Number) row.get("sequence")).longValue();
            String key = String.valueOf(row.get("key"));
            String payload = String.valueOf(row.get("payload"));
            var proposal =
                    rule.proposal(
                            request.parameters(), new SqliteJournal.Task(sequence, key, payload));
            result.add(
                    Map.of(
                            "sequence", sequence,
                            "record", Masking.stableIdentifier(key),
                            "action", proposal.action(),
                            "before", proposal.before(),
                            "after", proposal.after(),
                            "state", row.get("state"),
                            "outcome", row.get("outcome")));
        }
        return List.copyOf(result);
    }

    public Set<String> operations() {
        return Set.copyOf(workflows.keySet());
    }

    public Map<String, Object> summary(String id) throws Exception {
        var job = journal.job(id);
        long records = journal.count(id, null);
        long done = journal.count(id, "DONE");
        long errors = journal.errors(id);
        var effects = journal.effectCounts(id);
        long unresolved =
                effects.getOrDefault(EffectState.UNKNOWN, 0L)
                        + effects.getOrDefault(EffectState.INTENT, 0L);
        double errorRate = done == 0 ? 0.0 : errors / (double) done;
        double throughput = job.elapsedMillis() <= 0 ? 0.0 : done * 1000.0 / job.elapsedMillis();
        var result = new LinkedHashMap<String, Object>();
        result.put("job", statusView(id));
        result.put("outcomes", journal.outcomeCounts(id));
        result.put("effects", effects);
        result.put("errorRate", errorRate);
        result.put("recordsPerSecond", throughput);
        result.put("unresolvedEffects", unresolved);
        result.put("postOperationReconciled", unresolved == 0);
        result.put("records", records);
        result.put("done", done);
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> dryRunSummary(String id) throws Exception {
        var job = journal.job(id);
        var request = request(job);
        var rule = workflow(request.operation());
        if (request.mode() != JobMode.DRY_RUN || job.state() != JobState.DRY_RUN_COMPLETE)
            throw new IllegalStateException(
                    "Dry-run summary is available after DRY_RUN completion");
        long candidates = journal.count(id, null);
        var usage = journal.usage(id);
        long scanned = usage.get("scanned").longValue();
        long located = Math.max(scanned, candidates);
        long ignored = Math.max(0, located - candidates);
        var samples = new ArrayList<Map<String, Object>>();
        for (var task : journal.pending(id, settings.dryRunSampleSize())) {
            var proposal = rule.proposal(request.parameters(), task);
            samples.add(
                    Map.of(
                            "record", Masking.stableIdentifier(task.key()),
                            "action", proposal.action(),
                            "before", proposal.before(),
                            "after", proposal.after()));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("operationId", id);
        result.put("operation", request.operation());
        result.put("mode", request.mode().name());
        result.put("planHash", job.hash());
        result.put("located", located);
        result.put("candidates", candidates);
        result.put("ignored", ignored);
        result.put("proposedChanges", rule.writes() ? candidates : 0);
        result.put("resources", rule.resources(request.parameters()));
        result.put(
                "estimatedExecutionCalls",
                Math.multiplyExact(candidates, (long) rule.estimatedCallsPerCandidate()));
        result.put("observedReadUsage", usage);
        result.put("risks", rule.risks());
        result.put("samples", List.copyOf(samples));
        result.put(
                "selectionSemantics",
                request.operation().startsWith("sqs-") || request.operation().equals("dlq-replay")
                        ? "planned-command-budget; queue receive is intentionally not performed in dry-run"
                        : "observed-source-selection");
        return Map.copyOf(result);
    }

    public synchronized SqliteJournal.Job approve(
            String id, String hash, String reason, boolean promote) throws Exception {
        capacity();
        var job = journal.job(id);
        if (active.containsKey(id)
                || job.state().terminal()
                || !job.planned()
                || !job.hash().equals(hash)
                || hash.isBlank()
                || reason == null
                || reason.length() < MIN_APPROVAL_REASON_CHARS
                || reason.length() > JobRequestLimits.MAX_REASON_CHARS
                || reason.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(
                    "Approval must bind the current plan and change reason");
        if (job.created() + settings.planLifetime().toSeconds() < SqliteJournal.now())
            throw new IllegalStateException("Plan expired; generate a new plan");
        if (promote && job.state() != JobState.CANARY_COMPLETE && !job.promoted())
            throw new IllegalStateException("A successful canary is required");
        var request = request(job);
        var rule = workflow(request.operation());
        if (request.mode() != JobMode.EXECUTE)
            throw new IllegalStateException("DRY_RUN plans cannot dispatch effects");
        policy.validateIntent(request, rule.writes());
        policy.authorize(job, false);
        policy.preflight(journal, request, rule.resources(request.parameters()));
        journal.approve(
                id,
                SqliteJournal.now() + settings.approvalLifetime().toSeconds(),
                reason,
                promote || job.promoted());
        schedule(id, false);
        return journal.job(id);
    }

    public synchronized SqliteJournal.Job resumePlanning(String id) throws Exception {
        capacity();
        var job = journal.job(id);
        if (active.containsKey(id) || job.planned() || job.state().terminal())
            throw new IllegalStateException("Not a resumable plan");
        policy.authorize(job, false);
        var request = request(job);
        var rule = workflow(request.operation());
        policy.validateIntent(request, rule.writes());
        policy.preflight(journal, request, rule.resources(request.parameters()));
        schedule(id, true);
        return journal.job(id);
    }

    public synchronized SqliteJournal.Job stop(String id, boolean cancel) throws SQLException {
        var job = journal.job(id);
        if (job.state().terminal()) throw new IllegalStateException("Job already terminal");
        var target = cancel ? JobState.CANCELLED : JobState.PAUSED;
        var control = active.get(id);
        if (control != null) {
            control.updateAndGet(old -> old == JobState.CANCELLED ? old : target);
            journal.audit(id, "STOP_REQUESTED", target.name());
        } else journal.state(id, target);
        return journal.job(id);
    }

    public synchronized void tune(String id, int rate) throws SQLException {
        tune(id, rate, null);
    }

    public synchronized void tune(String id, Integer rate, Integer concurrency)
            throws SQLException {
        journal.job(id);
        if (rate == null && concurrency == null)
            throw new IllegalArgumentException("At least one tuning value is required");
        if (rate != null) {
            if (rate < 1 || rate > settings.requestsPerSecond())
                throw new IllegalArgumentException("Configured rate ceiling exceeded");
            journal.rate(id, rate);
        }
        if (concurrency != null) {
            if (concurrency < 1 || concurrency > settings.workers())
                throw new IllegalArgumentException("Configured concurrency ceiling exceeded");
            limiter.tuneConcurrency(concurrency);
            journal.audit(id, "CONCURRENCY_CEILING", Integer.toString(concurrency));
        }
    }

    public synchronized void reconcile(
            String id, long task, String step, boolean succeeded, String evidence)
            throws SQLException {
        reconcile(id, task, step, succeeded, evidence, null);
    }

    public synchronized void reconcile(
            String id,
            long task,
            String step,
            boolean succeeded,
            String evidence,
            String recoveredResult)
            throws SQLException {
        var job = journal.job(id);
        if (active.containsKey(id)
                || !Set.of(
                                JobState.RECONCILIATION_REQUIRED,
                                JobState.CANCELLED,
                                JobState.INTERRUPTED)
                        .contains(job.state())
                || evidence == null
                || evidence.length() < MIN_RECONCILIATION_EVIDENCE_CHARS
                || evidence.length() > MAX_RECONCILIATION_EVIDENCE_CHARS)
            throw new IllegalStateException("Reconciliation requires a stopped job and evidence");
        var effect = journal.effect(id, task, step);
        if (effect == null || !effect.state().unresolved())
            throw new IllegalArgumentException("No unknown effect");
        if (succeeded) validateRecoveredResult(step, recoveredResult);
        journal.effect(
                id,
                task,
                step,
                succeeded ? EffectState.SUCCEEDED : EffectState.NOT_SENT,
                succeeded ? recoveredResult : "");
        journal.audit(id, "OPERATOR_RECONCILIATION", task + ":" + step + ":" + evidence);
    }

    private void validateRecoveredResult(String step, String result) {
        if (result == null || result.isBlank() || result.length() > MAX_RECOVERED_RESULT_CHARS)
            throw new IllegalArgumentException("Actual recovered result is required");
        if (step.equals("receive") || step.startsWith("receive-refresh/")) {
            var envelope = json.readTree(result);
            if (!envelope.isObject())
                throw new IllegalArgumentException("Invalid recovered envelope");
            if (!envelope.isEmpty()) {
                for (String field : List.of("id", "receipt", "body"))
                    if (!envelope.path(field).isTextual())
                        throw new IllegalArgumentException("Invalid recovered envelope");
                if (envelope.path("id").asText().isBlank()
                        || envelope.path("receipt").asText().isBlank())
                    throw new IllegalArgumentException("Invalid recovered receipt");
                if (envelope.path("receivedAt").asLong() < 0
                        || envelope.path("leaseUntil").asLong() < 0)
                    throw new IllegalArgumentException("Invalid recovered lease");
            }
        } else if (step.equals("multipart-create")
                && !result.matches("[A-Za-z0-9+/=_-]{1," + MAX_UPLOAD_ID_CHARS + "}"))
            throw new IllegalArgumentException("Actual upload ID is required");
        else if (step.equals("lambda-validation")
                && !Set.of("ACCEPTED", "FUNCTION_ERROR", "BUSINESS_REJECTED").contains(result))
            throw new IllegalArgumentException("Actual Lambda result is required");
    }

    private Workflow workflow(String type) {
        var rule = workflows.get(type);
        if (rule == null) throw new IllegalArgumentException("Unknown operation");
        return rule;
    }

    private JobRequest request(SqliteJournal.Job job) {
        return json.readValue(job.request(), JobRequest.class);
    }

    private void capacity() {
        if (closing || !active.isEmpty())
            throw new IllegalStateException("One operational job may run at a time");
    }

    private void schedule(String id, boolean planning) throws SQLException {
        var stop = new AtomicReference<JobState>();
        active.put(id, stop);
        journal.state(id, planning ? JobState.PLANNING : JobState.RUNNING);
        executor.submit(() -> run(id, planning, stop));
    }

    private void run(String id, boolean planning, AtomicReference<JobState> stop) {
        JobState finalState = JobState.FAILED;
        JobContext context = null;
        try {
            var job = journal.job(id);
            var request = request(job);
            var rule = workflow(request.operation());
            if (!job.version().equals(rule.version()))
                throw new IllegalStateException("Rule changed");
            context =
                    new JobContext(
                            id,
                            request,
                            journal,
                            policy,
                            limiter,
                            stop,
                            rule.resources(request.parameters()),
                            json,
                            settings);
            if (planning) {
                journal.audit(id, "PREFLIGHT_STARTED", request.mode().name());
                rule.preflight(context);
                journal.audit(id, "PREFLIGHT_PASSED", request.mode().name());
                plan(rule, context);
                context.checkpoint();
                finalState =
                        request.mode() == JobMode.DRY_RUN
                                ? JobState.DRY_RUN_COMPLETE
                                : JobState.READY;
                journal.seal(id, finalState);
            } else {
                if (request.mode() != JobMode.EXECUTE)
                    throw new IllegalStateException("Only EXECUTE jobs may enter execution");
                finalState = execute(rule, context, job.promoted());
            }
        } catch (JobStopped e) {
            finalState = e.state();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finalState = JobState.INTERRUPTED;
        } catch (Exception e) {
            try {
                String category = e.getClass().getSimpleName();
                if (e instanceof software.amazon.awssdk.awscore.exception.AwsServiceException aws
                        && aws.awsErrorDetails() != null)
                    category += ":" + aws.awsErrorDetails().errorCode();
                journal.audit(id, "FAILURE", category);
            } catch (SQLException ignored) {
                /* Disk failure leaves durable intent for recovery. */
            }
            finalState = JobState.PAUSED;
        } finally {
            synchronized (this) {
                try {
                    if (context != null) journal.elapsed(id, context.elapsed());
                    if (stop.get() != null && finalState != JobState.RECONCILIATION_REQUIRED)
                        finalState = stop.get();
                    journal.state(id, finalState);
                } catch (SQLException e) {
                    org.slf4j.LoggerFactory.getLogger(getClass())
                            .error("Journal unavailable for job {}", id);
                } finally {
                    active.remove(id, stop);
                }
            }
        }
    }

    private void plan(Workflow rule, JobContext context) throws Exception {
        // A fixed worker cohort bounds memory; segment identity never changes on resume.
        var next = new AtomicInteger();
        parallel(
                Math.min(context.request().segments(), settings.workers()),
                () -> {
                    for (int segment;
                            (segment = next.getAndIncrement()) < context.request().segments(); ) {
                        var cursor = journal.cursor(context.id(), segment);
                        while (!cursor.complete()) {
                            context.checkpoint();
                            var page = rule.plan(context, segment, cursor.value());
                            if (page.records().size() > settings.pageSize()
                                    || (!page.complete() && page.cursor().equals(cursor.value())))
                                throw new IllegalStateException(
                                        "Unbounded or non-progressing rule page");
                            journal.page(
                                    context.id(), segment, page, context.request().maxRecords());
                            cursor = new SqliteJournal.Cursor(page.cursor(), page.complete());
                        }
                    }
                });
    }

    private JobState execute(Workflow rule, JobContext context, boolean promoted) throws Exception {
        long limit = promoted ? Long.MAX_VALUE : context.request().canaryRecords();
        while (journal.count(context.id(), "DONE") < limit) {
            context.checkpoint();
            int size =
                    (int)
                            Math.min(
                                    settings.pageSize(),
                                    limit - journal.count(context.id(), "DONE"));
            var batch = journal.pending(context.id(), size);
            if (batch.isEmpty()) return JobState.COMPLETED;
            // Sequential task commits preserve a strict conflict budget; source scan remains
            // parallel.
            for (var task : batch) {
                context.checkpoint();
                String outcome = rule.execute(context, task);
                journal.finish(context.id(), task.sequence(), outcome);
                io.micrometer.core.instrument.Metrics.counter(
                                "toolkit.records.processed",
                                "operation",
                                rule.type(),
                                "outcome",
                                outcome)
                        .increment();
                if (journal.conflicts(context.id()) > context.request().maxConflicts())
                    throw new JobStopped(JobState.BUDGET_EXCEEDED);
                long done = journal.count(context.id(), "DONE");
                long errors = journal.errors(context.id());
                boolean absoluteExceeded = errors > context.request().maxErrors();
                boolean rateExceeded =
                        done >= context.request().minErrorSample()
                                && errors / (double) done > context.request().maxErrorRate();
                if (absoluteExceeded || rateExceeded) {
                    journal.audit(
                            context.id(),
                            "ERROR_BUDGET_EXCEEDED",
                            "errors=" + errors + ",done=" + done);
                    throw new JobStopped(JobState.BUDGET_EXCEEDED);
                }
            }
        }
        return journal.count(context.id(), "PLANNED") == 0
                ? JobState.COMPLETED
                : JobState.CANARY_COMPLETE;
    }

    private static void parallel(int workers, CheckedRunnable work) throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = new ExecutorCompletionService<Void>(pool);
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++)
                futures.add(
                        completion.submit(
                                () -> {
                                    work.run();
                                    return null;
                                }));
            try {
                for (int i = 0; i < workers; i++) completion.take().get();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw new IllegalStateException(e.getCause());
            } finally {
                futures.forEach(f -> f.cancel(true));
                pool.shutdownNow();
            }
        }
    }

    @PreDestroy
    public void close() throws InterruptedException {
        synchronized (this) {
            closing = true;
            active.values().forEach(s -> s.compareAndSet(null, JobState.INTERRUPTED));
        }
        executor.shutdown();
        if (!executor.awaitTermination(
                settings.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS))
            executor.shutdownNow();
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
