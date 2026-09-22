package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.configuration.ReportProperties;
import io.github.awsopstoolkit.configuration.ToolkitProperties;
import io.github.awsopstoolkit.report.*;
import io.github.awsopstoolkit.security.Masking;
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
@ConditionalOnProperty(name = "toolkit.operations.enabled", havingValue = "true")
public final class JobController {
    private final JobCoordinator coordinator;
    private final SqliteJournal journal;
    private final ReportProperties reportProperties;
    private final CsvReportWriterFactory csvWriters;
    private final java.util.concurrent.Semaphore reportSlots;
    private final long minimumFreeBytes;

    public JobController(
            JobCoordinator coordinator,
            SqliteJournal journal,
            ToolkitProperties toolkit,
            ReportProperties reportProperties,
            CsvReportWriterFactory csvWriters) {
        this.coordinator = coordinator;
        this.journal = journal;
        this.reportProperties = reportProperties;
        this.csvWriters = csvWriters;
        this.reportSlots =
                new java.util.concurrent.Semaphore(reportProperties.maxConcurrentReports(), true);
        this.minimumFreeBytes = toolkit.minimumFreeBytes();
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
                OperationReportSchema.names(),
                "compressedCsv",
                true);
    }

    @GetMapping("/{id}/report.csv")
    public ResponseEntity<StreamingResponseBody> csv(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "sequence,record,state,outcome") String columns)
            throws Exception {
        coordinator.status(id.toString());
        var selected = OperationReportSchema.select(columns);
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
        var selected = OperationReportSchema.select(columns);
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
        var selected = OperationReportSchema.select(columns);
        long records = journal.count(id.toString(), null);
        if (records > reportProperties.xlsxMaxDataRows())
            throw new IllegalArgumentException("Use streaming CSV above one million rows");
        checkReportDisk(
                Math.addExact(
                        minimumFreeBytes,
                        Math.multiplyExact(records, reportProperties.xlsxEstimatedBytesPerRow())));
        return ResponseEntity.ok()
                .contentType(
                        MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".xlsx\"")
                .body(
                        out -> {
                            if (!reportSlots.tryAcquire())
                                throw new IOException("Another XLSX projection is active");
                            try (var workbook =
                                    new SXSSFWorkbook(reportProperties.xlsxRowWindow())) {
                                workbook.setCompressTempFiles(true);
                                var sheet = workbook.createSheet("Results");
                                var header = sheet.createRow(0);
                                for (int i = 0; i < selected.size(); i++)
                                    header.createCell(i).setCellValue(selected.get(i).header());
                                var index = new java.util.concurrent.atomic.AtomicInteger(1);
                                journal.report(
                                        id.toString(),
                                        result -> {
                                            if (index.get() % reportProperties.flushEveryRecords()
                                                    == 0)
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

    private void writeCsv(
            String id, BufferedWriter writer, List<CsvColumn<OperationReportRow>> columns)
            throws IOException {
        var csv = csvWriters.create(columns);
        var session = csv.open(writer, true);
        try {
            journal.report(
                    id,
                    row -> {
                        try {
                            session.write(toReportRow(row));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            session.finish();
        } catch (java.sql.SQLException e) {
            throw new IOException("Report unavailable", e);
        }
    }

    static List<String> reportColumns(String raw) {
        return OperationReportSchema.select(raw).stream().map(CsvColumn::header).toList();
    }

    private static String reportValue(
            SqliteJournal.ReportRow row, CsvColumn<OperationReportRow> column) {
        Object value = column.value(toReportRow(row));
        return value == null ? "" : String.valueOf(value);
    }

    private static OperationReportRow toReportRow(SqliteJournal.ReportRow row) {
        return new OperationReportRow(row.sequence(), mask(row.key()), row.state(), row.outcome());
    }

    private static void checkReportDisk(long required) throws IOException {
        var temporary = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        if (java.nio.file.Files.getFileStore(temporary).getUsableSpace() < required)
            throw new IOException("Insufficient temporary report disk");
    }

    static String mask(String key) {
        return Masking.stableIdentifier(key);
    }

    public record Approval(String hash, String reason, boolean promote) {}

    public record Tuning(Integer requestsPerSecond, Integer maxConcurrency) {}

    public record Reconciliation(
            long task, String step, boolean succeeded, String evidence, String result) {}
}
