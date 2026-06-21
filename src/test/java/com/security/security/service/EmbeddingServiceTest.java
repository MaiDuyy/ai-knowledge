package com.security.security.service;

import com.security.security.client.WorkspaceServiceClient;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.repository.EmbeddingRepository;
import com.security.security.service.tika.SemanticMarkdownChunker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddingService Scoped RAG Prefix Tests")
class EmbeddingServiceTest {

    @Mock private VectorStore vectorStore;
    @Mock private EmbeddingModel embeddingModel;
    @Mock private EmbeddingRepository embeddingRepository;
    @Mock private SemanticMarkdownChunker semanticMarkdownChunker;
    @Mock private WorkspaceServiceClient workspaceServiceClient;
    @Mock private javax.sql.DataSource dataSource;
    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        embeddingService = new EmbeddingService(
                vectorStore,
                embeddingModel,
                embeddingRepository,
                semanticMarkdownChunker,
                workspaceServiceClient,
                dataSource,
                objectMapper
        );
    }

    @Test
    @DisplayName("Should prepend full absolute prefix context to chunks")
    void ingestMarkdown_WithScopedMetaData_PrependsAbsolutePrefix() {
        Document doc = Document.builder()
                .id(1L)
                .userId("user-1")
                .fileName("policy.pdf")
                .workspaceId("ws-1")
                .departmentId("dept-1")
                .folderPath("/policies/hr")
                .securityClassification(SecurityClassification.INTERNAL)
                .build();

        // Mock workspace and department resolution
        when(workspaceServiceClient.getWorkspace("ws-1", "user-1")).thenReturn(Map.of(
                "name", "Project Alpha"
        ));
        when(workspaceServiceClient.getDepartment("dept-1", "user-1")).thenReturn(Map.of(
                "name", "Human Resources"
        ));

        // Mock chunker
        when(semanticMarkdownChunker.chunk("some content")).thenReturn(List.of(
                new SemanticMarkdownChunker.ChunkResult("some content", "Title 1")
        ));
        when(semanticMarkdownChunker.estimateTokens(anyString())).thenReturn(10);

        embeddingService.ingestMarkdown(doc, "some content");

        // Verify prefix in VectorStore
        ArgumentCaptor<List<org.springframework.ai.document.Document>> vectorCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(vectorCaptor.capture());
        
        List<org.springframework.ai.document.Document> addedDocs = vectorCaptor.getValue();
        assertThat(addedDocs).hasSize(1);
        
        String expectedPrefix = "[Context: Workspace: Project Alpha | Dept: Human Resources | Path: /policies/hr] ";
        org.springframework.ai.document.Document vectorDoc = addedDocs.get(0);
        assertThat(vectorDoc.getText()).isEqualTo(expectedPrefix + "some content");
        assertThat(vectorDoc.getMetadata().get("workspaceId")).isEqualTo("ws-1");
        assertThat(vectorDoc.getMetadata().get("departmentId")).isEqualTo("dept-1");
        assertThat(vectorDoc.getMetadata().get("classification")).isEqualTo("INTERNAL");
        assertThat(vectorDoc.getMetadata().get("securityClassification")).isEqualTo("INTERNAL");
        assertThat(vectorDoc.getMetadata().get("allowedRoles")).isEqualTo("ALL");

        // Verify prefix in database repository
        ArgumentCaptor<List<Embedding>> repoCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingRepository).saveAll(repoCaptor.capture());
        
        List<Embedding> savedEmbeddings = repoCaptor.getValue();
        assertThat(savedEmbeddings).hasSize(1);
        assertThat(savedEmbeddings.get(0).getChunkText()).isEqualTo(expectedPrefix + "some content");
    }

    @Test
    @DisplayName("Should fallback to System/General for workspace-default or empty fields")
    void ingestMarkdown_WithDefaultScope_AppliesFallbackNames() {
        Document doc = Document.builder()
                .id(2L)
                .userId("user-1")
                .fileName("public.pdf")
                .workspaceId("workspace-default")
                .departmentId("")
                .folderPath("/public")
                .build();

        // Mock chunker
        when(semanticMarkdownChunker.chunk("public content")).thenReturn(List.of(
                new SemanticMarkdownChunker.ChunkResult("public content", "Title 1")
        ));

        embeddingService.ingestMarkdown(doc, "public content");

        ArgumentCaptor<List<org.springframework.ai.document.Document>> vectorCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(vectorCaptor.capture());

        List<org.springframework.ai.document.Document> addedDocs = vectorCaptor.getValue();
        
        String expectedPrefix = "[Context: Workspace: System | Dept: General | Path: /public] ";
        assertThat(addedDocs.get(0).getText()).isEqualTo(expectedPrefix + "public content");
    }

    @Test
    @DisplayName("Should extract and normalize security metadata in storeDocumentsFromSync")
    void storeDocumentsFromSync_ExtractsAndNormalizesMetadata() {
        // Arrange
        com.security.security.dtorequest.DocumentSyncPayload.ChunkPayload chunk = 
                com.security.security.dtorequest.DocumentSyncPayload.ChunkPayload.builder()
                        .chunkId("c-1")
                        .content("Sync Chunk Content")
                        .metadata(Map.of(
                                "classification", "PUBLIC"
                        ))
                        .build();

        com.security.security.dtorequest.DocumentSyncPayload.MetadataPayload metadataPayload = 
                com.security.security.dtorequest.DocumentSyncPayload.MetadataPayload.builder()
                        .collectionId("col-1")
                        .uploadedBy("user-1")
                        .acl(List.of(
                                Map.of("workspaceId", "workspace-default"),
                                Map.of("departmentId", "dept-123"),
                                Map.of("allowedRoles", "HEAD")
                        ))
                        .build();

        com.security.security.dtorequest.DocumentSyncPayload payload = 
                com.security.security.dtorequest.DocumentSyncPayload.builder()
                        .documentId("doc-999")
                        .title("Sync Doc Title")
                        .chunks(List.of(chunk))
                        .metadata(metadataPayload)
                        .build();

        // Act
        embeddingService.storeDocumentsFromSync(payload);

        // Assert
        ArgumentCaptor<List<org.springframework.ai.document.Document>> vectorCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(vectorCaptor.capture());

        List<org.springframework.ai.document.Document> addedDocs = vectorCaptor.getValue();
        assertThat(addedDocs).hasSize(1);
        org.springframework.ai.document.Document doc = addedDocs.get(0);
        assertThat(doc.getText()).isEqualTo("Sync Chunk Content");
        assertThat(doc.getMetadata().get("documentId")).isEqualTo("doc-999");
        assertThat(doc.getMetadata().get("fileName")).isEqualTo("Sync Doc Title");
        assertThat(doc.getMetadata().get("collectionId")).isEqualTo("col-1");
        assertThat(doc.getMetadata().get("uploadedBy")).isEqualTo("user-1");

        // Verify normalized security metadata
        assertThat(doc.getMetadata().get("workspaceId")).isEqualTo("ALL"); // workspace-default normalizes to ALL
        assertThat(doc.getMetadata().get("departmentId")).isEqualTo("dept-123");
        assertThat(doc.getMetadata().get("allowedRoles")).isEqualTo("HEAD");
        assertThat(doc.getMetadata().get("classification")).isEqualTo("PUBLIC");
        assertThat(doc.getMetadata().get("securityClassification")).isEqualTo("PUBLIC");
    }
}
