package io.github.awsopstoolkit.report;

public record OperationReportRow(long sequence, String record, String state, String outcome) {}
