package com.security.security.repository;

import com.security.security.entity.Embedding;
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

