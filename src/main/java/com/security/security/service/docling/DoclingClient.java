package com.security.security.service.docling;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.security.security.entity.OcrResult;
import com.security.security.repository.OcrResultRepository;
import com.security.security.service.tika.HtmlToMarkdownConverter;
import com.security.security.service.tika.TikaHtmlExtractor;
import com.security.security.service.tika.TikaHtmlResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * DoclingClient — Unified Document Converter Orchestrator.
 *
 * Replaces the heavy, resource-intensive python-based docling-serve.
 * Instead, it orchestrates two premium methods:
 *
 * 1. METHOD A (Online): Google Gemini Multimodal API via Spring AI
 *    - PDF/Images/Text: Directly uploaded to Gemini as multimodal media.
 *    - Office (DOCX/XLSX/PPTX): Parsed to clean structured HTML via Tika,
 *      then styled/refactored into beautiful Markdown by Gemini.
 *
 * 2. METHOD C (Offline/Fallback): Pure Java Local Parser
 *    - Uses pre-existing Apache Tika (TikaHtmlExtractor) + Jsoup (HtmlToMarkdownConverter)
 *      to parse and convert entirely offline. Extremely fast, lightweight, and robust.
 */
@Component
@Slf4j
public class DoclingClient {

    private final WebClient webClient;
    private final ChatModel chatModel;
    private final TikaHtmlExtractor tikaHtmlExtractor;
    private final HtmlToMarkdownConverter htmlToMarkdownConverter;
    private final OcrResultRepository ocrResultRepository;
    private final ExecutorService executorService;

    @Value("${spring.ai.google.genai.api-key:}")
    private String geminiApiKey;

    @Value("${gemini.modle}")
    private String geminiModel;

    @Value("${docling.api-key:}")
    private String apiKey;

    @Value("${docling.timeout-seconds:300}")
    private int timeoutSeconds;

    @Value("${docling.ocr-engine:easyocr}")
    private String ocrEngine;

    @Value("${docling.table-mode:accurate}")
    private String tableMode;

    @Value("${docling.pdf-backend:dlparse_v2}")
    private String pdfBackend;

    @Value("${docling.ocr-lang:vi,en}")
    private List<String> ocrLang;

    public DoclingClient(
            @Value("${docling.base-url:http://127.0.0.1:5001}") String baseUrl,
            WebClient.Builder builder,
            ChatModel chatModel,
            TikaHtmlExtractor tikaHtmlExtractor,
            HtmlToMarkdownConverter htmlToMarkdownConverter,
            OcrResultRepository ocrResultRepository,
            @Qualifier("mrpVirtualThreadExecutor") ExecutorService executorService) {
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.chatModel = chatModel;
        this.tikaHtmlExtractor = tikaHtmlExtractor;
        this.htmlToMarkdownConverter = htmlToMarkdownConverter;
        this.ocrResultRepository = ocrResultRepository;
        this.executorService = executorService;
        log.info("[DocumentConverter] Refactored DoclingClient initialized with Gemini + Tika + page-by-page OCR support.");
    }

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    /**
     * Convert any document resource to Markdown.
     * Orchestrates between Method A (Google Gemini) and Method C (Apache Tika).
     *
     * @param resource Spring Resource (FileSystemResource or UrlResource)
     * @param filename Original filename
     * @return DoclingResult with markdown string or error details
     */
    public DoclingResult convertToMarkdown(Resource resource, String filename) {
        return convertToMarkdown(resource, filename, "gemini", null);
    }

    /**
     * Convert any document resource to Markdown.
     * Orchestrates between Method A (Google Gemini) and Method C (Apache Tika).
     *
     * @param resource Spring Resource (FileSystemResource or UrlResource)
     * @param filename Original filename
     * @param parserMethod The chosen parser: "gemini" or "tika"
     * @return DoclingResult with markdown string or error details
     */
    public DoclingResult convertToMarkdown(Resource resource, String filename, String parserMethod) {
        return convertToMarkdown(resource, filename, parserMethod, null);
    }

