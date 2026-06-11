package com.security.security.service;

import com.security.security.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class LlmRateLimiterService {

    private static final int MAX_CONCURRENT_REQUESTS = 10;

    private final AtomicInteger chatConcurrentCount = new AtomicInteger(0);
    private final AtomicInteger agentConcurrentCount = new AtomicInteger(0);
    private final AtomicInteger ragConcurrentCount = new AtomicInteger(0);

    public void acquireRagQuery() {
        int current = ragConcurrentCount.incrementAndGet();
        log.debug("[RateLimiter] RAG Query concurrent count: {}", current);
        if (current > MAX_CONCURRENT_REQUESTS) {
            ragConcurrentCount.decrementAndGet();
            throw new TooManyRequestsException("Rate limit exceeded. Maximum 10 concurrent requests allowed for RAG query.");
        }
    }

    public void releaseRagQuery() {
        int current = ragConcurrentCount.decrementAndGet();
        log.debug("[RateLimiter] RAG Query released, current count: {}", current);
    }

    public void acquireChat() {
        int current = chatConcurrentCount.incrementAndGet();
        log.debug("[RateLimiter] Chat Messages concurrent count: {}", current);
        if (current > MAX_CONCURRENT_REQUESTS) {
            chatConcurrentCount.decrementAndGet();
            throw new TooManyRequestsException("Rate limit exceeded. Maximum 10 concurrent requests allowed for Chat messages.");
        }
    }

    public void releaseChat() {
        int current = chatConcurrentCount.decrementAndGet();
        log.debug("[RateLimiter] Chat Messages released, current count: {}", current);
    }

    public void acquireAgentChat() {
        int current = agentConcurrentCount.incrementAndGet();
        log.debug("[RateLimiter] Agent Chat concurrent count: {}", current);
        if (current > MAX_CONCURRENT_REQUESTS) {
            agentConcurrentCount.decrementAndGet();
            throw new TooManyRequestsException("Rate limit exceeded. Maximum 10 concurrent requests allowed for Agent Chat.");
        }
    }

    public void releaseAgentChat() {
        int current = agentConcurrentCount.decrementAndGet();
        log.debug("[RateLimiter] Agent Chat released, current count: {}", current);
    }
}
