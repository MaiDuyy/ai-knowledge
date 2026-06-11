package com.security.security.service;

import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.client.WorkspaceServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RAGService Tests")
class RAGServiceTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private VectorStore vectorStore;
    @Mock
    private DocumentService documentService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private ChatMemory chatMemory;
    @Mock
    private WorkspaceServiceClient workspaceServiceClient;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RAGService ragService;

    private java.lang.reflect.Method buildFilterMethod;

    @BeforeEach
    void setUp() throws Exception {
        buildFilterMethod = RAGService.class.getDeclaredMethod("buildFilterExpression", 
                RAGQueryPayload.UserPermissionContext.class, String.class, boolean[].class);
        buildFilterMethod.setAccessible(true);
    }

    private String invokeBuildFilter(RAGQueryPayload.UserPermissionContext context, String userId, boolean[] partialResults) throws Exception {
        return (String) buildFilterMethod.invoke(ragService, context, userId, partialResults);
    }

    @Test
    @DisplayName("Should build filter expression correctly for Admin (Default Scope)")
    void buildFilterExpression_Admin_ReturnsWorkspaceFilterOnly() throws Exception {
        RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("ADMIN"))
                .workspaceId("workspace-abc")
                .build();

        boolean[] partialResults = new boolean[]{false};
        String filter = invokeBuildFilter(context, "user-123", partialResults);
        assertThat(filter).isEqualTo("workspaceId == 'workspace-abc' && (classification == 'PUBLIC' || securityClassification == 'PUBLIC')");
        assertThat(partialResults[0]).isFalse();
    }

    @Test
    @DisplayName("Should build filter expression correctly for Admin with Workspace Scope")
    void buildFilterExpression_Admin_WorkspaceScope() throws Exception {
        // Mock ObjectMapper parsing of x-rag-scope
        com.fasterxml.jackson.databind.node.ObjectNode scopeJson = new ObjectMapper().createObjectNode();
        scopeJson.put("type", "workspace");
        scopeJson.put("id", "ws-target-123");
        when(objectMapper.readTree("{\"type\":\"workspace\",\"id\":\"ws-target-123\"}")).thenReturn(scopeJson);

        RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("ADMIN"))
                .workspaceId("workspace-abc")
                .ragScope("{\"type\":\"workspace\",\"id\":\"ws-target-123\"}")
                .build();

        boolean[] partialResults = new boolean[]{false};
        String filter = invokeBuildFilter(context, "user-123", partialResults);
        assertThat(filter).isEqualTo("workspaceId == 'ws-target-123'");
        assertThat(partialResults[0]).isFalse();
    }

    @Test
    @DisplayName("Should build filter expression correctly for Admin with Department Scope")
    void buildFilterExpression_Admin_DepartmentScope() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode scopeJson = new ObjectMapper().createObjectNode();
        scopeJson.put("type", "department");
        scopeJson.put("id", "dept-target-123");
        when(objectMapper.readTree("{\"type\":\"department\",\"id\":\"dept-target-123\"}")).thenReturn(scopeJson);

        RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("ADMIN"))
                .workspaceId("workspace-abc")
                .ragScope("{\"type\":\"department\",\"id\":\"dept-target-123\"}")
                .build();

        boolean[] partialResults = new boolean[]{false};
        String filter = invokeBuildFilter(context, "user-123", partialResults);
        assertThat(filter).isEqualTo("((workspaceId == '' || workspaceId == 'default-workspace' || workspaceId == 'all') && departmentId == 'dept-target-123')");
        assertThat(partialResults[0]).isFalse();
    }

    @Test
    @DisplayName("Should build filter expression correctly for Guest")
    void buildFilterExpression_Guest_ReturnsPublicClassificationFilter() throws Exception {
        RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("EXTERNAL_GUEST"))
                .workspaceId("workspace-abc")
                .build();

        boolean[] partialResults = new boolean[]{false};
        String filter = invokeBuildFilter(context, "user-123", partialResults);
        assertThat(filter).contains("workspaceId == 'workspace-abc'");
        assertThat(filter).contains("classification == 'PUBLIC'");
    }

    @Test
    @DisplayName("Should build filter expression correctly for Member of Department (Success)")
    void buildFilterExpression_Member_ReturnsDepartmentFilters() throws Exception {
        when(workspaceServiceClient.getWorkspace("workspace-abc", "user-123")).thenReturn(Map.of("departmentId", "dept-1"));

        RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("MEMBER"))
                .workspaceId("workspace-abc")
                .userDepartments(Arrays.asList(
                        new RAGQueryPayload.DepartmentRole("dept-1", "MEMBER")
                ))
                .build();

        boolean[] partialResults = new boolean[]{false};
        String filter = invokeBuildFilter(context, "user-123", partialResults);
        assertThat(filter).contains("workspaceId == 'workspace-abc'");
        assertThat(filter).contains("departmentId == 'dept-1'");
        assertThat(filter).contains("allowedRoles != 'HEAD'");
        assertThat(partialResults[0]).isFalse();
    }

    @Test
    @DisplayName("Should build filter expression correctly for Head of Department (Success)")
    void buildFilterExpression_Head_DoesNotRestrictAllowedRoles() throws Exception {
        when(workspaceServiceClient.getWorkspace("workspace-abc", "user-123")).thenReturn(Map.of("departmentId", "dept-1"));

        RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("MEMBER"))
                .workspaceId("workspace-abc")
                .userDepartments(Arrays.asList(
                        new RAGQueryPayload.DepartmentRole("dept-1", "HEAD")
                ))
                .build();

        boolean[] partialResults = new boolean[]{false};
        String filter = invokeBuildFilter(context, "user-123", partialResults);
        assertThat(filter).contains("workspaceId == 'workspace-abc'");
        assertThat(filter).contains("departmentId == 'dept-1'");
        assertThat(filter).doesNotContain("allowedRoles != 'HEAD'");
        assertThat(partialResults[0]).isFalse();
    }

    @Test
    @DisplayName("Should fallback to current workspace filter and set partialResults to true when client fails")
    void buildFilterExpression_ClientFailure_SetsPartialResultsTrue() throws Exception {
        when(workspaceServiceClient.getWorkspace("workspace-abc", "user-123")).thenThrow(new RuntimeException("Connection failed"));

        RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("MEMBER"))
                .workspaceId("workspace-abc")
                .build();

        boolean[] partialResults = new boolean[]{false};
        String filter = invokeBuildFilter(context, "user-123", partialResults);
        assertThat(filter).isEqualTo("workspaceId == 'workspace-abc'");
        assertThat(partialResults[0]).isTrue();
    }
}
