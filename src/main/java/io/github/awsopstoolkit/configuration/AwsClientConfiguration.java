package io.github.awsopstoolkit.configuration;

import io.github.awsopstoolkit.authentication.AwsCredentialHealthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
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

/** Optional infrastructure. Creating clients does not authorize a write operation. */
@Configuration
@ConditionalOnProperty(name = "toolkit.aws.enabled", havingValue = "true")
public class AwsClientConfiguration {
    @Bean(destroyMethod = "close")
    ProfileCredentialsProvider credentials(AwsProperties aws) {
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
    SdkHttpClient awsTransport(AwsProperties aws) {
        return Apache5HttpClient.builder()
                .maxConnections(aws.maxConnections())
                .connectionTimeout(aws.connectionTimeout())
                .connectionAcquisitionTimeout(aws.acquisitionTimeout())
                .socketTimeout(aws.socketTimeout())
                .connectionMaxIdleTime(aws.maxIdle())
                .build();
    }

    @Bean
    @org.springframework.context.annotation.Scope("prototype")
    ClientOverrideConfiguration awsOverrides(AwsProperties aws) {
        if (aws.apiCallAttemptTimeout().compareTo(aws.apiCallTimeout()) > 0) {
            throw new IllegalStateException("AWS attempt timeout cannot exceed total timeout");
        }
        return ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(aws.apiCallAttemptTimeout())
                .apiCallTimeout(aws.apiCallTimeout())
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                .putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_SUFFIX, "aws-ops-toolkit/0.1")
                .build();
    }

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamo(
            AwsProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return DynamoDbClient.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean
    DynamoDbEnhancedClient enhancedDynamo(DynamoDbClient dynamo) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build();
    }

    @Bean(destroyMethod = "close")
    StsClient sts(
            AwsProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return StsClient.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean(destroyMethod = "close")
    SqsClient sqs(
            AwsProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return SqsClient.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean(destroyMethod = "close")
    SnsClient sns(
            AwsProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return SnsClient.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Client s3(
            AwsProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return S3Client.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean(destroyMethod = "close")
    LambdaClient lambda(
            AwsProperties p,
            ProfileCredentialsProvider c,
            SdkHttpClient h,
            ClientOverrideConfiguration o) {
        return LambdaClient.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(c)
                .httpClient(h)
                .overrideConfiguration(o)
                .build();
    }

    @Bean
    AwsCredentialHealthService credentialHealth(StsClient sts, AwsProperties p) {
        return new AwsCredentialHealthService(sts, p.expectedAccount(), p.region());
    }
}
