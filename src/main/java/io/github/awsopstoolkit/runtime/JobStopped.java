package io.github.awsopstoolkit.runtime;

public final class JobStopped extends RuntimeException {
    private final JobState state;

    public JobStopped(JobState state) {
        super(state.name());
        this.state = state;
    }

    public JobState state() {
        return state;
    }
}
