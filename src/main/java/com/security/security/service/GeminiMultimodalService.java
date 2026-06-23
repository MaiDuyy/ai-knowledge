package com.security.security.service;

import com.security.security.entity.OcrResult;
import com.security.security.entity.enumeration.OcrStatus;
import com.security.security.repository.OcrResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
public class GeminiMultimodalService {

    private final ChatModel chatModel;
    private final OcrResultRepository ocrResultRepository;
    private final StringRedisTemplate redisTemplate;
    private final ExecutorService executorService;

    @Value("${gemini.modle:gemini-2.5-flash}")
    private String geminiModel;

    @Value("${gemini.concurrency-limit:5}")
    private int concurrencyLimit;

    private Semaphore semaphore;

    private static final String SYSTEM_PROMPT_OCR = """
            You are a precise document-to-markdown compiler and high-accuracy OCR expert.
            Your ONLY job is to recognize all text, tables, and structure from the input document and convert it into clean, well-formatted Markdown.
            
            CRITICAL RULES:
            1. DO NOT write any introduction, notes, thoughts, explanations, self-corrections, planning, or summaries.
            2. Your response MUST start immediately with the first character of the Markdown content (e.g., the title '# ...' or paragraph).
            3. Do NOT wrap the Markdown in backticks like ```markdown ... ```.
            4. Keep the exact text and reading order of the document. Do not summarize or rewrite.
            5. Reconstruct headings (#, ##, ###, ####), bullet lists, and tables precisely. Use hierarchical heading levels: H1 (#) for main titles/chapters/major parts, H2 (##) for sections (e.g. "1. Introduction"), H3 (###) for subsections (e.g. "1.1"), and H4 (####) for deep subsections (e.g. "1.1.1"). Use standard Markdown table syntax for tables.
            6. Represent any images, diagrams, or illustrations using standard Markdown image tags, e.g., ![diagram description](image_placeholder). Do NOT include raw image bytes.
            7. Keep bold, italic, and underline stylings.
            8. Use standard LaTeX for mathematical equations if any.
            """;

    private static final String SYSTEM_PROMPT_HTML = """
            You are a precise raw-HTML-to-markdown compiler.
            Your ONLY job is to convert raw HTML into beautiful, clean, well-formatted Markdown.
            
            CRITICAL RULES:
            1. DO NOT write any introduction, notes, thoughts, explanations, self-corrections, planning, or summaries.
            2. Your response MUST start immediately with the first character of the Markdown content.
            3. Do NOT wrap the Markdown in backticks like ```markdown ... ```.
            4. Keep the exact text and reading order of the document. Do not summarize or rewrite.
            5. Reconstruct headings (#, ##, ###, ####), bullet lists, and tables precisely. Use hierarchical heading levels: H1 (#) for main titles/chapters/major parts, H2 (##) for sections (e.g. "1. Introduction"), H3 (###) for subsections (e.g. "1.1"), and H4 (####) for deep subsections (e.g. "1.1.1"). Use standard Markdown table syntax for tables.
            6. Represent any images using standard Markdown image tags, e.g., ![Image description](image_placeholder). Do NOT include inline raw base64 image bytes.
            7. Keep bold, italic, and underline stylings.
            """;

    public GeminiMultimodalService(
            ChatModel chatModel,
            OcrResultRepository ocrResultRepository,
            Optional<StringRedisTemplate> redisTemplate,
            @Qualifier("mrpVirtualThreadExecutor") ExecutorService executorService) {
        this.chatModel = chatModel;
        this.ocrResultRepository = ocrResultRepository;
        this.redisTemplate = redisTemplate.orElse(null);
        this.executorService = executorService;
    }

    @PostConstruct
    public void init() {
        this.semaphore = new Semaphore(concurrencyLimit);
        log.info("[GeminiMultimodalService] Initialized with model: {}, concurrency limit: {}", geminiModel, concurrencyLimit);
    }

    /**
     * Check if a file extension represents a scan/image that requires OCR.
     */
    public boolean isImageOrPdf(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".png") || lower.endsWith(".jpg") 
                || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    /**
     * Perform OCR on a PDF or image file and return Markdown.
     */
    public String parse(File file) throws IOException {
        String fileName = file.getName();
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        
        if (fileName.toLowerCase().endsWith(".pdf")) {
            return parsePdf(fileBytes);
        } else {
            String mimeType = getMimeType(fileName);
            return parseImage(fileBytes, mimeType);
        }
    }

