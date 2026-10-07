package io.github.awsopstoolkit.runtime;

import java.util.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Durable multipart copy. Each accepted part and upload ID can be reused after restart. */
public final class MultipartCopy {
    private static final int MAX_MULTIPART_PARTS = 10_000;

    private MultipartCopy() {}

    public static String copy(
            JobContext c,
            SqliteJournal.Task task,
            S3Client s3,
            String source,
            String sourceKey,
            String sourceVersion,
            String target,
            String targetKey,
            long bytes,
            long minimumPartSizeBytes)
            throws Exception {
        if (minimumPartSizeBytes < 1) {
            throw new IllegalArgumentException("minimumPartSizeBytes must be positive");
        }
        String marker = c.id() + ":" + task.sequence();
        String upload =
                c.effect(
                        task,
                        "multipart-create",
                        target,
                        () ->
                                s3.createMultipartUpload(
                                                b ->
                                                        b.bucket(target)
                                                                .key(targetKey)
                                                                .metadata(
                                                                        Map.of(
                                                                                "operation-marker",
                                                                                marker)))
                                        .uploadId(),
                        Optional::empty);
        long partSize =
                Math.max(minimumPartSizeBytes, Math.ceilDiv(bytes, (long) MAX_MULTIPART_PARTS));
        List<CompletedPart> parts = new ArrayList<>();
        for (long offset = 0; offset < bytes; offset += partSize) {
            c.checkpoint();
            int number = parts.size() + 1;
            String range = "bytes=" + offset + "-" + Math.min(bytes - 1, offset + partSize - 1);
            String etag =
                    c.effect(
                            task,
                            "multipart-part-" + number,
                            target,
                            () ->
                                    s3.uploadPartCopy(
                                                    b ->
                                                            b.sourceBucket(source)
                                                                    .sourceKey(sourceKey)
                                                                    .sourceVersionId(sourceVersion)
                                                                    .destinationBucket(target)
                                                                    .destinationKey(targetKey)
                                                                    .uploadId(upload)
                                                                    .partNumber(number)
                                                                    .copySourceRange(range))
                                            .copyPartResult()
                                            .eTag(),
                            () -> {
                                var listed =
                                        c.read(
                                                target,
                                                () ->
                                                        s3.listParts(
                                                                b ->
                                                                        b.bucket(target)
                                                                                .key(targetKey)
                                                                                .uploadId(upload)
                                                                                .partNumberMarker(
                                                                                        number - 1)
                                                                                .maxParts(1)));
                                return listed.parts().stream()
                                        .filter(p -> p.partNumber() == number)
                                        .map(Part::eTag)
                                        .findFirst();
                            });
            parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
        }
        return c.effect(
                task,
                "multipart-complete",
                target,
                () ->
                        s3.completeMultipartUpload(
                                        b ->
                                                b.bucket(target)
                                                        .key(targetKey)
                                                        .uploadId(upload)
                                                        .multipartUpload(
                                                                CompletedMultipartUpload.builder()
                                                                        .parts(parts)
                                                                        .build()))
                                .eTag(),
                () -> {
                    try {
                        var head =
                                c.read(
                                        target,
                                        () -> s3.headObject(b -> b.bucket(target).key(targetKey)));
                        return marker.equals(head.metadata().get("operation-marker"))
                                ? Optional.of(head.eTag())
                                : Optional.empty();
                    } catch (S3Exception e) {
                        if (e.statusCode() == 404) return Optional.empty();
                        throw e;
                    }
                });
    }
}
