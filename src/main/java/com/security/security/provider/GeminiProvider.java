package com.security.security.provider;

import com.google.genai.Client;
import com.security.security.config.AgentToolConfig;
import com.security.security.service.AppConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
//import org.springframework.ai.google.genai.GoogleGenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Gemini LLM Provider — reads model and api-key from AppConfigService (DB first).
 * Falls back to auto-configured ChatClient when no DB override exists.
 *
 * Priority chain:
 *   DB (app_config table) → application.properties → Spring AI default
 */
@Component
@Slf4j
public class GeminiProvider implements LlmProvider {

    /** Auto-configured by Spring AI (uses application.properties api-key). */
    private final ChatClient defaultChatClient;
    private final ChatMemory chatMemory;
    private final AppConfigService configService;

    @Value("${spring.ai.google.genai.api-key:}")
    private String defaultApiKey;

    @Value("${spring.ai.google.genai.chat.options.model:gemini-2.0-flash}")
    private String defaultModel;

    public GeminiProvider(ChatClient chatClient,
                          ChatMemory chatMemory,
                          AppConfigService configService) {
        this.defaultChatClient = chatClient;
        this.chatMemory = chatMemory;
        this.configService = configService;
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    // ── Dynamic config resolution ─────────────────────────────────────────────

    /** Effective model: DB → application.properties → hardcoded default. */
    private String resolveModel() {
        String dbModel = configService.getOrNull(AppConfigService.LLM_MODEL_KEY);
        return (dbModel != null && !dbModel.isBlank()) ? dbModel : defaultModel;
    }

    /**
     * Effective ChatClient: if DB has a different api-key, build a fresh
     * GoogleGenAiChatModel wired with that key. Otherwise reuse the
     * auto-configured bean (no extra bean allocation per call).
     */
    private ChatClient resolveClient(String model) {
        String dbKey = configService.getOrNull(AppConfigService.LLM_API_KEY_KEY);
        boolean useDbKey = dbKey != null && !dbKey.isBlank() && !dbKey.equals(defaultApiKey);

        if (useDbKey) {
            log.debug("[GeminiProvider] Using DB api-key override for model: {}", model);

            // 1. Create the native Google Gen AI Client with the DB key
            Client genAiClient = Client.builder()
                    .apiKey(dbKey)
                    .build();

            // 2. Build the chat options
            GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                    .model(model)
                    .temperature(0.5)
                    .build();

            // 3. Build the chat model using the model builder
            GoogleGenAiChatModel dynamicModel = GoogleGenAiChatModel.builder()
                    .genAiClient(genAiClient)
                    .defaultOptions(options)
                    // .retryTemplate(retryTemplate)             // Recommended (see note below)
                    // .observationRegistry(observationRegistry) // Recommended (see note below)
                    .build();

            // 4. Return the configured ChatClient
            return ChatClient.builder(dynamicModel).build();
        }

        return defaultChatClient;
    }

    // ── streamChat ────────────────────────────────────────────────────────────

    @Override
    public Flux<String> streamChat(String systemPrompt, String userMessage,
                                   AgentToolConfig tools, String conversationId) {
        String model = resolveModel();
        log.info("[GeminiProvider] Streaming — model: {}, conversation: {}", model, conversationId);

        String safeConversationId = sanitizeConversationId(conversationId);
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor
                .builder(chatMemory)
                .conversationId(safeConversationId)
                .build();

        ChatClient client = resolveClient(model);

        // Pass model as runtime option so auto-configured ChatClient also respects DB selection
        GoogleGenAiChatOptions runtimeOptions = GoogleGenAiChatOptions.builder()
                .model(model)
                .build();

        ChatClient.ChatClientRequestSpec spec = client.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .advisors(memoryAdvisor)
                .options(runtimeOptions);

        if (tools != null) {
            spec = spec.tools(tools);
        }

        return spec.stream().content()
                .onErrorResume(e -> {
                    log.warn("[GeminiProvider] Stream interrupted ({}): {}",
                            e.getClass().getSimpleName(), e.getMessage());
                    return Flux.empty();
                });
    }

    // ── callChat ──────────────────────────────────────────────────────────────

    @Override
    public String callChat(String systemPrompt, String userMessage,
                           String responseSchema, String conversationId) {
        String model = resolveModel();
        log.info("[GeminiProvider] Blocking JSON call — model: {}, conversation: {}", model, conversationId);

        boolean isGemini = model.toLowerCase().contains("gemini");
        ChatClient client = resolveClient(model);

        GoogleGenAiChatOptions.Builder optionsBuilder = GoogleGenAiChatOptions.builder()
                .model(model)
                .temperature(0.1);

        if (isGemini) {
            optionsBuilder.responseMimeType("application/json");
            if (responseSchema != null && !responseSchema.isBlank()) {
                optionsBuilder.outputSchema(responseSchema);
            }
            return client.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .options(optionsBuilder.build())
                    .call()
                    .content();
        } else {
            // Non-Gemini (Gemma, etc.): merge system into user message
            String merged = "System Instructions:\n" + systemPrompt
                    + "\n\nUser Input Context:\n" + userMessage
                    + "\n\nIMPORTANT: Respond with valid JSON only. Start with '{', end with '}'. No markdown.";
            return client.prompt()
                    .user(merged)
                    .options(optionsBuilder.build())
                    .call()
                    .content();
        }
    }

    @Override
    public String callChat(String systemPrompt, String userMessage, String conversationId) {
        return callChat(systemPrompt, userMessage, null, conversationId);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String sanitizeConversationId(String conversationId) {
        if (conversationId == null) return java.util.UUID.randomUUID().toString();
        if (conversationId.length() <= 36) return conversationId;

        String prefix = "";
        int firstDash = conversationId.indexOf('-');
        if (firstDash > 0) {
            int secondDash = conversationId.indexOf('-', firstDash + 1);
            prefix = conversationId.substring(0, Math.min(secondDash > 0 ? secondDash + 1 : firstDash + 1, 18));
        } else {
            prefix = conversationId.substring(0, Math.min(conversationId.length(), 18));
        }

        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(conversationId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            String result = prefix + sb.toString().substring(0, 16);
            return result.length() > 36 ? result.substring(0, 36) : result;
        } catch (Exception e) {
            return conversationId.substring(0, 36);
        }
    }
}
