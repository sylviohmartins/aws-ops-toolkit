package io.github.awsopstoolkit.aws;

import java.util.Objects;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

/**
 * SQS reference adapter. Receiving is an authorized side effect; nothing is automatically deleted.
 */
public final class SqsService {
    private final SqsClient client;
    private final AwsCallGate gate;
    private final WriteAuthorization authorization;

    public SqsService(SqsClient client, AwsCallGate gate, WriteAuthorization authorization) {
        this.client = Objects.requireNonNull(client);
        this.gate = Objects.requireNonNull(gate);
        this.authorization = Objects.requireNonNull(authorization);
    }

    public GetQueueAttributesResponse attributes(GetQueueAttributesRequest request)
            throws InterruptedException {
        return gate.call(() -> client.getQueueAttributes(request));
    }

    /**
     * Explicit acknowledgement is required because receive hides messages and affects receive
     * counts. The caller must also supply an explicit visibility period and pass resource
     * authorization.
     */
    public ReceiveMessageResponse receiveForInspection(
            ReceiveMessageRequest request, boolean visibilityImpactAccepted)
            throws InterruptedException {
        if (!visibilityImpactAccepted) {
            throw new IllegalArgumentException(
                    "Acknowledge visibility and receive-count impact before receiving");
        }
        int count = request.maxNumberOfMessages() == null ? 1 : request.maxNumberOfMessages();
        int wait = request.waitTimeSeconds() == null ? 20 : request.waitTimeSeconds();
        Integer visibility = request.visibilityTimeout();
        if (count < 1
                || count > 10
                || wait < 0
                || wait > 20
                || visibility == null
                || visibility < 0
                || visibility > 43_200) {
            throw new IllegalArgumentException(
                    "Invalid receive limit, wait time or explicit visibility timeout");
        }
        ReceiveMessageRequest bounded =
                request.toBuilder().maxNumberOfMessages(count).waitTimeSeconds(wait).build();
        return gate.call(
                () -> {
                    authorization.requirePermission("sqs:ReceiveMessage", bounded.queueUrl());
                    return client.receiveMessage(bounded);
                });
    }

    public SendMessageResponse send(SendMessageRequest request) throws InterruptedException {
        return gate.call(
                () -> {
                    authorization.requirePermission("sqs:SendMessage", request.queueUrl());
                    return client.sendMessage(request);
                });
    }

    /** Inspect Successful AND Failed entries even when the HTTP request succeeds. */
    public SendMessageBatchResponse sendBatch(SendMessageBatchRequest request)
            throws InterruptedException {
        requireBatchSize(request.entries().size());
        return gate.call(
                () -> {
                    authorization.requirePermission("sqs:SendMessage", request.queueUrl());
                    return client.sendMessageBatch(request);
                });
    }

    /** Acknowledge only after the operation policy has durably recorded successful processing. */
    public DeleteMessageResponse delete(DeleteMessageRequest request) throws InterruptedException {
        return gate.call(
                () -> {
                    authorization.requirePermission("sqs:DeleteMessage", request.queueUrl());
                    return client.deleteMessage(request);
                });
    }

    public DeleteMessageBatchResponse deleteBatch(DeleteMessageBatchRequest request)
            throws InterruptedException {
        requireBatchSize(request.entries().size());
        return gate.call(
                () -> {
                    authorization.requirePermission("sqs:DeleteMessage", request.queueUrl());
                    return client.deleteMessageBatch(request);
                });
    }

    public ChangeMessageVisibilityResponse changeVisibility(ChangeMessageVisibilityRequest request)
            throws InterruptedException {
        if (request.visibilityTimeout() == null
                || request.visibilityTimeout() < 0
                || request.visibilityTimeout() > 43_200) {
            throw new IllegalArgumentException("Invalid visibility timeout");
        }
        return gate.call(
                () -> {
                    authorization.requirePermission(
                            "sqs:ChangeMessageVisibility", request.queueUrl());
                    return client.changeMessageVisibility(request);
                });
    }

    private static void requireBatchSize(int size) {
        if (size < 1 || size > 10)
            throw new IllegalArgumentException("SQS batch requires 1 to 10 entries");
    }
}
