package io.github.awsopstoolkit.runtime;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/** Aggregate dispatch cap and AIMD for both request rate and effective concurrency. */
public final class DispatchLimiter {
    private final Semaphore permits;
    private final int rateCeiling;
    private final int workerCeiling;
    private final int healthyResponsesBeforeIncrease;
    private final int circuitFailuresBeforeOpen;
    private final long circuitOpenNanos;
    private final long retryBaseBackoffMillis;
    private int concurrencyCeiling;
    private int rate;
    private int concurrency;
    private int active;
    private int healthy;
    private int failures;
    private long openUntil;
    private long next;

    public DispatchLimiter(
            int workers,
            int ceiling,
            int healthyResponsesBeforeIncrease,
            int circuitFailuresBeforeOpen,
            Duration circuitOpenDuration,
            Duration retryBaseBackoff) {
        if (workers < 1 || ceiling < 1)
            throw new IllegalArgumentException("Dispatch limits must be positive");
        if (healthyResponsesBeforeIncrease < 1 || circuitFailuresBeforeOpen < 1)
            throw new IllegalArgumentException("Adaptive thresholds must be positive");
        if (circuitOpenDuration == null
                || circuitOpenDuration.isZero()
                || circuitOpenDuration.isNegative()
                || retryBaseBackoff == null
                || retryBaseBackoff.isZero()
                || retryBaseBackoff.isNegative())
            throw new IllegalArgumentException("Adaptive durations must be positive");
        permits = new Semaphore(workers, true);
        workerCeiling = workers;
        concurrencyCeiling = workers;
        rateCeiling = ceiling;
        concurrency = workers;
        rate = ceiling;
        this.healthyResponsesBeforeIncrease = healthyResponsesBeforeIncrease;
        this.circuitFailuresBeforeOpen = circuitFailuresBeforeOpen;
        circuitOpenNanos = circuitOpenDuration.toNanos();
        retryBaseBackoffMillis = Math.max(1, retryBaseBackoff.toMillis());
    }

    public void acquire(int requested) throws InterruptedException {
        permits.acquire();
        boolean adaptive = false;
        try {
            adaptiveAcquire();
            adaptive = true;
            awaitSlot(requested);
        } catch (InterruptedException | RuntimeException e) {
            if (adaptive) adaptiveRelease();
            permits.release();
            throw e;
        }
    }

    private synchronized void adaptiveAcquire() throws InterruptedException {
        while (active >= concurrency) wait();
        active++;
    }

    private synchronized void adaptiveRelease() {
        active--;
        notifyAll();
    }

    public void awaitSlot(int requested) throws InterruptedException {
        long delay;
        synchronized (this) {
            long now = System.nanoTime();
            if (now < openUntil) throw new JobStopped(JobState.PAUSED);
            long dispatch = Math.max(now, next);
            next = dispatch + 1_000_000_000L / Math.max(1, Math.min(requested, rate));
            delay = dispatch - now;
        }
        if (delay > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);
    }

    public void release() {
        adaptiveRelease();
        permits.release();
    }

    public synchronized void healthy() {
        failures = 0;
        if (++healthy >= healthyResponsesBeforeIncrease) {
            rate = Math.min(rateCeiling, rate + 1);
            concurrency = Math.min(concurrencyCeiling, concurrency + 1);
            healthy = 0;
        }
    }

    public synchronized void failure(boolean throttle) {
        healthy = 0;
        if (throttle) {
            rate = Math.max(1, rate / 2);
            concurrency = Math.max(1, concurrency / 2);
        }
        if (++failures >= circuitFailuresBeforeOpen) {
            openUntil = System.nanoTime() + circuitOpenNanos;
            failures = 0;
        }
    }

    public synchronized int currentRate() {
        return rate;
    }

    public synchronized int currentConcurrency() {
        return concurrency;
    }

    public synchronized int currentConcurrencyCeiling() {
        return concurrencyCeiling;
    }

    public synchronized void tuneConcurrency(int ceiling) {
        if (ceiling < 1 || ceiling > workerCeiling)
            throw new IllegalArgumentException("Configured concurrency ceiling exceeded");
        concurrencyCeiling = ceiling;
        concurrency = Math.min(concurrency, ceiling);
        healthy = 0;
        notifyAll();
    }

    public void backoff(int attempt) throws InterruptedException {
        int exponent = Math.max(0, Math.min(attempt, 20));
        long upperBound = Math.max(1, Math.multiplyExact(retryBaseBackoffMillis, 1L << exponent));
        Thread.sleep(ThreadLocalRandom.current().nextLong(upperBound));
    }
}
