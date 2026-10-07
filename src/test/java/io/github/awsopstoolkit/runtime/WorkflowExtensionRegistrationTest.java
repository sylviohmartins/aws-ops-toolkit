package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkflowExtensionRegistrationTest {
    @Test
    void edgeWorkflowBeanIsDiscoveredWithoutEditingCoordinator() {
        Workflow extension = new ExtensionWorkflow();
        @SuppressWarnings("unchecked")
        ObjectProvider<Workflow> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(extension));

        var configuration = new RuntimeConfiguration();
        var coordinator =
                configuration.jobCoordinator(
                        mock(SqliteJournal.class),
                        RuntimeTestFixtures.runtime(
                                false,
                                null,
                                Set.of("extension-resource"),
                                Set.of("principal"),
                                10,
                                2,
                                100),
                        RuntimeTestFixtures.http(null, Set.of()),
                        RuntimeTestFixtures.s3(),
                        RuntimeTestFixtures.sqs(),
                        mock(ExecutionPolicy.class),
                        mock(DynamoDbClient.class),
                        mock(SqsClient.class),
                        mock(SnsClient.class),
                        mock(LambdaClient.class),
                        mock(S3Client.class),
                        mock(PaymentGateway.class),
                        JsonMapper.builder().build(),
                        mock(Validator.class),
                        provider);

        assertTrue(coordinator.operations().contains("extension-example"));
    }

    private static final class ExtensionWorkflow implements Workflow {
        @Override
        public String type() {
            return "extension-example";
        }

        @Override
        public Set<String> resources(JsonNode parameters) {
            return Set.of("extension-resource");
        }

        @Override
        public void validate(JobRequest request) {}

        @Override
        public Page plan(JobContext context, int segment, String cursor) {
            return new Page(java.util.List.of(), "", true);
        }

        @Override
        public String execute(JobContext context, SqliteJournal.Task task) {
            return "NOOP";
        }

        @Override
        public boolean writes() {
            return false;
        }
    }
}
