package com.security.security.service;

import com.security.security.entity.Embedding;
import com.security.security.repository.EmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PostgreSQL full-text search (tsvector/tsquery) for BM25-equivalent keyword retrieval.
 * Inspired by WeKnora's dual-indexing pattern: vector + keyword search fusion.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KeywordSearchService {

    private final EmbeddingRepository embeddingRepository;

    public List<Embedding> search(String query, String workspaceId, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            String normalizedWs = ScopeNormalizer.normalizeWorkspace(workspaceId);
            return embeddingRepository.keywordSearch(query, normalizedWs, topK);
        } catch (Exception e) {
            log.warn("[KeywordSearch] Failed for query='{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    public List<Embedding> searchInDocument(String query, Long documentId, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return embeddingRepository.keywordSearchInDocument(query, documentId, topK);
        } catch (Exception e) {
            log.warn("[KeywordSearch] Failed in document {} for query='{}': {}", documentId, query, e.getMessage());
            return List.of();
        }
    }
}
