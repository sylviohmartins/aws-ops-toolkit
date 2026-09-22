package io.github.awsopstoolkit.operation;

import io.github.awsopstoolkit.report.CsvColumn;
import io.github.awsopstoolkit.report.CsvReportWriterFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.StringWriter;
import java.util.List;
import org.springframework.stereotype.Component;

/** Deterministic, local-only example. It never creates AWS clients or writes business data. */
@Component
public final class SyntheticInventoryOperation
        implements OperationDefinition<SyntheticInventoryOperation.Input> {
    private static final List<CsvColumn<SyntheticRow>> COLUMNS =
            List.of(
                    new CsvColumn<>("record", SyntheticRow::record),
                    new CsvColumn<>("state", SyntheticRow::state));

    private final CsvReportWriterFactory csvWriters;

    public SyntheticInventoryOperation(CsvReportWriterFactory csvWriters) {
        this.csvWriters = csvWriters;
    }

    public record Input(
            @Min(1) @Max(SyntheticOperationLimits.MAX_RECORDS) long records,
            @Min(0) @Max(SyntheticOperationLimits.MAX_DELAY_MILLIS) int delayMillis) {}

    private record SyntheticRow(String record, String state) {}

    @Override
    public String type() {
        return "synthetic-inventory";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public long total(Input input) {
        return input.records();
    }

    @Override
    public void execute(Input input, OperationContext context) throws Exception {
        long cursor = context.startCursor();
        var writer = csvWriters.create(COLUMNS);
        while (cursor < input.records() && !context.stopRequested().getAsBoolean()) {
            long end = Math.min(cursor + context.pageSize(), input.records());
            var csv =
                    new StringWriter(
                            context.pageSize()
                                    * SyntheticOperationLimits.ESTIMATED_CSV_CHARS_PER_RECORD);
            var session = writer.open(csv, false);
            for (long index = cursor; index < end; index++) {
                session.write(
                        new SyntheticRow(
                                "synthetic-" + index, index % 10 == 0 ? "CANDIDATE" : "SKIPPED"));
            }
            session.finish();
            if (input.delayMillis() > 0) Thread.sleep(input.delayMillis());
            context.commit().accept(end, csv.toString());
            cursor = end;
        }
    }
}
