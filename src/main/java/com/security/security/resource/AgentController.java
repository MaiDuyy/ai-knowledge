package com.security.security.resource;

import com.security.security.dtorequest.AgentRequest;
import com.security.security.entity.Conversation;
import com.security.security.service.AgentService;
import com.security.security.service.ConversationService;
import com.security.security.service.PermissionUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.security.security.client.WorkspaceServiceClient;
import com.security.security.dtorequest.RAGQueryPayload;
import java.util.List;
import java.util.ArrayList;
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
import com.security.security.service.LlmRateLimiterService;

@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {

    private final AgentService agentService;
    private final ConversationService conversationService;
    private final LlmRateLimiterService llmRateLimiterService;
    private final WorkspaceServiceClient workspaceServiceClient;
    private final ObjectMapper objectMapper;

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
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-role", required = false) String role,
            @RequestHeader(value = "x-user-roles", required = false) String rolesJson,
            @RequestHeader(value = "x-user-role-level", required = false) Integer roleLevel,
            @RequestHeader(value = "x-workspace-id", required = false) String workspaceId,
            @RequestHeader(value = "x-rag-scope", required = false) String ragScope,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsJson) {

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

        List<String> rolesList = new ArrayList<>();
        if (rolesJson != null && !rolesJson.isBlank()) {
            try {
                rolesList = objectMapper.readValue(rolesJson, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                if (role != null && !role.isBlank()) {
                    rolesList.add(role);
                }
            }
        } else if (role != null && !role.isBlank()) {
            rolesList.add(role);
        }

        List<RAGQueryPayload.DepartmentRole> userDepts = new ArrayList<>();
        if (userDepartmentsJson != null && !userDepartmentsJson.isBlank()) {
            try {
                userDepts = objectMapper.readValue(userDepartmentsJson, new TypeReference<List<RAGQueryPayload.DepartmentRole>>() {});
            } catch (Exception e) {
                // Fallback gracefully: treat as empty list
            }
        }
        if (userDepts.isEmpty() && userId != null) {
            userDepts = workspaceServiceClient.getUserDepartments(userId);
        }

        boolean hasHeadRole = false;
        for (RAGQueryPayload.DepartmentRole dr : userDepts) {
            if (PermissionUtils.isHeadOrDeputy(dr.getRole())) {
                hasHeadRole = true;
                break;
            }
        }

        // Sanitize workspaceId for regular user
        String wsId = request.getWorkspaceId();
        if (wsId == null || wsId.isBlank()) {
            wsId = workspaceId;
        }
        if (wsId == null || wsId.isBlank() || "all".equalsIgnoreCase(wsId) || "GLOBAL".equalsIgnoreCase(wsId)) {
            if (hasHeadRole) {
                wsId = "ALL";
            } else {
                wsId = "default-workspace";
            }
        }

        RAGQueryPayload.UserPermissionContext permissions = RAGQueryPayload.UserPermissionContext.builder()
                .roles(rolesList)
                .roleLevel(roleLevel)
                .workspaceId(wsId)
                .ragScope(ragScope)
                .userDepartments(userDepts)
                .build();

        final Long finalConversationId = conversationId;
        final String chatId = request.getChatId() != null ? request.getChatId() : "unknown";

        llmRateLimiterService.acquireAgentChat();
        try {
            return agentService.runAgent(finalConversationId, request.getMessage(), userId, chatId, request.getProvider(), request.getSkillId(), permissions)
                    .onErrorResume(e -> {
                         log.error("[AgentController] Agent error: {}", e.getMessage());
                         return Flux.just("Đã xảy ra lỗi khi xử lý yêu cầu. Vui lòng thử lại.");
                    })
                    .doFinally(signalType -> llmRateLimiterService.releaseAgentChat());
        } catch (Exception e) {
            llmRateLimiterService.releaseAgentChat();
            throw e;
        }
    }

    /**
     * Stream an agent response for admin.
     */
    @PostMapping(value = "/admin/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public Flux<String> adminAgentChat(
            @RequestBody AgentRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-role", required = false) String role,
            @RequestHeader(value = "x-user-roles", required = false) String rolesJson,
            @RequestHeader(value = "x-user-role-level", required = false) Integer roleLevel,
            @RequestHeader(value = "x-workspace-id", required = false) String workspaceId,
            @RequestHeader(value = "x-rag-scope", required = false) String ragScope,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsJson) {

        log.info("[AgentController Admin] userId={}, chatId={}, message='{}'",
                userId, request.getChatId(), request.getMessage());

        // Resolve or create conversation
        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            String title = "Admin Agent — " + (request.getChatId() != null ? request.getChatId() : "General");
            Conversation conv = conversationService.createConversation(userId, title, request.getChatId());
            conversationId = conv.getId();
        } else {
            // Verify ownership
            conversationService.getConversation(conversationId, userId);
        }

        List<String> rolesList = new ArrayList<>();
        if (rolesJson != null && !rolesJson.isBlank()) {
            try {
                rolesList = objectMapper.readValue(rolesJson, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                if (role != null && !role.isBlank()) {
                    rolesList.add(role);
                }
            }
        } else if (role != null && !role.isBlank()) {
            rolesList.add(role);
        }

        List<RAGQueryPayload.DepartmentRole> userDepts = new ArrayList<>();
        if (userDepartmentsJson != null && !userDepartmentsJson.isBlank()) {
            try {
                userDepts = objectMapper.readValue(userDepartmentsJson, new TypeReference<List<RAGQueryPayload.DepartmentRole>>() {});
            } catch (Exception e) {
                // Fallback gracefully
            }
        }
        if (userDepts.isEmpty() && userId != null) {
            userDepts = workspaceServiceClient.getUserDepartments(userId);
        }

        String wsId = request.getWorkspaceId();
        if (wsId == null || wsId.isBlank()) {
            wsId = workspaceId;
        }

        RAGQueryPayload.UserPermissionContext permissions = RAGQueryPayload.UserPermissionContext.builder()
                .roles(rolesList)
                .roleLevel(roleLevel)
                .workspaceId(wsId)
                .ragScope(ragScope)
                .userDepartments(userDepts)
                .build();

        final Long finalConversationId = conversationId;
        final String chatId = request.getChatId() != null ? request.getChatId() : "unknown";

        llmRateLimiterService.acquireAgentChat();
        try {
            return agentService.runAgent(finalConversationId, request.getMessage(), userId, chatId, request.getProvider(), request.getSkillId(), permissions)
                    .onErrorResume(e -> {
                         log.error("[AgentController Admin] Agent error: {}", e.getMessage());
                         return Flux.just("Đã xảy ra lỗi khi xử lý yêu cầu. Vui lòng thử lại.");
                    })
                    .doFinally(signalType -> llmRateLimiterService.releaseAgentChat());
        } catch (Exception e) {
            llmRateLimiterService.releaseAgentChat();
            throw e;
        }
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
