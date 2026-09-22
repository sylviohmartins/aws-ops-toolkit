package io.github.awsopstoolkit.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class OperationalPropertiesValidationTest {
    @Test
    void journalRejectsExcessiveBusyTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JournalProperties(Duration.ofMinutes(2), 100, 500));
    }

    @Test
    void s3RejectsUnsafeStreamingAndMultipartSizes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new S3Properties(DataSize.ofKilobytes(1), DataSize.ofMegabytes(8)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new S3Properties(DataSize.ofKilobytes(64), DataSize.ofMegabytes(1)));
    }

    @Test
    void sqsRejectsWaitOrLeaseConfigurationOutsideVisibilityWindow() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SqsProperties(
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(21),
                                3,
                                5,
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(20)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SqsProperties(
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(1),
                                3,
                                5,
                                Duration.ofSeconds(31),
                                Duration.ofSeconds(20)));
    }

    @Test
    void sqsRejectsInvalidPoisonReceiveThreshold() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SqsProperties(
                                Duration.ofSeconds(120),
                                Duration.ofSeconds(1),
                                3,
                                0,
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(40)));
    }

    @Test
    void representativeOperationalConfigurationIsAccepted() {
        assertDoesNotThrow(() -> new JournalProperties(Duration.ofSeconds(5), 100, 500));
        assertDoesNotThrow(
                () -> new S3Properties(DataSize.ofKilobytes(64), DataSize.ofMegabytes(8)));
        assertDoesNotThrow(
                () ->
                        new SqsProperties(
                                Duration.ofSeconds(120),
                                Duration.ofSeconds(1),
                                3,
                                5,
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(40)));
    }
}
