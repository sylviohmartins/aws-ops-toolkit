package io.github.awsopstoolkit.report;

/** External format limits and structural safety constants shared by report components. */
public final class ReportLimits {
    public static final int XLSX_MAX_DATA_ROWS = 1_048_575;
    public static final String SPREADSHEET_FORMULA_PREFIXES = "=+-@";

    private ReportLimits() {}
}
