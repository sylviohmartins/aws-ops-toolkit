package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.awsopstoolkit.configuration.ToolkitProperties;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;
import tools.jackson.databind.json.JsonMapper;

class MutableHttpEffectRecoveryTest {
    enum Mode {
        BEFORE_EFFECT_500,
        AFTER_EFFECT_500,
        SUCCESS
    }

    @TempDir Path directory;

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.SUCCESS);
    private final AtomicReference<String> applied = new AtomicReference<>();
    private final AtomicInteger posts = new AtomicInteger();
    private final AtomicInteger gets = new AtomicInteger();

    private HttpServer server;
    private HttpClient client;
    private URI endpoint;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/effects/", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach
    void stopServer() {
        if (client != null) client.close();
        if (server != null) server.stop(0);
    }

    @Test
    void fiveHundredBeforeEffectReplaysOnlyAfterAuthoritativeAbsence() throws Exception {
        mode.set(Mode.BEFORE_EFFECT_500);
        try (var journal = RuntimeTestFixtures.journal(directory)) {
            var policy = policy();
            var task = prepare(journal, policy);
            var context = context(journal, policy);

            var stopped =
                    assertThrows(
                            JobStopped.class,
                            () ->
                                    context.effect(
                                            task,
                                            "http-mutation",
                                            endpoint.toString(),
                                            () -> apply("effect-before"),
                                            () -> reconcile("effect-before")));

            assertEquals(JobState.RECONCILIATION_REQUIRED, stopped.state());
            assertEquals(
                    EffectState.UNKNOWN,
                    journal.effect("job", task.sequence(), "http-mutation").state());
            assertEquals(1, posts.get());
            assertEquals(0, gets.get());

            mode.set(Mode.SUCCESS);
            assertEquals(
                    "effect-before",
                    context.effect(
                            task,
                            "http-mutation",
                            endpoint.toString(),
                            () -> apply("effect-before"),
                            () -> reconcile("effect-before")));

            assertEquals(2, posts.get());
            assertEquals(1, gets.get());
            assertEquals("effect-before", applied.get());
            assertEquals(
                    EffectState.SUCCEEDED,
                    journal.effect("job", task.sequence(), "http-mutation").state());
        }
    }

    @Test
    void fiveHundredAfterEffectReconcilesWithoutSecondPost() throws Exception {
        mode.set(Mode.AFTER_EFFECT_500);
        try (var journal = RuntimeTestFixtures.journal(directory)) {
            var policy = policy();
            var task = prepare(journal, policy);
            var context = context(journal, policy);

            var stopped =
                    assertThrows(
                            JobStopped.class,
                            () ->
                                    context.effect(
                                            task,
                                            "http-mutation",
                                            endpoint.toString(),
                                            () -> apply("effect-after"),
                                            () -> reconcile("effect-after")));

            assertEquals(JobState.RECONCILIATION_REQUIRED, stopped.state());
            assertEquals("effect-after", applied.get());
            assertEquals(1, posts.get());

            mode.set(Mode.SUCCESS);
            assertEquals(
                    "effect-after",
                    context.effect(
                            task,
                            "http-mutation",
                            endpoint.toString(),
                            () -> apply("effect-after"),
                            () -> reconcile("effect-after")));

            assertEquals(1, posts.get());
            assertEquals(1, gets.get());
            assertEquals(
                    EffectState.SUCCEEDED,
                    journal.effect("job", task.sequence(), "http-mutation").state());
        }
    }

    private ExecutionPolicy policy() {
        var settings =
                RuntimeTestFixtures.runtime(
                        true,
                        endpoint,
                        Set.of(endpoint.toString()),
                        Set.of("principal"),
                        1000,
                        1,
                        10);
        return new ExecutionPolicy(
                settings,
                RuntimeTestFixtures.toolkit(ToolkitProperties.Environment.LOCAL, directory, false),
                RuntimeTestFixtures.aws("123456789012"),
                fakeSts());
    }

    private JobContext context(SqliteJournal journal, ExecutionPolicy policy) throws Exception {
        var settings =
                RuntimeTestFixtures.runtime(
                        true,
                        endpoint,
                        Set.of(endpoint.toString()),
                        Set.of("principal"),
                        1000,
                        1,
                        10);
        return new JobContext(
                "job",
                new JobRequest(
                        "mutable-http-test",
                        json.readTree("{}"),
                        JobMode.EXECUTE,
                        "INC-HTTP-500",
                        "CHG-HTTP-500",
                        "validate mutable HTTP reconciliation",
                        true,
                        1,
                        100,
                        60,
                        1,
                        0,
                        1,
                        false,
                        false),
                journal,
                policy,
                RuntimeTestFixtures.limiter(1, 1000),
                new AtomicReference<>(),
                Set.of(endpoint.toString()),
                json,
                settings);
    }

    private SqliteJournal.Task prepare(SqliteJournal journal, ExecutionPolicy policy)
            throws Exception {
        journal.create("job", "{}", "1", policy.identity(), 1000);
        journal.page(
                "job",
                0,
                new Workflow.Page(List.of(new Workflow.Candidate("id", "{}")), "", true),
                1);
        journal.seal("job");
        journal.approve("job", SqliteJournal.now() + 60, "LAB mutable HTTP approval", false);
        return journal.pending("job", 1).getFirst();
    }

    private String apply(String key) {
        try {
            var response =
                    client.send(
                            HttpRequest.newBuilder(endpoint.resolve("/effects/" + key))
                                    .header("Idempotency-Key", key)
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) return response.body();
            if (response.statusCode() >= 500)
                throw new IllegalStateException("Mutable HTTP result is ambiguous");
            throw new IllegalStateException("Mutable HTTP effect was rejected");
        } catch (IOException failure) {
            throw new IllegalStateException("Mutable HTTP transport failed", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new JobStopped(JobState.INTERRUPTED);
        }
    }

    private Optional<String> reconcile(String key) throws Exception {
        var response =
                client.send(
                        HttpRequest.newBuilder(endpoint.resolve("/effects/" + key)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) return Optional.of(response.body());
        if (response.statusCode() == 404) throw new EffectConfirmedAbsent();
        return Optional.empty();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestURI().getPath().substring("/effects/".length());
        if ("GET".equals(exchange.getRequestMethod())) {
            gets.incrementAndGet();
            if (key.equals(applied.get())) send(exchange, 200, key);
            else send(exchange, 404, "missing");
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "method");
            return;
        }

        posts.incrementAndGet();
        switch (mode.get()) {
            case BEFORE_EFFECT_500 -> send(exchange, 500, "failed-before-effect");
            case AFTER_EFFECT_500 -> {
                applied.compareAndSet(null, key);
                send(exchange, 500, "failed-after-effect");
            }
            case SUCCESS -> {
                String current = applied.get();
                if (current == null) {
                    applied.compareAndSet(null, key);
                    current = applied.get();
                }
                if (key.equals(current)) send(exchange, 200, key);
                else send(exchange, 409, "different-idempotency-key");
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
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
}
