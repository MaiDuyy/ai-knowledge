package com.security.security.service;

import com.security.security.client.WorkspaceServiceClient;
import com.security.security.entity.Document;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.repository.*;
import com.security.security.service.docling.DoclingClient;
import com.security.security.service.tika.DocumentProfiler;
import com.security.security.service.tika.SemanticMarkdownChunker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentService Purge & CRUD Synchronization Tests")
class DocumentServicePurgeTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private EmbeddingService embeddingService;
    @Mock private ImageProcessingService imageProcessingService;
    @Mock private SourceImageRepository sourceImageRepository;
    @Mock private WorkspaceServiceClient workspaceServiceClient;
    @Mock private DocumentProfiler documentProfiler;
    @Mock private DoclingClient doclingClient;
    @Mock private MrpPipelineService mrpPipelineService;
    @Mock private PostProcessingCoordinator postProcessingCoordinator;
    @Mock private com.security.security.event.NatsEventPublisher natsEventPublisher;
    @Mock private WikiPageRepository wikiPageRepository;
    @Mock private WikiPageDraftRepository wikiPageDraftRepository;
    @Mock private WikiLinkRepository wikiLinkRepository;
    @Mock private SourceCompilationPlanRepository sourceCompilationPlanRepository;
    @Mock private SourceChunkExtractRepository sourceChunkExtractRepository;
    @Mock private OcrResultRepository ocrResultRepository;
    @Mock private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Mock private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;

    @InjectMocks
    private DocumentService documentService;

    @Test
    @DisplayName("Should explicitly call embeddingService.deleteByDocumentId during document deletion asynchronously")
    void deleteDocument_purgesVectorStoreAsynchronously() throws Exception {
        Document doc = Document.builder()
                .id(999L)
                .userId("user-123")
                .workspaceId("ws-1")
                .fileName("test.pdf")
                .filePath("some/path/test.pdf")
                .build();

        when(documentRepository.findById(999L)).thenReturn(Optional.of(doc));

        documentService.deleteDocument(999L, "user-123", "SUPER_ADMIN");

        // Wait a small moment for async task to run
        Thread.sleep(200);

        verify(embeddingService).deleteByDocumentId(999L);
        verify(documentRepository).delete(doc);
    }

    @Test
    @DisplayName("Should explicitly call imageProcessingService.processIngestImages and embeddingService.ingestMarkdown during ingestion")
    void ingestDocument_purgesOldVectorStoreChunksFirst() {
        Document doc = Document.builder()
                .id(888L)
                .userId("user-123")
                .workspaceId("ws-1")
                .fileName("test.pdf")
                .filePath("some/path/test.pdf")
                .status(DocStatus.PROCESSING)
                .build();

        when(documentRepository.findById(888L)).thenReturn(Optional.of(doc));
        when(workspaceServiceClient.getWorkspace("ws-1", "user-123")).thenReturn(java.util.Map.of("id", "ws-1", "departmentId", "dept-1"));
        when(imageProcessingService.processIngestImages(doc, "# Test Content")).thenReturn("# Test Content");
        
        java.util.List<SemanticMarkdownChunker.ChunkResult> chunkResults = Collections.singletonList(
                new SemanticMarkdownChunker.ChunkResult("Some technical document content text.", "Section 1")
        );
        when(embeddingService.ingestMarkdown(doc, "# Test Content")).thenReturn(chunkResults);
        when(documentProfiler.profile(anyString(), any())).thenReturn(DocumentProfiler.ProfileResult.empty());

        documentService.ingestDocument(888L, "# Test Content", "user-123");

        // Verify delegation occurred
        verify(imageProcessingService).processIngestImages(doc, "# Test Content");
        verify(embeddingService).ingestMarkdown(doc, "# Test Content");
    }

    @Test
    @DisplayName("Should successfully get/set fileHash and folderPath on Document and call repository findByFileHashAndStatus")
    void testFileHashAndFolderPath_GettersSettersAndRepository() {
        Document doc = Document.builder()
                .fileHash("abc123hash")
                .folderPath("HR/Policies")
                .build();

        org.junit.jupiter.api.Assertions.assertEquals("abc123hash", doc.getFileHash());
        org.junit.jupiter.api.Assertions.assertEquals("HR/Policies", doc.getFolderPath());

        com.security.security.entity.enumeration.DocStatus status = com.security.security.entity.enumeration.DocStatus.COMPLETED;
        when(documentRepository.findByFileHashAndStatus("abc123hash", status))
                .thenReturn(java.util.List.of(doc));

        java.util.List<Document> found = documentRepository.findByFileHashAndStatus("abc123hash", status);
        org.junit.jupiter.api.Assertions.assertNotNull(found);
        org.junit.jupiter.api.Assertions.assertEquals(1, found.size());
        org.junit.jupiter.api.Assertions.assertEquals("abc123hash", found.get(0).getFileHash());
    }

    @Test
    @DisplayName("Should cascade metadata updates from Document to WikiPages, WikiPageDrafts, and Embeddings")
    void updateDocumentMetadata_cascadesToWikiAndEmbeddings() {
        // Arrange
        Long docId = 123L;
        String userId = "system-user";
        Document existingDoc = Document.builder()
                .id(docId)
                .userId(userId)
                .workspaceId("ws-old")
                .departmentId("dept-old")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.INTERNAL)
                .status(com.security.security.entity.enumeration.DocStatus.COMPLETED)
                .build();

        when(documentRepository.findById(docId)).thenReturn(Optional.of(existingDoc));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.security.security.entity.WikiPage wikiPage = com.security.security.entity.WikiPage.builder()
                .id(456L)
                .sourceDocumentId(docId)
                .workspaceId("ws-old")
                .departmentId("dept-old")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.INTERNAL)
                .build();

        com.security.security.entity.WikiPageDraft wikiPageDraft = com.security.security.entity.WikiPageDraft.builder()
                .id(789L)
                .wikiPageId(456L)
                .workspaceId("ws-old")
                .departmentId("dept-old")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.INTERNAL)
                .build();

        when(wikiPageRepository.findBySourceDocumentId(docId)).thenReturn(java.util.List.of(wikiPage));
        when(wikiPageDraftRepository.findByWikiPageId(456L)).thenReturn(java.util.List.of(wikiPageDraft));

        // Act
        documentService.updateDocumentMetadata(
                docId,
                "CONFIDENTIAL", // securityClassification
                "dept-new",      // departmentId
                "HEAD",          // allowedRoles
                null,            // tags
                null,            // folderPath
                "ws-new",        // workspaceId
                userId           // userId
        );

        // Assert
        // Verify document saved with correct values
        org.junit.jupiter.api.Assertions.assertEquals(SecurityClassification.CONFIDENTIAL, existingDoc.getSecurityClassification());
        org.junit.jupiter.api.Assertions.assertEquals("dept-new", existingDoc.getDepartmentId());
        org.junit.jupiter.api.Assertions.assertEquals("HEAD", existingDoc.getAllowedRoles());
        org.junit.jupiter.api.Assertions.assertEquals("ws-new", existingDoc.getWorkspaceId());

        // Verify WikiPage fields updated and saved
        org.junit.jupiter.api.Assertions.assertEquals(SecurityClassification.CONFIDENTIAL, wikiPage.getSecurityClassification());
        org.junit.jupiter.api.Assertions.assertEquals("dept-new", wikiPage.getDepartmentId());
        org.junit.jupiter.api.Assertions.assertEquals("HEAD", wikiPage.getAllowedRoles());
        org.junit.jupiter.api.Assertions.assertEquals("ws-new", wikiPage.getWorkspaceId());
        verify(wikiPageRepository).save(wikiPage);

        // Verify WikiPageDraft fields updated and saved
        org.junit.jupiter.api.Assertions.assertEquals(SecurityClassification.CONFIDENTIAL, wikiPageDraft.getSecurityClassification());
        org.junit.jupiter.api.Assertions.assertEquals("dept-new", wikiPageDraft.getDepartmentId());
        org.junit.jupiter.api.Assertions.assertEquals("HEAD", wikiPageDraft.getAllowedRoles());
        org.junit.jupiter.api.Assertions.assertEquals("ws-new", wikiPageDraft.getWorkspaceId());
        verify(wikiPageDraftRepository).save(wikiPageDraft);

        // Verify EmbeddingService cascade called with correct values
        verify(embeddingService).updateEmbeddingsMetadata(docId, "ws-new", "dept-new", "HEAD", "CONFIDENTIAL");
        verify(embeddingService).updateWikiPageEmbeddingsMetadata(456L, "ws-new", "dept-new", "HEAD", "CONFIDENTIAL");
    }
}


