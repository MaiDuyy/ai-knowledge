package com.security.security.resource;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthCheckController {

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new LinkedHashMap<>();

        response.put("status", "ok");
        response.put("service", "ai-knowledge");
        // Instant.now().toString() sẽ tạo ra chuỗi thời gian chuẩn ISO 8601
        // giống hệt new Date().toISOString() trong JavaScript
        response.put("timestamp", Instant.now().toString());

        return ResponseEntity.ok(response);
    }
}