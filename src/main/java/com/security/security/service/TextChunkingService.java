package com.security.security.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TextChunkingService — utility methods only.
 *
 * Chunking logic đã được chuyển sang Spring AI ETL Pipeline:
 *   TikaDocumentReader → TokenTextSplitter → VectorStore
 *
 * Class này chỉ còn giữ các helper được gọi từ nơi khác.
 */
@Service
public class TextChunkingService {

    /**
     * Simple chunk split — kept for backward compatibility with tests.
     * Production pipeline uses Spring AI TokenTextSplitter instead.
     */
    public List<String> chunkText(String text) {
        if (text == null || text.isBlank()) return List.of();
        // delegate to Spring AI TokenTextSplitter logic via simple split
        int chunkSize = 1200;
        List<String> result = new java.util.ArrayList<>();
        String normalized = text.replaceAll(" {2,}", " ").trim();
        int pos = 0;
        while (pos < normalized.length()) {
            int end = Math.min(pos + chunkSize, normalized.length());
            if (end < normalized.length()) {
                int space = normalized.lastIndexOf(' ', end);
                if (space > pos) end = space;
            }
            String chunk = normalized.substring(pos, end).trim();
            if (!chunk.isEmpty()) result.add(chunk);
            pos = end;
        }
        return result;
    }

    /**
     * Estimate token count (1 token ≈ 4 chars).
     * Used for metadata enrichment in DocumentProcessingListener.
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    /**
     * Normalize encoding artifacts from raw extracted text.
     * Called before passing to TikaDocumentReader if needed.
     */
    public String cleanText(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return raw
                .replace("\uFEFF", "")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\u00A0", " ")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\uFFFD", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                .replaceAll("\n{4,}", "\n\n\n")
                .trim();
    }
}
