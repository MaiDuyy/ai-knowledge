package com.security.security.service;

import com.security.security.dtorequest.DocumentSyncPayload;
import com.security.security.entity.Embedding;
import com.security.security.repository.EmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmbeddingService {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingRepository embeddingRepository;

    /**
     * Store document chunks in VectorStore
     */
    public void storeDocuments(Long documentId, Long userId, String fileName, List<String> chunks) {
        log.info("Storing {} chunks for document {} in VectorStore", chunks.size(), documentId);

        List<Document> documents = IntStream.range(0, chunks.size())
                .mapToObj(i -> new Document(
                        chunks.get(i),
                        Map.of(
                                "documentId", documentId.toString(),
                                "userId", userId.toString(),
                                "fileName", fileName,
                                "chunkIndex", String.valueOf(i),
                                "tokenCount", String.valueOf(estimateTokens(chunks.get(i))))))
                .toList();

        vectorStore.add(documents);
        log.info("Successfully stored {} documents in VectorStore", documents.size());
    }

    /**
     * Store documents from knowledge-service sync payload (InternalAIController)
     */
    public void storeDocumentsFromSync(DocumentSyncPayload payload) {
        log.info("Storing document {} with chunks from sync", payload.getDocumentId());

        List<Document> documents = payload.getChunks().stream()
                .map(chunk -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("documentId", payload.getDocumentId());
                    metadata.put("fileName", payload.getTitle());

                    if (payload.getMetadata() != null) {
                        metadata.put("collectionId", payload.getMetadata().getCollectionId());
                        metadata.put("classification", payload.getMetadata().getClassification());
                        metadata.put("uploadedBy", payload.getMetadata().getUploadedBy());
                    }

                    if (chunk.getMetadata() != null) {
                        metadata.putAll(chunk.getMetadata());
                    }

                    return new Document(chunk.getContent(), metadata);
                })
                .collect(Collectors.toList());

        vectorStore.add(documents);
        log.info("Successfully indexed {} chunks for document {}", documents.size(), payload.getDocumentId());
    }

    /**
     * Search similar documents for a user
     */
    public List<Document> searchSimilar(String query, Long userId, int topK, double similarityThreshold) {
        log.debug("Searching similar documents for query: {}", query.substring(0, Math.min(50, query.length())));

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .filterExpression(String.format("userId == '%s'", userId))
                .build();

        return vectorStore.similaritySearch(request);
    }

    /**
     * Search similar documents within a specific document
     */
    public List<Document> searchInDocument(String query, Long documentId, int topK) {
        log.debug("Searching in document {} for query: {}", documentId,
                query.substring(0, Math.min(50, query.length())));

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(String.format("documentId == '%s'", documentId))
                .build();

        return vectorStore.similaritySearch(request);
    }

    /**
     * Get all chunks for a document
     */
    public List<String> getDocumentChunks(Long documentId) {
        log.debug("Getting all chunks for document: {}", documentId);
        return embeddingRepository
                .findByDocumentIdOrderByChunkIndex(documentId)  // đúng thứ tự
                .stream()
                .map(Embedding::getChunkText)
                .collect(Collectors.toList());
    }

    /**
     * Delete documents by documentId
     */
    public void deleteByDocumentId(Long documentId) {
        log.info("Deleting embeddings for document: {}", documentId);
        vectorStore.delete(String.format("documentId == '%s'", documentId));
    }

    /**
     * Estimate token count for text
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // Approximate: 1 token ≈ 4 characters
        return Math.max(1, text.length() / 4);
    }
}
