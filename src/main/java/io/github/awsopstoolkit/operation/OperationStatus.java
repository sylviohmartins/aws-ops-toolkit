package io.github.awsopstoolkit.operation;

/** Deliberately smaller than the target production state machine. */
public enum OperationStatus {
    CREATED,
    RUNNING,
    PAUSING,
    PAUSED,
    CANCELLING,
    CANCELLED,
    INTERRUPTED,
    COMPLETED,
    FAILED;

    public boolean resumable() {
        return this == PAUSED || this == INTERRUPTED;
    }

    public boolean active() {
        return this == CREATED || this == RUNNING || this == PAUSING || this == CANCELLING;
    }
}
