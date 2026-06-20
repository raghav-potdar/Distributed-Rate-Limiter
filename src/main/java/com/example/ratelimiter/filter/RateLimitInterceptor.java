package com.example.ratelimiter.filter;

import com.example.ratelimiter.service.RateLimitResult;
import com.example.ratelimiter.service.TokenBucketService;
import com.example.ratelimiter.service.TokenBucketService.BucketConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final TokenBucketService tokenBucketService;

    public RateLimitInterceptor(TokenBucketService tokenBucketService) {
        this.tokenBucketService = tokenBucketService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String key = request.getHeader("X-API-Key");
        if (key == null || key.isBlank()) {
            key = request.getRemoteAddr();
        }

        BucketConfig config = tokenBucketService.getConfig(key);
        RateLimitResult result = tokenBucketService.tryConsume(key, config.capacity(), config.refillRate());

        response.setHeader("X-RateLimit-Limit", String.valueOf(config.capacity()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(result.remaining()));
        // Seconds until bucket is fully replenished from current level
        long resetSeconds = (long) Math.ceil(
                (config.capacity() - result.remaining()) / (double) config.refillRate());
        response.setHeader("X-RateLimit-Reset", String.valueOf(resetSeconds));

        if (result.allowed()) {
            return true;
        }

        response.setStatus(429);
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"rate limit exceeded\",\"retryAfter\":%d}".formatted(result.retryAfterSeconds()));
        return false;
    }
}
