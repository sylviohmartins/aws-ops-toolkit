package io.github.awsopstoolkit.report;

import java.io.IOException;
import java.io.Writer;
import java.util.Iterator;

public interface ReportWriter<T> {
    ReportResult write(Iterator<T> rows, Writer target, boolean includeHeader) throws IOException;
}
