package com.security.security.repository;

import com.security.security.entity.SourceChunkExtract;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SourceChunkExtractRepository extends JpaRepository<SourceChunkExtract, Long> {
    List<SourceChunkExtract> findBySourceDocumentId(Long sourceDocumentId);
    Optional<SourceChunkExtract> findBySourceDocumentIdAndChunkIndex(Long sourceDocumentId, Integer chunkIndex);
    List<SourceChunkExtract> findBySourceDocumentIdAndStatus(Long sourceDocumentId, String status);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    void deleteBySourceDocumentId(Long sourceDocumentId);
}
