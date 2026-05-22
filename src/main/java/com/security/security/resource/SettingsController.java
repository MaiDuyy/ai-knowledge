package com.security.security.resource;

import com.security.security.dto.UpdateConfigRequest;
import com.security.security.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing AI system settings (API keys, models, RAG config).
 * Mirrors Arkon's /api/settings endpoints pattern.
 *
 * Endpoints:
 *   GET  /api/settings         — get all config (sensitive values masked)
 *   PATCH /api/settings        — batch update config values
 *   GET  /api/settings/llm/catalog — list available LLM models
 */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private final AppConfigService configService;

    /**
     * GET /api/settings
     * Returns all config values safe for UI display (sensitive fields masked).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        log.info("[SettingsController] Fetching all settings for UI");
        Map<String, Object> settings = configService.getAllForUi();
        return ResponseEntity.ok(settings);
    }

    /**
     * PATCH /api/settings
     * Batch update config values. Masked values are ignored (not overwritten).
     */
    @PatchMapping
    public ResponseEntity<Map<String, Object>> updateSettings(
            @RequestBody UpdateConfigRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "admin") String userId) {

        log.info("[SettingsController] Updating settings by user: {}, keys: {}", userId, request.settings().keySet());
        Map<String, Boolean> results = configService.setBatch(request.settings());

        long failed = results.values().stream().filter(v -> !v).count();
        if (failed > 0) {
            log.warn("[SettingsController] {} config key(s) failed to save", failed);
        }

        // Return refreshed settings after save
        Map<String, Object> updated = configService.getAllForUi();
        updated.put("_saveResults", results);
        return ResponseEntity.ok(updated);
    }

    /**
     * GET /api/settings/llm/catalog
     * Returns available LLM model catalog for the UI model picker.
     */
    @GetMapping("/llm/catalog")
    public ResponseEntity<List<Map<String, Object>>> getLlmCatalog() {
        return ResponseEntity.ok(AppConfigService.LLM_CATALOG);
    }

    /**
     * POST /api/settings/llm/switch
     * Convenience endpoint to switch active LLM model.
     * Body: { "modelId": "gemini-2.0-flash", "provider": "gemini" }
     */
    @PostMapping("/llm/switch")
    public ResponseEntity<Map<String, Object>> switchLlmModel(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "x-user-id", defaultValue = "admin") String userId) {

        String modelId = body.get("modelId");
        String provider = body.get("provider");

        if (modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "modelId is required"));
        }

        log.info("[SettingsController] Switching LLM model to: {} (provider: {}) by user: {}", modelId, provider, userId);

        configService.set(AppConfigService.LLM_MODEL_KEY, modelId);
        if (provider != null && !provider.isBlank()) {
            configService.set(AppConfigService.LLM_PROVIDER_KEY, provider);
        }

        return ResponseEntity.ok(Map.of(
                "message", "LLM model switched successfully",
                "activeModel", modelId,
                "provider", provider != null ? provider : ""
        ));
    }

    /**
     * GET /api/settings/llm/active
     * Returns the currently active LLM model info.
     */
    @GetMapping("/llm/active")
    public ResponseEntity<Map<String, Object>> getActiveLlmModel() {
        String model = configService.getOrNull(AppConfigService.LLM_MODEL_KEY);
        String provider = configService.getOrNull(AppConfigService.LLM_PROVIDER_KEY);

        // fallback to application.properties values
        if (model == null) model = "gemini-2.0-flash";
        if (provider == null) provider = "gemini";

        return ResponseEntity.ok(Map.of(
                "activeModel", model,
                "provider", provider,
                "apiKeyConfigured", configService.getOrNull(AppConfigService.LLM_API_KEY_KEY) != null
        ));
    }
}
