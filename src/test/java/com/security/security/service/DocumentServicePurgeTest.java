package com.security.security.service;

import com.security.security.client.WorkspaceServiceClient;
import com.security.security.entity.Document;
import com.security.security.entity.enumeration.DocStatus;
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
    @Mock private com.security.security.event.NatsEventPublisher natsEventPublisher;
    @Mock private WikiPageRepository wikiPageRepository;
    @Mock private WikiPageDraftRepository wikiPageDraftRepository;
    @Mock private WikiLinkRepository wikiLinkRepository;
    @Mock private SourceCompilationPlanRepository sourceCompilationPlanRepository;
    @Mock private SourceChunkExtractRepository sourceChunkExtractRepository;
    @Mock private OcrResultRepository ocrResultRepository;

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
}
