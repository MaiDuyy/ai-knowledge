package com.security.security.repository;

import com.security.security.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByUserId(String userId);

    @Query("SELECT d FROM Document d WHERE d.userId = ?1 AND d.status = 'COMPLETED' ORDER BY d.createdAt DESC")
    List<Document> findCompletedByUserId(String userId);

    @Query("SELECT d FROM Document d WHERE d.status = 'PROCESSING'")
    List<Document> findProcessing();

    Optional<Document> findByIdAndUserId(Long id, String userId);

    List<Document> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Document> findAllByOrderByCreatedAtDesc();

    @Query("SELECT d FROM Document d WHERE d.status = 'COMPLETED' ORDER BY d.createdAt DESC")
    List<Document> findCompletedByOrderByCreatedAtDesc();
}
