package io.github.awsopstoolkit.report;

import io.github.awsopstoolkit.configuration.ReportProperties;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;

public final class CsvReportWriter<T> implements ReportWriter<T> {
    private final List<CsvColumn<T>> columns;
    private final ReportProperties properties;
    private final CSVFormat format;

    public CsvReportWriter(List<CsvColumn<T>> columns, ReportProperties properties) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("At least one CSV column is required");
        }
        this.columns = List.copyOf(columns);
        this.properties = Objects.requireNonNull(properties);
        this.format =
                CSVFormat.DEFAULT
                        .builder()
                        .setDelimiter(properties.csvDelimiter().charAt(0))
                        .setRecordSeparator("\r\n")
                        .setQuoteMode(QuoteMode.ALL)
                        .get();
    }

    @Override
    public ReportResult write(Iterator<T> rows, Writer target, boolean includeHeader)
            throws IOException {
        Objects.requireNonNull(rows, "rows");
        Session session = open(target, includeHeader);
        while (rows.hasNext()) session.write(rows.next());
        return session.finish();
    }

    public Session open(Writer target, boolean includeHeader) throws IOException {
        Objects.requireNonNull(target, "target");
        var printer = new CSVPrinter(target, format);
        if (includeHeader) {
            printer.printRecord(columns.stream().map(CsvColumn::header).toList());
        }
        return new Session(printer);
    }

    public final class Session {
        private final CSVPrinter printer;
        private long count;
        private boolean finished;

        private Session(CSVPrinter printer) {
            this.printer = printer;
        }

        public void write(T row) throws IOException {
            if (finished) throw new IllegalStateException("CSV session already finished");
            for (CsvColumn<T> column : columns) printer.print(safe(column.value(row)));
            printer.println();
            count++;
            if (count % properties.flushEveryRecords() == 0) printer.flush();
        }

        public ReportResult finish() throws IOException {
            if (!finished) {
                printer.flush();
                finished = true;
            }
            return new ReportResult(count);
        }
    }

    private Object safe(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value);
        if (!properties.protectSpreadsheetFormulas()) return text;
        String trimmed = text.stripLeading();
        if ((!trimmed.isEmpty()
                        && ReportLimits.SPREADSHEET_FORMULA_PREFIXES.indexOf(trimmed.charAt(0))
                                >= 0)
                || text.startsWith("\t")
                || text.startsWith("\r")
                || text.startsWith("\n")) {
            return "'" + text;
        }
        return text;
    }
}
