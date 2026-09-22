package io.github.awsopstoolkit.configuration;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.sqs", ignoreUnknownFields = false)
public record SqsProperties(@NotNull @DefaultValue("120s") Duration visibilityTimeout) {
    private static final Duration MAX_VISIBILITY_TIMEOUT = Duration.ofHours(12);

    public SqsProperties {
        if (visibilityTimeout == null
                || visibilityTimeout.isZero()
                || visibilityTimeout.isNegative()
                || visibilityTimeout.compareTo(MAX_VISIBILITY_TIMEOUT) > 0) {
            throw new IllegalArgumentException("visibility-timeout must be within (0, 12h]");
        }
    }

    public int visibilityTimeoutSeconds() {
        return Math.toIntExact(visibilityTimeout.toSeconds());
    }
}
