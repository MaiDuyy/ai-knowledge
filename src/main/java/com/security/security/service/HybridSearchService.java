package com.security.security.service;

import com.security.security.entity.Embedding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid search combining vector similarity (ANN) + PostgreSQL keyword search (BM25-like).
 * Inspired by WeKnora's dual-indexing retrieval pattern.
 *
 * Fusion strategy: Reciprocal Rank Fusion (RRF) — merges ranked lists from both
 * sources without requiring score normalization.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HybridSearchService {

    private final VectorStore vectorStore;
    private final KeywordSearchService keywordSearchService;

    private static final double RRF_K = 60.0;
    private static final double VECTOR_WEIGHT = 0.7;
    private static final double KEYWORD_WEIGHT = 0.3;

    public List<HybridResult> search(String query, String workspaceId, int topK, double similarityThreshold) {
        log.debug("[HybridSearch] query='{}', workspaceId={}, topK={}", query, workspaceId, topK);

        int fetchK = Math.min(topK * 3, 50);

        List<Document> vectorResults = vectorSearch(query, workspaceId, fetchK, similarityThreshold);
        List<Embedding> keywordResults = keywordSearchService.search(query, workspaceId, fetchK);

        return fuseResults(vectorResults, keywordResults, topK);
    }

    public List<HybridResult> searchInDocument(String query, Long documentId, int topK) {
        log.debug("[HybridSearch] query='{}', documentId={}, topK={}", query, documentId, topK);

        int fetchK = Math.min(topK * 3, 50);

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(fetchK)
                .filterExpression(String.format("documentId == '%s'", documentId))
                .build();
        List<Document> vectorResults;
        try {
            vectorResults = vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.warn("[HybridSearch] Vector search failed: {}", e.getMessage());
            vectorResults = List.of();
        }

        List<Embedding> keywordResults = keywordSearchService.searchInDocument(query, documentId, fetchK);

        return fuseResults(vectorResults, keywordResults, topK);
    }

    private List<Document> vectorSearch(String query, String workspaceId, int topK, double threshold) {
        try {
            String normalizedWs = ScopeNormalizer.normalizeWorkspace(workspaceId);
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold)
                    .filterExpression(String.format("workspaceId == '%s'", normalizedWs))
                    .build();
            return vectorStore.similaritySearch(request);
        } catch (Exception e) {
            log.warn("[HybridSearch] Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<HybridResult> fuseResults(List<Document> vectorResults, List<Embedding> keywordResults, int topK) {
        Map<String, HybridResult> merged = new LinkedHashMap<>();

        for (int rank = 0; rank < vectorResults.size(); rank++) {
            Document doc = vectorResults.get(rank);
            String key = resolveKey(doc);
            double rrfScore = VECTOR_WEIGHT / (RRF_K + rank + 1);

            merged.computeIfAbsent(key, k -> new HybridResult(key, doc.getText(), doc.getMetadata()))
                    .addScore(rrfScore)
                    .setVectorRank(rank);
        }

        for (int rank = 0; rank < keywordResults.size(); rank++) {
            Embedding emb = keywordResults.get(rank);
            String key = emb.getDocumentId() + ":" + emb.getChunkIndex();
            double rrfScore = KEYWORD_WEIGHT / (RRF_K + rank + 1);

            merged.computeIfAbsent(key, k -> {
                Map<String, Object> meta = new HashMap<>();
                meta.put("documentId", emb.getDocumentId().toString());
                meta.put("chunkIndex", String.valueOf(emb.getChunkIndex()));
                meta.put("chunkTitle", emb.getChunkTitle());
                if (emb.getContextHeader() != null) {
                    meta.put("contextHeader", emb.getContextHeader());
                }
                if (emb.getChunkType() != null) {
                    meta.put("chunkType", emb.getChunkType().name());
                }
                return new HybridResult(k, emb.getChunkText(), meta);
            }).addScore(rrfScore).setKeywordRank(rank);
        }

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(HybridResult::getScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    private String resolveKey(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        String docId = meta.getOrDefault("documentId", "").toString();
        String chunkIdx = meta.getOrDefault("chunkIndex", "0").toString();
        return docId + ":" + chunkIdx;
    }

    public static class HybridResult {
        private final String key;
        private final String text;
        private final Map<String, Object> metadata;
        private double score;
        private int vectorRank = -1;
        private int keywordRank = -1;

        public HybridResult(String key, String text, Map<String, Object> metadata) {
            this.key = key;
            this.text = text;
            this.metadata = metadata != null ? metadata : new HashMap<>();
            this.score = 0.0;
        }

        public HybridResult addScore(double delta) {
            this.score += delta;
            return this;
        }

        public HybridResult setVectorRank(int rank) {
            this.vectorRank = rank;
            return this;
        }

        public HybridResult setKeywordRank(int rank) {
            this.keywordRank = rank;
            return this;
        }

        public String getKey() { return key; }
        public String getText() { return text; }
        public Map<String, Object> getMetadata() { return metadata; }
        public double getScore() { return score; }
        public int getVectorRank() { return vectorRank; }
        public int getKeywordRank() { return keywordRank; }
        public boolean isFromBothSources() { return vectorRank >= 0 && keywordRank >= 0; }
    }
}
