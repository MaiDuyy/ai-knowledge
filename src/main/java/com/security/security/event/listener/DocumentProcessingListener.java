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
import com.security.security.service.PostProcessingCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

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
    private final PostProcessingCoordinator postProcessingCoordinator;
    private final SourceImageRepository   sourceImageRepository;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final ChatModel               chatModel;

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
                String ext = getFileExtension(document.getFileName()).toLowerCase();
                boolean isAudio = ".mp3".equals(ext) || ".wav".equals(ext) || ".m4a".equals(ext);

                if (isAudio) {
                    log.info("[ETL] Audio file detected: {}. Processing with Gemini ASR.", document.getFileName());
                    
                    Resource resource;
                    if (document.getFileUrl() != null && !document.getFileUrl().isBlank()) {
                        resource = new UrlResource(document.getFileUrl());
                    } else {
                        resource = new FileSystemResource(document.getFilePath());
                    }

                    String mimeType = "audio/mp3";
                    if (".wav".equals(ext)) {
                        mimeType = "audio/wav";
                    } else if (".m4a".equals(ext)) {
                        mimeType = "audio/x-m4a";
                    }

                    Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), resource);
                    ChatClient chatClient = ChatClient.builder(chatModel).build();
                    ChatResponse response = chatClient.prompt()
                            .options(GoogleGenAiChatOptions.builder()
                                    .model("gemini-2.5-flash")
                                    .temperature(0.0)
                                    .build())
                            .system("Bạn là một hệ thống tự động ghi âm và chuyển đổi âm thanh sang văn bản. Nhiệm vụ của bạn là nghe file âm thanh được cung cấp và chuyển toàn bộ nội dung lời nói sang văn bản Markdown chính xác. Trả về trực tiếp văn bản Markdown sạch, không có phần giải thích hay thẻ ```markdown xung quanh.")
                            .user(u -> u.text("Hãy chuyển đổi file âm thanh này sang văn bản Markdown:").media(media))
                            .call()
                            .chatResponse();

                    if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                        markdown = response.getResult().getOutput().getText();
                        if (markdown != null) {
                            markdown = cleanMarkdown(markdown);
                        }
                    } else {
                        throw new IllegalStateException("Gemini ASR returned empty response");
                    }
                    log.info("[ETL] Audio transcription completed successfully. Length: {}", markdown != null ? markdown.length() : 0);
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
            }

            if (markdown == null || markdown.length() < 60) {
                throw new IllegalStateException("Extracted markdown is empty or too short, doc=" + docId);
            }

            // ── Chunking & Vector Store Loading ─────────────────────────────
            List<SemanticMarkdownChunker.ChunkResult> chunkResults = embeddingService.ingestMarkdown(document, markdown);

            // ── Document Profiling ──────────────────────────────────────────
            DocumentProfiler.ProfileResult profile = documentProfiler.profile(markdown, chunkResults);

            document.setChunkCount(chunkResults.size());
            document.setNumHeadings(profile.numHeadings());
            document.setNumTables(profile.numTables());
            document.setNumParagraphs(profile.numParagraphs());
            document.setTotalTokens(profile.estimatedTokens());
            document.setAvgTokensPerChunk(profile.avgTokensPerChunk());
            document.setMarkdownContent(markdown);
            document.setErrorMessage(null);
            documentRepository.save(document);

            // Cache file hash for duplicate detection
            if (document.getFileHash() != null && !document.getFileHash().isEmpty() && redisTemplate != null) {
                try {
                    redisTemplate.opsForValue().set("doc:hash:" + document.getFileHash(), String.valueOf(document.getId()));
                    log.info("[Redis] Cached completed file hash mapping: doc:hash:{} -> {}", document.getFileHash(), document.getId());
                } catch (Exception re) {
                    log.error("[Redis] Failed to cache file hash in Redis: {}", re.getMessage());
                }
            }

            // Async post-processing: summary, Q&A, wiki compilation
            // Document transitions to COMPLETED only when all subtasks finish
            postProcessingCoordinator.startPostProcessing(document, markdown, chunkResults);

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

    private String getFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) return "";
        return filename.substring(filename.lastIndexOf('.'));
    }

    private String cleanMarkdown(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();
        if (cleaned.startsWith("```markdown")) {
            cleaned = cleaned.substring("```markdown".length());
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring("```".length());
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - "```".length());
        }
        return cleaned.trim();
    }
}
