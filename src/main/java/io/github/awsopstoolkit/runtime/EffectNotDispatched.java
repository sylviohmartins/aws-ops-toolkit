package io.github.awsopstoolkit.runtime;

/** A rule may raise this only before invoking its remote SDK call. */
public final class EffectNotDispatched extends RuntimeException {
    private final JobState state;

    public EffectNotDispatched(JobState state) {
        super("Effect was not dispatched");
        this.state = state;
    }

    public JobState state() {
        return state;
    }
}
