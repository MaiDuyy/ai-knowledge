package com.security.security.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.client.MessagingServiceClient;
import com.security.security.entity.Conversation;
import com.security.security.repository.ConversationRepository;
import com.security.security.service.AgentService;
import com.security.security.service.ConversationService;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Option C — Proactive AI Agent with Event-Driven Triggers.
 *
 * Subscribes to NATS subject "message.created" from messaging-service.
 * Intercepts user messages in real-time, detects proactive intent,
 * triggers the AI Agent core (with full tool capabilities), and sends
 * the formatted AI response back into the chat room.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NatsAgentSubscriber {

    private static final String SUBJECT = "message.created";
    private static final String BOT_USER_ID = "system-agent";

    private final Connection natsConnection;
    private final AgentService agentService;
    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final MessagingServiceClient messagingClient;
    private final ObjectMapper objectMapper;

    private Dispatcher dispatcher;

    @PostConstruct
    public void subscribe() {
        try {
            dispatcher = natsConnection.createDispatcher(this::handleMessage);
            dispatcher.subscribe(SUBJECT);
            log.info("[NATS Agent] Subscribed to proactive subject: {}", SUBJECT);
        } catch (Exception e) {
            log.warn("[NATS Agent] Could not subscribe to '{}'. Cause: {}", SUBJECT, e.getMessage());
        }
    }

    @PreDestroy
    public void unsubscribe() {
        try {
            if (dispatcher != null) {
                dispatcher.unsubscribe(SUBJECT);
            }
        } catch (Exception e) {
            log.warn("[NATS Agent] Error unsubscribing: {}", e.getMessage());
        }
    }

    private void handleMessage(Message msg) {
        try {
            String raw = new String(msg.getData());
            log.debug("[NATS Agent] Received message: {}", raw);

            // Unwrap envelope
            JsonNode root = objectMapper.readTree(raw);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;

            String chatId = getTextField(payload, "chatId");
            String senderId = getTextField(payload, "senderId");
            String content = getTextField(payload, "content");
            String workspaceId = getTextField(payload, "workspaceId");

            if (chatId == null || content == null) {
                return;
            }

            // 1. Smart filters to decide if AI should proactively trigger
            if (!shouldTriggerProactively(content, senderId)) {
                return;
            }

            log.info("[NATS Agent] Proactive Agent triggered in chatId={} | content='{}'", chatId, content);

            // 2. Find or create a dedicated system-agent conversation for this room
            Long conversationId = resolveConversationId(chatId);

            // 3. Trigger Agent Service (streams Gemini response and updates Chat Memory)
            // We use virtual thread backing to collect the stream synchronously in this handler
            StringBuilder builder = new StringBuilder();
            agentService.runAgent(conversationId, content, BOT_USER_ID, chatId, "gemini", null, workspaceId)
                    .doOnNext(builder::append)
                    .then() // waits for complete
                    .block(); // block safely inside NATS event executor thread

            String rawResponse = builder.toString().trim();
            if (rawResponse.isEmpty()) {
                log.warn("[NATS Agent] AI returned empty response for chatId={}", chatId);
                return;
            }

            // 4. Format the raw JSON response into beautiful Markdown
            String formattedMessage = formatAiResponse(rawResponse);

            // 5. Send the proactive response back to the chat room
            messagingClient.sendMessage(chatId, formattedMessage, "text", BOT_USER_ID);

        } catch (Exception e) {
            log.error("[NATS Agent] Error handling proactive message", e);
        }
    }

    private Long resolveConversationId(String chatId) {
        Optional<Conversation> existing = conversationRepository.findByChatIdAndUserId(chatId, BOT_USER_ID);
        if (existing.isPresent()) {
            return existing.get().getId();
        }
        Conversation created = conversationService.createConversation(BOT_USER_ID, "Proactive Assistant — " + chatId, chatId);
        return created.getId();
    }

    private boolean shouldTriggerProactively(String content, String senderId) {
        if (content == null || content.isBlank()) return false;

        // Never respond to our own messages to prevent infinite loops
        if (BOT_USER_ID.equalsIgnoreCase(senderId)) return false;

        String lower = content.toLowerCase();

        // Trigger Rule 1: Direct mentions
        if (lower.contains("@ai") || lower.contains("@system-agent") || lower.startsWith("/ai ")) {
            return true;
        }

        // Trigger Rule 2: Proactive Poll creation detection
        boolean hasPollKeywords = lower.contains("bình chọn") || lower.contains("khảo sát") || lower.contains("poll");
        boolean hasActionKeywords = lower.contains("tạo") || lower.contains("lập") || lower.contains("mở") || lower.contains("gợi ý");
        if (hasPollKeywords && hasActionKeywords) {
            return true;
        }

        // Trigger Rule 3: Proactive Task/Plan creation detection
        boolean hasTaskKeywords = lower.contains("task") || lower.contains("công việc") || lower.contains("kế hoạch") || lower.contains("giao việc");
        if (hasTaskKeywords && hasActionKeywords) {
            return true;
        }

        // Trigger Rule 4: Semantic Knowledge query
        boolean hasDocKeywords = lower.contains("quy trình") || lower.contains("chính sách") || lower.contains("hướng dẫn") || lower.contains("wiki");
        boolean hasQuestionKeywords = lower.contains("nào") || lower.contains("gì") || lower.contains("sao") || lower.contains("đâu") || lower.contains("?");
        if (hasDocKeywords && hasQuestionKeywords) {
            return true;
        }

        // Trigger Rule 5: Pin & Search Intent
        boolean hasPinSearchKeywords = lower.contains("tin nhắn ghim") || lower.contains("tin nhắn quan trọng") || lower.contains("pinned message") || lower.contains("tìm tin nhắn") || lower.contains("lục tin nhắn") || lower.contains("search tin nhắn");
        if (hasPinSearchKeywords) {
            return true;
        }

        return false;
    }

    private String formatAiResponse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            String summary = root.path("summary").asText("");
            JsonNode detailsNode = root.path("details");
            JsonNode sourcesNode = root.path("sources");

            StringBuilder sb = new StringBuilder();
            sb.append("🤖 **Trợ lý AI Chủ động (Proactive AI)**\n\n");
            if (!summary.isBlank()) {
                sb.append(summary).append("\n\n");
            }
            if (detailsNode.isArray() && detailsNode.size() > 0) {
                sb.append("*Chi tiết:*\n");
                for (JsonNode detail : detailsNode) {
                    sb.append("• ").append(detail.asText()).append("\n");
                }
                sb.append("\n");
            }
            if (sourcesNode.isArray() && sourcesNode.size() > 0) {
                sb.append("*Nguồn tài liệu:* ");
                List<String> srcList = new ArrayList<>();
                for (JsonNode src : sourcesNode) {
                    srcList.add(src.asText());
                }
                sb.append(String.join(", ", srcList)).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("[NATS Agent] Failed to parse JSON response: {}, returning raw content", e.getMessage());
            return rawJson;
        }
    }

    private String getTextField(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull()) ? n.asText() : null;
    }
}
