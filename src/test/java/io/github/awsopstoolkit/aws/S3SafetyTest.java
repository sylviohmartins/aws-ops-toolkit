package io.github.awsopstoolkit.aws;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

class S3SafetyTest {
    @Test
    void refusesGovernanceBypassBeforeDispatch() {
        var client =
                (S3Client)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {S3Client.class},
                                (proxy, method, args) -> {
                                    fail("No AWS request should be dispatched");
                                    return null;
                                });
        var service =
                new S3Service(
                        client,
                        new AwsCallGate(1, 1),
                        (action, resource) -> fail("Authorization should not be reached"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.delete(
                                DeleteObjectRequest.builder()
                                        .bucket("synthetic-bucket")
                                        .key("synthetic-key")
                                        .bypassGovernanceRetention(true)
                                        .build()));
    }
}
