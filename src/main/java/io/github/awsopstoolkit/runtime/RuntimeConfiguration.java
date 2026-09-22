package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.checkpoint.FileCheckpointStore;
import io.github.awsopstoolkit.configuration.ToolkitProperties;
import jakarta.validation.Validator;
import java.util.*;
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
@ConditionalOnProperty(name = "operations.enabled", havingValue = "true")
public class RuntimeConfiguration {
    @Bean
    RuntimeMaintenance runtimeMaintenance(SqliteJournal journal, RuntimeProperties properties) {
        return new RuntimeMaintenance(journal, properties);
    }

    @Bean(destroyMethod = "close")
    SqliteJournal journal(ToolkitProperties p, FileCheckpointStore exclusiveProcessLock)
            throws Exception {
        return new SqliteJournal(p.dataDirectory());
    }

    @Bean
    ExecutionPolicy executionPolicy(RuntimeProperties r, ToolkitProperties p, StsClient sts) {
        return new ExecutionPolicy(r, p, sts);
    }

    @Bean(destroyMethod = "close")
    PaymentGateway paymentGateway(RuntimeProperties r) {
        return new PaymentGateway(r);
    }

    @Bean
    JobCoordinator jobCoordinator(
            SqliteJournal journal,
            RuntimeProperties settings,
            ExecutionPolicy policy,
            DynamoDbClient dynamo,
            SqsClient sqs,
            SnsClient sns,
            LambdaClient lambda,
            S3Client s3,
            PaymentGateway http,
            ObjectMapper json,
            Validator validator) {
        List<Workflow> rules = new ArrayList<>();
        rules.add(new DynamoWorkflow(dynamo));
        if (settings.paymentEndpoint() != null)
            rules.add(new PaymentWorkflow(dynamo, sqs, sns, lambda, s3, http, settings));
        for (var kind : ServiceWorkflow.Kind.values())
            rules.add(new ServiceWorkflow(kind, sqs, sns, lambda, s3, dynamo));
        rules.add(new CompensationWorkflow(dynamo));
        return new JobCoordinator(journal, settings, policy, rules, json, validator);
    }
}
