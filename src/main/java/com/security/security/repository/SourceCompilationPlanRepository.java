package com.security.security.repository;

import com.security.security.entity.SourceCompilationPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SourceCompilationPlanRepository extends JpaRepository<SourceCompilationPlan, Long> {
    Optional<SourceCompilationPlan> findBySourceDocumentId(Long sourceDocumentId);
}
