package io.github.awsopstoolkit.report;

import static org.junit.jupiter.api.Assertions.*;

import io.github.awsopstoolkit.configuration.ReportProperties;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReportWriterTest {
    private final ReportProperties properties =
            new ReportProperties(",", 2, 1, 100, 1_000_000, 1024, true);

    @Test
    void delegatesEscapingAndNullHandlingToCommonsCsv() throws Exception {
        var writer =
                new CsvReportWriter<>(
                        List.of(
                                new CsvColumn<Row>("name", Row::name),
                                new CsvColumn<Row>("value", Row::value)),
                        properties);
        var target = new StringWriter();

        var result =
                writer.write(
                        List.of(new Row("a,\"b\"\n", null), new Row("x", "=1+1")).iterator(),
                        target,
                        true);

        assertEquals(2, result.rows());
        String csv = target.toString();
        assertTrue(csv.contains("\"a,\"\"b\"\"\n\""));
        assertTrue(csv.contains("\"'=1+1\""));
        assertTrue(csv.startsWith("\"name\",\"value\""));
    }

    private record Row(String name, String value) {}
}
