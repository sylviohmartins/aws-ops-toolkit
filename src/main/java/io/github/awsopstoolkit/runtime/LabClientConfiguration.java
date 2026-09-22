package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
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
@ConditionalOnProperty(name = "operations.lab-endpoint")
public class LabClientConfiguration {
    private final URI endpoint;

    public LabClientConfiguration(RuntimeProperties runtime, ToolkitProperties toolkit) {
        endpoint = runtime.labEndpoint();
        if (toolkit.environment() != ToolkitProperties.Environment.LOCAL
                || toolkit.aws().enabled()
                || !SetHolder.HOSTS.contains(endpoint.getHost())
                || !"http".equals(endpoint.getScheme())
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !SetHolder.PATHS.contains(endpoint.getPath()))
            throw new IllegalArgumentException(
                    "Emulator endpoint requires LOCAL, loopback and disabled real AWS clients");
    }

    @Bean(destroyMethod = "close")
    SdkHttpClient labTransport() {
        return Apache5HttpClient.builder()
                .maxConnections(8)
                .connectionTimeout(Duration.ofSeconds(2))
                .connectionAcquisitionTimeout(Duration.ofSeconds(2))
                .socketTimeout(Duration.ofSeconds(30))
                .build();
    }

    private <
                    B extends
                            software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, C>
                                    & AwsSyncClientBuilder<B, C>,
                    C>
            C build(B builder, SdkHttpClient transport) {
        return builder.endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create("testing", "testing")))
                .httpClient(transport)
                .overrideConfiguration(
                        ClientOverrideConfiguration.builder()
                                .retryStrategy(
                                        StandardRetryStrategy.builder().maxAttempts(1).build())
                                .apiCallAttemptTimeout(Duration.ofSeconds(35))
                                .apiCallTimeout(Duration.ofSeconds(40))
                                .build())
                .build();
    }

    @Bean(destroyMethod = "close")
    DynamoDbClient labDynamo(SdkHttpClient h) {
        return build(DynamoDbClient.builder(), h);
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
