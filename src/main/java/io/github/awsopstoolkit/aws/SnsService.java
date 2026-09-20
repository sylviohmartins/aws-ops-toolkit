package io.github.awsopstoolkit.aws;

import java.util.Objects;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishBatchRequest;
import software.amazon.awssdk.services.sns.model.PublishBatchResponse;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/** Topic publishing only; resource authorization is checked immediately before dispatch. */
public final class SnsService {
    private final SnsClient client;
    private final AwsCallGate gate;
    private final WriteAuthorization authorization;

    public SnsService(SnsClient client, AwsCallGate gate, WriteAuthorization authorization) {
        this.client = Objects.requireNonNull(client);
        this.gate = Objects.requireNonNull(gate);
        this.authorization = Objects.requireNonNull(authorization);
    }

    public PublishResponse publish(PublishRequest request) throws InterruptedException {
        requireTopic(request.topicArn());
        if (request.phoneNumber() != null || request.targetArn() != null) {
            throw new IllegalArgumentException("Only explicit topic destinations are supported");
        }
        return gate.call(
                () -> {
                    authorization.requirePermission("sns:Publish", request.topicArn());
                    return client.publish(request);
                });
    }

    /**
     * Batch success does not imply all entries succeeded; return both result lists to the caller.
     */
    public PublishBatchResponse publishBatch(PublishBatchRequest request)
            throws InterruptedException {
        requireTopic(request.topicArn());
        if (request.publishBatchRequestEntries().isEmpty()
                || request.publishBatchRequestEntries().size() > 10) {
            throw new IllegalArgumentException("SNS batch requires 1 to 10 entries");
        }
        return gate.call(
                () -> {
                    authorization.requirePermission("sns:Publish", request.topicArn());
                    return client.publishBatch(request);
                });
    }

    private static void requireTopic(String topic) {
        if (topic == null || topic.isBlank())
            throw new IllegalArgumentException("An explicit topic ARN is required");
    }
}