    /**
     * Convert any document resource to Markdown.
     * Orchestrates between Method A (Google Gemini) and Method C (Apache Tika).
     *
     * @param resource Spring Resource (FileSystemResource or UrlResource)
     * @param filename Original filename
     * @param parserMethod The chosen parser: "gemini" or "tika"
     * @param documentId Optional document ID for database caching of page-by-page OCR results
     * @return DoclingResult with markdown string or error details
     */
    public DoclingResult convertToMarkdown(Resource resource, String filename, String parserMethod, Long documentId) {
        long start = System.currentTimeMillis();
        log.info("[DocumentConverter] ▶ Converting: '{}' with parser: '{}', docId: {}", filename, parserMethod, documentId);

        String mimeType = getMimeType(filename);
        boolean hasGemini = chatModel != null && isGeminiConfigured();
        boolean forceTika = "tika".equalsIgnoreCase(parserMethod);

        // ── METHOD A: Google Gemini Multimodal ───────────────────────────────
        if (hasGemini && !forceTika) {
            try {
                String markdown;
                if (isMultimodalSupported(mimeType)) {
                    if (mimeType.equals("application/pdf")) {
                        log.info("[DocumentConverter] Method A1 (PDF): Processing page-by-page for '{}'", filename);
                        markdown = convertPdfPageByPageWithGemini(resource, filename, documentId);
                    } else {
                        log.info("[DocumentConverter] Method A1 (Non-PDF): Sending '{}' directly to Gemini", filename);
                        markdown = convertWithGeminiMultimodal(resource, mimeType, filename);
                    }
                } else {
                    log.info("[DocumentConverter] Method A2: Office document '{}' - converting to HTML via Tika, then beautifying with Gemini", filename);
                    markdown = convertOfficeWithGemini(resource, filename);
                }

                if (markdown != null && !markdown.isBlank()) {
                    long elapsed = System.currentTimeMillis() - start;
                    log.info("[DocumentConverter] ✓ Gemini successfully parsed '{}' in {}ms", filename, elapsed);
                    return DoclingResult.success(markdown, "GEMINI_SUCCESS", elapsed);
                }
            } catch (Exception e) {
                log.warn("[DocumentConverter] Gemini conversion failed for '{}': {}. Falling back to Method C (Tika local).", filename, e.getMessage(), e);
            }
        } else {
            if (forceTika) {
                log.info("[DocumentConverter] Forced Apache Tika parser method for '{}'", filename);
            } else {
                log.info("[DocumentConverter] Gemini not configured or offline. Using Method C (Tika local) for '{}'", filename);
            }
        }

        // ── METHOD C: Pure Java Tika Local Fallback ──────────────────────────
        try {
            log.info("[DocumentConverter] Method C: Running Apache Tika local fallback extraction for '{}'", filename);
            TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(resource);
            String rawHtml = htmlResult.html();
            if (rawHtml == null || rawHtml.isBlank()) {
                throw new IllegalStateException("Tika returned empty HTML");
            }
            String markdown = htmlToMarkdownConverter.convert(rawHtml);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[DocumentConverter] ✓ Tika successfully converted '{}' in {}ms", filename, elapsed);
            return DoclingResult.success(markdown, "TIKA_SUCCESS", elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[DocumentConverter] Tika fallback conversion failed for '{}': {}", filename, e.getMessage(), e);
            return DoclingResult.failed("Both Gemini and Tika methods failed: " + e.getMessage(), elapsed);
        }
    }

    /**
     * Returns true since we now support both Gemini (online) and Tika (offline local fallback) seamlessly.
     */
    public boolean isHealthy() {
        return true;
    }

    // =========================================================================
    //  PRIVATE HELPERS & METHOD A IMPLEMENTATIONS
    // =========================================================================

    private boolean isGeminiConfigured() {
        return geminiApiKey != null
                && !geminiApiKey.isBlank()
                && !geminiApiKey.contains("API_KEY"); // avoid placeholder literal ${API_KEY}
    }

    private boolean isMultimodalSupported(String mimeType) {
        return mimeType.equals("application/pdf")
                || mimeType.startsWith("image/")
                || mimeType.equals("text/plain")
                || mimeType.equals("text/csv")
                || mimeType.equals("text/html");
    }

    private String convertPdfPageByPageWithGemini(Resource resource, String filename, Long documentId) throws Exception {
        byte[] fileBytes;
        try (var in = resource.getInputStream()) {
            fileBytes = in.readAllBytes();
        }

        if (fileBytes.length == 0) {
            throw new IllegalArgumentException("Resource file is empty");
        }

        // 1. Split PDF into single pages using PDFBox
        List<byte[]> pageBytesList = new ArrayList<>();
        try (PDDocument pdDocument = PDDocument.load(fileBytes)) {
            int pageCount = pdDocument.getNumberOfPages();
            log.info("[DocumentConverter] Splitting PDF '{}' into {} pages", filename, pageCount);
            for (int i = 0; i < pageCount; i++) {
                try (PDDocument singlePageDoc = new PDDocument()) {
                    singlePageDoc.addPage(pdDocument.getPage(i));
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    singlePageDoc.save(baos);
                    pageBytesList.add(baos.toByteArray());
                }
            }
        }

        int totalPages = pageBytesList.size();
        String[] pageMarkdowns = new String[totalPages];
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Semaphore geminiSemaphore = new Semaphore(5); // Cap concurrency at 5 calls to avoid rate limits

        String systemPrompt = """
            You are a precise document-to-markdown compiler.
            Your ONLY job is to output the exact Markdown translation of the single-page document.
            
            CRITICAL RULES:
            1. DO NOT write any introduction, notes, thoughts, explanations, self-corrections, planning, or summaries.
            2. Your response MUST start immediately with the first character of the Markdown content (e.g., the title '# ...' or paragraph).
            3. Do NOT wrap the Markdown in backticks like ```markdown ... ```.
            4. Keep the exact text and reading order of the page. Do not summarize or rewrite.
            5. Reconstruct headings (#, ##, ###, ####), bullet lists, and tables precisely. Use standard Markdown table syntax for tables.
            6. Use standard LaTeX for mathematical equations if any.
            """;

        for (int i = 0; i < totalPages; i++) {
            final int pageIndex = i;
            final int pageNumber = i + 1;
            final byte[] pageBytes = pageBytesList.get(i);

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                long pageStart = System.currentTimeMillis();

                // 2. Check DB Cache first if documentId is present
                if (documentId != null) {
                    Optional<OcrResult> cached = ocrResultRepository.findByDocumentIdAndPageNumber(documentId, pageNumber);
                    if (cached.isPresent() && "COMPLETED".equals(cached.get().getStatus())) {
                        log.debug("[DocumentConverter] Page {} / {} loaded from DB cache", pageNumber, totalPages);
                        pageMarkdowns[pageIndex] = cached.get().getMarkdownContent();
                        return;
                    }
                }

                // Initialize/update OcrResult in DB to PROCESSING
                OcrResult ocrResult = null;
                if (documentId != null) {
                    ocrResult = ocrResultRepository.findByDocumentIdAndPageNumber(documentId, pageNumber)
                            .orElseGet(() -> OcrResult.builder()
                                    .documentId(documentId)
                                    .pageNumber(pageNumber)
                                    .build());
                    ocrResult.setStatus("PROCESSING");
                    ocrResultRepository.save(ocrResult);
                }

                try {
                    geminiSemaphore.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Page OCR execution interrupted", ie);
                }

                try {
                    int maxRetries = 3;
                    int retryCount = 0;
                    String pageMarkdown = null;
                    Exception lastException = null;

                    ByteArrayResource byteResource = new ByteArrayResource(pageBytes) {
                        @Override
                        public String getFilename() {
                            return "page_" + pageNumber + ".pdf";
                        }
                    };
                    Media media = new Media(MimeTypeUtils.parseMimeType("application/pdf"), byteResource);

                    while (retryCount < maxRetries && pageMarkdown == null) {
                        try {
                            if (retryCount > 0) {
                                long sleepMs = (long) Math.pow(2, retryCount) * 1000L;
                                Thread.sleep(sleepMs);
                                log.info("[DocumentConverter] Retrying page {} OCR (attempt {}) after {}ms delay", pageNumber, retryCount + 1, sleepMs);
                            }

                            ChatClient chatClient = ChatClient.builder(chatModel).build();
                            ChatResponse response = chatClient.prompt()
                                    .options(GoogleGenAiChatOptions.builder()
                                            .model(geminiModel)
                                            .temperature(0.0)
                                            .build())
                                    .system(systemPrompt)
                                    .user(u -> u.text("Convert this page into Markdown format. DO NOT write any thinking process, notes, planning, or explanations. Start immediately with the Markdown content:").media(media))
                                    .call()
                                    .chatResponse();

                            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                                String text = response.getResult().getOutput().getText();
                                if (text != null && !text.isBlank()) {
                                    pageMarkdown = cleanMarkdown(text);
                                }
                            }
                        } catch (Exception e) {
                            lastException = e;
                            retryCount++;
                        }
                    }

                    if (pageMarkdown == null) {
                        throw new RuntimeException("Failed to OCR page " + pageNumber + " after " + maxRetries + " attempts. Last error: " + (lastException != null ? lastException.getMessage() : "Unknown"));
                    }

                    long elapsed = System.currentTimeMillis() - pageStart;
                    pageMarkdowns[pageIndex] = pageMarkdown;

                    // Save completed page to DB
                    if (ocrResult != null) {
                        ocrResult.setStatus("COMPLETED");
                        ocrResult.setMarkdownContent(pageMarkdown);
                        ocrResult.setElapsedMs(elapsed);
                        ocrResult.setErrorMessage(null);
                        ocrResultRepository.save(ocrResult);
                    }
                } catch (Exception e) {
                    log.error("[DocumentConverter] Failed to parse page {} of documentId {}: {}", pageNumber, documentId, e.getMessage(), e);
                    pageMarkdowns[pageIndex] = "\n\n<!-- PAGE_ERROR: " + pageNumber + " - " + e.getMessage() + " -->\n\n";

                    if (ocrResult != null) {
                        ocrResult.setStatus("FAILED");
                        ocrResult.setErrorMessage(e.getMessage());
                        ocrResultRepository.save(ocrResult);
                    }
                } finally {
                    geminiSemaphore.release();
                }
            }, executorService);

