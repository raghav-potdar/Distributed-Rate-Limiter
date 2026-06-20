package com.example.ratelimiter.controller;

import com.example.ratelimiter.service.TokenBucketService;
import com.example.ratelimiter.service.TokenBucketService.BucketConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/limits")
public class AdminController {

    private final TokenBucketService tokenBucketService;

    public AdminController(TokenBucketService tokenBucketService) {
        this.tokenBucketService = tokenBucketService;
    }

    @GetMapping("/{key}")
    public ResponseEntity<Map<String, Object>> getLimit(@PathVariable String key) {
        BucketConfig config = tokenBucketService.getConfig(key);
        Map<Object, Object> state = tokenBucketService.getBucketState(key);

        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("capacity", config.capacity());
        result.put("refillRate", config.refillRate());
        result.put("tokens", state.get("tokens"));
        result.put("lastRefillTimestamp", state.get("lastRefillTimestamp"));
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> setLimit(
            @PathVariable String key,
            @RequestBody LimitRequest body) {
        tokenBucketService.setConfig(key, body.capacity(), body.refillRate());

        Map<String, Object> result = new HashMap<>();
        result.put("key", key);
        result.put("capacity", body.capacity());
        result.put("refillRate", body.refillRate());
        result.put("status", "updated");
        return ResponseEntity.ok(result);
    }

    public record LimitRequest(int capacity, int refillRate) {}
}
