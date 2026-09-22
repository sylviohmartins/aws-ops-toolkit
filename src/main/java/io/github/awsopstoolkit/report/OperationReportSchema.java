package io.github.awsopstoolkit.report;

import java.util.*;

public final class OperationReportSchema {
    private static final Map<String, CsvColumn<OperationReportRow>> COLUMNS =
            Arrays.stream(Column.values())
                    .collect(
                            java.util.stream.Collectors.toUnmodifiableMap(
                                    Column::externalName, Column::column));

    private OperationReportSchema() {}

    public static List<String> names() {
        return Arrays.stream(Column.values()).map(Column::externalName).toList();
    }

    public static List<CsvColumn<OperationReportRow>> select(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("At least one report column is required");
        }
        var result = new ArrayList<CsvColumn<OperationReportRow>>();
        var seen = new HashSet<String>();
        for (String token : raw.split(",")) {
            String name = token.strip();
            CsvColumn<OperationReportRow> column = COLUMNS.get(name);
            if (column == null || !seen.add(name)) {
                throw new IllegalArgumentException("Invalid or duplicate report column");
            }
            result.add(column);
        }
        return List.copyOf(result);
    }

    private enum Column {
        SEQUENCE("sequence", new CsvColumn<>("sequence", OperationReportRow::sequence)),
        RECORD("record", new CsvColumn<>("record", OperationReportRow::record)),
        STATE("state", new CsvColumn<>("state", OperationReportRow::state)),
        OUTCOME("outcome", new CsvColumn<>("outcome", OperationReportRow::outcome));

        private final String externalName;
        private final CsvColumn<OperationReportRow> column;

        Column(String externalName, CsvColumn<OperationReportRow> column) {
            this.externalName = externalName;
            this.column = column;
        }

        String externalName() {
            return externalName;
        }

        CsvColumn<OperationReportRow> column() {
            return column;
        }
    }
}
