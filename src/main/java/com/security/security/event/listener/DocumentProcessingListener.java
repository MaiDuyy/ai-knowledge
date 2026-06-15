package com.security.security.event.listener;

import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.event.NatsEventPublisher;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.EmbeddingRepository;
import com.security.security.service.tika.DocumentProfiler;
import com.security.security.service.tika.HtmlToMarkdownConverter;
import com.security.security.service.tika.SemanticMarkdownChunker;
import com.security.security.service.tika.TikaHtmlExtractor;
import com.security.security.service.tika.TikaHtmlResult;
import com.security.security.service.docling.DoclingClient;
import com.security.security.entity.SourceImage;
import com.security.security.repository.SourceImageRepository;
import com.security.security.service.ImageExtractionService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.Collections;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessingListener {

    private final VectorStore             vectorStore;
    private final DocumentRepository      documentRepository;
    private final EmbeddingRepository     embeddingRepository;
    private final TikaHtmlExtractor       tikaHtmlExtractor;
    private final HtmlToMarkdownConverter htmlToMarkdownConverter;
    private final SemanticMarkdownChunker semanticMarkdownChunker;
    private final DocumentProfiler        documentProfiler;
    private final DoclingClient           doclingClient;
    private final ImageExtractionService  imageExtractionService;
    private final SourceImageRepository   sourceImageRepository;
    private final ChatModel               chatModel;
    private final NatsEventPublisher      natsEventPublisher;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;
    @Value("${gemini.modle.image:gemini-1.5-flash}")
    private String geminiModel;

    private static final int BATCH_SIZE     = 30;
    private static final int MIN_TOKENS     = 60;

    // =========================================================================
    //  MAIN EVENT HANDLER
    // =========================================================================

    public void processDocument(Long docId) {
        log.info("[ETL] ▶ Start doc={}", docId);

        Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + docId));

        try {
            setStatus(document, DocStatus.PROCESSING, null);

            // ── G1: Ingestion ─────────────────────────────────────────────────
            Resource resource;
            if (document.getFileUrl() != null && !document.getFileUrl().isBlank()) {
                log.info("[ETL][1] URL: {}", document.getFileUrl());
                resource = new UrlResource(document.getFileUrl());
            } else {
                log.info("[ETL][1] Disk: {}", document.getFilePath());
                resource = new FileSystemResource(document.getFilePath());
            }

            String markdown = null;

            try {
                if (doclingClient.isHealthy()) {
                    log.info("[ETL] Docling is healthy. Using Docling API for docId={}, fileName={} with preferred parser: '{}'", docId, document.getFileName(), document.getParserMethod());
                    DoclingClient.DoclingResult doclingResult = doclingClient.convertToMarkdown(resource, document.getFileName(), document.getParserMethod(), docId);
                    if (doclingResult.success()) {
                        markdown = doclingResult.markdown();
                        log.info("[ETL] Successfully parsed docId={} with Docling. Markdown length: {}", docId, markdown.length());
                    } else {
                        log.warn("[ETL] Docling parsing failed for docId={}: {}. Falling back to Apache Tika.", docId, doclingResult.errorMessage());
                    }
                } else {
                    log.info("[ETL] Docling is offline. Falling back to Apache Tika for docId={}", docId);
                }
            } catch (Exception e) {
                log.warn("[ETL] Error calling Docling for docId={}: {}. Falling back to Apache Tika.", docId, e.getMessage(), e);
            }

            if (markdown == null) {
                log.info("[ETL] Running Apache Tika fallback extraction for docId={}", docId);
                // ── G2: Tika DOM Extraction ───────────────────────────────────────
                TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(resource);
                String rawHtml = htmlResult.html();
                log.info("[ETL][2] HTML: {} chars, doc={}", rawHtml.length(), docId);
                if (rawHtml.isBlank())
                    throw new IllegalStateException("Tika empty HTML, doc=" + docId);

                // ── G3+G4: Deduplication → HTML → Markdown ──────────────────────
                markdown = htmlToMarkdownConverter.convert(rawHtml);
                log.info("[ETL][3+4] Markdown: {} chars, doc={}", markdown.length(), docId);
            }

            // ── Extract and process inline images ─────────────────────────────
            try {
                byte[] fileBytes;
                try (InputStream is = resource.getInputStream()) {
                    fileBytes = is.readAllBytes();
                }
                
                List<ImageExtractionService.ExtractedImage> extractedImages = 
                        imageExtractionService.extractImages(fileBytes, document.getFileName());
                
                if (extractedImages != null && !extractedImages.isEmpty()) {
                    log.info("[ETL] Extracted {} inline images from document ID: {}", extractedImages.size(), docId);
                    
                    Path imagesDir = Paths.get(uploadDir).resolve("images");
                    if (!Files.exists(imagesDir)) {
                        Files.createDirectories(imagesDir);
                    }
                    
                    ProcessedImageResult[] resultsArray = new ProcessedImageResult[extractedImages.size()];
                    for (int idx = 0; idx < extractedImages.size(); idx++) {
                        ProcessedImageResult def = new ProcessedImageResult();
                        def.index = idx;
                        def.skipped = true;
                        resultsArray[idx] = def;
                    }

                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    Semaphore captionSemaphore = new Semaphore(3); // Cap concurrency for caption calls
                    ExecutorService imageExecutor = Executors.newVirtualThreadPerTaskExecutor();

                    for (int idx = 0; idx < extractedImages.size(); idx++) {
                        final int index = idx;
                        final ImageExtractionService.ExtractedImage extImg = extractedImages.get(idx);
                        
                        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                            try {
                                boolean isLogoOrHeader = false;
                                if (extImg.getWidth() > 0 && extImg.getHeight() > 0) {
                                    int w = extImg.getWidth();
                                    int h = extImg.getHeight();
                                    // Heuristic 1: Very small icon or logo (e.g. <= 80x80)
                                    if (w <= 80 && h <= 80) {
                                        isLogoOrHeader = true;
                                    }
                                    // Heuristic 2: Long horizontal/vertical lines or banners (aspect ratio > 8.0 or < 0.125)
                                    double aspect = (double) w / h;
                                    if (aspect > 8.0 || aspect < 0.125) {
                                        isLogoOrHeader = true;
                                    }
                                }

                                if (isLogoOrHeader) {
                                    log.info("[ETL] Skipping logo/header image index={} due to heuristics (w={}, h={})", index, extImg.getWidth(), extImg.getHeight());
                                    // Already initialized to skipped = true
                                    return;
                                }

                                UUID imgId = UUID.randomUUID();
                                String imgFilename = imgId.toString() + "." + extImg.getExtension();
                                Path targetPath = imagesDir.resolve(imgFilename);
                                
                                // Generate caption using Gemini (under concurrency control)
                                captionSemaphore.acquire();
                                String caption;
                                try {
                                    caption = generateCaption(extImg.getBytes(), extImg.getContentType());
                                } finally {
                                    captionSemaphore.release();
                                }

                                boolean isIgnoredText = false;
                                if (caption != null) {
                                    String cleanCaption = caption.toLowerCase().trim();
                                    if (cleanCaption.replaceAll("[^a-zA-Z]", "").equalsIgnoreCase("IGNORE")
                                            || cleanCaption.contains("logo")
                                            || cleanCaption.contains("biểu tượng")
                                            || cleanCaption.contains("bieu tuong")
                                            || cleanCaption.contains("header")
                                            || cleanCaption.contains("footer")
                                            || cleanCaption.contains("icon")
                                            || cleanCaption.contains("banner")
                                            || cleanCaption.contains("ảnh bìa")
                                            || cleanCaption.contains("anh bia")
                                            || cleanCaption.contains("trang trí")
                                            || cleanCaption.contains("trang tri")) {
                                        isIgnoredText = true;
                                    }
                                }

                                if (isIgnoredText) {
                                    log.info("[ETL] Skipping logo/header image index={} based on Gemini keyword detection in caption: '{}'", index, caption);
                                    // Already initialized to skipped = true
                                    return;
                                }

                                // Write to disk only if NOT skipped
                                Files.write(targetPath, extImg.getBytes());

                                SourceImage sourceImg = SourceImage.builder()
                                        .id(imgId)
                                        .source(document)
                                        .minioKey("images/" + imgFilename)
                                        .pageNumber(extImg.getPageNumber())
                                        .imageIndex(extImg.getImageIndex())
                                        .caption(caption)
                                        .contentType(extImg.getContentType())
                                        .sizeBytes(extImg.getBytes().length)
                                        .build();
                                        
                                ProcessedImageResult result = new ProcessedImageResult();
                                result.index = index;
                                result.skipped = false;
                                result.sourceImage = sourceImg;
                                resultsArray[index] = result;
                            } catch (Exception ex) {
                                log.warn("[ETL] Failed to process extracted image idx={} for docId={}: {}", index, docId, ex.getMessage());
                            }
                        }, imageExecutor);
                        futures.add(future);
                    }

                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                    imageExecutor.shutdown();

                    // Delete old source images first to prevent duplicates
                    try {
                        sourceImageRepository.deleteBySourceId(docId);
                    } catch (Exception e) {
                        log.warn("[ETL] Failed to delete old source images for docId={}: {}", docId, e.getMessage());
                    }

                    List<ProcessedImageResult> sortedResults = Arrays.asList(resultsArray);

                    // Save non-skipped SourceImage records to the database
                    List<SourceImage> imagesToSave = sortedResults.stream()
                            .filter(r -> !r.skipped && r.sourceImage != null)
                            .map(r -> r.sourceImage)
                            .toList();
                    if (!imagesToSave.isEmpty()) {
                        sourceImageRepository.saveAll(imagesToSave);
                    }
                    
                    // Replace image placeholders in the markdown text in-place using sortedResults (1-to-1 matching)
                    String updatedMarkdown = markdown;
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("!\\[(.*?)\\]\\((.*?)\\)");
                    java.util.regex.Matcher matcher = pattern.matcher(updatedMarkdown);
                    
                    StringBuilder sb = new StringBuilder();
                    int imgIndex = 0;
                    while (matcher.find()) {
                        String replacement = "";
                        if (imgIndex < sortedResults.size()) {
                            ProcessedImageResult r = sortedResults.get(imgIndex);
                            if (!r.skipped && r.sourceImage != null) {
                                SourceImage img = r.sourceImage;
                                String alt = sanitizeCaptionForAlt(img.getCaption());
                                replacement = String.format("![%s](image://%s)", alt, img.getId().toString());
                            }
                            imgIndex++;
                        }
                        matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
                    }
                    matcher.appendTail(sb);
                    markdown = sb.toString();
                    log.info("[ETL] Replaced inline image references in markdown content for docId={}", docId);
                }
            } catch (Exception imgEx) {
                log.warn("[ETL] Image extraction/processing failed for docId={}: {}", docId, imgEx.getMessage());
            }

            if (markdown == null || markdown.length() < 60)
                throw new IllegalStateException("Extracted markdown is empty or too short, doc=" + docId);

            // ── G5: Semantic Chunking ─────────────────────────────────────────
            List<SemanticMarkdownChunker.ChunkResult> chunkResults = semanticMarkdownChunker.chunk(markdown);
            log.info("[ETL][5] Chunks: {}, doc={}", chunkResults.size(), docId);
            if (chunkResults.isEmpty())
                throw new IllegalStateException("No chunks produced");

            // Purge old chunks from VectorStore before loading new ones
            try {
                com.security.security.dtorequest.DeleteRequest deleteReq = new com.security.security.dtorequest.DeleteRequest(docId);
                vectorStore.delete(String.format("documentId == '%s'", deleteReq.getDocumentId()));
            } catch (Exception e) {
                log.warn("[ETL] Could not delete old chunks from VectorStore during update for docId={}: {}", docId, e.getMessage());
            }

            // ── G6: Load ──────────────────────────────────────────────────────
            embeddingRepository.deleteByDocumentId(docId);

            List<org.springframework.ai.document.Document> vBatch = new ArrayList<>(BATCH_SIZE);
            List<Embedding>                               eBatch = new ArrayList<>(BATCH_SIZE);

            for (int i = 0; i < chunkResults.size(); i++) {
                SemanticMarkdownChunker.ChunkResult cr = chunkResults.get(i);

                Map<String, Object> meta = new HashMap<>();
                meta.put("documentId", document.getId().toString());
                meta.put("userId",     document.getUserId());
                meta.put("fileName",   document.getFileName());
                meta.put("chunkIndex", String.valueOf(i));
                meta.put("chunkTitle", cr.title());
                meta.put("tokenCount", String.valueOf(semanticMarkdownChunker.estimateTokens(cr.text())));
                meta.put("charCount",  String.valueOf(cr.text().length()));
                if (document.getSecurityClassification() != null) {
                    meta.put("classification", document.getSecurityClassification());
                    meta.put("securityClassification", document.getSecurityClassification());
                }
                meta.put("uploadedBy", document.getUserId());
                if (document.getTags() != null && !document.getTags().isEmpty()) {
                    meta.put("tags", String.join(",", document.getTags()));
                }
                if (document.getWorkspaceId() != null) {
                    meta.put("workspaceId", document.getWorkspaceId());
                }

                vBatch.add(new org.springframework.ai.document.Document(cr.text(), meta));
                eBatch.add(Embedding.builder()
                        .documentId(document.getId())
                        .workspaceId(document.getWorkspaceId())
                        .chunkIndex(i)
                        .chunkText(cr.text())
                        .chunkTitle(cr.title())
                        .tokenCount(semanticMarkdownChunker.estimateTokens(cr.text()))
                        .charCount(cr.text().length())
                        .build());

                if (vBatch.size() >= BATCH_SIZE) {
                    vectorStore.add(new ArrayList<>(vBatch));
                    embeddingRepository.saveAll(new ArrayList<>(eBatch));
                    log.info("[ETL][6] Batch [{}-{}] doc={}", i - BATCH_SIZE + 1, i, docId);
                    vBatch.clear();
                    eBatch.clear();
                }
            }
            if (!vBatch.isEmpty()) {
                vectorStore.add(vBatch);
                embeddingRepository.saveAll(eBatch);
                log.info("[ETL][6] Final batch doc={}", docId);
            }

            log.info("[ETL] ✓ {} chunks stored, doc={}", chunkResults.size(), docId);

            // ── G7: Document Profiling (docling-style) ────────────────────────
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
            natsEventPublisher.publishDocumentStatus(document.getId(), document.getUserId(), document.getWorkspaceId(), "COMPLETED");

        } catch (Exception e) {
            log.error("[ETL] ✗ Failed doc={}: {}", docId, e.getMessage(), e);
            setStatus(document, DocStatus.FAILED, e.getMessage());
            throw new RuntimeException("ETL processing failed for doc=" + docId, e);
        }
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private void setStatus(Document doc, DocStatus status, String error) {
        doc.setStatus(status);
        doc.setErrorMessage(error);
        documentRepository.save(doc);
        natsEventPublisher.publishDocumentStatus(doc.getId(), doc.getUserId(), doc.getWorkspaceId(), status.name());
    }

    private String generateCaption(byte[] imgBytes, String contentType) {
        try {
            if (chatModel == null) return "Extracted Image";
            
            // Check if contentType is supported by Gemini (png, jpeg, webp, heic, heif)
            String mime = contentType.toLowerCase();
            if (!mime.contains("png") && !mime.contains("jpeg") && !mime.contains("jpg") && !mime.contains("webp") && !mime.contains("heic") && !mime.contains("heif")) {
                log.info("Skipping Gemini caption generation for unsupported image type: {}", contentType);
                return "Extracted Image";
            }
            
            org.springframework.core.io.ByteArrayResource byteResource = 
                    new org.springframework.core.io.ByteArrayResource(imgBytes);
            org.springframework.ai.content.Media media = 
                    new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.parseMimeType(contentType), byteResource);
            org.springframework.ai.chat.client.ChatClient chatClient = 
                    org.springframework.ai.chat.client.ChatClient.builder(chatModel).build();
            
            String systemPrompt = "Analyze the image. If the image is a corporate logo, brand icon, page header, page footer, or decorative banner/line, you MUST reply with exactly the word 'IGNORE'. Otherwise, write a brief, 1-sentence description (maximum 10 words) of this image in Vietnamese. Do NOT write any intro, notes, or explanations. Keep it as short as possible.";
            
            org.springframework.ai.chat.model.ChatResponse response = chatClient.prompt()
                    .options(org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder()
                            .model(geminiModel)
                            .temperature(0.2)
                            .maxOutputTokens(40)
                            .build())
                    .system(systemPrompt)
                    .user(u -> u.text("Describe in max 10 words:").media(media))
                    .call()
                    .chatResponse();
                    
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null) {
                    return text.trim();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to generate caption using Gemini: {}", e.getMessage());
        }
        return "Extracted Image";
    }

    private String sanitizeCaptionForAlt(String caption) {
        if (caption == null) return "";
        String cleaned = caption.replace("\n", " ").replace("\r", " ");
        cleaned = cleaned.replace("[", "(").replace("]", ")");
        return cleaned.trim();
    }

    private static class ProcessedImageResult {
        int index;
        boolean skipped;
        SourceImage sourceImage;
    }
}