    /**
     * Render a page of a PDF to PNG format.
     */
    public byte[] renderPdfPageToPng(byte[] pdfBytes, int pageIndex) throws IOException {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            BufferedImage bim = pdfRenderer.renderImageWithDPI(pageIndex, 150, ImageType.RGB);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bim, "png", baos);
            return baos.toByteArray();
        }
    }

    /**
     * Perform OCR on all pages of a PDF and return concatenated Markdown (orchestrated via virtual threads & semaphore).
     */
    public String parsePdf(byte[] pdfBytes) throws IOException {
        return parsePdf(pdfBytes, null);
    }

    /**
     * Perform OCR on all pages of a PDF and return concatenated Markdown (orchestrated via virtual threads & semaphore).
     * Optionally ties page OCR results to a database documentId.
     */
    public String parsePdf(byte[] pdfBytes, Long documentId) throws IOException {
        log.info("[GeminiMultimodalService] Splitting PDF into individual pages");
        List<byte[]> pagePdfBytesList = new ArrayList<>();
        try (PDDocument pdDocument = PDDocument.load(pdfBytes)) {
            int pageCount = pdDocument.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                try (PDDocument singlePageDoc = new PDDocument()) {
                    singlePageDoc.addPage(pdDocument.getPage(i));
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    singlePageDoc.save(baos);
                    pagePdfBytesList.add(baos.toByteArray());
                }
            }
        }

        int totalPages = pagePdfBytesList.size();
        log.info("[GeminiMultimodalService] Running hybrid extraction on {} pages", totalPages);
        String[] pageMarkdowns = new String[totalPages];
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try (PDDocument fullDoc = PDDocument.load(pdfBytes)) {
            for (int i = 0; i < totalPages; i++) {
                final int pageIndex = i;
                final int pageNumber = i + 1;
                final byte[] pagePdfBytes = pagePdfBytesList.get(i);
                
                // Determine page complexity
                final boolean isComplex = isPageComplexOrScanned(fullDoc, pageIndex);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String pageMarkdown;
                        if (isComplex) {
                            log.info("[GeminiMultimodalService] Page {} is complex/scanned. Using Gemini PNG OCR.", pageNumber);
                            byte[] imgBytes = null;
                            try {
                                imgBytes = renderPdfPageToPng(pdfBytes, pageIndex);
                            } catch (Exception e) {
                                log.warn("[GeminiMultimodalService] Failed to render PDF page {} to PNG, falling back to raw PDF bytes: {}", pageNumber, e.getMessage());
                            }

                            if (imgBytes != null) {
                                pageMarkdown = parseImage(imgBytes, "image/png", documentId, pageNumber);
                            } else {
                                pageMarkdown = parseImage(pagePdfBytes, "application/pdf", documentId, pageNumber);
                            }
                        } else {
                            log.info("[GeminiMultimodalService] Page {} is pure text. Using fast offline extraction.", pageNumber);
                            // Offline direct extraction for pure text page
                            pageMarkdown = extractTextOffline(fullDoc, pageIndex);
                            // Save completed page to cache
                            String hash = calculateSha256(pagePdfBytes);
                            saveToCache(hash, pageMarkdown, documentId, pageNumber, 0L, null);
                        }
                        pageMarkdowns[pageIndex] = pageMarkdown;
                    } catch (Exception e) {
                        log.error("[GeminiMultimodalService] Failed to parse page {} of documentId {}: {}", pageNumber, documentId, e.getMessage(), e);
                        pageMarkdowns[pageIndex] = "\n\n<!-- PAGE_ERROR: " + pageNumber + " - " + e.getMessage() + " -->\n\n";
                    }
                }, executorService);

                futures.add(future);
            }

            // Wait for all pages to finish
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // Assemble all page markdowns
        StringBuilder finalMarkdown = new StringBuilder();
        for (int i = 0; i < totalPages; i++) {
            if (i > 0) {
                finalMarkdown.append("\n\n<!-- PAGE_BREAK: ").append(i + 1).append(" -->\n\n");
            }
            finalMarkdown.append(pageMarkdowns[i] != null ? pageMarkdowns[i] : "");
        }

        return finalMarkdown.toString();
    }

    private boolean isPageComplexOrScanned(PDDocument document, int pageIndex) {
        PDPage page = document.getPage(pageIndex);
        
        // 1. Check if it contains image resources
        try {
            Iterable<COSName> xNames = page.getResources().getXObjectNames();
            for (COSName name : xNames) {
                if (page.getResources().isImageXObject(name)) {
                    return true; // Contains images
                }
            }
        } catch (Exception e) {
            // ignore
        }
        
        // 2. Extract text and check length and layout
        try {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            String text = stripper.getText(document);
            
            if (text == null || text.trim().length() < 50) {
                return true; // Scanned or empty
            }
            
            // Check for table indicators (e.g. table keywords, or multiple numbers on the same line)
            String lower = text.toLowerCase();
            if (lower.contains("bảng") || lower.contains("table") || lower.contains("sơ đồ") || lower.contains("figure")) {
                return true;
            }
            
            // Check if lines look like tables or columns (e.g. contains rows with multiple separated columns/numbers)
            String[] lines = text.split("\n");
            int multiColumnLines = 0;
            for (String line : lines) {
                line = line.trim();
                // If a line has multiple parts separated by 3 or more spaces
                if (line.split("\\s{3,}").length >= 3) {
                    multiColumnLines++;
                }
            }
            if (multiColumnLines > 2) {
                return true; // Likely a table or multi-column layout
            }
        } catch (Exception e) {
            return true;
        }
        
        return false; // Pure text
    }

    private String extractTextOffline(PDDocument document, int pageIndex) {
        try {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            String text = stripper.getText(document);
            return text != null ? text.trim() : "";
        } catch (Exception e) {
            log.error("Failed to extract text offline for page {}", pageIndex + 1, e);
            return "";
        }
    }

    /**
     * Perform OCR on a single image and return Markdown.
     */
    public String parseImage(byte[] imageBytes, String mimeType) {
        return parseImage(imageBytes, mimeType, null, null);
    }

    /**
     * Perform OCR on a single image and return Markdown.
     * Optionally caches the result linked to a specific documentId and pageNumber.
     */
    public String parseImage(byte[] imageBytes, String mimeType, Long documentId, Integer pageNumber) {
        String hash = calculateSha256(imageBytes);
        
        // 1. Check Cache first
        String cached = getFromCache(hash, documentId, pageNumber);
        if (cached != null) {
            log.info("[GeminiMultimodalService] Cache hit for hash: {}", hash);
            return cached;
        }

        long start = System.currentTimeMillis();
        log.info("[GeminiMultimodalService] Running OCR on image, hash={}, mime={}", hash, mimeType);

        // Pre-save state as PROCESSING in DB if document info is available
        OcrResult dbRecord = getOrCreateDbRecord(hash, documentId, pageNumber);
        if (dbRecord != null) {
            dbRecord.setStatus(OcrStatus.PROCESSING);
            saveDbRecord(dbRecord);
        }

        try {
            semaphore.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Image OCR execution interrupted", ie);
        }

        String markdown = null;
        try {
            int maxRetries = 3;
            int retryCount = 0;
            Exception lastException = null;

            ByteArrayResource byteResource = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "ocr_image_" + (pageNumber != null ? pageNumber : "temp") + ".png";
                }
            };
            Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), byteResource);

            while (retryCount < maxRetries && markdown == null) {
                try {
                    if (retryCount > 0) {
                        long sleepMs = (long) Math.pow(2, retryCount) * 1000L;
                        Thread.sleep(sleepMs);
                        log.info("[GeminiMultimodalService] Retrying image OCR (attempt {}) after {}ms delay", retryCount + 1, sleepMs);
                    }

                    ChatClient chatClient = ChatClient.builder(chatModel).build();
                    ChatResponse response = chatClient.prompt()
                            .options(GoogleGenAiChatOptions.builder()
                                    .model(geminiModel)
                                    .temperature(0.0)
                                    .build())
                            .system(SYSTEM_PROMPT_OCR)
                            .user(u -> u.text("Trích xuất nội dung tài liệu này sang Markdown:").media(media))
                            .call()
                            .chatResponse();

                    if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                        String text = response.getResult().getOutput().getText();
                        if (text != null && !text.isBlank()) {
                            markdown = cleanMarkdown(text);
                        }
                    }
                } catch (Exception e) {
                    lastException = e;
                    retryCount++;
                }
            }

            if (markdown == null) {
                throw new RuntimeException("Failed to OCR image after " + maxRetries + " attempts. Last error: " + (lastException != null ? lastException.getMessage() : "Unknown"));
            }

            long elapsed = System.currentTimeMillis() - start;
            
            // 3. Save to Caches
            saveToCache(hash, markdown, documentId, pageNumber, elapsed, dbRecord);
            return markdown;

        } catch (Exception e) {
            log.error("[GeminiMultimodalService] Failed image OCR: {}", e.getMessage(), e);
            if (dbRecord != null) {
                dbRecord.setStatus(OcrStatus.FAILED);
                dbRecord.setErrorMessage(e.getMessage());
                saveDbRecord(dbRecord);
            }
            throw new RuntimeException("Failed Gemini OCR extraction", e);
        } finally {
            semaphore.release();
        }
    }

    /**
     * Convert Tika raw HTML into beautiful Markdown using Gemini.
     */
    public String parseHtml(String html) {
        return parseHtml(html, null, null);
    }

    /**
     * Convert Tika raw HTML into beautiful Markdown using Gemini.
     * Optionally caches the result linked to a specific documentId and pageNumber.
     */
    public String parseHtml(String html, Long documentId, Integer pageNumber) {
        String hash = calculateSha256(html);
        
        // 1. Check Cache first
        String cached = getFromCache(hash, documentId, pageNumber);
        if (cached != null) {
            log.info("[GeminiMultimodalService] Cache hit for HTML hash: {}", hash);
            return cached;
        }

        long start = System.currentTimeMillis();
        log.info("[GeminiMultimodalService] Converting HTML to Markdown using Gemini, hash={}", hash);

        // Pre-save state as PROCESSING in DB if document info is available
        OcrResult dbRecord = getOrCreateDbRecord(hash, documentId, pageNumber);
        if (dbRecord != null) {
            dbRecord.setStatus(OcrStatus.PROCESSING);
            saveDbRecord(dbRecord);
        }

        try {
            semaphore.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTML conversion interrupted", ie);
        }

        String markdown = null;
        try {
            int maxRetries = 3;
            int retryCount = 0;
            Exception lastException = null;

            while (retryCount < maxRetries && markdown == null) {
                try {
                    if (retryCount > 0) {
                        long sleepMs = (long) Math.pow(2, retryCount) * 1000L;
                        Thread.sleep(sleepMs);
                        log.info("[GeminiMultimodalService] Retrying HTML conversion (attempt {}) after {}ms delay", retryCount + 1, sleepMs);
                    }

                    ChatClient chatClient = ChatClient.builder(chatModel).build();
                    ChatResponse response = chatClient.prompt()
                            .options(GoogleGenAiChatOptions.builder()
                                    .model(geminiModel)
                                    .temperature(0.0)
                                    .build())
                            .system(SYSTEM_PROMPT_HTML)
                            .user("Convert the following HTML document to Markdown. DO NOT write any thinking process, notes, planning, or explanations. Start immediately with the Markdown content:\n\n" + html)
                            .call()
                            .chatResponse();

                    if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                        String text = response.getResult().getOutput().getText();
                        if (text != null && !text.isBlank()) {
                            markdown = cleanMarkdown(text);
                        }
                    }
                } catch (Exception e) {
                    lastException = e;
                    retryCount++;
                }
            }

            if (markdown == null) {
                throw new RuntimeException("Failed to convert HTML after " + maxRetries + " attempts. Last error: " + (lastException != null ? lastException.getMessage() : "Unknown"));
            }

            long elapsed = System.currentTimeMillis() - start;

            // 3. Save to Caches
            saveToCache(hash, markdown, documentId, pageNumber, elapsed, dbRecord);
            return markdown;

        } catch (Exception e) {
            log.error("[GeminiMultimodalService] Failed HTML conversion: {}", e.getMessage(), e);
            if (dbRecord != null) {
                dbRecord.setStatus(OcrStatus.FAILED);
                dbRecord.setErrorMessage(e.getMessage());
                saveDbRecord(dbRecord);
            }
            throw new RuntimeException("Failed Gemini HTML conversion", e);
        } finally {
            semaphore.release();
        }
    }

    // =========================================================================
    //  CACHING & HASHING HELPERS
    // =========================================================================

    private String calculateSha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            return HexFormat.of().formatHex(hashBytes);
        } catch (Exception e) {
            log.error("Failed to calculate SHA-256 hash", e);
            throw new RuntimeException("SHA-256 hash calculation failed", e);
        }
    }

    private String calculateSha256(String text) {
        return calculateSha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private String getFromCache(String hash, Long documentId, Integer pageNumber) {
        // 1. Try Redis first
        if (redisTemplate != null) {
            try {
                String cached = redisTemplate.opsForValue().get("ocr:hash:" + hash);
                if (cached != null && !cached.isBlank()) {
                    return cached;
                }
            } catch (Exception e) {
                log.warn("[GeminiMultimodalService] Redis read failed for hash {}: {}", hash, e.getMessage());
            }
        }

        // 2. Try Database Backup
        if (ocrResultRepository != null) {
            try {
                // Try finding by documentId and pageNumber if present
                if (documentId != null && pageNumber != null) {
                    Optional<OcrResult> dbResult = ocrResultRepository.findByDocumentIdAndPageNumber(documentId, pageNumber);
                    if (dbResult.isPresent() && dbResult.get().getStatus() == OcrStatus.COMPLETED) {
                        String content = dbResult.get().getMarkdownContent();
                        if (content != null && !content.isBlank()) {
                            // Populate back to Redis for faster access next time
                            populateRedisCache(hash, content);
                            return content;
                        }
                    }
                }
                
                // Fallback: Check by derived IDs
                long derivedDocId = deriveDocumentId(hash);
                int derivedPageNum = derivePageNumber(hash);
                Optional<OcrResult> dbResult = ocrResultRepository.findByDocumentIdAndPageNumber(derivedDocId, derivedPageNum);
                if (dbResult.isPresent() && dbResult.get().getStatus() == OcrStatus.COMPLETED) {
                    String content = dbResult.get().getMarkdownContent();
                    if (content != null && !content.isBlank()) {
                        populateRedisCache(hash, content);
                        return content;
                    }
                }
            } catch (Exception e) {
                log.warn("[GeminiMultimodalService] Database cache read failed for hash {}: {}", hash, e.getMessage());
            }
        }
        return null;
    }

    private void saveToCache(String hash, String content, Long documentId, Integer pageNumber, long elapsedMs, OcrResult dbRecord) {
        // 1. Save to Redis
        populateRedisCache(hash, content);

        // 2. Save to Database
        if (ocrResultRepository != null) {
            try {
                OcrResult record = dbRecord;
                if (record == null) {
                    record = getOrCreateDbRecord(hash, documentId, pageNumber);
                }
                if (record != null) {
                    record.setStatus(OcrStatus.COMPLETED);
                    record.setMarkdownContent(content);
                    record.setElapsedMs(elapsedMs);
                    record.setErrorMessage(null);
                    saveDbRecord(record);
                }
            } catch (Exception e) {
                log.warn("[GeminiMultimodalService] Database cache write failed for hash {}: {}", hash, e.getMessage());
            }
        }
    }

    private void populateRedisCache(String hash, String content) {
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set("ocr:hash:" + hash, content, Duration.ofDays(30));
            } catch (Exception e) {
                log.warn("[GeminiMultimodalService] Redis write failed for hash {}: {}", hash, e.getMessage());
            }
        }
    }

    private OcrResult getOrCreateDbRecord(String hash, Long documentId, Integer pageNumber) {
        if (ocrResultRepository == null) return null;
        try {
            long finalDocId = (documentId != null) ? documentId : deriveDocumentId(hash);
            int finalPageNum = (pageNumber != null) ? pageNumber : derivePageNumber(hash);
            
            return ocrResultRepository.findByDocumentIdAndPageNumber(finalDocId, finalPageNum)
                    .orElseGet(() -> OcrResult.builder()
                            .documentId(finalDocId)
                            .pageNumber(finalPageNum)
                            .build());
        } catch (Exception e) {
            log.warn("[GeminiMultimodalService] Failed to retrieve/create OcrResult db record: {}", e.getMessage());
            return null;
        }
    }

    private void saveDbRecord(OcrResult record) {
        if (ocrResultRepository == null || record == null) return;
        try {
            ocrResultRepository.save(record);
        } catch (Exception e) {
            log.warn("[GeminiMultimodalService] Failed to save OcrResult db record: {}", e.getMessage());
        }
    }

    private long deriveDocumentId(String hash) {
        String part = hash.substring(0, 16);
        long val = Long.parseUnsignedLong(part, 16);
        return val >= 0 ? -val - 1 : val; // Ensure it is strictly negative to prevent collisions
    }

    private int derivePageNumber(String hash) {
        String part = hash.substring(16, 24);
        return Integer.parseUnsignedInt(part, 16);
    }

    // =========================================================================
    //  MIME TYPE HELPER
    // =========================================================================

    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    private String cleanMarkdown(String markdown) {
        if (markdown == null) return "";
        markdown = markdown.trim();
        if (markdown.startsWith("```markdown")) {
            markdown = markdown.substring(11).trim();
        }
        if (markdown.startsWith("```")) {
            markdown = markdown.substring(3).trim();
        }
        if (markdown.endsWith("```")) {
            markdown = markdown.substring(0, markdown.length() - 3).trim();
        }
        return markdown;
    }
}
