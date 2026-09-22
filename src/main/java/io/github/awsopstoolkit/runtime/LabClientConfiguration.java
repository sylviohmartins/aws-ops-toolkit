package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.configuration.AwsProperties;
import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;

@Configuration
@ConditionalOnProperty(name = "toolkit.operations.lab-endpoint")
public class LabClientConfiguration {
    private static final int SDK_MAX_ATTEMPTS = 1;
    private static final String EMULATOR_ACCESS_KEY = "testing";
    private static final String EMULATOR_SECRET_KEY = "testing";

    private final URI endpoint;
    private final AwsProperties aws;

    public LabClientConfiguration(
            RuntimeProperties runtime, ToolkitProperties toolkit, AwsProperties aws) {
        this.aws = aws;
        endpoint = runtime.labEndpoint();
        if (toolkit.environment() != ToolkitProperties.Environment.LOCAL
                || aws.enabled()
                || !SetHolder.HOSTS.contains(endpoint.getHost())
                || !"http".equals(endpoint.getScheme())
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !SetHolder.PATHS.contains(endpoint.getPath())) {
            throw new IllegalArgumentException(
                    "Emulator endpoint requires LOCAL, loopback and disabled real AWS clients");
        }
    }

    @Bean(destroyMethod = "close")
    SdkHttpClient labTransport() {
        return Apache5HttpClient.builder()
                .maxConnections(aws.maxConnections())
                .connectionTimeout(aws.connectionTimeout())
                .connectionAcquisitionTimeout(aws.acquisitionTimeout())
                .socketTimeout(aws.socketTimeout())
                .connectionMaxIdleTime(aws.maxIdle())
                .build();
    }

    private <
                    B extends
                            software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, C>
                                    & AwsSyncClientBuilder<B, C>,
                    C>
            C build(B builder, SdkHttpClient transport) {
        return builder.endpointOverride(endpoint)
                .region(Region.of(aws.region()))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(
                                        EMULATOR_ACCESS_KEY, EMULATOR_SECRET_KEY)))
                .httpClient(transport)
                .overrideConfiguration(
                        ClientOverrideConfiguration.builder()
                                .retryStrategy(
                                        StandardRetryStrategy.builder()
                                                .maxAttempts(SDK_MAX_ATTEMPTS)
                                                .build())
                                .apiCallAttemptTimeout(aws.apiCallAttemptTimeout())
                                .apiCallTimeout(aws.apiCallTimeout())
                                .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    DynamoDbClient labDynamo(SdkHttpClient h) {
        return build(DynamoDbClient.builder(), h);
    }

    @Bean
    DynamoDbEnhancedClient labEnhancedDynamo(DynamoDbClient dynamo) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build();
    }

    @Bean(destroyMethod = "close")
    StsClient labSts(SdkHttpClient h) {
        return build(StsClient.builder(), h);
    }

    @Bean(destroyMethod = "close")
    SqsClient labSqs(SdkHttpClient h) {
        return build(SqsClient.builder(), h);
    }

    @Bean(destroyMethod = "close")
    SnsClient labSns(SdkHttpClient h) {
        return build(SnsClient.builder(), h);
    }

    @Bean(destroyMethod = "close")
    LambdaClient labLambda(SdkHttpClient h) {
        return build(LambdaClient.builder(), h);
    }

    @Bean(destroyMethod = "close")
    S3Client labS3(SdkHttpClient h) {
        return build(S3Client.builder().forcePathStyle(true), h);
    }

    private static final class SetHolder {
        private static final java.util.Set<String> HOSTS =
                java.util.Set.of("127.0.0.1", "localhost", "[::1]");
        private static final java.util.Set<String> PATHS = java.util.Set.of("", "/");
    }
}
