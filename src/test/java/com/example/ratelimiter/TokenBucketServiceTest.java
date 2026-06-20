package com.example.ratelimiter;

import com.example.ratelimiter.service.RateLimitResult;
import com.example.ratelimiter.service.TokenBucketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class TokenBucketServiceTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TokenBucketService tokenBucketService;

    private String testKey;

    @BeforeEach
    void setUp() {
        testKey = "test-" + UUID.randomUUID();
    }

    @Test
    void allowsRequestsUpToCapacity() {
        int capacity = 5;
        for (int i = 0; i < capacity; i++) {
            RateLimitResult result = tokenBucketService.tryConsume(testKey, capacity, 1);
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void rejectsRequestOverCapacity() {
        int capacity = 3;
        for (int i = 0; i < capacity; i++) {
            tokenBucketService.tryConsume(testKey, capacity, 1);
        }

        RateLimitResult result = tokenBucketService.tryConsume(testKey, capacity, 1);
        assertFalse(result.allowed(), "Request over capacity should be rejected");
        assertEquals(0, result.remaining());
        assertTrue(result.retryAfterSeconds() > 0);
    }

    @Test
    void remainingTokensDecreaseCorrectly() {
        int capacity = 5;

        RateLimitResult first = tokenBucketService.tryConsume(testKey, capacity, 1);
        assertTrue(first.allowed());
        assertEquals(capacity - 1, first.remaining());

        RateLimitResult second = tokenBucketService.tryConsume(testKey, capacity, 1);
        assertTrue(second.allowed());
        assertEquals(capacity - 2, second.remaining());
    }

    @Test
    void refillsTokensAfterWaiting() throws InterruptedException {
        int capacity = 2;
        int refillRate = 100; // 100 tokens/sec — 10ms to refill 1 token

        // Drain the bucket
        tokenBucketService.tryConsume(testKey, capacity, refillRate);
        tokenBucketService.tryConsume(testKey, capacity, refillRate);

        RateLimitResult rejected = tokenBucketService.tryConsume(testKey, capacity, refillRate);
        assertFalse(rejected.allowed(), "Bucket should be empty");

        // Wait 20ms — at 100 tokens/sec, 2 tokens refill in 20ms
        Thread.sleep(20);

        RateLimitResult refilled = tokenBucketService.tryConsume(testKey, capacity, refillRate);
        assertTrue(refilled.allowed(), "Should be allowed after refill");
    }
}
