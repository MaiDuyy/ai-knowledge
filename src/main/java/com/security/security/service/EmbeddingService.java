package com.security.security.service;

import com.security.security.dtorequest.DocumentSyncPayload;
import com.security.security.entity.Embedding;
import com.security.security.repository.EmbeddingRepository;
import com.security.security.service.tika.SemanticMarkdownChunker;
import com.security.security.client.WorkspaceServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    private final SemanticMarkdownChunker semanticMarkdownChunker;
    private final WorkspaceServiceClient workspaceServiceClient;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

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
     * Delete documents by documentId from both VectorStore and database.
     */
    @Transactional
    public void deleteByDocumentId(Long documentId) {
        log.info("Deleting embeddings for document: {}", documentId);
        try {
            vectorStore.delete(String.format("documentId == '%s'", documentId.toString()));
        } catch (Exception e) {
            log.warn("Could not delete from VectorStore: {}", e.getMessage());
        }
        embeddingRepository.deleteByDocumentId(documentId);
    }

    /**
     * Ingest document markdown into VectorStore and database with semantic chunking and batch loading.
     */
    @Transactional
    public List<SemanticMarkdownChunker.ChunkResult> ingestMarkdown(com.security.security.entity.Document document, String markdownContent) {
        log.info("[EmbeddingService] Ingesting document {} markdown content", document.getId());

        List<SemanticMarkdownChunker.ChunkResult> chunkResults = semanticMarkdownChunker.chunk(markdownContent);
        if (chunkResults.isEmpty()) {
            throw new IllegalStateException("No chunks produced from markdown");
        }

        // Purge old chunks from VectorStore before loading new ones
        try {
            vectorStore.delete(String.format("documentId == '%s'", document.getId().toString()));
        } catch (Exception e) {
            log.warn("[EmbeddingService] Could not delete old chunks from VectorStore for document id={}: {}", document.getId(), e.getMessage());
        }

        // Purge old database chunks
        embeddingRepository.deleteByDocumentId(document.getId());

        int batchSize = 30;
        List<org.springframework.ai.document.Document> vBatch = new ArrayList<>(batchSize);
        List<Embedding> eBatch = new ArrayList<>(batchSize);

        // Resolve workspace and department names once outside the loop
        String workspaceId = document.getWorkspaceId();
        String departmentId = document.getDepartmentId();
        String userId = document.getUserId();

        String workspaceName = "System";
        if (workspaceId != null && !workspaceId.isBlank() && !"workspace-default".equals(workspaceId)) {
            try {
                Map<String, Object> wsMap = workspaceServiceClient.getWorkspace(workspaceId, userId);
                if (wsMap != null && wsMap.get("name") != null) {
                    workspaceName = String.valueOf(wsMap.get("name"));
                }
            } catch (Exception e) {
                log.warn("[EmbeddingService] Failed to resolve workspace for id={}: {}", workspaceId, e.getMessage());
            }
        }

        String departmentName = "General";
        if (departmentId != null && !departmentId.isBlank()) {
            try {
                Map<String, Object> deptMap = workspaceServiceClient.getDepartment(departmentId, userId);
                if (deptMap != null && deptMap.get("name") != null) {
                    departmentName = String.valueOf(deptMap.get("name"));
                }
            } catch (Exception e) {
                log.warn("[EmbeddingService] Failed to resolve department for id={}: {}", departmentId, e.getMessage());
            }
        }

        String folderPath = document.getFolderPath();
        String folderPathStr = (folderPath != null && !folderPath.isBlank()) ? folderPath.strip() : "/";

        String prefix = String.format("[Context: Workspace: %s | Dept: %s | Path: %s] ", workspaceName, departmentName, folderPathStr);

        for (int i = 0; i < chunkResults.size(); i++) {
            SemanticMarkdownChunker.ChunkResult cr = chunkResults.get(i);

            // Prepend absolute context prefix to chunk text for RAG retrieval
            String chunkText = prefix + cr.text();

            Map<String, Object> meta = new HashMap<>();
            meta.put("documentId", document.getId().toString());
            meta.put("userId", document.getUserId());
            meta.put("fileName", document.getFileName());
            meta.put("chunkIndex", String.valueOf(i));
            meta.put("chunkTitle", cr.title());
            meta.put("tokenCount", String.valueOf(semanticMarkdownChunker.estimateTokens(chunkText)));
            meta.put("charCount", String.valueOf(chunkText.length()));
            
            if (document.getSecurityClassification() != null) {
                meta.put("classification", document.getSecurityClassification());
                meta.put("securityClassification", document.getSecurityClassification());
            }
            meta.put("uploadedBy", document.getUserId());
            meta.put("workspaceId", ScopeNormalizer.normalizeWorkspace(document.getWorkspaceId()));
            meta.put("departmentId", ScopeNormalizer.normalizeDepartment(document.getDepartmentId()));
            meta.put("allowedRoles", document.getAllowedRoles() != null ? document.getAllowedRoles() : "ALL");
            if (folderPath != null && !folderPath.isBlank()) {
                meta.put("folderPath", folderPath.strip());
            }

            vBatch.add(new org.springframework.ai.document.Document(chunkText, meta));
            eBatch.add(Embedding.builder()
                    .documentId(document.getId())
                    .workspaceId(ScopeNormalizer.normalizeWorkspace(document.getWorkspaceId()))
                    .chunkIndex(i)
                    .chunkText(chunkText)
                    .chunkTitle(cr.title())
                    .tokenCount(semanticMarkdownChunker.estimateTokens(chunkText))
                    .charCount(chunkText.length())
                    .build());

            if (vBatch.size() >= batchSize) {
                vectorStore.add(new ArrayList<>(vBatch));
                embeddingRepository.saveAll(new ArrayList<>(eBatch));
                vBatch.clear();
                eBatch.clear();
            }
        }
        if (!vBatch.isEmpty()) {
            vectorStore.add(vBatch);
            embeddingRepository.saveAll(eBatch);
        }

        log.info("[EmbeddingService] Successfully ingested {} chunks for document id={}", chunkResults.size(), document.getId());
        return chunkResults;
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

    /**
     * Update existing embedding metadata in PostgreSQL vector store and PostgreSQL databases.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateEmbeddingsMetadata(Long documentId, String workspaceId, String departmentId, String allowedRoles, String securityClassification) {
        log.info("[EmbeddingService] Updating metadata for document {} embeddings: workspaceId={}, departmentId={}, allowedRoles={}, securityClassification={}",
                documentId, workspaceId, departmentId, allowedRoles, securityClassification);

        // 1. Update workspaceId in the JPA embeddings database table
        String normalizedWorkspace = ScopeNormalizer.normalizeWorkspace(workspaceId);
        embeddingRepository.updateWorkspaceId(documentId, normalizedWorkspace);

        // 2. Update metadata inside PostgreSQL pgvector vector_store JSONB
        if (isPostgreSQL() && vectorStoreTableExists()) {
            Map<String, String> updates = new HashMap<>();
            updates.put("workspaceId", normalizedWorkspace);
            updates.put("departmentId", ScopeNormalizer.normalizeDepartment(departmentId));
            updates.put("allowedRoles", allowedRoles != null ? allowedRoles : "ALL");
            if (securityClassification != null) {
                updates.put("classification", securityClassification);
                updates.put("securityClassification", securityClassification);
            }

            try {
                String jsonStr = objectMapper.writeValueAsString(updates);
                int updatedRows = embeddingRepository.updateVectorMetadata(String.valueOf(documentId), jsonStr);
                log.info("[EmbeddingService] Updated {} rows in VectorStore for document {}", updatedRows, documentId);
            } catch (Exception e) {
                log.error("[EmbeddingService] Failed to update VectorStore metadata for document {}: {}", documentId, e.getMessage());
            }
        } else {
            log.info("[EmbeddingService] Skipping pgvector update because database is not PostgreSQL or vector_store table does not exist");
        }
    }

    /**
     * Update existing wiki page embedding metadata in PostgreSQL vector store.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateWikiPageEmbeddingsMetadata(Long wikiPageId, String workspaceId, String departmentId, String allowedRoles, String securityClassification) {
        log.info("[EmbeddingService] Updating metadata for wiki page {} embeddings: workspaceId={}, departmentId={}, allowedRoles={}, securityClassification={}",
                wikiPageId, workspaceId, departmentId, allowedRoles, securityClassification);

        if (isPostgreSQL() && vectorStoreTableExists()) {
            Map<String, String> updates = new HashMap<>();
            updates.put("workspaceId", ScopeNormalizer.normalizeWorkspace(workspaceId));
            updates.put("departmentId", ScopeNormalizer.normalizeDepartment(departmentId));
            updates.put("allowedRoles", allowedRoles != null ? allowedRoles : "ALL");
            if (securityClassification != null) {
                updates.put("classification", securityClassification);
                updates.put("securityClassification", securityClassification);
            }

            try {
                String jsonStr = objectMapper.writeValueAsString(updates);
                int updatedRows = embeddingRepository.updateWikiVectorMetadata(String.valueOf(wikiPageId), jsonStr);
                log.info("[EmbeddingService] Updated {} rows in VectorStore for wiki page {}", updatedRows, wikiPageId);
            } catch (Exception e) {
                log.error("[EmbeddingService] Failed to update VectorStore metadata for wiki page {}: {}", wikiPageId, e.getMessage());
            }
        } else {
            log.info("[EmbeddingService] Skipping pgvector update for wiki page because database is not PostgreSQL or vector_store table does not exist");
        }
    }

    private boolean isPostgreSQL() {
        try (java.sql.Connection conn = dataSource.getConnection()) {
            String dbProduct = conn.getMetaData().getDatabaseProductName();
            return dbProduct != null && dbProduct.toLowerCase().contains("postgres");
        } catch (Exception e) {
            log.warn("[EmbeddingService] Failed to check database product name: {}", e.getMessage());
            return false;
        }
    }

    private boolean vectorStoreTableExists() {
        try (java.sql.Connection conn = dataSource.getConnection()) {
            String schema = "ai_knowledge";
            try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, schema, "vector_store", null)) {
                if (rs.next()) {
                    return true;
                }
            }
            try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, schema.toLowerCase(), "vector_store", null)) {
                if (rs.next()) {
                    return true;
                }
            }
            try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, "vector_store", null)) {
                return rs.next();
            }
        } catch (Exception e) {
            log.warn("[EmbeddingService] Failed to check if vector_store table exists: {}", e.getMessage());
            return false;
        }
    }
}
