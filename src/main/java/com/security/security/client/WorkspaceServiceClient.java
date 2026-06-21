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
import com.security.security.dtorequest.RAGQueryPayload;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import user.UserServiceGrpc;
import user.User.UserDetailedDepartmentsRequest;
import user.User.UserDetailedDepartmentsResponse;
import user.User.DepartmentRoleMessage;

/**
 * REST & gRPC Client for workspace/identity endpoints, matching the pattern of MessagingServiceClient.
 */
@Component
@Slf4j
public class WorkspaceServiceClient {

    private final String messagingBaseUrl;
    private final String identityBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final UserServiceGrpc.UserServiceBlockingStub userServiceStub;

    public WorkspaceServiceClient(
            @Value("${messaging.service.url:http://localhost:3020}") String messagingBaseUrl,
            @Value("${identity.service.url:http://localhost:3010}") String identityBaseUrl,
            @Value("${identity.service.grpc.host:localhost}") String identityGrpcHost,
            @Value("${identity.service.grpc.port:50051}") int identityGrpcPort) {
        this.messagingBaseUrl = messagingBaseUrl;
        this.identityBaseUrl = identityBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();

        log.info("[WorkspaceServiceClient] Initializing gRPC channel to {}:{}", identityGrpcHost, identityGrpcPort);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(identityGrpcHost, identityGrpcPort)
                .usePlaintext()
                .build();
        this.userServiceStub = UserServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Fetch user's department memberships from identity service.
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "userDepartments",
            key = "#userId",
            unless = "#result == null || #result.isEmpty()"
    )
    public List<RAGQueryPayload.DepartmentRole> getUserDepartments(String userId) {
        if (userId == null || userId.isBlank() || "system-user".equals(userId)) {
            return List.of();
        }
        try {
            log.info("[WorkspaceServiceClient] Fetching department roles for userId={} via gRPC", userId);
            UserDetailedDepartmentsRequest request = UserDetailedDepartmentsRequest.newBuilder()
                    .setUserId(userId)
                    .build();
            UserDetailedDepartmentsResponse response = userServiceStub.getUserDetailedDepartments(request);

            List<RAGQueryPayload.DepartmentRole> list = new ArrayList<>();
            for (DepartmentRoleMessage deptMsg : response.getDepartmentsList()) {
                String deptId = deptMsg.getDepartmentId();
                String role = deptMsg.getRole();
                if (deptId != null && !deptId.isBlank()) {
                    list.add(RAGQueryPayload.DepartmentRole.builder()
                            .departmentId(deptId)
                            .role(role)
                            .build());
                }
            }
            log.info("[WorkspaceServiceClient] gRPC fetched {} department roles for userId={}", list.size(), userId);
            return list;
        } catch (Exception e) {
            log.warn("[WorkspaceServiceClient] gRPC call failed for userId={}, falling back to REST. Error: {}", 
                    userId, e.getMessage());
            return getUserDepartmentsRestFallback(userId);
        }
    }

    private List<RAGQueryPayload.DepartmentRole> getUserDepartmentsRestFallback(String userId) {
        try {
            String url = identityBaseUrl + "/users/" + userId + "/departments";
            HttpRequest req = buildGet(url, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                log.warn("[WorkspaceServiceClient] REST fallback getUserDepartments failed with HTTP {} for userId={}", 
                        res.statusCode(), userId);
                return List.of();
            }

            JsonNode root = objectMapper.readTree(res.body());
            JsonNode data = root.path("data");
            if (data.isMissingNode() || !data.isArray()) {
                return List.of();
            }

            List<RAGQueryPayload.DepartmentRole> list = new ArrayList<>();
            for (JsonNode node : data) {
                String deptId = node.path("id").asText("");
                String userRole = node.path("userRole").asText("");
                if (!deptId.isBlank()) {
                    list.add(RAGQueryPayload.DepartmentRole.builder()
                            .departmentId(deptId)
                            .role(userRole)
                            .build());
                }
            }
            log.info("[WorkspaceServiceClient] REST fallback fetched {} department roles for userId={}", list.size(), userId);
            return list;
        } catch (Exception e) {
            log.error("[WorkspaceServiceClient] REST fallback error fetching departments for userId={}: {}", 
                    userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Get department metadata.
     * Returns a Map containing department details, or an empty Map if not found.
     */
    @org.springframework.cache.annotation.Cacheable(
            value = "workspaceDepartment",
            key = "#departmentId",
            unless = "#result == null || #result.isEmpty()"
    )
    public Map<String, Object> getDepartment(String departmentId, String userId) {
        try {
            String url = identityBaseUrl + "/departments/" + departmentId;
            HttpRequest req = buildGet(url, userId);
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() != 200) {
                log.warn("[WorkspaceServiceClient] getDepartment failed with HTTP {} for departmentId={}, userId={}", 
                        res.statusCode(), departmentId, userId);
                return Map.of();
            }

            JsonNode root = objectMapper.readTree(res.body());
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                return Map.of();
            }

            // Return a mutable or immutable map containing department info
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", data.path("id").asText(""));
            map.put("name", data.path("name").asText(""));
            map.put("description", data.path("description").asText(""));
            return map;
        } catch (Exception e) {
            log.error("[WorkspaceServiceClient] Error fetching departmentId={} for userId={}: {}", 
                    departmentId, userId, e.getMessage());
            return Map.of();
        }
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
