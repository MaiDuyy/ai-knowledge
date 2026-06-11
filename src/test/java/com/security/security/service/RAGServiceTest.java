package com.security.security.service;

import com.security.security.dtorequest.RAGQueryPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

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

    @InjectMocks
    private RAGService ragService;

    @Test
    @DisplayName("Should build filter expression correctly for Admin")
    void buildFilterExpression_Admin_ReturnsWorkspaceFilterOnly() {
        // Reflection helper isn't needed as we are in package-private boundary
        java.lang.reflect.Method method;
        try {
            method = RAGService.class.getDeclaredMethod("buildFilterExpression", 
                    RAGQueryPayload.UserPermissionContext.class, String.class);
            method.setAccessible(true);
            
            RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                    .roles(Arrays.asList("ADMIN"))
                    .workspaceId("workspace-abc")
                    .build();

            String filter = (String) method.invoke(ragService, context, "user-123");
            assertThat(filter).isEqualTo("(workspaceId == 'workspace-abc' || workspaceId == '' || workspaceId == 'default-workspace')");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Should build filter expression correctly for Guest")
    void buildFilterExpression_Guest_ReturnsPublicClassificationFilter() {
        java.lang.reflect.Method method;
        try {
            method = RAGService.class.getDeclaredMethod("buildFilterExpression", 
                    RAGQueryPayload.UserPermissionContext.class, String.class);
            method.setAccessible(true);
            
            RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                    .roles(Arrays.asList("EXTERNAL_GUEST"))
                    .workspaceId("workspace-abc")
                    .build();

            String filter = (String) method.invoke(ragService, context, "user-123");
            assertThat(filter).contains("(workspaceId == 'workspace-abc' || workspaceId == '' || workspaceId == 'default-workspace')");
            assertThat(filter).contains("classification == 'PUBLIC'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Should build filter expression correctly for Member of Department")
    void buildFilterExpression_Member_ReturnsDepartmentFilters() {
        java.lang.reflect.Method method;
        try {
            method = RAGService.class.getDeclaredMethod("buildFilterExpression", 
                    RAGQueryPayload.UserPermissionContext.class, String.class);
            method.setAccessible(true);
            
            RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                    .roles(Arrays.asList("MEMBER"))
                    .workspaceId("workspace-abc")
                    .userDepartments(Arrays.asList(
                            new RAGQueryPayload.DepartmentRole("dept-1", "MEMBER")
                    ))
                    .build();

            String filter = (String) method.invoke(ragService, context, "user-123");
            assertThat(filter).contains("workspaceId == 'workspace-abc'");
            assertThat(filter).contains("departmentId == 'dept-1' && allowedRoles != 'HEAD'");
            assertThat(filter).contains("uploadedBy == 'user-123'");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
