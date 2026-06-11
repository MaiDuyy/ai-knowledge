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
import java.util.Map;

/**
 * REST Client for workspace endpoints in messaging-service, matching the pattern of MessagingServiceClient.
 */
@Component
@Slf4j
public class WorkspaceServiceClient {

    private final String messagingBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WorkspaceServiceClient(
            @Value("${messaging.service.url:http://localhost:3020}") String messagingBaseUrl) {
        this.messagingBaseUrl = messagingBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Get workspace metadata and check if the user has access.
     * Returns a Map containing workspace details, or an empty Map if access is denied or workspace is not found.
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "workspaceDepartment",
            key = "#workspaceId",
            unless = "#result == null || #result.isEmpty()"
    )
    public Map<String, Object> getWorkspace(String workspaceId, String userId) {
        try {
            String url = messagingBaseUrl + "/workspaces/" + workspaceId;
            HttpRequest req = buildGet(url, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                log.warn("[WorkspaceServiceClient] getWorkspace failed with HTTP {} for workspaceId={}, userId={}", 
                        res.statusCode(), workspaceId, userId);
                return Map.of();
            }

            JsonNode root = objectMapper.readTree(res.body());
            JsonNode workspace = root.path("workspace");
            if (workspace.isMissingNode() || workspace.isNull()) {
                return Map.of();
            }

            return Map.of(
                    "id", workspace.path("id").asText(""),
                    "name", workspace.path("name").asText(""),
                    "slug", workspace.path("slug").asText(""),
                    "isPublic", workspace.path("isPublic").asBoolean(false),
                    "departmentId", workspace.path("departmentId").asText("")
            );
        } catch (Exception e) {
            log.error("[WorkspaceServiceClient] Error fetching workspaceId={} for userId={}: {}", 
                    workspaceId, userId, e.getMessage());
            return Map.of();
        }
    }

    private HttpRequest buildGet(String url, String userId) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("x-user-id", userId != null ? userId : "system-agent")
                .GET()
                .build();
    }
}
