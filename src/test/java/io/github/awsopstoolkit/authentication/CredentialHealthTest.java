package io.github.awsopstoolkit.authentication;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

class CredentialHealthTest {
    private AwsCredentialHealthService service(Supplier<GetCallerIdentityResponse> response) {
        var client =
                (StsClient)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {StsClient.class},
                                (proxy, method, args) -> response.get());
        return new AwsCredentialHealthService(client, "EXPECTED_ACCOUNT", "us-east-1");
    }

    @Test
    void wrongAccountIsNotHealthy() {
        var health =
                service(
                                () ->
                                        GetCallerIdentityResponse.builder()
                                                .account("DIFFERENT_ACCOUNT")
                                                .arn("synthetic-principal")
                                                .build())
                        .check();
        assertEquals(AwsCredentialHealthService.Status.WRONG_ACCOUNT, health.status());
    }

    @Test
    void expiredTokenRequiresAuthenticationRatherThanRetry() {
        var health =
                service(
                                () -> {
                                    throw StsException.builder()
                                            .statusCode(403)
                                            .awsErrorDetails(
                                                    AwsErrorDetails.builder()
                                                            .errorCode("ExpiredToken")
                                                            .build())
                                            .build();
                                })
                        .check();
        assertEquals(AwsCredentialHealthService.Status.AUTHENTICATION_REQUIRED, health.status());
        assertNull(health.account());
    }
}
