package io.github.awsopstoolkit.aws;

/**
 * Port for authorization of operations with remote side effects, including SQS receive.
 * Implementations must deny unless the operation, live identity and exact resource are approved.
 * The resource is the exact SDK identifier; implementations must resolve/validate its account and
 * region. No permitting implementation is supplied by these adapters.
 */
@FunctionalInterface
public interface WriteAuthorization {
    void requirePermission(String action, String resource);
}
