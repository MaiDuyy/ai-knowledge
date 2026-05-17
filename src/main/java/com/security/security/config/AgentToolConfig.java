package com.security.security.config;

import com.security.security.client.MessagingServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 2 — Agent Tool Registry using @Tool annotation.
 * This class is instantiated per-request in AgentService to hold the current user context.
 */
@Slf4j
public class AgentToolConfig {

    private final VectorStore vectorStore;
    private final MessagingServiceClient messagingClient;
    private final String userId;

    public AgentToolConfig(VectorStore vectorStore, MessagingServiceClient messagingClient, String userId) {
        this.vectorStore = vectorStore;
        this.messagingClient = messagingClient;
        this.userId = userId;
    }

    public record KnowledgeSearchInput(String query) {}
    public record KnowledgeSearchOutput(List<String> results, int count) {}

    @Tool(name = "searchKnowledge", description = "Search the internal knowledge base documents using semantic similarity.")
    public KnowledgeSearchOutput searchKnowledge(KnowledgeSearchInput input) {
        log.info("[Agent Tool] searchKnowledge: query='{}'", input.query());
        try {
            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(input.query())
                            .topK(5)
                            .similarityThreshold(0.2)
                            .build()
            );
            List<String> texts = docs.stream()
                    .map(doc -> {
                        String fileName = (String) doc.getMetadata().getOrDefault("fileName", "Document");
                        return "[" + fileName + "]\n" + doc.getText();
                    })
                    .collect(Collectors.toList());
            return new KnowledgeSearchOutput(texts, texts.size());
        } catch (Exception e) {
            log.error("[Agent Tool] searchKnowledge error", e);
            return new KnowledgeSearchOutput(List.of(), 0);
        }
    }

    public record SummarizeChatInput(String chatId, int messageCount) {}
    public record SummarizeChatOutput(String summary, int messagesSummarized) {}

    @Tool(name = "summarizeChat", description = "Fetch and summarize the most recent messages from a chat conversation.")
    public SummarizeChatOutput summarizeChat(SummarizeChatInput input) {
        int limit = Math.min(Math.max(input.messageCount(), 10), 100);
        log.info("[Agent Tool] summarizeChat: chatId='{}', limit={}, userId='{}'", input.chatId(), limit, userId);
        List<String> messages = messagingClient.getRecentMessages(input.chatId(), limit, userId);
        if (messages.isEmpty()) {
            return new SummarizeChatOutput("Không có tin nhắn nào trong cuộc trò chuyện này.", 0);
        }
        String joined = String.join("\n", messages);
        return new SummarizeChatOutput(joined, messages.size());
    }

    public record CreateTaskInput(String chatId, String title, String description) {}
    public record CreateTaskOutput(boolean success, String taskId, String message) {}

    @Tool(name = "createTask", description = "Create a new task in the current chat.")
    public CreateTaskOutput createTask(CreateTaskInput input) {
        log.info("[Agent Tool] createTask: chatId='{}', title='{}', userId='{}'", input.chatId(), input.title(), userId);
        if (input.chatId() == null || input.chatId().isBlank()) {
            return new CreateTaskOutput(false, null, "Không thể tạo task: không có chatId.");
        }
        String taskId = messagingClient.createTask(input.chatId(), input.title(), input.description(), userId);
        if (taskId != null) {
            return new CreateTaskOutput(true, taskId, "✅ Task \"" + input.title() + "\" đã được tạo thành công.");
        }
        return new CreateTaskOutput(false, null, "Không thể tạo task. Vui lòng thử lại.");
    }

    public record GetChatInfoInput(String chatId) {}
    public record GetChatInfoOutput(String name, boolean isGroup, int participantCount) {}

    @Tool(name = "getChatInfo", description = "Get information about the current chat or group.")
    public GetChatInfoOutput getChatInfo(GetChatInfoInput input) {
        log.info("[Agent Tool] getChatInfo: chatId='{}', userId='{}'", input.chatId(), userId);
        Map<String, Object> info = messagingClient.getChatInfo(input.chatId(), userId);
        return new GetChatInfoOutput(
                (String) info.getOrDefault("name", "Unknown"),
                (Boolean) info.getOrDefault("isGroup", false),
                (Integer) info.getOrDefault("participantCount", 0)
        );
    }
}
