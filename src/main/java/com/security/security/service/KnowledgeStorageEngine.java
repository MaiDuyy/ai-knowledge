package com.security.security.service;

import com.security.security.dto.StorageSearchHit;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.WikiPage;

import java.util.List;

/**
 * Strategy interface for side-by-side PostgreSQL vs MongoDB storage engines
 * used in database trade-off analysis.
 */
public interface KnowledgeStorageEngine {

    StorageEngineType getEngineType();

    /**
     * Persist document metadata and its chunks (with embedding vectors).
     */
    void storeDocument(Document document, List<Embedding> chunks);

    /**
     * Similarity search with defense-in-depth RBAC metadata filters.
     *
     * @param queryEmbedding precomputed query vector (768-dim); if null, engines may embed {@code queryText}
     */
    List<StorageSearchHit> similaritySearch(
            String queryText,
            List<Double> queryEmbedding,
            int limit,
            RAGQueryPayload.UserPermissionContext permissionContext);

    /**
     * Persist a wiki page and its outbound graph edges.
     */
    void storeWikiPage(WikiPage page, List<String> outboundSlugs);

    /**
     * Multi-hop graph reachability from {@code startSlug}.
     * Postgres path uses in-memory adjacency; Mongo uses $graphLookup when available.
     */
    default List<String> graphReachable(String startSlug, int maxDepth) {
        return List.of();
    }
}
