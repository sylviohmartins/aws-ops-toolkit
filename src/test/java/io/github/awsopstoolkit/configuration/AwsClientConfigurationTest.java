package io.github.awsopstoolkit.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AwsClientConfigurationTest {

    @Test
    void sdkRetryStrategyUsesExactlyOnePhysicalAttempt() {
        var properties =
                new AwsProperties(
                        true,
                        "us-east-1",
                        "test-profile",
                        "123456789012",
                        8,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(25),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(35));

        var overrides = new AwsClientConfiguration().awsOverrides(properties);

        assertEquals(1, overrides.retryStrategy().orElseThrow().maxAttempts());
    }
}
