package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.checkpoint.FileCheckpointStore;
import io.github.awsopstoolkit.configuration.*;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;
import tools.jackson.databind.ObjectMapper;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
@ConditionalOnProperty(name = "toolkit.operations.enabled", havingValue = "true")
public class RuntimeConfiguration {
    @Bean
    RuntimeMaintenance runtimeMaintenance(SqliteJournal journal, RuntimeProperties properties) {
        return new RuntimeMaintenance(journal, properties);
    }

    @Bean(destroyMethod = "close")
    SqliteJournal journal(
            ToolkitProperties p,
            JournalProperties journalProperties,
            FileCheckpointStore exclusiveProcessLock)
            throws Exception {
        return new SqliteJournal(p.dataDirectory(), journalProperties);
    }

    @Bean
    ExecutionPolicy executionPolicy(
            RuntimeProperties runtime,
            ToolkitProperties toolkit,
            AwsProperties aws,
            StsClient sts) {
        return new ExecutionPolicy(runtime, toolkit, aws, sts);
    }

    @Bean(destroyMethod = "close")
    PaymentGateway paymentGateway(HttpProperties http, RuntimeProperties runtime) {
        return new PaymentGateway(http, runtime);
    }

    @Bean
    JobCoordinator jobCoordinator(
            SqliteJournal journal,
            RuntimeProperties settings,
            HttpProperties httpSettings,
            S3Properties s3Settings,
            SqsProperties sqsSettings,
            ExecutionPolicy policy,
            DynamoDbClient dynamo,
            SqsClient sqs,
            SnsClient sns,
            LambdaClient lambda,
            S3Client s3,
            PaymentGateway http,
            ObjectMapper json,
            Validator validator,
            ObjectProvider<Workflow> workflowExtensions) {
        // Operation-specific edge workflows may be ordinary conditional Spring beans. The stable
        // coordinator/controller core does not need editing to discover them.
        List<Workflow> rules = new ArrayList<>(workflowExtensions.orderedStream().toList());
        rules.add(new DynamoWorkflow(dynamo));
        if (httpSettings.paymentEndpoint() != null) {
            rules.add(new PaymentWorkflow(dynamo, sqs, sns, lambda, s3, http, httpSettings));
        }
        for (var kind : ServiceWorkflow.Kind.values()) {
            rules.add(
                    new ServiceWorkflow(
                            kind, sqs, sns, lambda, s3, dynamo, sqsSettings, s3Settings));
        }
        rules.add(new CompensationWorkflow(dynamo));
        return new JobCoordinator(journal, settings, policy, rules, json, validator);
    }
}
