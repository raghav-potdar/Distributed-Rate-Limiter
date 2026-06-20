package com.example.ratelimiter;

import com.example.ratelimiter.service.CircuitBreaker;
import com.example.ratelimiter.service.CircuitBreaker.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests — no Spring context, no Redis.
 */
class CircuitBreakerTest {

    private CircuitBreaker cb;

    @BeforeEach
    void setUp() {
        // threshold=3, resetTimeout=100ms for fast tests
        cb = new CircuitBreaker(3, 100);
    }

    @Test
    void startsInClosedState() {
        assertEquals(State.CLOSED, cb.getState());
        assertTrue(cb.allowRequest());
    }

    @Test
    void tripsToOpenAfterFailureThreshold() {
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(State.CLOSED, cb.getState(), "Should still be CLOSED before threshold");

        cb.recordFailure(); // 3rd failure — trips
        assertEquals(State.OPEN, cb.getState());
        assertFalse(cb.allowRequest(), "Should block requests when OPEN");
    }

    @Test
    void blocksAllRequestsWhenOpen() {
        tripCircuit();
        for (int i = 0; i < 10; i++) {
            assertFalse(cb.allowRequest(), "All requests should be blocked when OPEN");
        }
    }

    @Test
    void transitionsToHalfOpenAfterResetTimeout() throws InterruptedException {
        tripCircuit();
        assertFalse(cb.allowRequest());

        Thread.sleep(110); // wait past the 100ms reset timeout

        assertTrue(cb.allowRequest(), "One probe should be allowed after reset timeout");
        assertEquals(State.HALF_OPEN, cb.getState());
    }

    @Test
    void closesAfterSuccessfulProbe() throws InterruptedException {
        tripCircuit();
        Thread.sleep(110);

        assertTrue(cb.allowRequest()); // probe goes through, state → HALF_OPEN
        cb.recordSuccess();

        assertEquals(State.CLOSED, cb.getState());
        assertTrue(cb.allowRequest());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    void reopensAfterFailedProbe() throws InterruptedException {
        tripCircuit();
        Thread.sleep(110);

        cb.allowRequest(); // probe → HALF_OPEN
        cb.recordFailure(); // probe fails → back to OPEN

        assertEquals(State.OPEN, cb.getState());
        assertFalse(cb.allowRequest());
    }

    @Test
    void successResetsFailureCount() {
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();

        assertEquals(0, cb.getConsecutiveFailures());
        assertEquals(State.CLOSED, cb.getState());

        // Threshold still applies from zero after reset
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(State.CLOSED, cb.getState());
        cb.recordFailure();
        assertEquals(State.OPEN, cb.getState());
    }

    @Test
    void halfOpenBlocksSubsequentRequestsUntilProbeResolves() throws InterruptedException {
        tripCircuit();
        Thread.sleep(110);

        assertTrue(cb.allowRequest());  // first call → probe → HALF_OPEN
        assertFalse(cb.allowRequest()); // subsequent calls blocked while probe is in flight
        assertFalse(cb.allowRequest());
    }

    @Test
    void openedAtIsSetWhenCircuitTrips() {
        assertEquals(0L, cb.getOpenedAt());
        tripCircuit();
        assertTrue(cb.getOpenedAt() > 0);
    }

    // ── helper ──────────────────────────────────────────────────────────────

    private void tripCircuit() {
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(State.OPEN, cb.getState());
    }
}
