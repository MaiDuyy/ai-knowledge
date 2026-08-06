package com.security.security.service;

import com.security.security.exception.TooManyRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LlmRateLimiterService Tests")
class LlmRateLimiterServiceTest {

    private LlmRateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        rateLimiterService = new LlmRateLimiterService();
    }

    @Test
    @DisplayName("Should allow up to 10 concurrent RAG queries and block the 11th")
    void ragQuery_enforcesConcurrentLimit() {
        // Acquire 10 slots
        for (int i = 0; i < 10; i++) {
            rateLimiterService.acquireRagQuery();
        }

        // 11th should fail
        assertThatThrownBy(() -> rateLimiterService.acquireRagQuery())
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Maximum 10 concurrent requests allowed for RAG query");

        // Release one slot
        rateLimiterService.releaseRagQuery();

        // Should be able to acquire again
        rateLimiterService.acquireRagQuery();

        // 11th should still fail
        assertThatThrownBy(() -> rateLimiterService.acquireRagQuery())
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    @DisplayName("Should allow up to 10 concurrent Chat requests and block the 11th")
    void chat_enforcesConcurrentLimit() {
        // Acquire 10 slots
        for (int i = 0; i < 10; i++) {
            rateLimiterService.acquireChat();
        }

        // 11th should fail
        assertThatThrownBy(() -> rateLimiterService.acquireChat())
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Maximum 10 concurrent requests allowed for Chat messages");

        // Release one slot
        rateLimiterService.releaseChat();

        // Should be able to acquire again
        rateLimiterService.acquireChat();

        // 11th should still fail
        assertThatThrownBy(() -> rateLimiterService.acquireChat())
                .isInstanceOf(TooManyRequestsException.class);
    }

    @Test
    @DisplayName("Should allow up to 10 concurrent Agent Chat requests and block the 11th")
    void agentChat_enforcesConcurrentLimit() {
        // Acquire 10 slots
        for (int i = 0; i < 10; i++) {
            rateLimiterService.acquireAgentChat();
        }

        // 11th should fail
        assertThatThrownBy(() -> rateLimiterService.acquireAgentChat())
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Maximum 10 concurrent requests allowed for Agent Chat");

        // Release one slot
        rateLimiterService.releaseAgentChat();

        // Should be able to acquire again
        rateLimiterService.acquireAgentChat();

        // 11th should still fail
        assertThatThrownBy(() -> rateLimiterService.acquireAgentChat())
                .isInstanceOf(TooManyRequestsException.class);
    }
}