            futures.add(future);
        }

        // Wait for all pages to finish
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 3. Assemble all page markdowns
        StringBuilder finalMarkdown = new StringBuilder();
        for (int i = 0; i < totalPages; i++) {
            if (i > 0) {
                finalMarkdown.append("\n\n<!-- PAGE_BREAK: ").append(i + 1).append(" -->\n\n");
            }
            finalMarkdown.append(pageMarkdowns[i] != null ? pageMarkdowns[i] : "");
        }

        return finalMarkdown.toString();
    }

    private String convertWithGeminiMultimodal(Resource resource, String mimeType, String filename) throws Exception {
        byte[] fileBytes;
        try (var in = resource.getInputStream()) {
            fileBytes = in.readAllBytes();
        }

        if (fileBytes.length == 0) {
            throw new IllegalArgumentException("Resource file is empty");
        }

        ByteArrayResource byteResource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), byteResource);
        ChatClient chatClient = ChatClient.builder(chatModel).build();

        String systemPrompt = """
            You are a precise document-to-markdown compiler.
            Your ONLY job is to output the exact Markdown translation of the input document.
            
            CRITICAL RULES:
            1. DO NOT write any introduction, notes, thoughts, explanations, self-corrections, planning, or summaries.
            2. Your response MUST start immediately with the first character of the Markdown content (e.g., the title '# ...' or paragraph).
            3. Do NOT wrap the Markdown in backticks like ```markdown ... ```.
            4. Keep the exact text and reading order of the document. Do not summarize or rewrite.
            5. Reconstruct headings (#, ##, ###, ####), bullet lists, and tables precisely. Use hierarchical heading levels: H1 (#) for main titles/chapters/major parts, H2 (##) for sections (e.g. "1. Giới thiệu"), H3 (###) for subsections (e.g. "1.1"), and H4 (####) for deep subsections (e.g. "1.1.1"). Use standard Markdown table syntax for tables.

            7. Use standard LaTeX for mathematical equations if any.
            """;

        ChatResponse response = chatClient.prompt()
                .options(GoogleGenAiChatOptions.builder()
                        .model(geminiModel) // force gemini-1.5-flash for multimodal support
                        .temperature(0.0) // deterministic outputs
                        .build())
                .system(systemPrompt)
                .user(u -> u.text("Convert the attached document into Markdown format. DO NOT write any thinking process, notes, planning, or explanations. Start immediately with the Markdown content:").media(media))
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Gemini returned an empty response");
        }

        String markdown = response.getResult().getOutput().getText();
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("Gemini returned empty text");
        }

        return cleanMarkdown(markdown);
    }

    private String convertOfficeWithGemini(Resource resource, String filename) throws Exception {
        TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(resource);
        String rawHtml = htmlResult.html();
        if (rawHtml == null || rawHtml.isBlank()) {
            throw new IllegalStateException("Tika returned empty HTML for Office document");
        }

        ChatClient chatClient = ChatClient.builder(chatModel).build();

        String systemPrompt = """
            You are a precise raw-HTML-to-markdown compiler.
            Your ONLY job is to convert raw HTML into beautiful, clean, well-formatted Markdown.
            
            CRITICAL RULES:
            1. DO NOT write any introduction, notes, thoughts, explanations, self-corrections, planning, or summaries.
            2. Your response MUST start immediately with the first character of the Markdown content.
            3. Do NOT wrap the Markdown in backticks like ```markdown ... ```.
            4. Keep the exact text and reading order of the document. Do not summarize or rewrite.
            5. Reconstruct headings (#, ##, ###, ####), bullet lists, and tables precisely. Use hierarchical heading levels: H1 (#) for main titles/chapters/major parts, H2 (##) for sections (e.g. "1. Giới thiệu"), H3 (###) for subsections (e.g. "1.1"), and H4 (####) for deep subsections (e.g. "1.1.1"). Use standard Markdown table syntax for tables.
            6. Do NOT include image tags, raw image bytes, or picture placeholders.
            7. Keep bold, italic, and underline stylings.
            """;

        ChatResponse response = chatClient.prompt()
                .options(GoogleGenAiChatOptions.builder()
                        .model("gemini-1.5-flash")
                        .temperature(0.0)
                        .build())
                .system(systemPrompt)
                .user("Convert the following HTML document to Markdown. DO NOT write any thinking process, notes, planning, or explanations. Start immediately with the Markdown content:\n\n" + rawHtml)
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Gemini returned an empty response");
        }

        String markdown = response.getResult().getOutput().getText();
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalStateException("Gemini returned empty text");
        }

        return cleanMarkdown(markdown);
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

    private String getMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc")) return "application/msword";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls")) return "application/vnd.ms-excel";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".mp3")) return "audio/mp3";
        if (lower.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    // =========================================================================
    //  RESPONSE DTOs — Jackson @JsonProperty for snake_case keys (Deprecated/Unused)
    // =========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DoclingApiResponse(
            @JsonProperty("status")    String status,
            @JsonProperty("documents") List<DoclingDocument> documents
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record DoclingDocument(
                @JsonProperty("status")     String status,
                @JsonProperty("md_content") String mdContent,
                @JsonProperty("doc_meta")   DoclingMeta docMeta
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record DoclingMeta(
                @JsonProperty("filename")   String filename,
                @JsonProperty("page_count") Integer pageCount
        ) {}
    }

    // =========================================================================
    //  RESULT TYPE
    // =========================================================================

    public record DoclingResult(
            boolean success,
            String  markdown,
            String  doclingStatus,
            String  errorMessage,
            long    elapsedMs
    ) {
        public static DoclingResult success(String markdown, String status, long ms) {
            return new DoclingResult(true, markdown, status, null, ms);
        }

        public static DoclingResult failed(String error, long ms) {
            return new DoclingResult(false, null, "FAILURE", error, ms);
        }

        public boolean hasMarkdown() {
            return success && markdown != null && !markdown.isBlank();
        }
    }
}