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

import org.springframework.ai.chat.model.ChatModel;

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
    @Mock private ChatModel chatModel;

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
                objectMapper,
                chatModel
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

    @Test
    @DisplayName("Should generate document summary using ChatModel")
    void generateDocumentSummary_WithValidContent_ReturnsSummary() {
        String markdown = "# Title\nSome content";
        when(chatModel.call(anyString())).thenReturn("This is a summary of the document.");

        String summary = embeddingService.generateDocumentSummary(markdown);

        assertThat(summary).isEqualTo("This is a summary of the document.");
        verify(chatModel, times(1)).call(anyString());
    }

    @Test
    @DisplayName("Should generate synthetic Q&A and index them in VectorStore")
    void generateAndIndexQuestions_WithChildEmbeddings_IndexesQuestions() throws InterruptedException {
        Document doc = Document.builder()
                .id(1L)
                .userId("user-1")
                .fileName("policy.pdf")
                .securityClassification(SecurityClassification.INTERNAL)
                .workspaceId("ws-1")
                .departmentId("dept-1")
                .folderPath("/policies/hr")
                .build();

        Embedding child = Embedding.builder()
                .id(100L)
                .documentId(1L)
                .chunkIndex(0)
                .chunkText("Child text content about JWT configurations.")
                .chunkTitle("JWT Config")
                .parentId(50L) // points to parent embedding
                .build();

        when(chatModel.call(anyString())).thenReturn("- Làm thế nào để cấu hình JWT?\n- Thời gian hết hạn mặc định là bao lâu?");

        // Run the Q&A generation method
        embeddingService.generateAndIndexQuestions(doc, List.of(child));

        // Since it runs inside a Virtual Thread, we wait briefly for the thread to execute
        Thread.sleep(500);

        ArgumentCaptor<List<org.springframework.ai.document.Document>> vectorCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(vectorCaptor.capture());

        List<org.springframework.ai.document.Document> addedDocs = vectorCaptor.getValue();
        // The mock returned 2 valid questions (ending with '?')
        assertThat(addedDocs).hasSize(2);

        org.springframework.ai.document.Document q1 = addedDocs.get(0);
        assertThat(q1.getText()).isEqualTo("Làm thế nào để cấu hình JWT?");
        assertThat(q1.getMetadata().get("isQuestion")).isEqualTo("true");
        assertThat(q1.getMetadata().get("parentId")).isEqualTo("50"); // should point to child's parentId (50L)
        assertThat(q1.getMetadata().get("documentId")).isEqualTo("1");
        assertThat(q1.getMetadata().get("userId")).isEqualTo("user-1");

        org.springframework.ai.document.Document q2 = addedDocs.get(1);
        assertThat(q2.getText()).isEqualTo("Thời gian hết hạn mặc định là bao lâu?");
        assertThat(q2.getMetadata().get("isQuestion")).isEqualTo("true");
        assertThat(q2.getMetadata().get("parentId")).isEqualTo("50");
    }
}
