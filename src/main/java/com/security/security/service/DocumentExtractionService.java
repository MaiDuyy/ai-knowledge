package com.security.security.service;

import com.security.security.service.tika.HtmlToMarkdownConverter;
import com.security.security.service.tika.TikaHtmlExtractor;
import com.security.security.service.tika.TikaHtmlResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

/**
 * DocumentExtractionService — Docling-style document extraction pipeline.
 *
 * Flow: Tika → HTML → Jsoup NodeVisitor → Markdown
 * Thay thế TikaDocumentReader (plain text) để giữ cấu trúc tài liệu.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentExtractionService {

    private final TikaHtmlExtractor tikaHtmlExtractor;
    private final HtmlToMarkdownConverter htmlToMarkdownConverter;
    private final com.security.security.service.tika.GeminiOcrParser geminiOcrParser;

    /**
     * Extract structured Markdown from any supported file (PDF, DOCX, TXT, PNG, JPG).
     */
    public String extractMarkdown(String filePath) {
        log.info("[Extract] Markdown from: {}", filePath);
        java.io.File file = new java.io.File(filePath);
        String fileName = file.getName();
        
        // 1. If it's a direct image file, route directly to Gemini OCR
        if (fileName != null && isImageFile(fileName)) {
            log.info("[Extract] Direct image detected. Using GeminiOcrParser for: {}", fileName);
            try {
                return geminiOcrParser.parse(file);
            } catch (Exception ocrEx) {
                log.error("[Extract] Gemini OCR failed for image {}: {}", filePath, ocrEx.getMessage(), ocrEx);
                throw new RuntimeException("Failed Gemini OCR extraction from image: " + filePath, ocrEx);
            }
        }

        // 2. Otherwise (PDF, DOCX, TXT, etc.), try normal Tika pipeline first
        try {
            TikaHtmlResult result = tikaHtmlExtractor.extract(new FileSystemResource(filePath));
            String markdown = htmlToMarkdownConverter.convert(result.html());
            
            // 3. Scanned PDF Check: if it's a PDF but text output is empty or extremely short, fall back to Gemini OCR
            if (fileName != null && fileName.toLowerCase().endsWith(".pdf") && (markdown == null || markdown.trim().length() < 150)) {
                log.info("[Extract] Scanned or empty PDF detected (chars={}). Falling back to GeminiOcrParser.", 
                        markdown != null ? markdown.trim().length() : 0);
                try {
                    return geminiOcrParser.parse(file);
                } catch (Exception ocrEx) {
                    log.error("[Extract] Gemini OCR fallback failed for PDF {}: {}", filePath, ocrEx.getMessage(), ocrEx);
                    // fallback to Tika output if OCR fails rather than throwing exception, to be safe
                    return markdown;
                }
            }

            log.info("[Extract] Extracted {} chars from {}", markdown.length(), filePath);
            return markdown;
        } catch (Exception e) {
            // 4. Fallback to Gemini OCR on general failure if PDF or image
            if (fileName != null && geminiOcrParser.isImageOrPdf(fileName)) {
                log.warn("[Extract] Tika failed. Falling back to GeminiOcrParser for: {}", fileName, e);
                try {
                    return geminiOcrParser.parse(file);
                } catch (Exception ocrEx) {
                    log.error("[Extract] Gemini OCR fallback failed: {}", ocrEx.getMessage(), ocrEx);
                }
            }
            log.error("[Extract] Failed to extract {}: {}", filePath, e.getMessage(), e);
            throw new RuntimeException("Failed to extract text from: " + filePath, e);
        }
    }

    private boolean isImageFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") 
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp");
    }

    /**
     * Extract plain text (strip Markdown formatting).
     * Backward-compatible wrapper — Markdown tốt hơn cho LLM.
     */
    @Deprecated
    public String extractText(String filePath, String documentType) {
        String md = extractMarkdown(filePath);
        return md.replaceAll("#+\\s*", "")
                .replaceAll("[|\\-]{3,}", "")
                .replaceAll("`{1,3}", "")
                .replaceAll("\\*{1,2}", "")
                .strip();
    }
}
