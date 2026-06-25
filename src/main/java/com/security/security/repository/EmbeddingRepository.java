package com.security.security.repository;

import com.security.security.entity.Embedding;
import com.security.security.entity.enumeration.ChunkType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface EmbeddingRepository extends JpaRepository<Embedding, Long> {

    List<Embedding> findByDocumentIdOrderByChunkIndex(Long documentId);

    long countByDocumentId(Long documentId);

    @Transactional
    void deleteByDocumentId(Long documentId);

    List<Embedding> findByDocumentId(Long documentId);

    List<Embedding> findByDocumentIdAndChunkType(Long documentId, ChunkType chunkType);

    @Query("SELECT e FROM Embedding e WHERE e.documentId = :documentId AND e.chunkType IN :types ORDER BY e.chunkIndex")
    List<Embedding> findByDocumentIdAndChunkTypeIn(@Param("documentId") Long documentId, @Param("types") List<ChunkType> types);

    @Query(value = """
        SELECT * FROM embeddings
        WHERE workspace_id = :workspaceId
          AND chunk_type = 'TEXT'
          AND to_tsvector('simple', chunk_text) @@ plainto_tsquery('simple', :query)
        ORDER BY ts_rank(to_tsvector('simple', chunk_text), plainto_tsquery('simple', :query)) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Embedding> keywordSearch(
        @Param("query") String query,
        @Param("workspaceId") String workspaceId,
        @Param("limit") int limit
    );

    @Query(value = """
        SELECT * FROM embeddings
        WHERE document_id = :documentId
          AND chunk_type = 'TEXT'
          AND to_tsvector('simple', chunk_text) @@ plainto_tsquery('simple', :query)
        ORDER BY ts_rank(to_tsvector('simple', chunk_text), plainto_tsquery('simple', :query)) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Embedding> keywordSearchInDocument(
        @Param("query") String query,
        @Param("documentId") Long documentId,
        @Param("limit") int limit
    );

    @Modifying
    @Transactional
    @Query("UPDATE Embedding e SET e.workspaceId = :workspaceId WHERE e.documentId = :documentId")
    void updateWorkspaceId(@Param("documentId") Long documentId, @Param("workspaceId") String workspaceId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE vector_store SET metadata = CAST(metadata AS jsonb) || CAST(:metadataJson AS jsonb) WHERE metadata->>'documentId' = :documentId", nativeQuery = true)
    int updateVectorMetadata(@Param("documentId") String documentId, @Param("metadataJson") String metadataJson);

    @Modifying
    @Transactional
    @Query(value = "UPDATE vector_store SET metadata = CAST(metadata AS jsonb) || CAST(:metadataJson AS jsonb) WHERE metadata->>'wikiPageId' = :wikiPageId", nativeQuery = true)
    int updateWikiVectorMetadata(@Param("wikiPageId") String wikiPageId, @Param("metadataJson") String metadataJson);
}

