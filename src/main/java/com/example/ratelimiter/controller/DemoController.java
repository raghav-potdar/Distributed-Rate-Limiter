package com.example.ratelimiter.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DemoController {

    @GetMapping("/api/resource")
    public Map<String, String> resource() {
        return Map.of("message", "ok");
    }
}
