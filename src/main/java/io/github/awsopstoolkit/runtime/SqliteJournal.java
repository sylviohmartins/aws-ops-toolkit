package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.security.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Single local writer, FULL durability. No remote effect is presumed atomic with SQLite. */
public final class SqliteJournal implements AutoCloseable {
    private static final int MAX_RECORD_KEY_CHARS = 2_048;
    private static final int MAX_RECORD_PAYLOAD_CHARS = 1_048_576;
    private static final int PLAN_PAGE_SIZE = 100;
    private static final int REPORT_WINDOW_SIZE = 500;
    private static final int MAX_EFFECT_PREFIX_CHARS = 64;
    private final Connection db;
    private final Path directory;

    public SqliteJournal(Path directory) throws SQLException, IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        db =
                DriverManager.getConnection(
                        "jdbc:sqlite:" + this.directory.resolve("operations.sqlite"));
        try (var s = db.createStatement()) {
            int version;
            try (var r = s.executeQuery("PRAGMA user_version")) {
                r.next();
                version = r.getInt(1);
            }
            if (version > 5) throw new SQLException("Unsupported journal schema");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=FULL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA foreign_keys=ON");
            db.setAutoCommit(false);
            s.execute(
                    "CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, request TEXT NOT NULL, version TEXT NOT NULL, identity TEXT NOT NULL, state TEXT NOT NULL, hash TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL, approved_until INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '', promoted INTEGER NOT NULL DEFAULT 0, calls INTEGER NOT NULL DEFAULT 0, elapsed INTEGER NOT NULL DEFAULT 0, rate INTEGER NOT NULL, planned INTEGER NOT NULL DEFAULT 0)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS cursors(job TEXT NOT NULL REFERENCES jobs(id), segment INTEGER NOT NULL, cursor TEXT NOT NULL, complete INTEGER NOT NULL, PRIMARY KEY(job,segment))");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS tasks(seq INTEGER PRIMARY KEY AUTOINCREMENT, job TEXT NOT NULL REFERENCES jobs(id), record_key TEXT NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'PLANNED', outcome TEXT NOT NULL DEFAULT '', UNIQUE(job,record_key))");
            s.execute("CREATE INDEX IF NOT EXISTS pending ON tasks(job,state,seq)");
            s.execute("CREATE INDEX IF NOT EXISTS report_pages ON tasks(job,seq)");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS effects(job TEXT NOT NULL REFERENCES jobs(id), task INTEGER NOT NULL REFERENCES tasks(seq), step TEXT NOT NULL, state TEXT NOT NULL, result TEXT NOT NULL DEFAULT '', PRIMARY KEY(job,task,step))");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS audit(seq INTEGER PRIMARY KEY AUTOINCREMENT, job TEXT NOT NULL REFERENCES jobs(id), time INTEGER NOT NULL, event TEXT NOT NULL, detail TEXT NOT NULL)");
            if (version < 2)
                s.execute("ALTER TABLE jobs ADD COLUMN run_started INTEGER NOT NULL DEFAULT 0");
            if (version < 3) {
                s.execute("ALTER TABLE jobs ADD COLUMN scanned INTEGER NOT NULL DEFAULT 0");
                s.execute("ALTER TABLE jobs ADD COLUMN read_units REAL NOT NULL DEFAULT 0");
            }
            if (version < 4) {
                s.execute("ALTER TABLE jobs ADD COLUMN finished INTEGER NOT NULL DEFAULT 0");
                s.execute("ALTER TABLE jobs ADD COLUMN retries INTEGER NOT NULL DEFAULT 0");
                s.execute("ALTER TABLE jobs ADD COLUMN throttles INTEGER NOT NULL DEFAULT 0");
            }
            if (version < 5) {
                s.execute("ALTER TABLE jobs ADD COLUMN planned_records INTEGER NOT NULL DEFAULT 0");
                s.execute(
                        "UPDATE jobs SET planned_records=(SELECT count(*) FROM tasks WHERE tasks.job=jobs.id)");
            }
            s.execute("PRAGMA user_version=5");
            s.executeUpdate(
                    "UPDATE jobs SET elapsed=elapsed+MAX(0,CAST(strftime('%s','now') AS INTEGER)*1000-run_started),run_started=0 WHERE run_started>0");
            s.executeUpdate(
                    "UPDATE jobs SET state='INTERRUPTED', approved_until=0 WHERE state IN ('PLANNING','RUNNING','APPROVED')");
            db.commit();
            db.setAutoCommit(true);
        } catch (SQLException | RuntimeException e) {
            if (!db.getAutoCommit()) db.rollback();
            db.close();
            throw e;
        }
    }

    public synchronized void create(
            String id, String request, String version, String identity, int rate)
            throws SQLException {
        transaction(
                () -> {
                    update(
                            "INSERT INTO jobs(id,request,version,identity,state,created,rate) VALUES(?,?,?,?,?,?,?)",
                            id,
                            request,
                            version,
                            identity,
                            "PLANNING",
                            now(),
                            rate);
                    audit(id, "CREATED", "");
                });
    }

    public synchronized Job job(String id) throws SQLException {
        try (var s = statement("SELECT * FROM jobs WHERE id=?", id);
                var r = s.executeQuery()) {
            if (!r.next()) throw new IllegalArgumentException("Unknown job");
            return new Job(
                    id,
                    r.getString("request"),
                    r.getString("version"),
                    r.getString("identity"),
                    JobState.valueOf(r.getString("state")),
                    r.getString("hash"),
                    r.getLong("created"),
                    r.getLong("finished"),
                    r.getLong("approved_until"),
                    r.getBoolean("promoted"),
                    r.getInt("calls"),
                    r.getInt("retries"),
                    r.getInt("throttles"),
                    r.getLong("elapsed"),
                    r.getInt("rate"),
                    r.getBoolean("planned"));
        }
    }

    public synchronized void page(String id, int segment, Workflow.Page page, long maxRecords)
            throws SQLException {
        transaction(
                () -> {
                    long inserted = 0;
                    for (var candidate : page.records()) {
                        if (candidate.key().length() > MAX_RECORD_KEY_CHARS
                                || candidate.payload().length() > MAX_RECORD_PAYLOAD_CHARS)
                            throw new IllegalArgumentException("Record too large");
                        inserted +=
                                update(
                                        "INSERT OR IGNORE INTO tasks(job,record_key,payload) VALUES(?,?,?)",
                                        id,
                                        candidate.key(),
                                        candidate.payload());
                    }
                    if (inserted > 0
                            && update(
                                            "UPDATE jobs SET planned_records=planned_records+? WHERE id=? AND planned_records+?<=?",
                                            inserted,
                                            id,
                                            inserted,
                                            maxRecords)
                                    != 1) throw new IllegalStateException("Record budget exceeded");
                    update(
                            "INSERT INTO cursors VALUES(?,?,?,?) ON CONFLICT(job,segment) DO UPDATE SET cursor=excluded.cursor, complete=excluded.complete",
                            id,
                            segment,
                            page.cursor(),
                            page.complete());
                    audit(id, "PAGE_COMMITTED", Integer.toString(segment));
                });
    }

    public synchronized Cursor cursor(String id, int segment) throws SQLException {
        try (var s =
                        statement(
                                "SELECT cursor,complete FROM cursors WHERE job=? AND segment=?",
                                id,
                                segment);
                var r = s.executeQuery()) {
            return r.next() ? new Cursor(r.getString(1), r.getBoolean(2)) : new Cursor("", false);
        }
    }

    public synchronized String seal(String id) throws SQLException {
        return seal(id, JobState.READY);
    }

    public synchronized String seal(String id, JobState targetState) throws SQLException {
        if (targetState != JobState.READY && targetState != JobState.DRY_RUN_COMPLETE)
            throw new IllegalArgumentException("Invalid sealed-plan state");
        var digest = Hashing.sha256Digest();
        var job = job(id);
        hashField(digest, job.request());
        hashField(digest, job.version());
        hashField(digest, job.identity());
        try (var s =
                        statement(
                                "SELECT record_key,payload FROM tasks WHERE job=? ORDER BY record_key",
                                id);
                var r = s.executeQuery()) {
            while (r.next()) {
                hashField(digest, r.getString(1));
                hashField(digest, r.getString(2));
            }
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        transaction(
                () -> {
                    update(
                            "UPDATE jobs SET hash=?,planned=1,state=? WHERE id=?",
                            hash,
                            targetState.name(),
                            id);
                    audit(id, "PLAN_SEALED", hash);
                });
        return hash;
    }

    public synchronized void approve(String id, long until, String reason, boolean promote)
            throws SQLException {
        transaction(
                () -> {
                    update(
                            "UPDATE jobs SET approved_until=?, reason=?, promoted=?,state='APPROVED' WHERE id=?",
                            until,
                            reason,
                            promote,
                            id);
                    audit(id, promote ? "PROMOTED" : "APPROVED", reason);
                });
    }

    public synchronized void state(String id, JobState state) throws SQLException {
        transaction(
                () -> {
                    if (state.terminal())
                        update(
                                "UPDATE jobs SET state=?,finished=? WHERE id=?",
                                state.name(),
                                now(),
                                id);
                    else update("UPDATE jobs SET state=? WHERE id=?", state.name(), id);
                    if (state == JobState.RUNNING || state == JobState.PLANNING)
                        update(
                                "UPDATE jobs SET run_started=? WHERE id=?",
                                System.currentTimeMillis(),
                                id);
                    else update("UPDATE jobs SET run_started=0 WHERE id=?", id);
                    audit(id, "STATE", state.name());
                });
        io.micrometer.core.instrument.Metrics.counter(
                        "toolkit.job.state.transitions", "state", state.name())
                .increment();
        org.slf4j.LoggerFactory.getLogger(getClass())
                .atInfo()
                .addKeyValue("operationId", id)
                .addKeyValue("state", state.name())
                .log("Job state transition");
    }

    public synchronized List<Task> pending(String id, int limit) throws SQLException {
        List<Task> tasks = new ArrayList<>();
        try (var s =
                        statement(
                                "SELECT seq,record_key,payload FROM tasks WHERE job=? AND state='PLANNED' ORDER BY seq LIMIT ?",
                                id,
                                limit);
                var r = s.executeQuery()) {
            while (r.next()) tasks.add(new Task(r.getLong(1), r.getString(2), r.getString(3)));
        }
        return List.copyOf(tasks);
    }

    public synchronized Effect effect(String id, long task, String step) throws SQLException {
        try (var s =
                        statement(
                                "SELECT state,result FROM effects WHERE job=? AND task=? AND step=?",
                                id,
                                task,
                                step);
                var r = s.executeQuery()) {
            return r.next() ? new Effect(r.getString(1), r.getString(2)) : null;
        }
    }

    public synchronized void effect(String id, long task, String step, String state, String result)
            throws SQLException {
        transaction(
                () -> {
                    update(
                            "INSERT INTO effects VALUES(?,?,?,?,?) ON CONFLICT(job,task,step) DO UPDATE SET state=excluded.state,result=excluded.result",
                            id,
                            task,
                            step,
                            state,
                            result);
                    audit(id, "EFFECT_" + state, task + ":" + step);
                });
    }

    public synchronized void finish(String id, long task, String outcome) throws SQLException {
        transaction(
                () -> {
                    update(
                            "UPDATE tasks SET state='DONE',outcome=? WHERE job=? AND seq=?",
                            outcome,
                            id,
                            task);
                    audit(id, "TASK_DONE", task + ":" + outcome);
                });
    }

    public synchronized long count(String id, String state) throws SQLException {
        try (var s =
                        state == null
                                ? statement("SELECT count(*) FROM tasks WHERE job=?", id)
                                : statement(
                                        "SELECT count(*) FROM tasks WHERE job=? AND state=?",
                                        id,
                                        state);
                var r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        }
    }

    public synchronized long conflicts(String id) throws SQLException {
        try (var s =
                        statement(
                                "SELECT count(*) FROM tasks WHERE job=? AND outcome='CONFLICT'",
                                id);
                var r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        }
    }

    public synchronized long errors(String id) throws SQLException {
        try (var s =
                        statement(
                                "SELECT count(*) FROM tasks WHERE job=? AND state='DONE' AND outcome IN ('FAILED','FUNCTION_ERROR','BUSINESS_REJECTED','ERROR')",
                                id);
                var r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        }
    }

    public synchronized void reserveCall(String id, int maxCalls) throws SQLException {
        reserveCalls(id, maxCalls, 1);
    }

    public synchronized void recordReadUsage(
            String id, int scanned, double capacity, long maxScanned, double maxCapacity)
            throws SQLException {
        if (scanned < 0 || capacity < 0 || !Double.isFinite(capacity))
            throw new IllegalArgumentException("Invalid service usage");
        update(
                "UPDATE jobs SET scanned=scanned+?,read_units=read_units+? WHERE id=?",
                scanned,
                capacity,
                id);
        var usage = usage(id);
        if (usage.get("scanned").longValue() > maxScanned
                || usage.get("readCapacity").doubleValue() > maxCapacity)
            throw new JobStopped(JobState.BUDGET_EXCEEDED);
    }

    public synchronized void recordRetry(String id) throws SQLException {
        update("UPDATE jobs SET retries=retries+1 WHERE id=?", id);
    }

    public synchronized void recordThrottle(String id) throws SQLException {
        update("UPDATE jobs SET throttles=throttles+1 WHERE id=?", id);
    }

    public synchronized Map<String, Number> usage(String id) throws SQLException {
        try (var s =
                        statement(
                                "SELECT scanned,read_units,retries,throttles FROM jobs WHERE id=?",
                                id);
                var r = s.executeQuery()) {
            if (!r.next()) throw new IllegalArgumentException("Unknown job");
            return Map.of(
                    "scanned",
                    r.getLong(1),
                    "readCapacity",
                    r.getDouble(2),
                    "retries",
                    r.getLong(3),
                    "throttles",
                    r.getLong(4));
        }
    }

    public synchronized void reserveCalls(String id, int maxCalls, int count) throws SQLException {
        if (count < 1) throw new IllegalArgumentException("Invalid call reservation");
        if (update(
                        "UPDATE jobs SET calls=calls+? WHERE id=? AND calls<=?",
                        count,
                        id,
                        maxCalls - count)
                != 1) throw new JobStopped(JobState.BUDGET_EXCEEDED);
    }

    public synchronized NamedEffect latestEffect(String id, long task, String prefix)
            throws SQLException {
        if (!prefix.matches("[a-z0-9/-]{1," + MAX_EFFECT_PREFIX_CHARS + "}"))
            throw new IllegalArgumentException("Invalid effect prefix");
        try (var s =
                        statement(
                                "SELECT step,state,result FROM effects WHERE job=? AND task=? AND step LIKE ? ORDER BY step DESC LIMIT 1",
                                id,
                                task,
                                prefix + "%");
                var r = s.executeQuery()) {
            return r.next()
                    ? new NamedEffect(r.getString(1), r.getString(2), r.getString(3))
                    : null;
        }
    }

    public synchronized List<Map<String, Object>> planPage(String id, long after)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var s =
                        statement(
                                "SELECT seq,record_key,payload,state,outcome FROM tasks WHERE job=? AND seq>? ORDER BY seq LIMIT ?",
                                id,
                                after,
                                PLAN_PAGE_SIZE);
                var r = s.executeQuery()) {
            while (r.next())
                rows.add(
                        Map.of(
                                "sequence",
                                r.getLong(1),
                                "key",
                                r.getString(2),
                                "payload",
                                r.getString(3),
                                "state",
                                r.getString(4),
                                "outcome",
                                r.getString(5)));
        }
        return rows;
    }

    public synchronized Map<String, Long> effectCounts(String id) throws SQLException {
        Map<String, Long> result = new TreeMap<>();
        try (var s =
                        statement(
                                "SELECT state,count(*) FROM effects WHERE job=? GROUP BY state",
                                id);
                var r = s.executeQuery()) {
            while (r.next()) result.put(r.getString(1), r.getLong(2));
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized Map<String, Long> outcomeCounts(String id) throws SQLException {
        Map<String, Long> result = new TreeMap<>();
        try (var s =
                        statement(
                                "SELECT CASE WHEN outcome='' THEN state ELSE outcome END,count(*) FROM tasks WHERE job=? GROUP BY CASE WHEN outcome='' THEN state ELSE outcome END",
                                id);
                var r = s.executeQuery()) {
            while (r.next()) result.put(r.getString(1), r.getLong(2));
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized Map<String, Long> totals() throws SQLException {
        try (var s =
                        statement(
                                "SELECT count(*),coalesce(sum(calls),0),coalesce(sum(elapsed),0) FROM jobs");
                var r = s.executeQuery()) {
            r.next();
            return Map.of(
                    "jobs", r.getLong(1), "calls", r.getLong(2), "elapsedMillis", r.getLong(3));
        }
    }

    public synchronized long priorDelivery(String id, String step) throws SQLException {
        try (var s =
                        statement(
                                "SELECT task FROM effects WHERE job=? AND step=? ORDER BY task LIMIT 1",
                                id,
                                step);
                var r = s.executeQuery()) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    public synchronized void elapsed(String id, long millis) throws SQLException {
        update("UPDATE jobs SET elapsed=elapsed+? WHERE id=?", millis, id);
    }

    public synchronized void rate(String id, int rate) throws SQLException {
        transaction(
                () -> {
                    update("UPDATE jobs SET rate=? WHERE id=?", rate, id);
                    audit(id, "RATE", Integer.toString(rate));
                });
    }

    public synchronized void audit(String id, String event, String detail) throws SQLException {
        update(
                "INSERT INTO audit(job,time,event,detail) VALUES(?,?,?,?)",
                id,
                now(),
                event,
                detail);
    }

    /** Fetch windows release the writer before potentially slow report consumers. */
    public void report(String id, Consumer<ReportRow> consumer) throws SQLException {
        long after = 0;
        while (true) {
            List<ReportRow> rows = new ArrayList<>();
            synchronized (this) {
                try (var s =
                                statement(
                                        "SELECT seq,record_key,state,outcome FROM tasks WHERE job=? AND seq>? ORDER BY seq LIMIT ?",
                                        id,
                                        after,
                                        REPORT_WINDOW_SIZE);
                        var r = s.executeQuery()) {
                    while (r.next())
                        rows.add(
                                new ReportRow(
                                        r.getLong(1),
                                        r.getString(2),
                                        r.getString(3),
                                        r.getString(4)));
                }
            }
            if (rows.isEmpty()) return;
            for (var row : rows) {
                consumer.accept(row);
                after = row.sequence();
            }
        }
    }

    public synchronized List<ReportRow> errorPage(String id, long after) throws SQLException {
        List<ReportRow> rows = new ArrayList<>();
        try (var s =
                        statement(
                                "SELECT seq,record_key,state,outcome FROM tasks WHERE job=? AND seq>? AND outcome IN ('FAILED','FUNCTION_ERROR','BUSINESS_REJECTED','ERROR','CONFLICT') ORDER BY seq LIMIT ?",
                                id,
                                after,
                                PLAN_PAGE_SIZE);
                var r = s.executeQuery()) {
            while (r.next())
                rows.add(
                        new ReportRow(
                                r.getLong(1), r.getString(2), r.getString(3), r.getString(4)));
        }
        return List.copyOf(rows);
    }

    public synchronized List<Map<String, Object>> auditPage(String id, long after)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var s =
                        statement(
                                "SELECT seq,time,event,detail FROM audit WHERE job=? AND seq>? ORDER BY seq LIMIT ?",
                                id,
                                after,
                                REPORT_WINDOW_SIZE);
                var r = s.executeQuery()) {
            while (r.next())
                rows.add(
                        Map.of(
                                "sequence",
                                r.getLong(1),
                                "time",
                                r.getLong(2),
                                "event",
                                r.getString(3),
                                "detail",
                                r.getString(4)));
        }
        return rows;
    }

    public synchronized void expirePayloads(long cutoff) throws SQLException {
        update(
                "UPDATE tasks SET payload='{}' WHERE job IN (SELECT id FROM jobs WHERE created<? AND state IN ('DRY_RUN_COMPLETE','COMPLETED','CANCELLED','FAILED'))",
                cutoff);
        update(
                "UPDATE effects SET result='' WHERE job IN (SELECT id FROM jobs WHERE created<? AND state IN ('DRY_RUN_COMPLETE','COMPLETED','CANCELLED','FAILED'))",
                cutoff);
    }

    public long freeBytes() throws IOException {
        return Files.getFileStore(directory).getUsableSpace();
    }

    private PreparedStatement statement(String sql, Object... args) throws SQLException {
        var s = db.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
        return s;
    }

    private int update(String sql, Object... args) throws SQLException {
        try (var s = statement(sql, args)) {
            return s.executeUpdate();
        }
    }

    private void transaction(SqlAction action) throws SQLException {
        db.setAutoCommit(false);
        try {
            action.run();
            db.commit();
        } catch (SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    static void hashField(MessageDigest digest, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public static long now() {
        return Instant.now().getEpochSecond();
    }

    @Override
    public synchronized void close() throws SQLException {
        db.close();
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    public record Job(
            String id,
            String request,
            String version,
            String identity,
            JobState state,
            String hash,
            long created,
            long finished,
            long approvedUntil,
            boolean promoted,
            int calls,
            int retries,
            int throttles,
            long elapsedMillis,
            int rate,
            boolean planned) {}

    public record Task(long sequence, String key, String payload) {}

    public record Cursor(String value, boolean complete) {}

    public record Effect(String state, String result) {}

    public record NamedEffect(String step, String state, String result) {}

    public record ReportRow(long sequence, String key, String state, String outcome) {}
}
