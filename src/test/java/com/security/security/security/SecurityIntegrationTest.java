package com.security.security.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.security.security.client.WorkspaceServiceClient;
import com.security.security.entity.Document;
import com.security.security.entity.WikiPageDraft;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.WikiPageDraftRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Security Integration Tests")
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private WikiPageDraftRepository wikiPageDraftRepository;

    @Autowired
    private com.security.security.service.EmbeddingService embeddingService;

    @MockBean
    private WorkspaceServiceClient workspaceServiceClient;

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private io.nats.client.Connection natsConnection;

    @AfterEach
    void tearDown() {
        wikiPageDraftRepository.deleteAll();
        documentRepository.deleteAll();
    }

    @Test
    @DisplayName("Should return 401 Unauthorized for unauthenticated requests to protected endpoints")
    void whenUnauthenticated_shouldReturnUnauthorizedForProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should allow access to public endpoints (healthz) without authentication")
    void whenUnauthenticated_shouldAllowAccessToPublicEndpoints() throws Exception {
        mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should return 401 Unauthorized when x-user-id header is provided without valid gateway key")
    void whenSpoofingUserIdWithoutGatewayKey_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/documents")
                        .header("x-user-id", "admin")
                        .header("x-user-role", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Should authenticate and allow access when valid gateway key and user headers are provided")
    void whenProvidingValidGatewayKeyAndUserId_shouldAuthenticate() throws Exception {
        mockMvc.perform(get("/documents")
                        .header("x-internal-gateway-key", "test-gateway-key")
                        .header("x-user-id", "test-user")
                        .header("x-user-role", "WORKSPACE_MEMBER"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should return 403 Forbidden when user from other workspace accesses document")
    void whenUserFromOtherWorkspaceAccessesDocument_shouldReturnForbidden() throws Exception {
        Document doc = Document.builder()
                .userId("owner-user")
                .workspaceId("workspace-a")
                .fileName("secret.pdf")
                .fileSize(1024)
                .filePath("uploads/secret.pdf")
                .documentType(com.security.security.entity.enumeration.DocType.pdf)
                .status(com.security.security.entity.enumeration.DocStatus.COMPLETED)
                .chunkCount(1)
                .build();
        doc = documentRepository.save(doc);

        Mockito.when(workspaceServiceClient.getWorkspace("workspace-a", "user-b"))
                .thenReturn(java.util.Map.of());

        mockMvc.perform(get("/documents/" + doc.getId())
                        .header("x-internal-gateway-key", "test-gateway-key")
                        .header("x-user-id", "user-b")
                        .header("x-user-role", "WORKSPACE_MEMBER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should return 403 Forbidden when user from other workspace downloads document raw file")
    void whenUserFromOtherWorkspaceDownloadsDocumentRaw_shouldReturnForbidden() throws Exception {
        Document doc = Document.builder()
                .userId("owner-user")
                .workspaceId("workspace-a")
                .fileName("secret.pdf")
                .fileSize(1024)
                .filePath("uploads/secret.pdf")
                .documentType(com.security.security.entity.enumeration.DocType.pdf)
                .status(com.security.security.entity.enumeration.DocStatus.COMPLETED)
                .chunkCount(1)
                .build();
        doc = documentRepository.save(doc);

        Mockito.when(workspaceServiceClient.getWorkspace("workspace-a", "user-b"))
                .thenReturn(java.util.Map.of());

        mockMvc.perform(get("/documents/" + doc.getId() + "/raw")
                        .header("x-internal-gateway-key", "test-gateway-key")
                        .header("x-user-id", "user-b")
                        .header("x-user-role", "WORKSPACE_MEMBER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should return 403 Forbidden when user from other workspace deletes document")
    void whenUserFromOtherWorkspaceDeletesDocument_shouldReturnForbidden() throws Exception {
        Document doc = Document.builder()
                .userId("owner-user")
                .workspaceId("workspace-a")
                .fileName("secret.pdf")
                .fileSize(1024)
                .filePath("uploads/secret.pdf")
                .documentType(com.security.security.entity.enumeration.DocType.pdf)
                .status(com.security.security.entity.enumeration.DocStatus.COMPLETED)
                .chunkCount(1)
                .build();
        doc = documentRepository.save(doc);

        Mockito.when(workspaceServiceClient.getWorkspace("workspace-a", "user-b"))
                .thenReturn(java.util.Map.of());

        mockMvc.perform(delete("/documents/" + doc.getId())
                        .header("x-internal-gateway-key", "test-gateway-key")
                        .header("x-user-id", "user-b")
                        .header("x-user-role", "WORKSPACE_MEMBER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should return 403 Forbidden when regular member tries to approve draft")
    void whenRegularMemberApprovesDraft_shouldReturnForbidden() throws Exception {
        WikiPageDraft draft = WikiPageDraft.builder()
                .slug("test-page")
                .title("Test Page")
                .content("Some content")
                .workspaceId("workspace-a")
                .departmentId("dept-a")
                .authorId("author-user")
                .status("PENDING")
                .build();
        draft = wikiPageDraftRepository.save(draft);

        Mockito.when(workspaceServiceClient.getWorkspace("workspace-a", "user-b"))
                .thenReturn(java.util.Map.of("id", "workspace-a"));

        mockMvc.perform(post("/api/mrp/drafts/" + draft.getId() + "/approve")
                        .header("x-internal-gateway-key", "test-gateway-key")
                        .header("x-user-id", "user-b")
                        .header("x-user-role", "WORKSPACE_MEMBER")
                        .header("x-user-roles", "[\"WORKSPACE_MEMBER\"]")
                        .header("x-user-departments", "[{\"departmentId\":\"dept-a\",\"role\":\"MEMBER\"}]"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should return 403 Forbidden when regular member tries to reject draft")
    void whenRegularMemberRejectsDraft_shouldReturnForbidden() throws Exception {
        WikiPageDraft draft = WikiPageDraft.builder()
                .slug("test-page")
                .title("Test Page")
                .content("Some content")
                .workspaceId("workspace-a")
                .departmentId("dept-a")
                .authorId("author-user")
                .status("PENDING")
                .build();
        draft = wikiPageDraftRepository.save(draft);

        Mockito.when(workspaceServiceClient.getWorkspace("workspace-a", "user-b"))
                .thenReturn(java.util.Map.of("id", "workspace-a"));

        mockMvc.perform(post("/api/mrp/drafts/" + draft.getId() + "/reject")
                        .header("x-internal-gateway-key", "test-gateway-key")
                        .header("x-user-id", "user-b")
                        .header("x-user-role", "WORKSPACE_MEMBER")
                        .header("x-user-roles", "[\"WORKSPACE_MEMBER\"]")
                        .header("x-user-departments", "[{\"departmentId\":\"dept-a\",\"role\":\"MEMBER\"}]")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Rejected\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should return 403 Forbidden when regular member tries to request changes on draft")
    void whenRegularMemberRequestsChangesOnDraft_shouldReturnForbidden() throws Exception {
        WikiPageDraft draft = WikiPageDraft.builder()
                .slug("test-page")
                .title("Test Page")
                .content("Some content")
                .workspaceId("workspace-a")
                .departmentId("dept-a")
                .authorId("author-user")
                .status("PENDING")
                .build();
        draft = wikiPageDraftRepository.save(draft);

        Mockito.when(workspaceServiceClient.getWorkspace("workspace-a", "user-b"))
                .thenReturn(java.util.Map.of("id", "workspace-a"));

        mockMvc.perform(post("/api/mrp/drafts/" + draft.getId() + "/request-changes")
                        .header("x-internal-gateway-key", "test-gateway-key")
                        .header("x-user-id", "user-b")
                        .header("x-user-role", "WORKSPACE_MEMBER")
                        .header("x-user-roles", "[\"WORKSPACE_MEMBER\"]")
                        .header("x-user-departments", "[{\"departmentId\":\"dept-a\",\"role\":\"MEMBER\"}]")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Changes requested\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Should reject CORS requests from unauthorized wildcard origins")
    void whenCorsRequestFromUnauthorizedOrigin_shouldNotAllow() throws Exception {
        mockMvc.perform(options("/documents")
                        .header("Origin", "http://localhost:9999")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Should allow CORS requests from authorized specific origins")
    void whenCorsRequestFromAuthorizedOrigin_shouldAllow() throws Exception {
        mockMvc.perform(options("/documents")
                        .header("Origin", "http://localhost:3002")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "http://localhost:3002"));
    }

    @Test
    @DisplayName("Should successfully load all chunks for a document without NullPointerException")
    void whenGettingDocumentChunks_shouldNotThrowNullPointerException() {
        java.util.List<String> chunks = embeddingService.getDocumentChunks(999L);
        org.assertj.core.api.Assertions.assertThat(chunks).isEmpty();
    }
}
