package com.security.security.repository;

import com.security.security.entity.Embedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmbeddingRepository extends JpaRepository<Embedding, Long> {

    List<Embedding> findByDocumentIdOrderByChunkIndex(Long documentId);

    long countByDocumentId(Long documentId);

    @org.springframework.transaction.annotation.Transactional
    void deleteByDocumentId(Long documentId);

    List<Embedding> findByDocumentId(Long documentId);
}
