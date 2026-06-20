package com.example.ratelimiter.service;

import com.example.ratelimiter.config.RateLimiterProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenBucketService {

    static final String REDIS_KEY_PREFIX = "rate_limit:";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> script;
    private final RateLimiterProperties properties;
    private final ConcurrentHashMap<String, BucketConfig> overrides = new ConcurrentHashMap<>();
    private final CircuitBreaker circuitBreaker;

    public record BucketConfig(int capacity, int refillRate) {}

    public TokenBucketService(StringRedisTemplate redisTemplate, RateLimiterProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties    = properties;
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        script.setResultType(List.class);

        RateLimiterProperties.CircuitBreakerConfig cb = properties.getCircuitBreaker();
        this.circuitBreaker = new CircuitBreaker(cb.getFailureThreshold(), cb.getResetTimeoutMs());
    }

    @SuppressWarnings("unchecked")
    public RateLimitResult tryConsume(String key, int capacity, int refillRate) {
        if (!circuitBreaker.allowRequest()) {
            return RateLimitResult.failOpen(capacity);
        }

        try {
            long now = System.currentTimeMillis();
            List<Long> result = (List<Long>) redisTemplate.execute(
                    script,
                    List.of(REDIS_KEY_PREFIX + key),
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(now)
            );

            if (result == null || result.isEmpty()) {
                circuitBreaker.recordFailure();
                return RateLimitResult.failOpen(capacity);
            }

            circuitBreaker.recordSuccess();
            boolean allowed = result.get(0) == 1L;
            long remaining  = result.get(1);
            long retryAfter = result.get(2);
            return new RateLimitResult(allowed, remaining, retryAfter);

        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return RateLimitResult.failOpen(capacity);
        }
    }

    public BucketConfig getConfig(String key) {
        return overrides.getOrDefault(key, new BucketConfig(
                properties.getDefaults().getCapacity(),
                properties.getDefaults().getRefillRate()
        ));
    }

    public void setConfig(String key, int capacity, int refillRate) {
        overrides.put(key, new BucketConfig(capacity, refillRate));
    }

    public Map<Object, Object> getBucketState(String key) {
        return redisTemplate.opsForHash().entries(REDIS_KEY_PREFIX + key);
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
