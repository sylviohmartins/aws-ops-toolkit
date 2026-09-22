package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.util.Set;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sts.StsClient;

public final class ExecutionPolicy {
    private final RuntimeProperties runtime;
    private final ToolkitProperties toolkit;
    private final StsClient sts;

    public ExecutionPolicy(RuntimeProperties runtime, ToolkitProperties toolkit, StsClient sts) {
        this.runtime = runtime;
        this.toolkit = toolkit;
        this.sts = sts;
        if (runtime.resources().isEmpty()
                || runtime.resources().stream().anyMatch(s -> s.contains("*"))
                || runtime.principals().isEmpty())
            throw new IllegalArgumentException("Exact resources and principals required");
    }

    public String identity() {
        try {
            var identity = sts.getCallerIdentity();
            String principal = normalizePrincipal(identity.arn());
            if (!identity.account().equals(toolkit.aws().expectedAccount())
                    || (!runtime.principals().contains(principal)
                            && !runtime.principals().contains(identity.arn())))
                throw new JobStopped(JobState.AUTHENTICATION_REQUIRED);
            return identity.account()
                    + "|"
                    + principal
                    + "|"
                    + toolkit.aws().region()
                    + "|"
                    + toolkit.environment();
        } catch (AwsServiceException | SdkClientException e) {
            throw new JobStopped(JobState.AUTHENTICATION_REQUIRED);
        }
    }

    static String normalizePrincipal(String arn) {
        if (arn == null) return "";
        String[] fields = arn.split(":", 6);
        if (fields.length != 6 || !"sts".equals(fields[2])) return arn;
        String resource = fields[5];
        if (!resource.startsWith("assumed-role/")) return arn;
        String roleAndSession = resource.substring("assumed-role/".length());
        int lastSlash = roleAndSession.lastIndexOf('/');
        if (lastSlash <= 0) return arn;
        String role = roleAndSession.substring(0, lastSlash);
        return fields[0] + ":" + fields[1] + ":iam::" + fields[4] + ":role/" + role;
    }

    public void resources(Set<String> resources) {
        if (resources.isEmpty() || !runtime.resources().containsAll(resources))
            throw new IllegalArgumentException("Resource is outside the configured allowlist");
        resources.forEach(this::validateCoordinates);
    }

    private void validateCoordinates(String resource) {
        if (resource.startsWith("arn:")) {
            String[] fields = resource.split(":", 6);
            if (fields.length != 6) throw new IllegalArgumentException("Malformed AWS ARN");
            boolean hasArnPrincipal =
                    runtime.principals().stream().anyMatch(p -> p.startsWith("arn:"));
            if (hasArnPrincipal
                    && runtime.principals().stream()
                            .filter(p -> p.startsWith("arn:"))
                            .noneMatch(p -> p.startsWith("arn:" + fields[1] + ":")))
                throw new IllegalArgumentException("AWS partition mismatch");
            if (!fields[3].isBlank() && !fields[3].equals(toolkit.aws().region()))
                throw new IllegalArgumentException("AWS region mismatch");
            if (!fields[4].isBlank() && !fields[4].equals(toolkit.aws().expectedAccount()))
                throw new IllegalArgumentException("AWS account mismatch");
        } else if (resource.startsWith("http://") || resource.startsWith("https://")) {
            try {
                var uri = java.net.URI.create(resource);
                String[] parts = uri.getPath() == null ? new String[0] : uri.getPath().split("/");
                for (String part : parts) {
                    if (part.matches("\\d{12}") && !part.equals(toolkit.aws().expectedAccount()))
                        throw new IllegalArgumentException("AWS account mismatch in resource URL");
                    if (!part.isBlank()) break;
                }
            } catch (IllegalArgumentException failure) {
                throw failure;
            }
        }
    }

    public void validateIntent(JobRequest request, boolean writes) {
        if (request.mode() != JobMode.EXECUTE || !writes) return;
        if (!runtime.writes()
                || (toolkit.environment() != ToolkitProperties.Environment.LOCAL
                        && !toolkit.writeEnabled()))
            throw new IllegalStateException("Writes are disabled by one or more safety gates");
        if (!request.explicitConfirmation()
                || !request.hasOperationalReference()
                || request.reason() == null
                || request.reason().length() < 8) {
            throw new IllegalArgumentException(
                    "EXECUTE writes require incident/change, reason and explicit confirmation");
        }
        if (toolkit.environment() == ToolkitProperties.Environment.PROD
                && (request.incidentId() == null || request.changeId() == null)) {
            throw new IllegalArgumentException("PROD writes require both incidentId and changeId");
        }
    }

    public void authorize(SqliteJournal.Job job, boolean write) {
        if (!job.identity().equals(identity()))
            throw new JobStopped(JobState.AUTHENTICATION_REQUIRED);
        if (write
                && (!runtime.writes()
                        || (toolkit.environment() != ToolkitProperties.Environment.LOCAL
                                && !toolkit.writeEnabled())
                        || job.approvedUntil() <= SqliteJournal.now()))
            throw new JobStopped(JobState.AUTHORIZATION_REQUIRED);
    }

    public String environment() {
        return toolkit.environment().name();
    }

    public String region() {
        return toolkit.aws().region();
    }

    public String account() {
        return toolkit.aws().expectedAccount();
    }

    public void disk(SqliteJournal journal) throws java.io.IOException {
        if (journal.freeBytes() < toolkit.minimumFreeBytes())
            throw new JobStopped(JobState.BUDGET_EXCEEDED);
    }

    public void preflight(SqliteJournal journal, JobRequest request, Set<String> resources)
            throws java.io.IOException {
        resources(resources);
        disk(journal);
        if (journal.freeBytes()
                < toolkit.minimumFreeBytes() + Math.multiplyExact(request.maxRecords(), 8192L))
            throw new JobStopped(JobState.BUDGET_EXCEEDED);
    }
}
