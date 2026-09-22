package io.github.awsopstoolkit.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DispatchLimiterTest {
    @Test
    void throttlingReducesRateAndConcurrencyAndHealthyTrafficRecoversGradually() {
        var limiter =
                new DispatchLimiter(8, 100, 20, 5, Duration.ofSeconds(10), Duration.ofMillis(100));
        assertEquals(8, limiter.currentConcurrency());
        assertEquals(100, limiter.currentRate());

        limiter.failure(true);
        assertEquals(4, limiter.currentConcurrency());
        assertEquals(50, limiter.currentRate());

        for (int i = 0; i < 20; i++) limiter.healthy();
        assertEquals(5, limiter.currentConcurrency());
        assertEquals(51, limiter.currentRate());

        limiter.tuneConcurrency(2);
        assertEquals(2, limiter.currentConcurrency());
        assertEquals(2, limiter.currentConcurrencyCeiling());

        limiter.tuneConcurrency(8);
        assertEquals(2, limiter.currentConcurrency());
        assertEquals(8, limiter.currentConcurrencyCeiling());
        for (int i = 0; i < 20; i++) limiter.healthy();
        assertEquals(3, limiter.currentConcurrency());
    }
}
