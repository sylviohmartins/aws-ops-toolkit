package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void paymentIntegrationRequiresSingleJdkSendAttempt() {
        assertThrows(IllegalStateException.class, () -> PaymentGateway.validateJdkRetryLimit(null));
        assertThrows(IllegalStateException.class, () -> PaymentGateway.validateJdkRetryLimit("5"));
        PaymentGateway.validateJdkRetryLimit("1");
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
    void concurrent429BurstTriggersSharedBudgetAndAimdWithoutRetryAmplification() throws Exception {
        respond(exchange -> send(exchange, 429, "{}", "Retry-After", "not-a-valid-retry-value"));
        server.start();

        try (var fixture = fixture(Duration.ofSeconds(10), Duration.ofMillis(1), 4, 100_000, 100);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<JobState>>();
            for (int index = 0; index < 4; index++) {
                String payment = "burst-" + index;
                futures.add(
                        pool.submit(
                                () ->
                                        assertThrows(
                                                        JobStopped.class,
                                                        () ->
                                                                fixture.gateway()
                                                                        .get(
                                                                                fixture.context(),
                                                                                payment))
                                                .state()));
            }

            var states = new java.util.ArrayList<JobState>();
            for (var future : futures) {
                states.add(future.get(10, TimeUnit.SECONDS));
            }

            assertTrue(
                    states.stream()
                            .allMatch(
                                    state ->
                                            state == JobState.PAUSED
                                                    || state == JobState.BUDGET_EXCEEDED));
            assertTrue(states.contains(JobState.BUDGET_EXCEEDED));
            assertTrue(requests.get() >= 4 && requests.get() < 12);
            assertEquals(1, fixture.limiter().currentConcurrency());
            assertTrue(fixture.limiter().currentRate() < 100_000);
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
    void interruptionDuringRetryAfterStopsWithoutSecondRequest() throws Exception {
        var firstResponse = new CountDownLatch(1);
        respond(
                exchange -> {
                    send(exchange, 429, "{}", "Retry-After", "5");
                    firstResponse.countDown();
                });
        server.start();

        try (var fixture = fixture()) {
            var failure = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            Thread worker =
                    Thread.ofVirtual()
                            .unstarted(
                                    () -> {
                                        try {
                                            fixture.gateway().get(fixture.context(), "cancel-wait");
                                        } catch (Throwable thrown) {
                                            failure.set(thrown);
                                        } finally {
                                            interrupted.set(Thread.currentThread().isInterrupted());
                                        }
                                    });

            worker.start();
            assertTrue(firstResponse.await(2, TimeUnit.SECONDS));
            long retryDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (fixture.journal().job("job").retries() < 1
                    && System.nanoTime() < retryDeadline) {
                Thread.sleep(10);
            }
            assertEquals(1, fixture.journal().job("job").retries());

            worker.interrupt();
            worker.join(2_000);

            assertFalse(worker.isAlive());
            var stopped = assertInstanceOf(JobStopped.class, failure.get());
            assertEquals(JobState.INTERRUPTED, stopped.state());
            assertTrue(interrupted.get());
            assertEquals(1, requests.get());
        }
    }

    @Test
    void interruptionDuringNormalBackoffStopsWithoutSecondRequest() throws Exception {
        var firstResponse = new CountDownLatch(1);
        respond(
                exchange -> {
                    send(exchange, 503, "{}");
                    firstResponse.countDown();
                });
        server.start();

        try (var fixture = fixture(Duration.ofSeconds(10), Duration.ofSeconds(5))) {
            var failure = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            Thread worker =
                    Thread.ofVirtual()
                            .unstarted(
                                    () -> {
                                        try {
                                            fixture.gateway()
                                                    .get(fixture.context(), "cancel-backoff");
                                        } catch (Throwable thrown) {
                                            failure.set(thrown);
                                        } finally {
                                            interrupted.set(Thread.currentThread().isInterrupted());
                                        }
                                    });

            worker.start();
            assertTrue(firstResponse.await(2, TimeUnit.SECONDS));
            long retryDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (fixture.journal().job("job").retries() < 1
                    && System.nanoTime() < retryDeadline) {
                Thread.sleep(10);
            }
            assertEquals(1, fixture.journal().job("job").retries());

            worker.interrupt();
            worker.join(2_000);

            assertFalse(worker.isAlive());
            var stopped = assertInstanceOf(JobStopped.class, failure.get());
            assertEquals(JobState.INTERRUPTED, stopped.state());
            assertTrue(interrupted.get());
            assertEquals(1, requests.get());
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
    void repeatedConnectionResetPausesWithinAttemptBudget() throws Exception {
        respond(HttpExchange::close);
        server.start();

        try (var fixture = fixture()) {
            var stopped =
                    assertThrows(
                            JobStopped.class,
                            () -> fixture.gateway().get(fixture.context(), "reset"));

            assertEquals(JobState.PAUSED, stopped.state());
            assertEquals(2, fixture.journal().job("job").retries());
            assertEquals(3, requests.get());
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
    void forbiddenResponseRequiresAuthorizationWithoutRetry() throws Exception {
        respond(exchange -> send(exchange, 403, "{}"));
        server.start();

        try (var fixture = fixture()) {
            var stopped =
                    assertThrows(
                            JobStopped.class,
                            () -> fixture.gateway().get(fixture.context(), "p403"));

            assertEquals(JobState.AUTHORIZATION_REQUIRED, stopped.state());
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
    void oversizedBodyIsRejectedWithoutRetry() throws Exception {
        byte[] oversized = new byte[65_537];
        respond(exchange -> send(exchange, 200, oversized));
        server.start();

        try (var fixture = fixture()) {
            var failure =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> fixture.gateway().get(fixture.context(), "p6"));

            assertEquals("Payment response body exceeds configured limit", failure.getMessage());
            assertEquals(1, requests.get());
            assertEquals(2, fixture.journal().job("job").calls());
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
        return fixture(timeout, Duration.ofMillis(100));
    }

    private Fixture fixture(Duration timeout, Duration retryBaseBackoff) throws Exception {
        return fixture(timeout, retryBaseBackoff, 1, 1000, 5);
    }

    private Fixture fixture(
            Duration timeout,
            Duration retryBaseBackoff,
            int workers,
            int rateCeiling,
            int circuitFailuresBeforeOpen)
            throws Exception {
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
        var journal = RuntimeTestFixtures.journal(directory);
        journal.create("job", "{}", "1", policy.identity(), 1000);
        var request = new JobRequest("test", json.readTree("{}"), 1, 10, 30, 1, 0, 1, false, false);
        var limiter =
                new DispatchLimiter(
                        workers,
                        rateCeiling,
                        20,
                        circuitFailuresBeforeOpen,
                        Duration.ofSeconds(10),
                        retryBaseBackoff);
        var context =
                new JobContext(
                        "job",
                        request,
                        journal,
                        policy,
                        limiter,
                        new AtomicReference<>(),
                        Set.of(endpoint.toString()),
                        json,
                        settings);
        return new Fixture(journal, context, limiter, new PaymentGateway(http, settings, timeout));
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

    private record Fixture(
            SqliteJournal journal,
            JobContext context,
            DispatchLimiter limiter,
            PaymentGateway gateway)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            gateway.close();
            journal.close();
        }
    }
}
