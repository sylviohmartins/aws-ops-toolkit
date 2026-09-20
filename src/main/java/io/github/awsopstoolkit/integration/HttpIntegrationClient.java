package io.github.awsopstoolkit.integration;

import static io.github.awsopstoolkit.integration.IntegrationException.Category.*;

import io.github.awsopstoolkit.aws.AwsCallGate;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Explicitly constructed example: HTTPS allowlist, bounded calls, no redirects or automatic
 * retries.
 */
public final class HttpIntegrationClient implements AutoCloseable {
    private final HttpClient transport;
    private final PaymentLookup lookup;
    private final AwsCallGate gate;

    public HttpIntegrationClient(
            URI base, Set<String> approvedHosts, int maxConcurrency, double requestsPerSecond) {
        if (!"https".equals(base.getScheme())
                || base.getHost() == null
                || !approvedHosts.contains(base.getHost())
                || base.getUserInfo() != null
                || base.getFragment() != null
                || base.getQuery() != null) {
            throw new IllegalArgumentException("Unapproved integration origin");
        }
        gate = new AwsCallGate(maxConcurrency, requestsPerSecond);
        transport =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        var requestFactory = new JdkClientHttpRequestFactory(transport);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        var client =
                RestClient.builder()
                        .baseUrl(base.toString())
                        .requestFactory(requestFactory)
                        .defaultStatusHandler(
                                status -> status.value() >= 300,
                                (request, response) -> {
                                    int status = response.getStatusCode().value();
                                    throw new IntegrationException(
                                            switch (status) {
                                                case 401 -> AUTHENTICATION;
                                                case 403 -> AUTHORIZATION;
                                                case 429 -> RATE_LIMITED;
                                                case 400, 404, 409, 422 -> BUSINESS;
                                                default ->
                                                        status >= 500
                                                                ? UNAVAILABLE
                                                                : UNEXPECTED_RESPONSE;
                                            });
                                })
                        .build();
        lookup =
                HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client))
                        .build()
                        .createClient(PaymentLookup.class);
    }

    public PaymentLookup.PaymentState lookup(String reference, UUID operationId)
            throws InterruptedException {
        if (reference == null || !reference.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid reference");
        try {
            var result = gate.call(() -> lookup.get(reference, operationId.toString()));
            if (result == null
                    || !reference.equals(result.reference())
                    || result.status() == null
                    || result.version() < 0) {
                throw new IntegrationException(UNEXPECTED_RESPONSE);
            }
            return result;
        } catch (ResourceAccessException failure) {
            throw new IntegrationException(NETWORK_OR_TIMEOUT);
        } catch (RestClientException failure) {
            throw new IntegrationException(UNEXPECTED_RESPONSE);
        }
    }

    @Override
    public void close() {
        transport.close();
    }
}
