package io.github.awsopstoolkit.aws;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounds concurrent logical SDK calls and their start rate; SDK retries remain inside each call.
 */
public final class AwsCallGate {
    private final Semaphore inFlight;
    private final long intervalNanos;
    private final Object rateLock = new Object();
    private long nextStartNanos;

    public AwsCallGate(int maxInFlight, double logicalCallsPerSecond) {
        if (maxInFlight < 1
                || !Double.isFinite(logicalCallsPerSecond)
                || logicalCallsPerSecond < 0.01
                || logicalCallsPerSecond > 1_000_000) {
            throw new IllegalArgumentException("Invalid concurrency or rate limit");
        }
        inFlight = new Semaphore(maxInFlight, true);
        intervalNanos = (long) Math.ceil(1_000_000_000.0 / logicalCallsPerSecond);
        nextStartNanos = System.nanoTime();
    }

    public <T> T call(Supplier<T> request) throws InterruptedException {
        inFlight.acquire();
        try {
            while (true) {
                long delay;
                synchronized (rateLock) {
                    long now = System.nanoTime();
                    delay = Math.max(0L, nextStartNanos - now);
                    if (delay == 0) {
                        nextStartNanos = now + intervalNanos;
                        break;
                    }
                }
                TimeUnit.NANOSECONDS.sleep(delay);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("AWS call interrupted before dispatch");
            }
            return request.get();
        } finally {
            inFlight.release();
        }
    }
}
