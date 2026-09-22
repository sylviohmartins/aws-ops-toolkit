package io.github.awsopstoolkit.runtime;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** A rule only selects a bounded page and implements guarded, journaled effects per item. */
public interface Workflow {
    String type();

    default String version() {
        return "1";
    }

    Set<String> resources(JsonNode parameters);

    void validate(JobRequest request);

    default void preflight(JobContext context) throws Exception {}

    Page plan(JobContext context, int segment, String cursor) throws Exception;

    String execute(JobContext context, SqliteJournal.Task task) throws Exception;

    default Proposal proposal(JsonNode parameters, SqliteJournal.Task task) {
        return new Proposal(
                writes() ? "EFFECT" : "READ_ONLY", java.util.Map.of(), java.util.Map.of());
    }

    default int estimatedCallsPerCandidate() {
        return writes() ? 1 : 0;
    }

    default java.util.List<String> risks() {
        return java.util.List.of();
    }

    default boolean writes() {
        return true;
    }

    record Candidate(String key, String payload) {}

    record Page(java.util.List<Candidate> records, String cursor, boolean complete) {}

    record Proposal(
            String action,
            java.util.Map<String, Object> before,
            java.util.Map<String, Object> after) {}
}
