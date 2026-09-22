package io.github.awsopstoolkit.runtime;

public enum JobState {
    PLANNING,
    READY,
    DRY_RUN_COMPLETE,
    APPROVED,
    RUNNING,
    CANARY_COMPLETE,
    PAUSED,
    CANCELLED,
    INTERRUPTED,
    AUTHENTICATION_REQUIRED,
    AUTHORIZATION_REQUIRED,
    BUDGET_EXCEEDED,
    RECONCILIATION_REQUIRED,
    COMPLETED,
    FAILED;

    public boolean terminal() {
        return this == DRY_RUN_COMPLETE || this == COMPLETED || this == CANCELLED || this == FAILED;
    }
}
