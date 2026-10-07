package io.github.awsopstoolkit.runtime;

/** Durable lifecycle of one remote side-effect attempt. Persisted by enum name in SQLite. */
public enum EffectState {
    INTENT,
    UNKNOWN,
    SUCCEEDED,
    NOT_SENT;

    public boolean unresolved() {
        return this == INTENT || this == UNKNOWN;
    }
}
