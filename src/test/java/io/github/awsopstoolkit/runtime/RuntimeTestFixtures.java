package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.configuration.*;
import io.github.awsopstoolkit.report.CsvReportWriterFactory;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.util.unit.DataSize;

final class RuntimeTestFixtures {
    private RuntimeTestFixtures() {}

    static RuntimeProperties runtime(
            boolean writes,
            URI labEndpoint,
            Set<String> resources,
            Set<String> principals,
            int requestsPerSecond,
            int workers,
            int pageSize) {
        return new RuntimeProperties(
                true,
                writes,
                labEndpoint,
                resources,
                principals,
                requestsPerSecond,
                workers,
                pageSize,
                Duration.ofHours(24),
                Duration.ofMinutes(15),
                Duration.ofDays(30),
                10,
                Duration.ofSeconds(25),
                DataSize.ofKilobytes(8),
                20,
                5,
                Duration.ofSeconds(10),
                Duration.ofMillis(100),
                3);
    }

    static ToolkitProperties toolkit(
            ToolkitProperties.Environment environment, Path path, boolean writeEnabled) {
        return new ToolkitProperties(
                environment,
                path,
                DataSize.ofMegabytes(1),
                1,
                100,
                writeEnabled,
                "local-test-token-not-for-real-use-123");
    }

    static AwsProperties aws(String expectedAccount) {
        return new AwsProperties(
                false,
                "us-east-1",
                "",
                expectedAccount,
                2,
                Duration.ofSeconds(3),
                Duration.ofSeconds(2),
                Duration.ofSeconds(25),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(35));
    }

    static HttpProperties http(URI endpoint, Set<String> hosts) {
        return new HttpProperties(
                endpoint,
                hosts,
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                3,
                Duration.ofSeconds(10),
                DataSize.ofKilobytes(64));
    }

    static HttpProperties http(URI endpoint, Set<String> hosts, Duration responseTimeout) {
        return new HttpProperties(
                endpoint,
                hosts,
                Duration.ofSeconds(3),
                responseTimeout,
                3,
                Duration.ofSeconds(10),
                DataSize.ofKilobytes(64));
    }

    static DispatchLimiter limiter(int workers, int requestsPerSecond) {
        return new DispatchLimiter(
                workers, requestsPerSecond, 20, 5, Duration.ofSeconds(10), Duration.ofMillis(100));
    }

    static SqsProperties sqs() {
        return new SqsProperties(Duration.ofSeconds(120));
    }

    static ReportProperties report() {
        return new ReportProperties(",", 500, 1, 100, 1_000_000, 1024, true);
    }

    static CsvReportWriterFactory csvFactory() {
        return new CsvReportWriterFactory(report());
    }
}
