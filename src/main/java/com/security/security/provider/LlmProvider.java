package com.security.security.provider;

import com.security.security.config.AgentToolConfig;
import reactor.core.publisher.Flux;

/**
 * Adapter interface for different LLM providers (Multi-LLM Catalog).
 * Allows switching between Gemini, OpenAI, Anthropic without changing core logic.
 */
public interface LlmProvider {
    /**
     * Get the unique name of the provider (e.g. "gemini", "openai", "anthropic").
     */
    String getProviderName();

    /**
     * Stream chat response from the LLM (for long-form / markdown content).
     * Do NOT use this for structured JSON output — use callChat() instead.
     *
     * @param systemPrompt   The system prompt with rules and context.
     * @param userMessage    The user's query.
     * @param tools          Tool configuration for Function Calling.
     * @param conversationId The ID of the conversation for memory/history.
     * @return A Flux of text tokens.
     */
    Flux<String> streamChat(String systemPrompt, String userMessage, AgentToolConfig tools, String conversationId);

    /**
     * Blocking call for structured JSON responses.
     * Use this when the response must be valid JSON (Map/Reduce pipeline phases).
     * Streaming + JSON mode is incompatible with the Google Gen AI SDK — the SDK
     * tries to parse each individual chunk as a full JSON document, causing JsonEOFException.
     *
     * @param systemPrompt   The system prompt defining the JSON schema.
     * @param userMessage    The user content / data input.
     * @param conversationId The conversation ID (for logging, not memory).
     * @return The complete response string (expected to be valid JSON).
     */
    default String callChat(String systemPrompt, String userMessage, String conversationId) {
        return callChat(systemPrompt, userMessage, null, conversationId);
    }

    /**
     * Blocking call for structured JSON responses with an optional JSON schema.
     * Use this when the response must conform to a specific JSON schema (Map/Reduce pipeline phases).
     *
     * @param systemPrompt   The system prompt defining the instructions.
     * @param userMessage    The user content / data input.
     * @param responseSchema The JSON schema to enforce on the output. Can be null or blank.
     * @param conversationId The conversation ID.
     * @return The complete response string conforming to the schema.
     */
    default String callChat(String systemPrompt, String userMessage, String responseSchema, String conversationId) {
        // Default: collect stream (providers that do not override this)
        return streamChat(systemPrompt, userMessage, null, conversationId)
                .collectList()
                .map(list -> String.join("", list))
                .block();
    }
}
