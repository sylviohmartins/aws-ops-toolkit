package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.json.JsonMapper;

class PaymentGatewayTest {
    @TempDir Path directory;

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void repeated429StopsAfterThreeRequests() throws Exception {
        respond(exchange -> send(exchange, 429, "{}", "Retry-After", "not-a-valid-retry-value"));
        server.start();

        try (var fixture = fixture()) {
            var stopped =
                    assertThrows(
                            JobStopped.class, () -> fixture.gateway().get(fixture.context(), "p1"));

            assertEquals(JobState.PAUSED, stopped.state());
            assertEquals(3, requests.get());
            assertEquals(6, fixture.journal().job("job").calls());
        }
    }

    @Test
    void retryAfterAboveTenSecondsPausesWithoutWaitingOrRetrying() throws Exception {
        respond(exchange -> send(exchange, 429, "{}", "Retry-After", "11"));
        server.start();

        try (var fixture = fixture()) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> {
                        var stopped =
                                assertThrows(
                                        JobStopped.class,
                                        () -> fixture.gateway().get(fixture.context(), "p2"));
                        assertEquals(JobState.PAUSED, stopped.state());
                    });
            assertEquals(1, requests.get());
            assertEquals(2, fixture.journal().job("job").calls());
        }
    }

    @Test
    void transient503ResponsesCanRecoverOnTheLastAllowedAttempt() throws Exception {
        respond(
                exchange -> {
                    int attempt = requests.get();
                    if (attempt < 3) send(exchange, 503, "{}");
                    else
                        send(
                                exchange,
                                200,
                                "{\"reference\":\"p3\",\"status\":\"SETTLED\",\"version\":4}");
                });
        server.start();

        try (var fixture = fixture()) {
            var payment = fixture.gateway().get(fixture.context(), "p3");

            assertEquals("p3", payment.path("reference").asText());
            assertEquals(4, payment.path("version").asLong());
            assertEquals(3, requests.get());
            assertEquals(6, fixture.journal().job("job").calls());
        }
    }

    @Test
    void repeatedTransportTimeoutPausesAfterAttemptBudget() throws Exception {
        respond(
                exchange -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        send(
                                exchange,
                                200,
                                "{\"reference\":\"slow\",\"status\":\"SETTLED\",\"version\":1}");
                    } catch (IOException ignored) {
                        // The client is expected to have cancelled the timed-out exchange.
                    }
                });
        server.start();

        try (var fixture = fixture(Duration.ofMillis(100))) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(3),
                    () -> {
                        var stopped =
                                assertThrows(
                                        JobStopped.class,
                                        () -> fixture.gateway().get(fixture.context(), "slow"));
                        assertEquals(JobState.PAUSED, stopped.state());
                    });
            assertEquals(2, fixture.journal().job("job").retries());
            assertTrue(requests.get() >= 1 && requests.get() <= 3);
        }
    }

    @Test
    void unauthorizedResponseRequiresAuthenticationWithoutRetry() throws Exception {
        respond(exchange -> send(exchange, 401, "{}"));
        server.start();

        try (var fixture = fixture()) {
            var stopped =
                    assertThrows(
                            JobStopped.class, () -> fixture.gateway().get(fixture.context(), "p4"));

            assertEquals(JobState.AUTHENTICATION_REQUIRED, stopped.state());
            assertEquals(1, requests.get());
            assertEquals(2, fixture.journal().job("job").calls());
        }
    }

    @Test
    void malformedJsonIsRejectedWithoutRetry() throws Exception {
        respond(exchange -> send(exchange, 200, "{\"reference\":\"p5\""));
        server.start();

        try (var fixture = fixture()) {
            assertThrows(
                    StreamReadException.class,
                    () -> fixture.gateway().get(fixture.context(), "p5"));
            assertEquals(1, requests.get());
            assertEquals(2, fixture.journal().job("job").calls());
        }
    }

    @Test
    void oversizedBodyIsAbortedAndUsesOnlyTheAttemptBudget() throws Exception {
        byte[] oversized = new byte[65_537];
        respond(exchange -> send(exchange, 200, oversized));
        server.start();

        try (var fixture = fixture()) {
            var stopped =
                    assertThrows(
                            JobStopped.class, () -> fixture.gateway().get(fixture.context(), "p6"));

            assertEquals(JobState.PAUSED, stopped.state());
            assertEquals(3, requests.get());
            assertEquals(6, fixture.journal().job("job").calls());
        }
    }

    @Test
    void redirectIsRejectedWithoutFollowingItsLocation() throws Exception {
        var redirected = new AtomicInteger();
        server.createContext(
                "/payments/final",
                exchange -> {
                    redirected.incrementAndGet();
                    send(
                            exchange,
                            200,
                            "{\"reference\":\"redirect\",\"status\":\"SETTLED\",\"version\":1}");
                });
        respond(exchange -> send(exchange, 302, "", "Location", endpoint + "/payments/final"));
        server.start();

        try (var fixture = fixture()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.gateway().get(fixture.context(), "redirect"));
            assertEquals(1, requests.get());
            assertEquals(0, redirected.get());
            assertEquals(2, fixture.journal().job("job").calls());
        }
    }

    private void respond(ThrowingHandler handler) {
        server.createContext(
                "/payments/",
                exchange -> {
                    requests.incrementAndGet();
                    handler.handle(exchange);
                });
    }

    private Fixture fixture() throws Exception {
        return fixture(Duration.ofSeconds(10));
    }

    private Fixture fixture(Duration timeout) throws Exception {
        var settings =
                RuntimeTestFixtures.runtime(
                        false,
                        endpoint,
                        Set.of(endpoint.toString()),
                        Set.of("principal"),
                        1000,
                        1,
                        10);
        var http = RuntimeTestFixtures.http(endpoint, Set.of(endpoint.getHost()), timeout);
        var policy =
                new ExecutionPolicy(
                        settings,
                        RuntimeTestFixtures.toolkit(
                                ToolkitProperties.Environment.LOCAL, directory, false),
                        RuntimeTestFixtures.aws("123456789012"),
                        fakeSts());
        var journal = new SqliteJournal(directory);
        journal.create("job", "{}", "1", policy.identity(), 1000);
        var request = new JobRequest("test", json.readTree("{}"), 1, 10, 30, 1, 0, 1, false, false);
        var context =
                new JobContext(
                        "job",
                        request,
                        journal,
                        policy,
                        RuntimeTestFixtures.limiter(1, 1000),
                        new AtomicReference<>(),
                        Set.of(endpoint.toString()),
                        json,
                        settings);
        return new Fixture(journal, context, new PaymentGateway(http, settings, timeout));
    }

    private StsClient fakeSts() {
        return (StsClient)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {StsClient.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getCallerIdentity"))
                                return GetCallerIdentityResponse.builder()
                                        .account("123456789012")
                                        .arn("principal")
                                        .build();
                            return null;
                        });
    }

    private static void send(HttpExchange exchange, int status, String body, String... header)
            throws IOException {
        send(exchange, status, body.getBytes(StandardCharsets.UTF_8), header);
    }

    private static void send(HttpExchange exchange, int status, byte[] body, String... header)
            throws IOException {
        if (header.length == 2) exchange.getResponseHeaders().add(header[0], header[1]);
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Fixture(SqliteJournal journal, JobContext context, PaymentGateway gateway)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            gateway.close();
            journal.close();
        }
    }
}
