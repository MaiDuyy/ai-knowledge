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
                    String type = msg.path("type").asText("text");
                    String fileName = msg.path("fileName").asText("");

                    if (!content.isBlank()) {
                        result.add(sender + ": " + content);
                    } else {
                        if ("image".equalsIgnoreCase(type)) {
                            result.add(sender + ": [Hình ảnh]");
                        } else if ("video".equalsIgnoreCase(type)) {
                            result.add(sender + ": [Video]");
                        } else if ("file".equalsIgnoreCase(type)) {
                            String displayName = fileName.isBlank() ? "Không rõ tên" : fileName;
                            result.add(sender + ": [Tệp tin: " + displayName + "]");
                        } else if ("audio".equalsIgnoreCase(type)) {
                            result.add(sender + ": [Gửi tin nhắn thoại]");
                        } else {
                            result.add(sender + ": [Tin nhắn đa phương tiện]");
                        }
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
     * Returns a Map containing creation success, taskId and descriptive success/error message.
     */
    public Map<String, Object> createTask(String chatId, String title, String description, String deadlineAt, List<String> assigneeIds, String userId) {
        try {
            String url = messagingBaseUrl + "/chats/" + chatId + "/tasks";
            java.util.HashMap<String, Object> body = new java.util.HashMap<>();
            body.put("title", title);
            body.put("description", description != null ? description : "");
            if (deadlineAt != null && !deadlineAt.isBlank()) {
                body.put("deadlineAt", deadlineAt);
            }
            if (assigneeIds != null && !assigneeIds.isEmpty()) {
                body.put("assigneeIds", assigneeIds);
            }
            String bodyJson = objectMapper.writeValueAsString(body);
            HttpRequest req = buildPost(url, bodyJson, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(res.body());
                String taskId = root.path("task").path("id").asText("");
                return Map.of(
                        "success", true,
                        "taskId", taskId,
                        "message", "Tạo kế hoạch thành công!"
                );
            } else {
                String errorMsg = "Lỗi hệ thống khi tạo kế hoạch";
                try {
                    JsonNode root = objectMapper.readTree(res.body());
                    if (root.has("message")) {
                        errorMsg = root.path("message").asText();
                    }
                } catch (Exception e) {
                    log.warn("[Agent Tool] Cannot parse error response body: {}", e.getMessage());
                }
                log.warn("[Agent Tool] createTask failed: HTTP {} — {}", res.statusCode(), res.body());
                return Map.of(
                        "success", false,
                        "taskId", "",
                        "message", errorMsg
                );
            }
        } catch (Exception e) {
            log.error("[Agent Tool] createTask error: {}", e.getMessage());
            return Map.of(
                    "success", false,
                    "taskId", "",
                    "message", "Lỗi kết nối dịch vụ: " + e.getMessage()
            );
        }
    }

    /**
     * Create a poll in a chat room.
     * Returns a Map containing creation success, pollId and descriptive success/error message.
     */
    public Map<String, Object> createPoll(String chatId, String title, List<String> options, String endsAt, String userId) {
        try {
            String url = messagingBaseUrl + "/polls";
            java.util.HashMap<String, Object> body = new java.util.HashMap<>();
            body.put("chatId", chatId);
            body.put("title", title);
            body.put("options", options);
            if (endsAt != null && !endsAt.isBlank()) {
                body.put("endsAt", endsAt);
            }
            String bodyJson = objectMapper.writeValueAsString(body);
            HttpRequest req = buildPost(url, bodyJson, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(res.body());
                String pollId = root.path("poll").path("id").asText("");
                return Map.of(
                        "success", true,
                        "pollId", pollId,
                        "message", "Tạo cuộc bình chọn thành công!"
                );
            } else {
                String errorMsg = "Lỗi hệ thống khi tạo cuộc bình chọn";
                try {
                    JsonNode root = objectMapper.readTree(res.body());
                    if (root.has("message")) {
                        errorMsg = root.path("message").asText();
                    }
                } catch (Exception e) {
                    log.warn("[Agent Tool] Cannot parse error response body: {}", e.getMessage());
                }
                log.warn("[Agent Tool] createPoll failed: HTTP {} — {}", res.statusCode(), res.body());
                return Map.of(
                        "success", false,
                        "pollId", "",
                        "message", errorMsg
                );
            }
        } catch (Exception e) {
            log.error("[Agent Tool] createPoll error: {}", e.getMessage());
            return Map.of(
                    "success", false,
                    "pollId", "",
                    "message", "Lỗi kết nối dịch vụ: " + e.getMessage()
            );
        }
    }

    /**
     * Get chat metadata (name, participant count, isGroup).
     */
    public Map<String, Object> getChatInfo(String chatId, String userId) {
        try {
            String chatName = "";
            boolean isGroup = false;
            int participantCount = 0;
            boolean metadataLoaded = false;

            // Step 1: Attempt to call the internal metadata endpoint first (bypass permissions and gRPC heavy profile fetching)
            String urlInternal = messagingBaseUrl + "/chats/internal/" + chatId + "/metadata";
            HttpRequest reqInternal = buildGet(urlInternal, userId);
            HttpResponse<String> resInternal = httpClient.send(reqInternal, HttpResponse.BodyHandlers.ofString());

            if (resInternal.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resInternal.body());
                JsonNode chat = root.path("chat");
                if (!chat.isMissingNode() && !chat.isNull()) {
                    isGroup = chat.path("isGroup").asBoolean(false);
                    participantCount = chat.path("participantCount").asInt(0);
                    chatName = chat.path("name").asText("");
                    metadataLoaded = true;

                    if (participantCount == 0 && chat.path("participants").isArray()) {
                        participantCount = chat.path("participants").size();
                    }
                }
            }

            // Step 2: Fallback to the detailed endpoint /chats/:chatId if metadata failed, or if it is a private chat (to resolve partner name),
            // OR if participantCount is still 0 (due to potential docker container out-of-sync or backend filtering bugs)
            if (!metadataLoaded || !isGroup || participantCount == 0) {
                String urlDetail = messagingBaseUrl + "/chats/" + chatId;
                HttpRequest reqDetail = buildGet(urlDetail, userId);
                HttpResponse<String> resDetail = httpClient.send(reqDetail, HttpResponse.BodyHandlers.ofString());

                if (resDetail.statusCode() == 200) {
                    JsonNode rootDetail = objectMapper.readTree(resDetail.body());
                    JsonNode chatDetail = rootDetail.path("chat");
                    if (!chatDetail.isMissingNode() && !chatDetail.isNull()) {
                        isGroup = chatDetail.path("isGroup").asBoolean(isGroup);

                        String detailName = chatDetail.path("name").asText("");
                        if (!detailName.isBlank() && !"Unknown".equalsIgnoreCase(detailName)) {
                            chatName = detailName;
                        }

                        JsonNode participants = chatDetail.path("participants");
                        if (participants.isArray()) {
                            participantCount = participants.size();

                            // Resolve partner name for 1-1 private chats
                            if (!isGroup && (chatName.isBlank() || "Unknown".equalsIgnoreCase(chatName))) {
                                for (JsonNode participant : participants) {
                                    String accId = participant.path("accountId").asText("");
                                    if (!accId.isBlank() && !accId.equals(userId)) {
                                        chatName = participant.path("name").asText("Người dùng");
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (chatName.isBlank() || "Unknown".equalsIgnoreCase(chatName)) {
                chatName = isGroup ? "Nhóm chưa đặt tên" : "Trò chuyện riêng tư";
            }

            return Map.of(
                    "name",             chatName,
                    "isGroup",          isGroup,
                    "participantCount", participantCount
            );
        } catch (Exception e) {
            log.error("[Agent Tool] getChatInfo error: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Get list of tasks in a chat room.
     */
    public List<Map<String, Object>> getTasks(String chatId, String userId) {
        try {
            String url = messagingBaseUrl + "/chats/" + chatId + "/tasks";
            HttpRequest req = buildGet(url, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(res.body());
                JsonNode tasksNode = root.path("tasks");
                List<Map<String, Object>> result = new ArrayList<>();
                if (tasksNode.isArray()) {
                    for (JsonNode task : tasksNode) {
                        java.util.HashMap<String, Object> map = new java.util.HashMap<>();
                        map.put("id", task.path("id").asText(""));
                        map.put("title", task.path("title").asText(""));
                        map.put("description", task.path("description").asText(""));
                        map.put("status", task.path("status").asText("TODO"));
                        map.put("deadlineAt", task.path("deadlineAt").asText(""));
                        map.put("startAt", task.path("startAt").asText(""));
                        result.add(map);
                    }
                }
                return result;
            }
            log.warn("[Agent Tool] getTasks failed: HTTP {}", res.statusCode());
            return List.of();
        } catch (Exception e) {
            log.error("[Agent Tool] getTasks error: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Update task status (TODO, IN_PROGRESS, DONE, CANCELLED).
     */
    public Map<String, Object> updateTaskStatus(String taskId, String status, String chatId, String userId) {
        try {
            String url = messagingBaseUrl + "/chats/tasks/" + taskId + "/status";
            Map<String, Object> body = Map.of(
                    "status", status,
                    "chatId", chatId != null ? chatId : ""
            );
            String bodyJson = objectMapper.writeValueAsString(body);
            HttpRequest req = buildPatch(url, bodyJson, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                return Map.of(
                        "success", true,
                        "message", "Cập nhật trạng thái kế hoạch thành công!"
                );
            } else {
                String errorMsg = "Lỗi hệ thống khi cập nhật trạng thái kế hoạch";
                try {
                    JsonNode root = objectMapper.readTree(res.body());
                    if (root.has("message")) {
                        errorMsg = root.path("message").asText();
                    }
                } catch (Exception e) {
                    log.warn("[Agent Tool] Cannot parse error response body: {}", e.getMessage());
                }
                log.warn("[Agent Tool] updateTaskStatus failed: HTTP {} — {}", res.statusCode(), res.body());
                return Map.of(
                        "success", false,
                        "message", errorMsg
                );
            }
        } catch (Exception e) {
            log.error("[Agent Tool] updateTaskStatus error: {}", e.getMessage());
            return Map.of(
                    "success", false,
                    "message", "Lỗi kết nối dịch vụ: " + e.getMessage()
                );
        }
    }

    /**
     * Send a message to a chat room proactively.
     */
    public boolean sendMessage(String chatId, String content, String type, String userId) {
        try {
            String url = messagingBaseUrl + "/messages/" + chatId;
            java.util.HashMap<String, Object> body = new java.util.HashMap<>();
            body.put("content", content);
            body.put("type", type != null ? type : "text");
            String bodyJson = objectMapper.writeValueAsString(body);
            HttpRequest req = buildPost(url, bodyJson, userId != null ? userId : "system-agent");
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                log.info("[Agent Service] Proactive message sent to chatId={}", chatId);
                return true;
            }
            log.warn("[Agent Service] sendMessage failed: HTTP {} — {}", res.statusCode(), res.body());
            return false;
        } catch (Exception e) {
            log.error("[Agent Service] sendMessage error: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Toggle pin message in a chat room.
     * Returns a Map containing the new pin state (true/false) and descriptive message.
     */
    public Map<String, Object> togglePinMessage(String messageId, String userId) {
        try {
            String url = messagingBaseUrl + "/messages/" + messageId + "/pin";
            HttpRequest req = buildPut(url, "", userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                JsonNode root = objectMapper.readTree(res.body());
                boolean pin = root.path("pin").asBoolean(false);
                return Map.of(
                        "success", true,
                        "pin", pin,
                        "message", pin ? "Đã ghim tin nhắn thành công!" : "Đã bỏ ghim tin nhắn thành công!"
                );
            } else {
                String errorMsg = "Lỗi hệ thống khi thay đổi trạng thái ghim";
                try {
                    JsonNode root = objectMapper.readTree(res.body());
                    if (root.has("message")) {
                        errorMsg = root.path("message").asText();
                    }
                } catch (Exception e) {
                    log.warn("[Agent Tool] Cannot parse error response body in togglePinMessage: {}", e.getMessage());
                }
                log.warn("[Agent Tool] togglePinMessage failed: HTTP {} — {}", res.statusCode(), res.body());
                return Map.of(
                        "success", false,
                        "pin", false,
                        "message", errorMsg
                );
            }
        } catch (Exception e) {
            log.error("[Agent Tool] togglePinMessage error: {}", e.getMessage());
            return Map.of(
                    "success", false,
                    "pin", false,
                    "message", "Lỗi kết nối dịch vụ: " + e.getMessage()
            );
        }
    }

    /**
     * Get list of pinned messages in a chat room.
     */
    public List<Map<String, Object>> getPinnedMessages(String chatId, String userId) {
        try {
            String url = messagingBaseUrl + "/messages/" + chatId + "/pinned";
            HttpRequest req = buildGet(url, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(res.body());
                JsonNode messagesNode = root.path("pinnedMessages");
                List<Map<String, Object>> result = new ArrayList<>();
                if (messagesNode.isArray()) {
                    for (JsonNode msg : messagesNode) {
                        java.util.HashMap<String, Object> map = new java.util.HashMap<>();
                        map.put("id", msg.path("id").asText(""));
                        map.put("content", msg.path("content").asText(""));
                        map.put("type", msg.path("type").asText("text"));
                        map.put("time", msg.path("time").asText(""));
                        map.put("senderId", msg.path("senderId").asText(""));
                        map.put("senderName", msg.path("sender").path("name").asText("Unknown"));
                        result.add(map);
                    }
                }
                return result;
            }
            log.warn("[Agent Tool] getPinnedMessages failed: HTTP {}", res.statusCode());
            return List.of();
        } catch (Exception e) {
            log.error("[Agent Tool] getPinnedMessages error: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Search old messages in a chat room by keyword.
     */
    public List<Map<String, Object>> searchMessages(String chatId, String query, String userId) {
        try {
            if (query == null || query.isBlank()) {
                return List.of();
            }
            String encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            String url = messagingBaseUrl + "/messages/" + chatId + "/search?q=" + encodedQuery;
            HttpRequest req = buildGet(url, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(res.body());
                JsonNode messagesNode = root.path("messages");
                List<Map<String, Object>> result = new ArrayList<>();
                if (messagesNode.isArray()) {
                    for (JsonNode msg : messagesNode) {
                        java.util.HashMap<String, Object> map = new java.util.HashMap<>();
                        map.put("id", msg.path("id").asText(""));
                        map.put("content", msg.path("content").asText(""));
                        map.put("time", msg.path("time").asText(""));
                        map.put("senderId", msg.path("senderId").asText(""));
                        map.put("senderName", msg.path("sender").path("name").asText("Unknown"));
                        result.add(map);
                    }
                }
                return result;
            }
            log.warn("[Agent Tool] searchMessages failed: HTTP {}", res.statusCode());
            return List.of();
        } catch (Exception e) {
            log.error("[Agent Tool] searchMessages error: {}", e.getMessage());
            return List.of();
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

    private HttpRequest buildPatch(String url, String body, String userId) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("x-user-id", userId != null ? userId : "system-agent")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest buildPut(String url, String body, String userId) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("x-user-id", userId != null ? userId : "system-agent")
                .PUT(HttpRequest.BodyPublishers.ofString(body != null ? body : ""))
                .build();
    }
}
