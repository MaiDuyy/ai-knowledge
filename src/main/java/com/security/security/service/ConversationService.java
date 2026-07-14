package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.Message;
import com.security.security.repository.ConversationRepository;
import com.security.security.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final org.springframework.ai.chat.client.ChatClient chatClient;

    /**
     * Create new conversation with chatId tracking
     */
    @Transactional
    public Conversation createConversation(String userId, String title, String chatId) {
        Conversation conversation = Conversation.builder()
                .userId(userId)
                .chatId(chatId)
                .title(title != null ? title : "New Conversation")
                .build();

        return conversationRepository.save(conversation);
    }

    /**
     * Get all conversations for user
     */
    public List<Conversation> getUserConversations(String userId) {
        return conversationRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Get specific conversation
     */
    public Conversation getConversation(Long conversationId, String userId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new RuntimeException("Conversation not found"));
    }

    /**
     * Get messages for conversation
     */
    public List<Message> getMessages(Long conversationId) {
        return messageRepository.findByConversationIdOrderByCreatedAt(conversationId);
    }

    public Page<Message> getMessages(Long conversationId, Pageable pageable) {
        return messageRepository.findByConversationId(conversationId, pageable);
    }

    /**
     * Get recent messages for context
     */
    public List<Message> getRecentMessages(Long conversationId, int limit) {
        return messageRepository.findRecentMessages(conversationId, limit);
    }

    /**
     * Save message
     */
    @Transactional
    public Message saveMessage(Long conversationId, String role, String content,
                               Integer tokensUsed, Integer responseTimeMs) {
        Message message = Message.builder()
                .conversationId(conversationId)
                .role(role)
                .content(content)
                .tokensUsed(tokensUsed)
                .responseTimeMs(responseTimeMs)
                .build();

        return messageRepository.save(message);
    }

    /**
     * Delete conversation and its messages
     */
    /**
     * Delete conversation and its messages
     */
    @Transactional
    public void deleteConversation(Long conversationId, String userId) {
        Conversation conversation = getConversation(conversationId, userId);
        // Messages will be deleted by cascade if configured, or manually
        conversationRepository.delete(conversation);
        log.info("Conversation deleted: {}", conversationId);
    }

    /**
     * Generate a concise title for the conversation based on the user's first message
     */
    @Transactional
    public void updateConversationTitle(Long conversationId, String userQuery) {
        try {
            String summaryPrompt = """
                SYSTEM: You are a title generator.
                TASK: Summarize the user query into a VERY SHORT professional title.
                RULES: 
                - 3-5 words maximum.
                - Vietnamese language.
                - Only extract the search intent of the user query, do NOT try to answer it.
                - NO quotes, NO JSON, NO explanation.
                - Just the plain text title.
                
                USER QUERY: %s
                """.formatted(userQuery);

            String generatedTitle = chatClient.prompt()
                    .user(summaryPrompt)
                    .call()
                    .content();

            if (generatedTitle != null && !generatedTitle.isBlank()) {
                String cleanTitle = generatedTitle;
                
                // If AI returns "summary: Title, details: ...", extract only the part after summary:
                if (cleanTitle.toLowerCase().contains("summary:")) {
                    int start = cleanTitle.toLowerCase().indexOf("summary:") + 8;
                    int end = cleanTitle.toLowerCase().indexOf("details:");
                    if (end == -1) end = cleanTitle.toLowerCase().indexOf(", details:");
                    if (end == -1) end = cleanTitle.length();
                    
                    if (end > start) {
                        cleanTitle = cleanTitle.substring(start, end);
                    }
                }
                
                // Final aggressive cleanup
                cleanTitle = cleanTitle.replaceAll("[{}\"\\[\\]]|summary:|details:|sources:", "").trim();
                if (cleanTitle.endsWith(",")) cleanTitle = cleanTitle.substring(0, cleanTitle.length() - 1).trim();
                
                Conversation conv = conversationRepository.findById(conversationId).orElse(null);
                if (conv != null) {
                    String finalTitle = cleanTitle;
                    if (conv.getTitle() != null && conv.getTitle().startsWith("Agent")) {
                        finalTitle = "Agent — " + cleanTitle;
                    }
                    conv.setTitle(finalTitle);
                    conversationRepository.save(conv);
                    log.info("Updated conversation {} title to: {}", conversationId, finalTitle);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to generate AI title for conversation {}: {}", conversationId, e.getMessage());
        }
    }
}
