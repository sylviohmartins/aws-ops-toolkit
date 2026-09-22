package io.github.awsopstoolkit.configuration;

import io.github.awsopstoolkit.report.ReportLimits;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.report", ignoreUnknownFields = false)
public record ReportProperties(
        @DefaultValue(",") @Size(min = 1, max = 1) String csvDelimiter,
        @DefaultValue("500") @Min(1) @Max(100000) int flushEveryRecords,
        @DefaultValue("1") @Min(1) @Max(8) int maxConcurrentReports,
        @DefaultValue("100") @Min(1) @Max(10000) int xlsxRowWindow,
        @DefaultValue("1000000") @Min(1) @Max(ReportLimits.XLSX_MAX_DATA_ROWS) int xlsxMaxDataRows,
        @DefaultValue("1024") @Min(128) long xlsxEstimatedBytesPerRow,
        @DefaultValue("true") boolean protectSpreadsheetFormulas) {}
