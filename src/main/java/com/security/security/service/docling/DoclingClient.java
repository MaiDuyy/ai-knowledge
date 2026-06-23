package com.security.security.service.docling;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.security.security.service.GeminiMultimodalService;
import com.security.security.service.tika.HtmlToMarkdownConverter;
import com.security.security.service.tika.TikaHtmlExtractor;
import com.security.security.service.tika.TikaHtmlResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * DoclingClient — Unified Document Converter Orchestrator.
 *
 * Orchestrates between Method A (Google Gemini) and Method C (Apache Tika).
 * Now delegating all Gemini multimodal and OCR parsing to GeminiMultimodalService.
 */
@Component
@Slf4j
public class DoclingClient {

    private final WebClient webClient;
    private final GeminiMultimodalService geminiMultimodalService;
    private final TikaHtmlExtractor tikaHtmlExtractor;
    private final HtmlToMarkdownConverter htmlToMarkdownConverter;

    @Value("${spring.ai.google.genai.api-key:}")
    private String geminiApiKey;

    public DoclingClient(
            @Value("${docling.base-url:http://127.0.0.1:5001}") String baseUrl,
            WebClient.Builder builder,
            GeminiMultimodalService geminiMultimodalService,
            TikaHtmlExtractor tikaHtmlExtractor,
            HtmlToMarkdownConverter htmlToMarkdownConverter) {
        this.webClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.geminiMultimodalService = geminiMultimodalService;
        this.tikaHtmlExtractor = tikaHtmlExtractor;
        this.htmlToMarkdownConverter = htmlToMarkdownConverter;
        log.info("[DocumentConverter] Refactored DoclingClient initialized with GeminiMultimodalService.");
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
        boolean hasGemini = geminiMultimodalService != null && isGeminiConfigured();
        boolean forceTika = "tika".equalsIgnoreCase(parserMethod);

        // ── METHOD A: Google Gemini Multimodal / Hybrid PDF ──────────────────
        if (hasGemini && !forceTika) {
            try {
                String markdown = null;
                if (mimeType.equals("application/pdf")) {
                    log.info("[DocumentConverter] PDF detected. Running Gemini page-by-page hybrid parsing.");
                    byte[] fileBytes;
                    try (var in = resource.getInputStream()) {
                        fileBytes = in.readAllBytes();
                    }
                    markdown = geminiMultimodalService.parsePdf(fileBytes, documentId);
                } else if (isMultimodalSupported(mimeType)) {
                    log.info("[DocumentConverter] Method A1 (Non-PDF): Sending '{}' directly to Gemini", filename);
                    byte[] fileBytes;
                    try (var in = resource.getInputStream()) {
                        fileBytes = in.readAllBytes();
                    }
                    markdown = geminiMultimodalService.parseImage(fileBytes, mimeType, documentId, 1);
                } else {
                    log.info("[DocumentConverter] Method A2: Office document '{}' - converting to HTML via Tika, then beautifying with Gemini", filename);
                    TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(resource);
                    String rawHtml = htmlResult.html();
                    markdown = geminiMultimodalService.parseHtml(rawHtml, documentId, 1);
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

    public boolean isHealthy() {
        return true;
    }

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
        return "application/octet-stream";
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