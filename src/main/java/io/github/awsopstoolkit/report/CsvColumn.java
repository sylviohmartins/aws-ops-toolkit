package io.github.awsopstoolkit.report;

import java.util.Objects;
import java.util.function.Function;

public record CsvColumn<T>(String header, Function<T, ?> valueExtractor) {
    public CsvColumn {
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException("CSV header cannot be blank");
        }
        Objects.requireNonNull(valueExtractor, "valueExtractor");
    }

    public Object value(T row) {
        return valueExtractor.apply(row);
    }
}
