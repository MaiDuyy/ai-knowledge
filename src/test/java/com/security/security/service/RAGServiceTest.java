package com.security.security.service;

import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.client.WorkspaceServiceClient;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.WikiLinkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.entity.enumeration.SecurityClassification;
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
import java.util.List;

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
    @Mock
    private WikiPageRepository wikiPageRepository;
    @Mock
    private WikiLinkRepository wikiLinkRepository;

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
        when(workspaceServiceClient.getWorkspace("workspace-abc", "user-123")).thenReturn(Map.of("id", "workspace-abc"));

        RAGQueryPayload.UserPermissionContext context = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("ADMIN"))
                .workspaceId("workspace-abc")
                .build();

        boolean[] partialResults = new boolean[]{false};
        String filter = invokeBuildFilter(context, "user-123", partialResults);
        assertThat(filter).isEqualTo("workspaceId == 'workspace-abc'");
        assertThat(partialResults[0]).isFalse();
    }

    @Test
    @DisplayName("Should build filter expression correctly for Admin with Workspace Scope")
    void buildFilterExpression_Admin_WorkspaceScope() throws Exception {
        when(workspaceServiceClient.getWorkspace("workspace-abc", "user-123")).thenReturn(Map.of("id", "workspace-abc"));

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
        when(workspaceServiceClient.getWorkspace("workspace-abc", "user-123")).thenReturn(Map.of("id", "workspace-abc"));

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
        assertThat(filter).isEqualTo("(workspaceId == 'GLOBAL' && departmentId == 'dept-target-123')");
        assertThat(partialResults[0]).isFalse();
    }

    @Test
    @DisplayName("Should build filter expression correctly for Guest")
    void buildFilterExpression_Guest_ReturnsPublicClassificationFilter() throws Exception {
        when(workspaceServiceClient.getWorkspace("workspace-abc", "user-123")).thenReturn(Map.of("id", "workspace-abc"));

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

    @Test
    @DisplayName("Should blend results from Vector Store and SQL Keyword Search using RRF")
    void executeHybridSearchAndExpansion_BlendsResultsUsingRRF() {
        // Arrange
        String query = "test query";
        String userId = "user-123";
        int maxResults = 5;
        double minScore = 0.2;

        RAGQueryPayload.UserPermissionContext permissions = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("ADMIN"))
                .workspaceId("workspace-abc")
                .build();

        org.springframework.ai.document.Document vectorDoc = new org.springframework.ai.document.Document("doc-id-1", "Vector Match content", Map.of("fileName", "file1.txt"));
        
        com.security.security.entity.WikiPage keywordPage = new com.security.security.entity.WikiPage();
        keywordPage.setId(101L);
        keywordPage.setTitle("Keyword Match Title");
        keywordPage.setContent("Keyword Match Content");
        keywordPage.setWorkspaceId("workspace-abc");
        keywordPage.setSlug("keyword-slug");
        keywordPage.setSecurityClassification(SecurityClassification.PUBLIC);

        // Mock Vector Store Search
        when(vectorStore.similaritySearch(Mockito.any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(Arrays.asList(vectorDoc));

        // Mock Database Search
        when(wikiPageRepository.searchAccessiblePagesByKeyword(
                Mockito.eq("workspace-abc"),
                Mockito.any(),
                Mockito.eq(true),
                Mockito.anyList(),
                Mockito.anyList(),
                Mockito.eq(query)
        )).thenReturn(Arrays.asList(keywordPage));

        // Mock WorkspaceServiceClient for buildFilterExpression
        when(workspaceServiceClient.getWorkspace("workspace-abc", userId))
                .thenReturn(Map.of("id", "workspace-abc"));

        // Act
        List<org.springframework.ai.document.Document> results = ragService.executeHybridSearchAndExpansion(
                query, permissions, userId, maxResults, minScore
        );

        // Assert
        assertThat(results).hasSize(2);
        
        // Assert that the keyword matched page was successfully wrapped as a Document
        boolean foundVectorDoc = false;
        boolean foundKeywordDoc = false;
        for (org.springframework.ai.document.Document doc : results) {
            if (doc.getText().contains("Vector Match content")) {
                foundVectorDoc = true;
            } else if (doc.getText().contains("Keyword Match Content")) {
                foundKeywordDoc = true;
                assertThat(doc.getMetadata().get("wikiPageId")).isEqualTo("101");
                assertThat(doc.getMetadata().get("slug")).isEqualTo("keyword-slug");
            }
        }
        assertThat(foundVectorDoc).isTrue();
        assertThat(foundKeywordDoc).isTrue();
    }

    @Test
    @DisplayName("Should expand context with linked Wiki Pages (incoming and outgoing)")
    void expandContextWithWikiGraph_RetrievesLinks() {
        // Arrange
        String userId = "user-123";
        RAGQueryPayload.UserPermissionContext permissions = RAGQueryPayload.UserPermissionContext.builder()
                .roles(Arrays.asList("ADMIN"))
                .workspaceId("workspace-abc")
                .build();

        // 1. Base doc representing a Wiki page (has wikiPageId in metadata)
        org.springframework.ai.document.Document baseDoc = new org.springframework.ai.document.Document(
                "Base content",
                Map.of("wikiPageId", "1", "slug", "base-slug")
        );

        // Current page in DB
        com.security.security.entity.WikiPage currentPage = new com.security.security.entity.WikiPage();
        currentPage.setId(1L);
        currentPage.setSlug("base-slug");
        currentPage.setWorkspaceId("workspace-abc");
        currentPage.setTitle("Base Page Title");
        currentPage.setContent("Base Page Content");
        currentPage.setSecurityClassification(SecurityClassification.PUBLIC);

        // Linked target page (outgoing link target)
        com.security.security.entity.WikiPage targetPage = new com.security.security.entity.WikiPage();
        targetPage.setId(2L);
        targetPage.setSlug("target-slug");
        targetPage.setWorkspaceId("workspace-abc");
        targetPage.setTitle("Target Page Title");
        targetPage.setSummary("Target Page Summary");
        targetPage.setSecurityClassification(SecurityClassification.PUBLIC);

        // Mock wikiPageRepository findById for base page
        when(wikiPageRepository.findById(1L)).thenReturn(java.util.Optional.of(currentPage));

        // Mock outgoing link base-slug -> target-slug
        com.security.security.entity.WikiLink link = new com.security.security.entity.WikiLink(10L, 1L, "target-slug");
        when(wikiLinkRepository.findByFromPageId(1L)).thenReturn(Arrays.asList(link));
        when(wikiPageRepository.fetchBySlugAndWorkspaceId("target-slug", "workspace-abc"))
                .thenReturn(java.util.Optional.of(targetPage));

        // Act
        List<org.springframework.ai.document.Document> results = ragService.expandContextWithWikiGraph(
                Arrays.asList(baseDoc), permissions, userId
        );

        // Assert
        assertThat(results).hasSize(2); // base doc + expanded doc
        org.springframework.ai.document.Document expanded = results.get(1);
        assertThat(expanded.getText()).contains("Target Page Summary");
        assertThat(expanded.getMetadata().get("type")).isEqualTo("wiki-graph-extension");
        assertThat(expanded.getMetadata().get("slug")).isEqualTo("target-slug");
    }
}
