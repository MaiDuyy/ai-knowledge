package com.security.security.service;

import com.security.security.service.docling.DoclingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

/**
 * DocumentExtractionService — Docling-style document extraction pipeline wrapper.
 * Delegating all extraction logic to DoclingClient to enforce Single Source of Truth.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentExtractionService {

    private final DoclingClient doclingClient;

    /**
     * Extract structured Markdown from any supported file (PDF, DOCX, TXT, PNG, JPG).
     */
    public String extractMarkdown(String filePath) {
        log.info("[Extract] Markdown from: {}", filePath);
        java.io.File file = new java.io.File(filePath);
        FileSystemResource resource = new FileSystemResource(file);
        
        DoclingClient.DoclingResult result = doclingClient.convertToMarkdown(resource, file.getName(), "gemini");
        if (result.success() && result.hasMarkdown()) {
            return result.markdown();
        } else {
            throw new RuntimeException("Failed to extract text from: " + filePath + ". Error: " + result.errorMessage());
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
