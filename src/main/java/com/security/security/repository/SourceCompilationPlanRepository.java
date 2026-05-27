package com.security.security.repository;

import com.security.security.entity.SourceCompilationPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SourceCompilationPlanRepository extends JpaRepository<SourceCompilationPlan, Long> {
    Optional<SourceCompilationPlan> findBySourceDocumentId(Long sourceDocumentId);

    @Query("SELECT p FROM SourceCompilationPlan p JOIN Document d ON p.sourceDocumentId = d.id WHERE d.workspaceId = :workspaceId")
    List<SourceCompilationPlan> findByWorkspaceId(@Param("workspaceId") String workspaceId);

    @Query("SELECT p FROM SourceCompilationPlan p JOIN Document d ON p.sourceDocumentId = d.id WHERE d.workspaceId = :workspaceId")
    Page<SourceCompilationPlan> findByWorkspaceId(@Param("workspaceId") String workspaceId, Pageable pageable);
}
