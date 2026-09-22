package io.github.awsopstoolkit.configuration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(value = "toolkit.s3", ignoreUnknownFields = false)
public record S3Properties(
        @NotNull @DefaultValue("64KB") DataSize streamBufferSize,
        @NotNull @DefaultValue("8MB") DataSize multipartPartSize) {
    private static final DataSize MIN_STREAM_BUFFER = DataSize.ofKilobytes(4);
    private static final DataSize MAX_STREAM_BUFFER = DataSize.ofMegabytes(8);
    private static final DataSize MIN_MULTIPART_PART = DataSize.ofMegabytes(5);
    private static final DataSize MAX_MULTIPART_PART = DataSize.ofGigabytes(5);

    public S3Properties {
        requireWithin(streamBufferSize, MIN_STREAM_BUFFER, MAX_STREAM_BUFFER, "stream-buffer-size");
        requireWithin(
                multipartPartSize, MIN_MULTIPART_PART, MAX_MULTIPART_PART, "multipart-part-size");
    }

    private static void requireWithin(DataSize value, DataSize min, DataSize max, String name) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
    }

    public int streamBufferBytes() {
        return Math.toIntExact(streamBufferSize.toBytes());
    }

    public long multipartPartBytes() {
        return multipartPartSize.toBytes();
    }
}
