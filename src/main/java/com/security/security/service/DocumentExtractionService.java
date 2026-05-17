package com.security.security.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

/**
 * DocumentExtractionService — thin wrapper around TikaDocumentReader.
 *
 * Apache Tika (via spring-ai-tika-document-reader) handles all file types:
 * PDF, DOCX, TXT, HTML, Excel, PowerPoint, etc.
 *
 * The actual ETL pipeline is in DocumentProcessingListener.
 * This class is kept for any direct extraction needs.
 */
@Service
@Slf4j
public class DocumentExtractionService {

    /**
     * Extract plain text from any supported file using Apache Tika.
     * Replaces manual PDFBox + Apache POI extraction.
     */
    public String extractText(String filePath, String documentType) {
        log.info("[Extract] file={} type={}", filePath, documentType);
        try {
            TikaDocumentReader reader = new TikaDocumentReader(
                    new FileSystemResource(filePath)
            );
            StringBuilder sb = new StringBuilder();
            reader.get().forEach(doc -> {
                if (doc.getText() != null) sb.append(doc.getText());
            });
            String text = sb.toString();
            log.info("[Extract] Extracted {} chars from {}", text.length(), filePath);
            return text;
        } catch (Exception e) {
            log.error("[Extract] Failed to extract {}: {}", filePath, e.getMessage(), e);
            throw new RuntimeException("Failed to extract text from: " + filePath, e);
        }
    }
}
