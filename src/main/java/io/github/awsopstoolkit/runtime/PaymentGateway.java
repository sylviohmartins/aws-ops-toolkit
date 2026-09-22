package io.github.awsopstoolkit.runtime;

import io.github.awsopstoolkit.configuration.HttpProperties;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;

/**
 * Read-only HTTP: bounded body, configured attempt budget, end-to-end deadline and no redirects.
 */
public final class PaymentGateway implements AutoCloseable {
    private final HttpClient client;
    private final URI base;
    private final Duration timeout;
    private final int maxAttempts;
    private final Duration maxRetryAfter;
    private final long maxResponseBodyBytes;

    public PaymentGateway(HttpProperties http, RuntimeProperties runtime) {
        this(http, runtime, http.responseTimeout());
    }

    PaymentGateway(HttpProperties http, RuntimeProperties runtime, Duration timeout) {
        this(http, runtime, timeout, http.connectTimeout());
    }

    private PaymentGateway(
            HttpProperties http,
            RuntimeProperties runtime,
            Duration timeout,
            Duration connectTimeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Invalid HTTP response timeout");
        this.timeout = timeout;
        this.maxAttempts = http.maxAttempts();
        this.maxRetryAfter = http.maxRetryAfter();
        this.maxResponseBodyBytes = http.maxResponseBody().toBytes();
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        base = http.paymentEndpoint();
        if (base == null) return;
        boolean local =
                runtime.labEndpoint() != null
                        && java.util.Set.of("127.0.0.1", "localhost").contains(base.getHost());
        if ((!"https".equals(base.getScheme()) && !local)
                || !http.paymentHosts().contains(base.getHost())
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null)
            throw new IllegalArgumentException("Invalid payment origin");
    }

    public JsonNode get(JobContext context, String reference) throws Exception {
        if (base == null || !reference.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid payment integration");
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            HttpResponse<byte[]> response;
            try {
                io.micrometer.core.instrument.Metrics.counter("toolkit.http.requests").increment();
                response = context.read(base.toString(), () -> fetch(reference, context.id()));
            } catch (UncheckedIOException e) {
                context.externalFailure(false);
                if (attempt == maxAttempts - 1) throw new JobStopped(JobState.PAUSED);
                context.externalRetry();
                io.micrometer.core.instrument.Metrics.counter("toolkit.http.retries").increment();
                context.backoff(attempt);
                continue;
            }
            int status = response.statusCode();
            io.micrometer.core.instrument.Metrics.counter(
                            "toolkit.http.responses", "status", Integer.toString(status))
                    .increment();
            if (status == 401) throw new JobStopped(JobState.AUTHENTICATION_REQUIRED);
            if (status == 403) throw new JobStopped(JobState.AUTHORIZATION_REQUIRED);
            if (status == 429 || status >= 500) {
                context.externalFailure(status == 429);
                if (attempt == maxAttempts - 1) throw new JobStopped(JobState.PAUSED);
                long seconds = retryAfter(response.headers().firstValue("Retry-After").orElse("0"));
                if (Duration.ofSeconds(seconds).compareTo(maxRetryAfter) > 0)
                    throw new JobStopped(JobState.PAUSED);
                context.externalRetry();
                io.micrometer.core.instrument.Metrics.counter("toolkit.http.retries").increment();
                if (seconds > 0) Thread.sleep(Duration.ofSeconds(seconds));
                else context.backoff(attempt);
                continue;
            }
            if (status != 200) throw new IllegalArgumentException("Payment response rejected");
            var result = context.json.readTree(response.body());
            if (!reference.equals(result.path("reference").asText())
                    || !result.path("status").isTextual()
                    || !result.path("version").canConvertToLong()
                    || result.path("version").asLong() < 0)
                throw new IllegalArgumentException("Invalid payment contract");
            return result;
        }
        throw new IllegalStateException("Attempt budget exhausted");
    }

    private HttpResponse<byte[]> fetch(String reference, String job) {
        var builder =
                HttpRequest.newBuilder(base.resolve("/payments/" + reference))
                        .timeout(timeout)
                        .header("X-Correlation-ID", job)
                        .GET();
        String token = System.getenv("TOOLKIT_PAYMENT_TOKEN");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        var future =
                client.sendAsync(builder.build(), info -> new LimitedBody(maxResponseBodyBytes));
        try {
            return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new JobStopped(JobState.INTERRUPTED);
        } catch (TimeoutException | ExecutionException e) {
            future.cancel(true);
            throw new UncheckedIOException(new IOException("Payment transport failed"));
        }
    }

    static long retryAfter(String value) {
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            try {
                return Math.max(
                        0,
                        Duration.between(
                                        Instant.now(),
                                        ZonedDateTime.parse(
                                                        value, DateTimeFormatter.RFC_1123_DATE_TIME)
                                                .toInstant())
                                .toSeconds());
            } catch (java.time.format.DateTimeParseException invalid) {
                return 0;
            }
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long maxBytes;
        private Flow.Subscription subscription;

        private LimitedBody(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("Response body limit"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
