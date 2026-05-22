package com.security.security.provider;

import com.security.security.config.AgentToolConfig;
import com.security.security.service.AppConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * OpenAI LLM Provider — reads api-key and model from AppConfigService (DB first).
 * Falls back to application.properties environment variables.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiProvider implements LlmProvider {

    private final AppConfigService configService;

    @Value("${llm.openai.api-key:}")
    private String defaultApiKey;

    @Override
    public String getProviderName() {
        return "openai";
    }

    private String resolveApiKey() {
        String dbKey = configService.getOrNull(AppConfigService.LLM_API_KEY_KEY);
        // Only use DB key if provider is openai
        String provider = configService.getOrNull(AppConfigService.LLM_PROVIDER_KEY);
        if ("openai".equalsIgnoreCase(provider) && dbKey != null && !dbKey.isBlank()) {
            return dbKey;
        }
        return defaultApiKey;
    }

    private String resolveModel() {
        String provider = configService.getOrNull(AppConfigService.LLM_PROVIDER_KEY);
        if ("openai".equalsIgnoreCase(provider)) {
            String dbModel = configService.getOrNull(AppConfigService.LLM_MODEL_KEY);
            if (dbModel != null && !dbModel.isBlank()) return dbModel;
        }
        return "gpt-4o-mini";
    }

    @Override
    public Flux<String> streamChat(String systemPrompt, String userMessage,
                                   AgentToolConfig tools, String conversationId) {
        String apiKey = resolveApiKey();
        String model = resolveModel();
        log.info("[OpenAiProvider] streamChat — model: {}, conversation: {}", model, conversationId);

        if (apiKey == null || apiKey.isBlank()) {
            return Flux.just("{\"error\": \"[OpenAI] API Key chưa được cấu hình. Vào Admin → Cài đặt AI → nhập OpenAI API Key.\"}");
        }

        // OpenAI via Spring AI — placeholder until spring-ai-openai dep is added
        return Flux.just("{\"summary\": \"[OpenAI " + model + "] Adapter sẵn sàng. Thêm spring-ai-starter-model-openai dependency để kích hoạt.\"}" );
    }

    @Override
    public String callChat(String systemPrompt, String userMessage,
                           String responseSchema, String conversationId) {
        String apiKey = resolveApiKey();
        String model = resolveModel();
        log.info("[OpenAiProvider] callChat — model: {}, conversation: {}", model, conversationId);

        if (apiKey == null || apiKey.isBlank()) {
            return "{\"error\": \"[OpenAI] API Key chưa được cấu hình.\"}";
        }

        return "{\"summary\": \"[OpenAI " + model + "] Ready. Add spring-ai-starter-model-openai to enable.\"}";
    }
}
