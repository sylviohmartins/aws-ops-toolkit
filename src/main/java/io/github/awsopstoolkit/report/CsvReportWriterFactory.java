package io.github.awsopstoolkit.report;

import io.github.awsopstoolkit.configuration.ReportProperties;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class CsvReportWriterFactory {
    private final ReportProperties properties;

    public CsvReportWriterFactory(ReportProperties properties) {
        this.properties = properties;
    }

    public <T> CsvReportWriter<T> create(List<CsvColumn<T>> columns) {
        return new CsvReportWriter<>(columns, properties);
    }
}
