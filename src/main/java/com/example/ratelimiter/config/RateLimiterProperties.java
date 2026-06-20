package com.example.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rate-limiter")
public class RateLimiterProperties {

    private Defaults defaults = new Defaults();

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public static class Defaults {
        private int capacity = 100;
        private int refillRate = 10;

        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }

        public int getRefillRate() { return refillRate; }
        public void setRefillRate(int refillRate) { this.refillRate = refillRate; }
    }
}
