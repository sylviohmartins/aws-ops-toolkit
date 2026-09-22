package io.github.awsopstoolkit.configuration;

import io.github.awsopstoolkit.authentication.AwsCredentialHealthService;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
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

/** Optional infrastructure examples. Creating beans does not authorize an operation. */
@Configuration
@ConditionalOnProperty(name = "toolkit.aws.enabled", havingValue = "true")
public class AwsClientConfiguration {
    @Bean(destroyMethod = "close")
    ProfileCredentialsProvider credentials(ToolkitProperties properties) {
        var aws = properties.aws();
        if (aws.profile() == null
                || aws.profile().isBlank()
                || aws.expectedAccount() == null
                || !aws.expectedAccount().matches("\\d{12}")) {
            throw new IllegalStateException(
                    "Explicit profile and expected account are required when AWS is enabled");
        }
        return ProfileCredentialsProvider.builder().profileName(aws.profile()).build();
    }

    @Bean(destroyMethod = "close")
    SdkHttpClient awsTransport(ToolkitProperties p) {
        var aws = p.aws();
        return Apache5HttpClient.builder()
                .maxConnections(aws.maxConnections())
                .connectionTimeout(Duration.ofMillis(aws.connectionTimeoutMillis()))
                .connectionAcquisitionTimeout(Duration.ofMillis(aws.acquisitionTimeoutMillis()))
                .socketTimeout(Duration.ofMillis(aws.socketTimeoutMillis()))
                .connectionMaxIdleTime(Duration.ofMillis(aws.maxIdleMillis()))
                .build();
    }

    @Bean
    @org.springframework.context.annotation.Scope("prototype")
    ClientOverrideConfiguration awsOverrides(ToolkitProperties properties) {
        var aws = properties.aws();
        if (aws.apiCallAttemptTimeoutMillis() > aws.apiCallTimeoutMillis())
            throw new IllegalStateException("AWS attempt timeout cannot exceed total timeout");
        // SDK retry stays at one attempt by design. Safe read retries belong to JobContext,
        // avoiding
        // multiplicative retry layers for effects whose remote outcome may be ambiguous.
        return ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(Duration.ofMillis(aws.apiCallAttemptTimeoutMillis()))
                .apiCallTimeout(Duration.ofMillis(aws.apiCallTimeoutMillis()))
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                .putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_SUFFIX, "aws-ops-toolkit/0.1")
                .build();
    }

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamo(
            ToolkitProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return DynamoDbClient.builder()
                .region(Region.of(p.aws().region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean(destroyMethod = "close")
    StsClient sts(
            ToolkitProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return StsClient.builder()
                .region(Region.of(p.aws().region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean(destroyMethod = "close")
    SqsClient sqs(
            ToolkitProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return SqsClient.builder()
                .region(Region.of(p.aws().region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean(destroyMethod = "close")
    SnsClient sns(
            ToolkitProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return SnsClient.builder()
                .region(Region.of(p.aws().region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Client s3(
            ToolkitProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return S3Client.builder()
                .region(Region.of(p.aws().region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean(destroyMethod = "close")
    LambdaClient lambda(
            ToolkitProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return LambdaClient.builder()
                .region(Region.of(p.aws().region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean
    AwsCredentialHealthService credentialHealth(StsClient sts, ToolkitProperties p) {
        return new AwsCredentialHealthService(sts, p.aws().expectedAccount(), p.aws().region());
    }
}
