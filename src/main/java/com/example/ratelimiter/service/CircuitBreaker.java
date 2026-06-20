package com.example.ratelimiter.service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hand-rolled circuit breaker with three states:
 *
 *  CLOSED    — normal operation; every call reaches Redis.
 *  OPEN      — Redis is unhealthy; calls are short-circuited and the rate
 *               limiter fails open (requests are allowed through).
 *  HALF_OPEN — after resetTimeoutMs the circuit lets one probe call through;
 *               success → CLOSED, failure → OPEN.
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long resetTimeoutMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long openedAt = 0L;

    public CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs   = resetTimeoutMs;
    }

    /**
     * Returns true if the caller should proceed to Redis.
     * Transitions OPEN → HALF_OPEN when the reset timeout has elapsed.
     */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            if (System.currentTimeMillis() - openedAt >= resetTimeoutMs) {
                // Let exactly one probe through by transitioning to HALF_OPEN
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    return true;
                }
            }
            return false;
        }
        // HALF_OPEN: probe already in flight — block subsequent requests until probe resolves
        return false;
    }

    /**
     * Call after a successful Redis response.
     * Resets failure count and closes the circuit.
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
    }

    /**
     * Call after a Redis failure (exception or unexpected null response).
     * Trips the circuit to OPEN once the failure threshold is reached.
     */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)
                    || state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt = System.currentTimeMillis();
            }
        }
    }

    public State getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** Epoch millis when the circuit was last opened, or 0 if never opened. */
    public long getOpenedAt() {
        return openedAt;
    }
}
