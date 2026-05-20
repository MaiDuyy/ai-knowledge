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

    /**
     * Extract structured Markdown from any supported file (PDF, DOCX, TXT).
     */
    public String extractMarkdown(String filePath) {
        log.info("[Extract] Markdown from: {}", filePath);
        try {
            TikaHtmlResult result = tikaHtmlExtractor.extract(new FileSystemResource(filePath));
            String markdown = htmlToMarkdownConverter.convert(result.html());
            log.info("[Extract] Extracted {} chars from {}", markdown.length(), filePath);
            return markdown;
        } catch (Exception e) {
            log.error("[Extract] Failed to extract {}: {}", filePath, e.getMessage(), e);
            throw new RuntimeException("Failed to extract text from: " + filePath, e);
        }
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
