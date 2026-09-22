package io.github.awsopstoolkit.aws;

import io.github.awsopstoolkit.configuration.S3Properties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/**
 * Bounded streaming examples. The operation owns local artifact policy and multipart orchestration.
 */
public final class S3Service {
    private final S3Client client;
    private final AwsCallGate gate;
    private final WriteAuthorization authorization;
    private final int streamBufferBytes;

    public S3Service(
            S3Client client,
            AwsCallGate gate,
            WriteAuthorization authorization,
            S3Properties properties) {
        this.client = Objects.requireNonNull(client);
        this.gate = Objects.requireNonNull(gate);
        this.authorization = Objects.requireNonNull(authorization);
        this.streamBufferBytes = Objects.requireNonNull(properties).streamBufferBytes();
    }

    public HeadObjectResponse head(HeadObjectRequest request) throws InterruptedException {
        return gate.call(() -> client.headObject(request));
    }

    /**
     * Streams with a configured bounded buffer, a byte budget and cancellation; never materializes
     * the object.
     */
    public GetObjectResponse download(
            GetObjectRequest request, OutputStream target, long maxBytes, BooleanSupplier cancelled)
            throws InterruptedException, IOException {
        if (maxBytes < 1)
            throw new IllegalArgumentException("A positive download budget is required");
        // The gate permit covers the entire stream, not only response headers.
        try {
            return gate.call(
                    () -> {
                        try (ResponseInputStream<GetObjectResponse> stream =
                                client.getObject(request)) {
                            try {
                                Long declared = stream.response().contentLength();
                                if (declared != null && declared > maxBytes) {
                                    throw new IOException("Object exceeds download budget");
                                }
                                byte[] buffer = new byte[streamBufferBytes];
                                long copied = 0;
                                while (true) {
                                    if (cancelled.getAsBoolean()
                                            || Thread.currentThread().isInterrupted()) {
                                        throw new CancellationException("Download cancelled");
                                    }
                                    int length = stream.read(buffer);
                                    if (length < 0) break;
                                    if (length > maxBytes - copied)
                                        throw new IOException("Object exceeds download budget");
                                    target.write(buffer, 0, length);
                                    copied += length;
                                }
                                return stream.response();
                            } catch (IOException | RuntimeException failure) {
                                stream.abort();
                                throw failure;
                            }
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    });
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /**
     * Caller owns the stream; exact length and a replayable body are necessary for safe retries.
     */
    public PutObjectResponse upload(PutObjectRequest request, InputStream source, long exactLength)
            throws InterruptedException {
        if (exactLength < 0) throw new IllegalArgumentException("Exact content length is required");
        return gate.call(
                () -> {
                    authorization.requirePermission(
                            "s3:PutObject", resource(request.bucket(), request.key()));
                    return client.putObject(
                            request, RequestBody.fromInputStream(source, exactLength));
                });
    }

    public ListObjectsV2Response listPage(ListObjectsV2Request request)
            throws InterruptedException {
        if (request.maxKeys() == null
                || request.maxKeys() < 1
                || request.maxKeys() > S3Limits.MAX_LIST_KEYS) {
            throw new IllegalArgumentException(
                    "An explicit list page size from 1 to "
                            + S3Limits.MAX_LIST_KEYS
                            + " is required");
        }
        return gate.call(() -> client.listObjectsV2(request));
    }

    public DeleteObjectResponse delete(DeleteObjectRequest request) throws InterruptedException {
        if (Boolean.TRUE.equals(request.bypassGovernanceRetention())) {
            throw new IllegalArgumentException("Governance retention bypass is not supported");
        }
        return gate.call(
                () -> {
                    authorization.requirePermission(
                            "s3:DeleteObject", resource(request.bucket(), request.key()));
                    if (request.versionId() != null) {
                        authorization.requirePermission(
                                "s3:DeleteObjectVersion",
                                resource(request.bucket(), request.key()));
                    }
                    return client.deleteObject(request);
                });
    }

    private static String resource(String bucket, String key) {
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("Explicit bucket and key are required");
        }
        return "s3://" + bucket + "/" + key;
    }
}
