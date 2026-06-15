package com.security.security.repository;

import com.security.security.entity.OcrResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface OcrResultRepository extends JpaRepository<OcrResult, Long> {
    List<OcrResult> findByDocumentIdOrderByPageNumberAsc(Long documentId);
    Optional<OcrResult> findByDocumentIdAndPageNumber(Long documentId, Integer pageNumber);
    List<OcrResult> findByDocumentIdAndStatus(Long documentId, String status);
    void deleteByDocumentId(Long documentId);
}
