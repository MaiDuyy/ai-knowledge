package com.security.security.resource;

import com.security.security.dtorequest.AgentRequest;
import com.security.security.entity.Conversation;
import com.security.security.service.AgentService;
import com.security.security.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * Phase 2 — Agent REST Controller.
 *
 * Exposes POST /agent/chat which streams Gemini + Function Calling responses.
 * Called by ws-gateway when socket event `chat:agent_query` is received.
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentService agentService;
    private final ConversationService conversationService;

    /**
     * Stream an agent response.
     * The response is text/event-stream (SSE).
     *
     * Request body: { conversationId?, message, chatId, workspaceId? }
     * Header:       x-user-id (injected by ws-gateway)
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> agentChat(
            @RequestBody AgentRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        log.info("[AgentController] userId={}, chatId={}, message='{}'",
                userId, request.getChatId(), request.getMessage());

        // Resolve or create conversation
        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            String title = "Agent — " + (request.getChatId() != null ? request.getChatId() : "General");
            Conversation conv = conversationService.createConversation(userId, title, request.getChatId());
            conversationId = conv.getId();
        } else {
            // Verify ownership
            conversationService.getConversation(conversationId, userId);
        }

        final Long finalConversationId = conversationId;
        final String chatId = request.getChatId() != null ? request.getChatId() : "unknown";

        return agentService.runAgent(finalConversationId, request.getMessage(), userId, chatId, request.getProvider(), request.getSkillId(), request.getWorkspaceId())
                .onErrorResume(e -> {
                    log.error("[AgentController] Agent error: {}", e.getMessage());
                    return Flux.just("Đã xảy ra lỗi khi xử lý yêu cầu. Vui lòng thử lại.");
                });
    }

    /**
     * Health check for agent subsystem.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "service", "ai-agent",
                "phase", "2"
        ));
    }
}
