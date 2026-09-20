package io.github.awsopstoolkit.integration;

/** Stable categories; never put an upstream response body or credential in an exception. */
public final class IntegrationException extends RuntimeException {
    public enum Category {
        AUTHENTICATION,
        AUTHORIZATION,
        RATE_LIMITED,
        UNAVAILABLE,
        BUSINESS,
        UNEXPECTED_RESPONSE,
        NETWORK_OR_TIMEOUT
    }

    private final Category category;

    public IntegrationException(Category category) {
        super(category.name());
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
