package com.security.security.event.listener;

import com.security.security.entity.Document;
import com.security.security.entity.SourceImage;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.event.NatsEventPublisher;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.SourceImageRepository;
import com.security.security.service.docling.DoclingClient;
import com.security.security.service.ImageProcessingService;
import com.security.security.service.EmbeddingService;
import com.security.security.service.tika.DocumentProfiler;
import com.security.security.service.tika.SemanticMarkdownChunker;
import com.security.security.service.MrpPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessingListener {

    private final DocumentRepository      documentRepository;
    private final DoclingClient           doclingClient;
    private final ImageProcessingService  imageProcessingService;
    private final EmbeddingService         embeddingService;
    private final DocumentProfiler        documentProfiler;
    private final NatsEventPublisher      natsEventPublisher;
    private final MrpPipelineService      mrpPipelineService;
    private final SourceImageRepository   sourceImageRepository;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @org.springframework.context.event.EventListener
    public void processDocument(Long docId) {
        log.info("[ETL] ▶ Start doc={}", docId);

        Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + docId));

        try {
            setStatus(document, DocStatus.PROCESSING, null);

            // ── Check for duplicate completed document ─────────────────────
            Document duplicateDoc = null;
            String fileHash = document.getFileHash();
            if (fileHash != null && !fileHash.isEmpty()) {
                // 1. Check Redis cache
                if (redisTemplate != null) {
                    try {
                        String cachedIdStr = redisTemplate.opsForValue().get("doc:hash:" + fileHash);
                        if (cachedIdStr != null) {
                            Long cachedId = Long.valueOf(cachedIdStr);
                            duplicateDoc = documentRepository.findById(cachedId).orElse(null);
                        }
                    } catch (Exception e) {
                        log.error("[ETL] Failed to fetch duplicate from Redis: {}", e.getMessage());
                    }
                }
                
                // 2. Fallback to database
                if (duplicateDoc == null) {
                    List<Document> completedDocs = documentRepository.findByFileHashAndStatus(fileHash, DocStatus.COMPLETED);
                    if (!completedDocs.isEmpty()) {
                        duplicateDoc = completedDocs.get(0);
                    }
                }
            }

            String markdown;
            if (duplicateDoc != null) {
                log.info("[ETL] Duplicate completed document found: docId={}, using fast-path bypass.", duplicateDoc.getId());
                markdown = duplicateDoc.getMarkdownContent();
                
                // Fetch and clone source images
                List<SourceImage> originalImages = sourceImageRepository.findBySourceId(duplicateDoc.getId());
                log.info("[ETL] Cloning {} source images from cached doc={}", originalImages.size(), duplicateDoc.getId());
                for (SourceImage originalImage : originalImages) {
                    java.util.UUID newImageId = java.util.UUID.randomUUID();
                    SourceImage cloned = SourceImage.builder()
                            .id(newImageId)
                            .source(document)
                            .minioKey(originalImage.getMinioKey())
                            .pageNumber(originalImage.getPageNumber())
                            .imageIndex(originalImage.getImageIndex())
                            .caption(originalImage.getCaption())
                            .contentType(originalImage.getContentType())
                            .sizeBytes(originalImage.getSizeBytes())
                            .build();
                    sourceImageRepository.save(cloned);
                    
                    // Replace image reference in markdown
                    if (markdown != null) {
                        markdown = markdown.replace("image://" + originalImage.getId().toString(), "image://" + newImageId.toString());
                    }
                }
                
                if (markdown == null) {
                    markdown = "";
                }
            } else {
                // ── G1: Ingestion / Markdown conversion ─────────────────────────
                Resource resource;
                if (document.getFileUrl() != null && !document.getFileUrl().isBlank()) {
                    log.info("[ETL][1] URL: {}", document.getFileUrl());
                    resource = new UrlResource(document.getFileUrl());
                } else {
                    log.info("[ETL][1] Disk: {}", document.getFilePath());
                    resource = new FileSystemResource(document.getFilePath());
                }

                DoclingClient.DoclingResult doclingResult = doclingClient.convertToMarkdown(resource, document.getFileName(), document.getParserMethod(), docId);
                if (!doclingResult.success()) {
                    throw new IllegalStateException("Docling parsing failed: " + doclingResult.errorMessage());
                }
                markdown = doclingResult.markdown();
                log.info("[ETL] Markdown converted successfully. Length: {}", markdown.length());

                // ── Extract, caption and process inline images ──────────────────
                markdown = imageProcessingService.processIngestImages(document, markdown);
            }

            if (markdown == null || markdown.length() < 60) {
                throw new IllegalStateException("Extracted markdown is empty or too short, doc=" + docId);
            }

            // ── Chunking & Vector Store Loading ─────────────────────────────
            List<SemanticMarkdownChunker.ChunkResult> chunkResults = embeddingService.ingestMarkdown(document, markdown);

            // ── Document Profiling ──────────────────────────────────────────
            DocumentProfiler.ProfileResult profile = documentProfiler.profile(markdown, chunkResults);

            document.setStatus(DocStatus.COMPLETED);
            document.setChunkCount(chunkResults.size());
            document.setNumHeadings(profile.numHeadings());
            document.setNumTables(profile.numTables());
            document.setNumParagraphs(profile.numParagraphs());
            document.setTotalTokens(profile.estimatedTokens());
            document.setAvgTokensPerChunk(profile.avgTokensPerChunk());
            document.setMarkdownContent(markdown);
            document.setErrorMessage(null);
            documentRepository.save(document);
            
            if (document.getFileHash() != null && !document.getFileHash().isEmpty() && redisTemplate != null) {
                try {
                    redisTemplate.opsForValue().set("doc:hash:" + document.getFileHash(), String.valueOf(document.getId()));
                    log.info("[Redis] Cached completed file hash mapping: doc:hash:{} -> {}", document.getFileHash(), document.getId());
                } catch (Exception re) {
                    log.error("[Redis] Failed to cache file hash in Redis: {}", re.getMessage());
                }
            }

            natsEventPublisher.publishDocumentStatus(document.getId(), document.getUserId(), document.getWorkspaceId(), "COMPLETED");

            // ── Auto-compile Wiki Pages ─────────────────────────────────────
            try {
                mrpPipelineService.initiateCompile(document.getId(), document.getWorkspaceId(), document.getUserId(), true);
            } catch (Exception e) {
                log.error("[ETL] Failed to trigger wiki compilation for doc={}: {}", docId, e.getMessage());
            }

        } catch (Exception e) {
            log.error("[ETL] ✗ Failed doc={}: {}", docId, e.getMessage(), e);
            setStatus(document, DocStatus.FAILED, e.getMessage());
            throw new RuntimeException("ETL processing failed for doc=" + docId, e);
        }
    }

    private void setStatus(Document doc, DocStatus status, String error) {
        doc.setStatus(status);
        doc.setErrorMessage(error);
        documentRepository.save(doc);
        natsEventPublisher.publishDocumentStatus(doc.getId(), doc.getUserId(), doc.getWorkspaceId(), status.name());
    }
}
