package com.example.ratelimiter.service;

public record RateLimitResult(boolean allowed, long remaining, long retryAfterSeconds, boolean degraded) {

    /** Convenience constructor for normal (non-degraded) results. */
    public RateLimitResult(boolean allowed, long remaining, long retryAfterSeconds) {
        this(allowed, remaining, retryAfterSeconds, false);
    }

    /** Returned when the circuit breaker is OPEN — allow the request through. */
    public static RateLimitResult failOpen(int capacity) {
        return new RateLimitResult(true, capacity, 0, true);
    }
}
