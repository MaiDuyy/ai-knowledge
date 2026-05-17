package com.security.security.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 — Internal HTTP client for messaging-service.
 *
 * Calls messaging-service directly over the internal network using
 * x-internal-signature bypass (same pattern as ws-gateway).
 * All methods are synchronous — they run inside Spring AI tool callbacks
 * which are executed on a virtual thread pool.
 */
@Component
@Slf4j
public class MessagingServiceClient {

    private final String messagingBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MessagingServiceClient(
            @Value("${messaging.service.url:http://localhost:3020}") String messagingBaseUrl) {
        this.messagingBaseUrl = messagingBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Fetch recent messages from a chat.
     * Returns a list of formatted "senderName: content" strings.
     */
    public List<String> getRecentMessages(String chatId, int limit, String userId) {
        try {
            String url = messagingBaseUrl + "/messages/" + chatId + "?limit=" + limit;
            HttpRequest req = buildGet(url, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                log.warn("[Agent Tool] getRecentMessages failed: HTTP {}", res.statusCode());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(res.body());
            JsonNode messages = root.path("messages");
            List<String> result = new ArrayList<>();
            if (messages.isArray()) {
                for (JsonNode msg : messages) {
                    String sender = msg.path("sender").path("name").asText("Unknown");
                    String content = msg.path("content").asText("");
                    if (!content.isBlank()) {
                        result.add(sender + ": " + content);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[Agent Tool] getRecentMessages error for chatId={}: {}", chatId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Create a task in a chat.
     * Returns task ID on success, null on failure.
     */
    public String createTask(String chatId, String title, String description, String userId) {
        try {
            String url = messagingBaseUrl + "/chats/" + chatId + "/tasks";
            Map<String, Object> body = Map.of(
                    "title", title,
                    "description", description != null ? description : ""
            );
            String bodyJson = objectMapper.writeValueAsString(body);
            HttpRequest req = buildPost(url, bodyJson, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(res.body());
                return root.path("task").path("id").asText(null);
            }
            log.warn("[Agent Tool] createTask failed: HTTP {} — {}", res.statusCode(), res.body());
            return null;
        } catch (Exception e) {
            log.error("[Agent Tool] createTask error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get chat metadata (name, participant count, isGroup).
     */
    public Map<String, Object> getChatInfo(String chatId, String userId) {
        try {
            String url = messagingBaseUrl + "/chats/" + chatId;
            HttpRequest req = buildGet(url, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) return Map.of();

            JsonNode root = objectMapper.readTree(res.body());
            JsonNode chat = root.path("chat");
            return Map.of(
                    "name",             chat.path("name").asText("Unknown"),
                    "isGroup",          chat.path("isGroup").asBoolean(false),
                    "participantCount", chat.path("participantCount").asInt(0)
            );
        } catch (Exception e) {
            log.error("[Agent Tool] getChatInfo error: {}", e.getMessage());
            return Map.of();
        }
    }

    // ────────────────────────────────────────────────────────────────
    // HTTP helpers
    // ────────────────────────────────────────────────────────────────

    private HttpRequest buildGet(String url, String userId) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("x-user-id", userId != null ? userId : "system-agent")
                .GET()
                .build();
    }

    private HttpRequest buildPost(String url, String body, String userId) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("x-user-id", userId != null ? userId : "system-agent")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
