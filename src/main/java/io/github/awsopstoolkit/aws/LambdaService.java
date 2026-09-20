package io.github.awsopstoolkit.aws;

import java.util.Objects;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * SDK success is classified separately from function errors and business-level payload validation.
 */
public final class LambdaService {
    private final LambdaClient client;
    private final AwsCallGate gate;
    private final WriteAuthorization authorization;

    public LambdaService(LambdaClient client, AwsCallGate gate, WriteAuthorization authorization) {
        this.client = Objects.requireNonNull(client);
        this.gate = Objects.requireNonNull(gate);
        this.authorization = Objects.requireNonNull(authorization);
    }

    public InvocationResult invoke(InvokeRequest request) throws InterruptedException {
        InvocationType type = request.invocationType();
        if (type == null || type == InvocationType.UNKNOWN_TO_SDK_VERSION) {
            throw new IllegalArgumentException("Specify an explicit supported invocation type");
        }
        String function = request.functionName();
        if (function == null || function.isBlank())
            throw new IllegalArgumentException("Function is required");
        String target =
                request.qualifier() == null ? function : function + ":" + request.qualifier();
        InvokeResponse response =
                gate.call(
                        () -> {
                            authorization.requirePermission("lambda:InvokeFunction", target);
                            return client.invoke(request);
                        });
        int expectedStatus =
                switch (type) {
                    case REQUEST_RESPONSE -> 200;
                    case EVENT -> 202;
                    case DRY_RUN -> 204;
                    default -> throw new IllegalArgumentException("Unsupported invocation type");
                };
        if (response.statusCode() == null || response.statusCode() != expectedStatus) {
            throw new IllegalStateException("Unexpected Lambda invocation status");
        }
        Outcome outcome;
        if (type == InvocationType.EVENT) {
            outcome = Outcome.ACCEPTED;
        } else if (type == InvocationType.DRY_RUN) {
            outcome = Outcome.PERMISSION_CHECKED;
        } else {
            outcome =
                    response.functionError() == null || response.functionError().isBlank()
                            ? Outcome.EXECUTED
                            : Outcome.FUNCTION_ERROR;
        }
        return new InvocationResult(outcome, response);
    }

    public enum Outcome {
        ACCEPTED,
        EXECUTED,
        FUNCTION_ERROR,
        PERMISSION_CHECKED
    }

    /** EXECUTED still requires the operation's business-level payload validator. */
    public record InvocationResult(Outcome outcome, InvokeResponse response) {}
}
