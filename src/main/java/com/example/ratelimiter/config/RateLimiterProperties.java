package com.example.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rate-limiter")
public class RateLimiterProperties {

    private Defaults defaults = new Defaults();
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();

    public Defaults getDefaults() { return defaults; }
    public void setDefaults(Defaults defaults) { this.defaults = defaults; }

    public CircuitBreakerConfig getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreakerConfig circuitBreaker) { this.circuitBreaker = circuitBreaker; }

    public static class Defaults {
        private int capacity = 100;
        private int refillRate = 10;

        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }

        public int getRefillRate() { return refillRate; }
        public void setRefillRate(int refillRate) { this.refillRate = refillRate; }
    }

    public static class CircuitBreakerConfig {
        private int failureThreshold = 5;
        private long resetTimeoutMs = 30_000;

        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }

        public long getResetTimeoutMs() { return resetTimeoutMs; }
        public void setResetTimeoutMs(long resetTimeoutMs) { this.resetTimeoutMs = resetTimeoutMs; }
    }
}
