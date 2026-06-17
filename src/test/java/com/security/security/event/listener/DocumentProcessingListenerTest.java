package com.security.security.event.listener;

import com.security.security.entity.Document;
import com.security.security.entity.SourceImage;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.SourceImageRepository;
import com.security.security.service.docling.DoclingClient;
import com.security.security.service.ImageProcessingService;
import com.security.security.service.EmbeddingService;
import com.security.security.service.tika.DocumentProfiler;
import com.security.security.service.tika.SemanticMarkdownChunker;
import com.security.security.service.MrpPipelineService;
import com.security.security.event.NatsEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentProcessingListener Fast-Path Duplicate Tests")
class DocumentProcessingListenerTest {

    @Mock private DocumentRepository documentRepository;
    @Mock private DoclingClient doclingClient;
    @Mock private ImageProcessingService imageProcessingService;
    @Mock private EmbeddingService embeddingService;
    @Mock private DocumentProfiler documentProfiler;
    @Mock private NatsEventPublisher natsEventPublisher;
    @Mock private MrpPipelineService mrpPipelineService;
    @Mock private SourceImageRepository sourceImageRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private DocumentProcessingListener listener;

    @Test
    @DisplayName("Should skip parsing and clone images on duplicate Completed hash hit in Redis")
    void processDocument_withDuplicateHashHit_skipsParsingAndClonesImages() {
        // Given
        Long newDocId = 101L;
        Long cachedDocId = 202L;
        String fileHash = "duplicate_sha256_hash_value";
        UUID oldImageId = UUID.randomUUID();
        String oldMarkdown = "Here is an image: image://" + oldImageId;

        Document newDoc = Document.builder()
                .id(newDocId)
                .fileName("new.pdf")
                .fileHash(fileHash)
                .status(DocStatus.PROCESSING)
                .build();

        Document cachedDoc = Document.builder()
                .id(cachedDocId)
                .fileName("cached.pdf")
                .fileHash(fileHash)
                .markdownContent(oldMarkdown)
                .status(DocStatus.COMPLETED)
                .build();

        SourceImage oldImage = SourceImage.builder()
                .id(oldImageId)
                .source(cachedDoc)
                .minioKey("minio/keys/image.png")
                .pageNumber(1)
                .imageIndex(0)
                .caption("An important diagram")
                .contentType("image/png")
                .sizeBytes(1024)
                .build();

        when(documentRepository.findById(newDocId)).thenReturn(Optional.of(newDoc));
        
        // Mock Redis hit
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("doc:hash:" + fileHash)).thenReturn(String.valueOf(cachedDocId));
        when(documentRepository.findById(cachedDocId)).thenReturn(Optional.of(cachedDoc));

        // Mock Source Images
        when(sourceImageRepository.findBySourceId(cachedDocId)).thenReturn(List.of(oldImage));

        // Mock chunking and profiling
        List<SemanticMarkdownChunker.ChunkResult> chunkResults = List.of(
                new SemanticMarkdownChunker.ChunkResult("Here is an image: image://cloned_uuid", "Section 1")
        );
        when(embeddingService.ingestMarkdown(eq(newDoc), anyString())).thenReturn(chunkResults);
        when(documentProfiler.profile(anyString(), any())).thenReturn(DocumentProfiler.ProfileResult.empty());

        // When
        listener.processDocument(newDocId);

        // Then
        // Verify parsing client and image extractor are bypassed
        verifyNoInteractions(doclingClient);
        verifyNoInteractions(imageProcessingService);

        // Verify image cloning
        ArgumentCaptor<SourceImage> imageCaptor = ArgumentCaptor.forClass(SourceImage.class);
        verify(sourceImageRepository).save(imageCaptor.capture());
        SourceImage clonedImage = imageCaptor.getValue();
        assertThat(clonedImage.getId()).isNotEqualTo(oldImageId);
        assertThat(clonedImage.getSource()).isEqualTo(newDoc);
        assertThat(clonedImage.getMinioKey()).isEqualTo(oldImage.getMinioKey());

        // Verify updated markdown content passed to embedding service
        ArgumentCaptor<String> markdownCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingService).ingestMarkdown(eq(newDoc), markdownCaptor.capture());
        String finalMarkdown = markdownCaptor.getValue();
        assertThat(finalMarkdown).contains("image://" + clonedImage.getId().toString());
        assertThat(finalMarkdown).doesNotContain("image://" + oldImageId.toString());

        // Verify document is set to COMPLETED and saved
        assertThat(newDoc.getStatus()).isEqualTo(DocStatus.COMPLETED);
        assertThat(newDoc.getMarkdownContent()).isEqualTo(finalMarkdown);
        verify(documentRepository, atLeastOnce()).save(newDoc);
    }
}
