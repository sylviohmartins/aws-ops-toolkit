package io.github.awsopstoolkit.operation;

import io.github.awsopstoolkit.report.Csv;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.stereotype.Component;

/** Deterministic, local-only example. It never creates AWS clients or writes business data. */
@Component
public final class SyntheticInventoryOperation
        implements OperationDefinition<SyntheticInventoryOperation.Input> {
    public record Input(@Min(1) @Max(10000000) long records, @Min(0) @Max(100) int delayMillis) {}

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
        while (cursor < input.records() && !context.stopRequested().getAsBoolean()) {
            long end = Math.min(cursor + context.pageSize(), input.records());
            var csv = new StringBuilder(context.pageSize() * 40);
            for (long index = cursor; index < end; index++) {
                csv.append(Csv.cell("synthetic-" + index))
                        .append(',')
                        .append(Csv.cell(index % 10 == 0 ? "CANDIDATE" : "SKIPPED"))
                        .append("\r\n");
            }
            if (input.delayMillis() > 0) Thread.sleep(input.delayMillis());
            context.commit().accept(end, csv.toString());
            cursor = end;
        }
    }
}
