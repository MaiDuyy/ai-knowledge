package com.security.security.service;

import com.security.security.entity.OcrResult;
import com.security.security.entity.enumeration.OcrStatus;
import com.security.security.repository.OcrResultRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import com.google.genai.Client;
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
    private final AppConfigService configService;

    @Value("${spring.ai.google.genai.api-key:}")
    private String defaultApiKey;

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
            6. For each visible image, diagram, figure, chart, or illustration in the document, place a Markdown image tag at the EXACT reading position where it appears. Use the format: ![description](image://0) for the first image, ![description](image://1) for the second, etc. Increment the counter sequentially starting from 0 (per page). Do NOT include raw bytes or base64. Do NOT use any URL format other than image://N (N is a 0-based integer).
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

    private static final String SYSTEM_PROMPT_ASR = """
            Bạn là một hệ thống tự động ghi âm và chuyển đổi âm thanh sang văn bản.
            Nhiệm vụ của bạn là nghe file âm thanh được cung cấp và chuyển toàn bộ nội dung lời nói sang văn bản Markdown chính xác.
            Trả về trực tiếp văn bản Markdown sạch, không có phần giải thích hay thẻ ```markdown xung quanh.
            """;

    public GeminiMultimodalService(
            ChatModel chatModel,
            OcrResultRepository ocrResultRepository,
            Optional<StringRedisTemplate> redisTemplate,
            @Qualifier("mrpVirtualThreadExecutor") ExecutorService executorService,
            AppConfigService configService) {
        this.chatModel = chatModel;
        this.ocrResultRepository = ocrResultRepository;
        this.redisTemplate = redisTemplate.orElse(null);
        this.executorService = executorService;
        this.configService = configService;
    }

    private ChatModel getEffectiveChatModel() {
        String dbKey = configService.getOrNull(AppConfigService.LLM_API_KEY_KEY);
        boolean useDbKey = dbKey != null && !dbKey.isBlank() && !dbKey.equals(defaultApiKey);

        if (useDbKey) {
            log.debug("[GeminiMultimodalService] Using DB api-key override");

            Client genAiClient = Client.builder()
                    .apiKey(dbKey)
                    .build();

            GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                    .model(geminiModel)
                    .temperature(0.0)
                    .build();

            return GoogleGenAiChatModel.builder()
                    .genAiClient(genAiClient)
                    .defaultOptions(options)
                    .build();
        }

        return chatModel;
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
     * Render a single PDF page to PNG at low DPI for Gemini OCR.
     */
    private byte[] renderPageToPng(PDFRenderer renderer, int pageIndex) throws IOException {
        BufferedImage bim = renderer.renderImageWithDPI(pageIndex, 96, ImageType.GRAY);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(bim, "png", baos);
        } finally {
            bim.flush();
        }
        return baos.toByteArray();
    }

    /**
     * Render a page of a PDF to PNG format (external callers).
     */
    public byte[] renderPdfPageToPng(byte[] pdfBytes, int pageIndex) throws IOException {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return renderPageToPng(new PDFRenderer(document), pageIndex);
        }
    }

    /**
     * Perform OCR on all pages of a PDF and return concatenated Markdown.
     */
    public String parsePdf(byte[] pdfBytes) throws IOException {
        return parsePdf(pdfBytes, null);
    }

    /**
     * Perform OCR on all pages of a PDF and return concatenated Markdown.
     *
     * Directly sends the PDF bytes to Gemini for native document conversion and extraction.
     */
    public String parsePdf(byte[] pdfBytes, Long documentId) throws IOException {
        log.info("[GeminiMultimodalService] Sending entire PDF directly to Gemini for extraction, docId={}", documentId);
        return parseImage(pdfBytes, "application/pdf", documentId, 1);
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
                    String ext = "application/pdf".equals(mimeType) ? ".pdf" : ".png";
                    return "ocr_doc_" + (pageNumber != null ? pageNumber : "temp") + ext;
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

                    ChatClient chatClient = ChatClient.builder(getEffectiveChatModel()).build();
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

                    ChatClient chatClient = ChatClient.builder(getEffectiveChatModel()).build();
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

    /**
     * Perform ASR on an audio file and return Markdown.
     */
    public String parseAudio(byte[] audioBytes, String mimeType) {
        return parseAudio(audioBytes, mimeType, null);
    }

    /**
     * Perform ASR on an audio file and return Markdown.
     * Optionally caches the result linked to a specific documentId.
     */
    public String parseAudio(byte[] audioBytes, String mimeType, Long documentId) {
        String hash = calculateSha256(audioBytes);
        Integer pageNumber = 1; // Default to 1 for audio files
        
        // 1. Check Cache first
        String cached = getFromCache(hash, documentId, pageNumber);
        if (cached != null) {
            log.info("[GeminiMultimodalService] Cache hit for audio hash: {}", hash);
            return cached;
        }

        long start = System.currentTimeMillis();
        log.info("[GeminiMultimodalService] Running ASR on audio, hash={}, mime={}", hash, mimeType);

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
            throw new RuntimeException("Audio ASR execution interrupted", ie);
        }

        String markdown = null;
        try {
            int maxRetries = 3;
            int retryCount = 0;
            Exception lastException = null;

            ByteArrayResource byteResource = new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return "asr_audio_" + (documentId != null ? documentId : "temp") + ".mp3";
                }
            };
            Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), byteResource);

            while (retryCount < maxRetries && markdown == null) {
                try {
                    if (retryCount > 0) {
                        long sleepMs = (long) Math.pow(2, retryCount) * 1000L;
                        Thread.sleep(sleepMs);
                        log.info("[GeminiMultimodalService] Retrying audio ASR (attempt {}) after {}ms delay", retryCount + 1, sleepMs);
                    }

                    ChatClient chatClient = ChatClient.builder(getEffectiveChatModel()).build();
                    ChatResponse response = chatClient.prompt()
                            .options(GoogleGenAiChatOptions.builder()
                                    .model(geminiModel)
                                    .temperature(0.0)
                                    .build())
                            .system(SYSTEM_PROMPT_ASR)
                            .user(u -> u.text("Hãy chuyển đổi file âm thanh này sang văn bản Markdown:").media(media))
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
                throw new RuntimeException("Failed to ASR audio after " + maxRetries + " attempts. Last error: " + (lastException != null ? lastException.getMessage() : "Unknown"));
            }

            long elapsed = System.currentTimeMillis() - start;
            
            // 3. Save to Caches
            saveToCache(hash, markdown, documentId, pageNumber, elapsed, dbRecord);
            return markdown;

        } catch (Exception e) {
            log.error("[GeminiMultimodalService] Failed audio ASR: {}", e.getMessage(), e);
            if (dbRecord != null) {
                dbRecord.setStatus(OcrStatus.FAILED);
                dbRecord.setErrorMessage(e.getMessage());
                saveDbRecord(dbRecord);
            }
            throw new RuntimeException("Failed Gemini ASR extraction", e);
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
