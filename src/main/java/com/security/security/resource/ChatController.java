package com.security.security.resource;

import com.security.security.dto.UserDTO;
import com.security.security.dtorequest.ChatRequest;
import com.security.security.dtorequest.ConversationRequest;
import com.security.security.entity.Conversation;
import com.security.security.entity.Message;
import com.security.security.service.ConversationService;
import com.security.security.service.RAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final RAGService ragService;
    private final ConversationService conversationService;

    /**
     * Create new conversation
     */
    @PostMapping("/conversations")
    public ResponseEntity<Conversation> createConversation(
            @RequestBody ConversationRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        Conversation conversation = conversationService.createConversation(userId, request.getTitle(), request.getChatId());

        return ResponseEntity.ok(conversation);
    }

    /**
     * List user's conversations
     */
    @GetMapping("/conversations")
    public ResponseEntity<List<Conversation>> getConversations(
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        List<Conversation> conversations = conversationService.getUserConversations(userId);

        return ResponseEntity.ok(conversations);
    }

    /**
     * Get messages for a conversation
     */
    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<Message>> getMessages(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {


        // Verify conversation belongs to user
        conversationService.getConversation(id, userId);

        List<Message> messages = conversationService.getMessages(id);
        return ResponseEntity.ok(messages);
    }

    /**
     * Send message and get streaming response (RAG)
     */
    @PostMapping(value = "/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {


        // Verify conversation belongs to user
        conversationService.getConversation(request.getConversationId(), userId);

        return ragService.generateAnswerStream(
                request.getConversationId(),
                request.getMessage(),
                userId
        );
    }

    /**
     * Delete conversation
     */
    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Map<String, String>> deleteConversation(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        conversationService.deleteConversation(id, userId);

        return ResponseEntity.ok(Map.of("message", "Conversation deleted successfully"));
    }


}
