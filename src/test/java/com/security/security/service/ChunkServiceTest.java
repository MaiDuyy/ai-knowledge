//package com.security.security.service;
//
//import com.security.security.dto.ChunkDTO;
//import com.security.security.dto.ChunkSearchResponse;
//import com.security.security.dto.DocumentChunksResponse;
//import com.security.security.dto.DocumentStatsDTO;
//import com.security.security.entity.Document;
//import com.security.security.entity.Embedding;
//import com.security.security.entity.enumeration.DocStatus;
//import com.security.security.entity.enumeration.DocType;
//import com.security.security.repository.EmbeddingRepository;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.time.LocalDateTime;
//import java.util.List;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.assertj.core.api.Assertions.assertThatThrownBy;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//@DisplayName("ChunkService Tests")
//class ChunkServiceTest {
//
//    @Mock
//    private EmbeddingRepository embeddingRepository;
//
//    @Mock
//    private EmbeddingService embeddingService;
//
//    @Mock
//    private DocumentService documentService;
//
//    @InjectMocks
//    private ChunkService chunkService;
//
//    private Document testDocument;
//    private Embedding testEmbedding;
//
//    @BeforeEach
//    void setUp() {
//        testDocument = Document.builder()
//                .id(1L)
//                .userId(100L)
//                .fileName("test.pdf")
//                .fileSize(1024)
//                .status(DocStatus.COMPLETED)
//                .documentType(DocType.pdf)
//                .chunkCount(3)
//                .build();
//
//        testEmbedding = Embedding.builder()
//                .id(1L)
//                .documentId(1L)
//                .chunkIndex(0)
//                .chunkText("This is test chunk content")
//                .embedding("[0.1, 0.2, 0.3]")
//                .tokenCount(10)
//                .charCount(26)
//                .createdAt(LocalDateTime.now())
//                .build();
//    }
//
//    @Test
//    @DisplayName("Should get all chunks for a document")
//    void getDocumentChunks_ValidDocument_ReturnsChunks() {
//        when(documentService.getDocument(1L, 100L)).thenReturn(testDocument);
//        when(embeddingRepository.findByDocumentIdOrderByChunkIndex(1L))
//                .thenReturn(List.of(testEmbedding));
//
//        DocumentChunksResponse result = chunkService.getDocumentChunks(1L, 100L);
//
//        assertThat(result.getDocumentId()).isEqualTo(1L);
//        assertThat(result.getFileName()).isEqualTo("test.pdf");
//        assertThat(result.getTotalChunks()).isEqualTo(1);
//        assertThat(result.getChunks()).hasSize(1);
//    }
//
//    @Test
//    @DisplayName("Should get specific chunk by index")
//    void getChunk_ValidIndex_ReturnsChunk() {
//        when(documentService.getDocument(1L, 100L)).thenReturn(testDocument);
//        when(embeddingRepository.findByDocumentIdOrderByChunkIndex(1L))
//                .thenReturn(List.of(testEmbedding));
//
//        ChunkDTO result = chunkService.getChunk(1L, 0, 100L);
//
//        assertThat(result.getChunkIndex()).isEqualTo(0);
//        assertThat(result.getText()).isEqualTo("This is test chunk content");
//    }
//
//    @Test
//    @DisplayName("Should throw exception when chunk not found")
//    void getChunk_InvalidIndex_ThrowsException() {
//        when(documentService.getDocument(1L, 100L)).thenReturn(testDocument);
//        when(embeddingRepository.findByDocumentIdOrderByChunkIndex(1L))
//                .thenReturn(List.of(testEmbedding));
//
//        assertThatThrownBy(() -> chunkService.getChunk(1L, 999, 100L))
//                .isInstanceOf(RuntimeException.class)
//                .hasMessageContaining("Chunk not found");
//    }
//
//    @Test
//    @DisplayName("Should get document statistics")
//    void getDocumentStats_ValidDocument_ReturnsStats() {
//        when(documentService.getDocument(1L, 100L)).thenReturn(testDocument);
//        when(embeddingRepository.findByDocumentId(1L))
//                .thenReturn(List.of(testEmbedding));
//
//        DocumentStatsDTO result = chunkService.getDocumentStats(1L, 100L);
//
//        assertThat(result.getDocumentId()).isEqualTo(1L);
//        assertThat(result.getFileName()).isEqualTo("test.pdf");
//        assertThat(result.getTotalChunks()).isEqualTo(1);
//        assertThat(result.getTotalTokens()).isEqualTo(10);
//        assertThat(result.getTotalCharacters()).isEqualTo(26);
//    }
//
//    @Test
//    @DisplayName("Should return empty search results when no documents")
//    void searchChunks_NoDocuments_ReturnsEmptyResults() {
//        when(documentService.getCompletedDocuments(100L)).thenReturn(List.of());
//
//        ChunkSearchResponse result = chunkService.searchChunks("test query", 5, 0.5, 100L);
//
//        assertThat(result.getTotalResults()).isZero();
//        assertThat(result.getChunks()).isEmpty();
//    }
//
//    @Test
//    @DisplayName("Should search chunks by similarity")
//    void searchChunks_WithDocuments_ReturnsMatchingChunks() {
//        float[] queryEmbedding = new float[]{0.1f, 0.2f, 0.3f};
//        float[] chunkEmbedding = new float[]{0.1f, 0.2f, 0.3f};  // Same = similarity 1.0
//
//        when(documentService.getCompletedDocuments(100L)).thenReturn(List.of(testDocument));
//        when(embeddingService.embed("test query")).thenReturn("[0.1, 0.2, 0.3]");
//        when(embeddingService.parseEmbedding("[0.1, 0.2, 0.3]")).thenReturn(queryEmbedding);
//        when(embeddingRepository.findByDocumentId(1L)).thenReturn(List.of(testEmbedding));
//        when(embeddingService.parseEmbedding(testEmbedding.getEmbedding())).thenReturn(chunkEmbedding);
//        when(embeddingService.cosineSimilarity(queryEmbedding, chunkEmbedding)).thenReturn(0.9);
//
//        ChunkSearchResponse result = chunkService.searchChunks("test query", 5, 0.5, 100L);
//
//        assertThat(result.getTotalResults()).isEqualTo(1);
//        assertThat(result.getChunks().get(0).getSimilarity()).isEqualTo(0.9);
//    }
//}
