package com.example.ratelimiter;

import com.example.ratelimiter.service.RateLimitResult;
import com.example.ratelimiter.service.TokenBucketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ConcurrencyTest {

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

    /**
     * Fires 200 concurrent requests at a bucket with capacity 50.
     * The Lua EVAL is atomic, so exactly 50 must be allowed and 150 rejected —
     * no matter how the threads interleave on the JVM or the network.
     */
    @Test
    void exactlyCapacityAllowedUnderConcurrentLoad() throws InterruptedException {
        int capacity  = 50;
        int threads   = 200;
        // refillRate=1 token/sec: the test completes in well under 1s, so no refill occurs
        int refillRate = 1;
        String key = "concurrency-test-" + UUID.randomUUID();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);
        AtomicInteger allowed  = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await(); // all threads release simultaneously
                    RateLimitResult result = tokenBucketService.tryConsume(key, capacity, refillRate);
                    if (result.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads at once
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All threads should finish within 30s");
        pool.shutdown();

        assertEquals(capacity, allowed.get(),
                "Exactly %d requests should be allowed; got %d".formatted(capacity, allowed.get()));
        assertEquals(threads - capacity, rejected.get(),
                "Exactly %d requests should be rejected; got %d".formatted(threads - capacity, rejected.get()));
    }
}
