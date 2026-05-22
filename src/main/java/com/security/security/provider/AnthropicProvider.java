package com.security.security.provider;

import com.security.security.config.AgentToolConfig;
import com.security.security.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Anthropic LLM Provider — reads api-key and model from AppConfigService (DB first).
 * Falls back to application.properties environment variables.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnthropicProvider implements LlmProvider {

    private final AppConfigService configService;

    @Value("${llm.anthropic.api-key:}")
    private String defaultApiKey;

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    private String resolveApiKey() {
        String provider = configService.getOrNull(AppConfigService.LLM_PROVIDER_KEY);
        if ("anthropic".equalsIgnoreCase(provider)) {
            String dbKey = configService.getOrNull(AppConfigService.LLM_API_KEY_KEY);
            if (dbKey != null && !dbKey.isBlank()) return dbKey;
        }
        return defaultApiKey;
    }

    private String resolveModel() {
        String provider = configService.getOrNull(AppConfigService.LLM_PROVIDER_KEY);
        if ("anthropic".equalsIgnoreCase(provider)) {
            String dbModel = configService.getOrNull(AppConfigService.LLM_MODEL_KEY);
            if (dbModel != null && !dbModel.isBlank()) return dbModel;
        }
        return "claude-3-5-haiku-latest";
    }

    @Override
    public Flux<String> streamChat(String systemPrompt, String userMessage,
                                   AgentToolConfig tools, String conversationId) {
        String apiKey = resolveApiKey();
        String model = resolveModel();
        log.info("[AnthropicProvider] streamChat — model: {}, conversation: {}", model, conversationId);

        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just("{\"error\": \"[Anthropic] API Key chưa được cấu hình. Vào Admin → Cài đặt AI → nhập Anthropic API Key.\"}");
        }

        return Flux.just("{\"summary\": \"[Anthropic " + model + "] Adapter sẵn sàng. Thêm spring-ai-starter-model-anthropic dependency để kích hoạt.\"}" );
    }

    @Override
    public String callChat(String systemPrompt, String userMessage,
                           String responseSchema, String conversationId) {
        String apiKey = resolveApiKey();
        String model = resolveModel();
        log.info("[AnthropicProvider] callChat — model: {}, conversation: {}", model, conversationId);

        if (apiKey == null || apiKey.isBlank()) {
            return "{\"error\": \"[Anthropic] API Key chưa được cấu hình.\"}";
        }

        return "{\"summary\": \"[Anthropic " + model + "] Ready. Add spring-ai-starter-model-anthropic to enable.\"}";
    }
}
