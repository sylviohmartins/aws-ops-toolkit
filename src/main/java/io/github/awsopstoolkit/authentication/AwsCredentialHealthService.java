package io.github.awsopstoolkit.authentication;

import java.util.Set;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sts.StsClient;

/** STS identity confirms account/principal, never target-service permissions. */
public final class AwsCredentialHealthService {
    public enum Status {
        HEALTHY,
        AUTHENTICATION_REQUIRED,
        ACCESS_DENIED,
        WRONG_ACCOUNT,
        CONFIGURATION_OR_NETWORK_ERROR
    }

    public record Health(Status status, String account, String principalArn, String region) {}

    private final StsClient client;
    private final String account;
    private final String region;

    public AwsCredentialHealthService(StsClient client, String account, String region) {
        this.client = client;
        this.account = account;
        this.region = region;
    }

    public Health check() {
        try {
            var identity = client.getCallerIdentity();
            return new Health(
                    account.equals(identity.account()) ? Status.HEALTHY : Status.WRONG_ACCOUNT,
                    identity.account(),
                    identity.arn(),
                    region);
        } catch (AwsServiceException failure) {
            String code =
                    failure.awsErrorDetails() == null ? "" : failure.awsErrorDetails().errorCode();
            if (code == null) code = "";
            var status =
                    Set.of(
                                            "ExpiredToken",
                                            "ExpiredTokenException",
                                            "InvalidClientTokenId",
                                            "UnrecognizedClientException")
                                    .contains(code)
                            ? Status.AUTHENTICATION_REQUIRED
                            : failure.statusCode() == 403
                                    ? Status.ACCESS_DENIED
                                    : Status.CONFIGURATION_OR_NETWORK_ERROR;
            return new Health(status, null, null, region);
        } catch (SdkClientException failure) {
            // SSO/cache/process errors and network failures require inspection, not message-string
            // guesses.
            return new Health(Status.CONFIGURATION_OR_NETWORK_ERROR, null, null, region);
        }
    }
}
