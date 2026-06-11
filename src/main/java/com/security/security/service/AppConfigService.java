package com.security.security.service;

import com.security.security.entity.AppConfig;
import com.security.security.repository.AppConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * Dynamic Config Service — DB > application.properties > default.
 * Reads settings from app_config table first, falls back to env/defaults.
 * Sensitive values (API keys, tokens) are encrypted at rest with AES-256.
 *
 * <p>Port of Arkon's config_service.py, adapted for Spring Boot / JPA.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppConfigService {

    // ── Config key constants ─────────────────────────────────────────────────
    public static final String LLM_PROVIDER_KEY       = "llm_provider";       // gemini | openai | anthropic
    public static final String LLM_API_KEY_KEY        = "llm_api_key";        // encrypted
    public static final String LLM_MODEL_KEY          = "llm_model";          // e.g. gemini-2.0-flash
    public static final String LLM_BASE_URL_KEY       = "llm_base_url";       // custom endpoint (optional)

    public static final String EMBEDDING_PROVIDER_KEY = "embedding_provider"; // google | openai
    public static final String EMBEDDING_API_KEY_KEY  = "embedding_api_key";  // encrypted
    public static final String EMBEDDING_MODEL_KEY    = "embedding_model";
    public static final String EMBEDDING_BASE_URL_KEY = "embedding_base_url";

    public static final String VISION_PROVIDER_KEY    = "vision_provider";
    public static final String VISION_API_KEY_KEY     = "vision_api_key";     // encrypted
    public static final String VISION_MODEL_KEY       = "vision_model";
    public static final String VISION_BASE_URL_KEY    = "vision_base_url";

    public static final String MRP_AUTO_APPROVE_KEY   = "mrp_auto_approve";   // true | false
    public static final String CHUNK_SIZE_KEY         = "chunk_size";         // integer
    public static final String CHUNK_OVERLAP_KEY      = "chunk_overlap";      // integer
    public static final String SESSION_TIMEOUT_KEY    = "session_timeout_minutes";

    /** Keys whose values must be encrypted when stored. */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            LLM_API_KEY_KEY,
            EMBEDDING_API_KEY_KEY,
            VISION_API_KEY_KEY
    );

    /** All UI-configurable keys. */
    public static final List<String> ALL_CONFIG_KEYS = List.of(
            LLM_PROVIDER_KEY, LLM_API_KEY_KEY, LLM_MODEL_KEY, LLM_BASE_URL_KEY,
            EMBEDDING_PROVIDER_KEY, EMBEDDING_API_KEY_KEY, EMBEDDING_MODEL_KEY, EMBEDDING_BASE_URL_KEY,
            VISION_PROVIDER_KEY, VISION_API_KEY_KEY, VISION_MODEL_KEY, VISION_BASE_URL_KEY,
            MRP_AUTO_APPROVE_KEY, CHUNK_SIZE_KEY, CHUNK_OVERLAP_KEY, SESSION_TIMEOUT_KEY
    );

    // ── Available LLM models catalog ─────────────────────────────────────────
    public static final List<Map<String, Object>> LLM_CATALOG = List.of(
            Map.of("id", "gemini-2.0-flash", "provider", "gemini", "label", "Gemini 2.0 Flash",
                    "description", "Fast, efficient multimodal model", "recommended", true),
            Map.of("id", "gemini-2.5-flash-preview-05-20", "provider", "gemini", "label", "Gemini 2.5 Flash Preview",
                    "description", "Latest preview with extended context", "recommended", false),
            Map.of("id", "gemini-1.5-pro", "provider", "gemini", "label", "Gemini 1.5 Pro",
                    "description", "High-quality with 2M context window", "recommended", false),
            Map.of("id", "gpt-4o", "provider", "openai", "label", "GPT-4o",
                    "description", "OpenAI's most capable multimodal model", "recommended", false),
            Map.of("id", "gpt-4o-mini", "provider", "openai", "label", "GPT-4o Mini",
                    "description", "Affordable and intelligent small model", "recommended", false),
            Map.of("id", "gpt-3.5-turbo", "provider", "openai", "label", "GPT-3.5 Turbo",
                    "description", "Fast and cost-effective for simple tasks", "recommended", false)
    );

    // ── Dependencies ─────────────────────────────────────────────────────────
    private final AppConfigRepository configRepository;
    private final Environment environment;

    @Value("${jwt.secret:change-me-in-production-at-least-32-chars}")
    private String secretKey;

    private SecretKeySpec aesKey;

    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // in bits

    @PostConstruct
    private void initKey() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(secretKey.getBytes(StandardCharsets.UTF_8));
            aesKey = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive AES key for config encryption", e);
        }
    }

    // ── Encryption ───────────────────────────────────────────────────────────

    private String encrypt(String value) {
        try {
            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, parameterSpec);
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            // Combine IV and Ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("[AppConfigService] Encryption failed", e);
            throw new RuntimeException("Failed to encrypt config value", e);
        }
    }

    private String decrypt(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length > GCM_IV_LENGTH) {
                // Try GCM decryption
                try {
                    Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
                    byte[] iv = new byte[GCM_IV_LENGTH];
                    System.arraycopy(decoded, 0, iv, 0, GCM_IV_LENGTH);
                    int ciphertextLen = decoded.length - GCM_IV_LENGTH;
                    byte[] ciphertext = new byte[ciphertextLen];
                    System.arraycopy(decoded, GCM_IV_LENGTH, ciphertext, 0, ciphertextLen);

                    GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
                    cipher.init(Cipher.DECRYPT_MODE, aesKey, parameterSpec);
                    return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
                } catch (Exception gcmException) {
                    log.debug("[AppConfigService] GCM Decryption failed, falling back to ECB", gcmException);
                }
            }

            // Fallback to old ECB decryption
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[AppConfigService] Decryption failed — returning raw value (key may have changed)");
            return value;
        }
    }

    private boolean isSensitive(String key) {
        return SENSITIVE_KEYS.contains(key);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /**
     * Get a config value. Priority: DB > application.properties > default.
     */
    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        // 1. DB first
        Optional<AppConfig> row = configRepository.findByConfigKey(key);
        if (row.isPresent() && row.get().getConfigValue() != null && !row.get().getConfigValue().isBlank()) {
            String value = row.get().getConfigValue();
            if (isSensitive(key)) {
                value = decrypt(value);
            }
            return Optional.of(value);
        }

        // 2. application.properties / env
        String envKey = key.replace("_", ".");
        String envValue = environment.getProperty(envKey);
        if (envValue == null) {
            // try uppercase underscore env var (e.g. LLM_API_KEY)
            envValue = environment.getProperty(key.toUpperCase());
        }
        if (envValue != null && !envValue.isBlank()) {
            return Optional.of(envValue);
        }

        return Optional.empty();
    }

    /** Convenience: returns null if not found. */
    @Transactional(readOnly = true)
    public String getOrNull(String key) {
        return get(key).orElse(null);
    }

    // ── Write ────────────────────────────────────────────────────────────────

    @Transactional
    public void set(String key, String value) {
        String storeValue = value;
        if (isSensitive(key) && value != null && !value.isBlank()) {
            storeValue = encrypt(value);
        }

        AppConfig config = configRepository.findByConfigKey(key)
                .orElse(AppConfig.builder().configKey(key).build());
        config.setConfigValue(storeValue);
        configRepository.save(config);
        log.info("[AppConfigService] Saved config key: {}", key);
    }

    /**
     * Batch update. Skips masked values (user did not change them).
     * Returns map of key → success.
     */
    @Transactional
    public Map<String, Boolean> setBatch(Map<String, String> updates) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!ALL_CONFIG_KEYS.contains(key)) {
                results.put(key, false);
                continue;
            }
            // Skip masked values (unchanged sensitive fields from UI)
            if (value != null && value.startsWith("••••")) {
                results.put(key, true);
                continue;
            }
            try {
                set(key, value);
                results.put(key, true);
            } catch (Exception e) {
                log.error("[AppConfigService] Failed to save key: {}", key, e);
                results.put(key, false);
            }
        }
        return results;
    }

    // ── Bulk read for UI ─────────────────────────────────────────────────────

    /**
     * Get all config values, masking sensitive ones for safe UI display.
     * Sensitive keys will have: masked value + _configured boolean.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getAllForUi() {
        Map<String, Object> ui = new LinkedHashMap<>();
        for (String key : ALL_CONFIG_KEYS) {
            Optional<String> value = get(key);
            if (isSensitive(key)) {
                if (value.isPresent() && !value.get().isBlank()) {
                    String v = value.get();
                    // Mask: show only last 4 chars with bullets
                    if (v.length() > 8) {
                        ui.put(key, "••••••••" + v.substring(v.length() - 4));
                    } else {
                        ui.put(key, "•".repeat(v.length()));
                    }
                    ui.put(key + "_configured", true);
                } else {
                    ui.put(key, null);
                    ui.put(key + "_configured", false);
                }
            } else {
                ui.put(key, value.orElse(null));
            }
        }
        return ui;
    }
}
