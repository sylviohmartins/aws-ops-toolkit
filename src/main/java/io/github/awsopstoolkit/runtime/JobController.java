package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.report.Csv;
import jakarta.validation.Valid;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/jobs")
@ConditionalOnProperty(name = "operations.enabled", havingValue = "true")
public final class JobController {
    private final JobCoordinator coordinator;
    private final SqliteJournal journal;
    private final java.util.concurrent.Semaphore reportSlots =
            new java.util.concurrent.Semaphore(1);
    private final long minimumFreeBytes;

    public JobController(JobCoordinator coordinator, SqliteJournal journal) {
        this(coordinator, journal, 1048576L);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JobController(
            JobCoordinator coordinator,
            SqliteJournal journal,
            io.github.awsopstoolkit.configuration.ToolkitProperties properties) {
        this(coordinator, journal, properties.minimumFreeBytes());
    }

    private JobController(
            JobCoordinator coordinator, SqliteJournal journal, long minimumFreeBytes) {
        this.coordinator = coordinator;
        this.journal = journal;
        this.minimumFreeBytes = minimumFreeBytes;
    }

    @GetMapping("/types")
    public Set<String> types() {
        return coordinator.operations();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> create(@Valid @RequestBody JobRequest request) throws Exception {
        var job = coordinator.create(request);
        return coordinator.statusView(job.id());
    }

    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable UUID id) throws Exception {
        return coordinator.statusView(id.toString());
    }

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> approve(@PathVariable UUID id, @RequestBody Approval approval)
            throws Exception {
        coordinator.approve(id.toString(), approval.hash(), approval.reason(), approval.promote());
        return coordinator.statusView(id.toString());
    }

    @PostMapping("/{id}/resume-plan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> resumePlan(@PathVariable UUID id) throws Exception {
        coordinator.resumePlanning(id.toString());
        return coordinator.statusView(id.toString());
    }

    @PostMapping("/{id}/pause")
    public Map<String, Object> pause(@PathVariable UUID id) throws Exception {
        coordinator.stop(id.toString(), false);
        return coordinator.statusView(id.toString());
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id) throws Exception {
        coordinator.stop(id.toString(), true);
        return coordinator.statusView(id.toString());
    }

    @PostMapping("/{id}/tuning")
    public void tuning(@PathVariable UUID id, @RequestBody Tuning tuning) throws Exception {
        coordinator.tune(id.toString(), tuning.requestsPerSecond(), tuning.maxConcurrency());
    }

    @PostMapping("/{id}/reconcile")
    public void reconcile(@PathVariable UUID id, @RequestBody Reconciliation r) throws Exception {
        coordinator.reconcile(
                id.toString(), r.task(), r.step(), r.succeeded(), r.evidence(), r.result());
    }

    @GetMapping("/{id}/audit")
    public Object audit(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after)
            throws Exception {
        return journal.auditPage(id.toString(), after);
    }

    @GetMapping("/{id}/plan")
    public Object plan(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after)
            throws Exception {
        return coordinator.planPageView(id.toString(), after);
    }

    @GetMapping("/{id}/dry-run")
    public Object dryRun(@PathVariable UUID id) throws Exception {
        return coordinator.dryRunSummary(id.toString());
    }

    @GetMapping("/{id}/summary")
    public Object summary(@PathVariable UUID id) throws Exception {
        return coordinator.summary(id.toString());
    }

    @GetMapping("/{id}/errors")
    public Object errors(@PathVariable UUID id, @RequestParam(defaultValue = "0") long after)
            throws Exception {
        coordinator.status(id.toString());
        return journal.errorPage(id.toString(), after).stream()
                .map(
                        row ->
                                Map.of(
                                        "sequence", row.sequence(),
                                        "record", mask(row.key()),
                                        "state", row.state(),
                                        "outcome", row.outcome()))
                .toList();
    }

    @GetMapping("/{id}/manifest")
    public Object manifest(@PathVariable UUID id) throws Exception {
        return Map.of(
                "schemaVersion",
                5,
                "job",
                coordinator.statusView(id.toString()),
                "redaction",
                "sha256-prefix-16",
                "reportSource",
                "durable-task-outcomes",
                "availableReportColumns",
                List.of("sequence", "record", "state", "outcome"),
                "compressedCsv",
                true);
    }

    @GetMapping("/{id}/report.csv")
    public ResponseEntity<StreamingResponseBody> csv(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "sequence,record,state,outcome") String columns)
            throws Exception {
        coordinator.status(id.toString());
        var selected = reportColumns(columns);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".csv\"")
                .body(
                        out -> {
                            var writer =
                                    new BufferedWriter(
                                            new OutputStreamWriter(out, StandardCharsets.UTF_8));
                            writeCsv(id.toString(), writer, selected);
                        });
    }

    @GetMapping("/{id}/report.csv.gz")
    public ResponseEntity<StreamingResponseBody> csvGzip(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "sequence,record,state,outcome") String columns)
            throws Exception {
        coordinator.status(id.toString());
        var selected = reportColumns(columns);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/gzip"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + id + ".csv.gz\"")
                .body(
                        out -> {
                            try (var gzip = new java.util.zip.GZIPOutputStream(out)) {
                                var writer =
                                        new BufferedWriter(
                                                new OutputStreamWriter(
                                                        gzip, StandardCharsets.UTF_8));
                                writeCsv(id.toString(), writer, selected);
                                writer.flush();
                                gzip.finish();
                            }
                        });
    }

    @GetMapping("/{id}/report.xlsx")
    public ResponseEntity<StreamingResponseBody> xlsx(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "sequence,record,state,outcome") String columns)
            throws Exception {
        coordinator.status(id.toString());
        var selected = reportColumns(columns);
        long records = journal.count(id.toString(), null);
        if (records > 1_000_000)
            throw new IllegalArgumentException("Use streaming CSV above one million rows");
        checkReportDisk(Math.addExact(minimumFreeBytes, Math.multiplyExact(records, 1024L)));
        return ResponseEntity.ok()
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".xlsx\"")
                .body(
                        out -> {
                            if (!reportSlots.tryAcquire())
                                throw new IOException("Another XLSX projection is active");
                            try (var workbook = new SXSSFWorkbook(100)) {
                                workbook.setCompressTempFiles(true);
                                var sheet = workbook.createSheet("Results");
                                var header = sheet.createRow(0);
                                for (int i = 0; i < selected.size(); i++)
                                    header.createCell(i).setCellValue(selected.get(i));
                                var index = new java.util.concurrent.atomic.AtomicInteger(1);
                                journal.report(
                                        id.toString(),
                                        result -> {
                                            if (index.get() % 500 == 0)
                                                try {
                                                    checkReportDisk(minimumFreeBytes);
                                                } catch (IOException e) {
                                                    throw new UncheckedIOException(e);
                                                }
                                            var row = sheet.createRow(index.getAndIncrement());
                                            for (int column = 0; column < selected.size(); column++)
                                                row.createCell(column)
                                                        .setCellValue(
                                                                reportValue(
                                                                        result,
                                                                        selected.get(column)));
                                        });
                                workbook.write(out);
                            } catch (java.sql.SQLException e) {
                                throw new IOException("Report unavailable", e);
                            } finally {
                                reportSlots.release();
                            }
                        });
    }

    private void writeCsv(String id, BufferedWriter writer, List<String> columns)
            throws IOException {
        writer.write(String.join(",", columns));
        writer.write("\n");
        try {
            journal.report(
                    id,
                    row -> {
                        try {
                            for (int i = 0; i < columns.size(); i++) {
                                if (i > 0) writer.write(",");
                                writer.write(Csv.cell(reportValue(row, columns.get(i))));
                            }
                            writer.write("\n");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            writer.flush();
        } catch (java.sql.SQLException e) {
            throw new IOException("Report unavailable", e);
        }
    }

    static List<String> reportColumns(String raw) {
        var allowed = Set.of("sequence", "record", "state", "outcome");
        var selected = new ArrayList<String>();
        for (String token : raw.split(",")) {
            String column = token.strip();
            if (!allowed.contains(column) || selected.contains(column))
                throw new IllegalArgumentException("Invalid or duplicate report column");
            selected.add(column);
        }
        if (selected.isEmpty())
            throw new IllegalArgumentException("At least one report column is required");
        return List.copyOf(selected);
    }

    private static String reportValue(SqliteJournal.ReportRow row, String column) {
        return switch (column) {
            case "sequence" -> Long.toString(row.sequence());
            case "record" -> mask(row.key());
            case "state" -> row.state();
            case "outcome" -> row.outcome();
            default -> throw new IllegalArgumentException("Unknown report column");
        };
    }

    private static void checkReportDisk(long required) throws IOException {
        var temporary = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        if (java.nio.file.Files.getFileStore(temporary).getUsableSpace() < required)
            throw new IOException("Insufficient temporary report disk");
    }

    static String mask(String key) {
        return HexFormat.of()
                .formatHex(SqliteJournal.sha256().digest(key.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 16);
    }

    public record Approval(String hash, String reason, boolean promote) {}

    public record Tuning(Integer requestsPerSecond, Integer maxConcurrency) {}

    public record Reconciliation(
            long task, String step, boolean succeeded, String evidence, String result) {}
}
