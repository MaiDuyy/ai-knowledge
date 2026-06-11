package com.security.security.repository;

import com.security.security.entity.WikiPage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

@Repository
public interface WikiPageRepository extends JpaRepository<WikiPage, Long> {
    List<WikiPage> findBySourceDocumentId(Long sourceDocumentId);
    List<WikiPage> findByWorkspaceId(String workspaceId);
    Page<WikiPage> findByWorkspaceId(String workspaceId, Pageable pageable);
    Optional<WikiPage> findBySlugAndWorkspaceId(String slug, String workspaceId);

    @Query("SELECT w FROM WikiPage w WHERE w.workspaceId = :workspaceId AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = 'PUBLIC' OR "
         + "(w.securityClassification = 'INTERNAL' AND (w.departmentId IS NULL OR w.departmentId = '')) OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND w.allowedRoles != 'HEAD')"
         + ")")
    List<WikiPage> findAccessiblePages(
        @Param("workspaceId") String workspaceId,
        @Param("isAdmin") boolean isAdmin,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT w FROM WikiPage w WHERE w.workspaceId = :workspaceId AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = 'PUBLIC' OR "
         + "(w.securityClassification = 'INTERNAL' AND (w.departmentId IS NULL OR w.departmentId = '')) OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND w.allowedRoles != 'HEAD')"
         + ")")
    Page<WikiPage> findAccessiblePages(
        @Param("workspaceId") String workspaceId,
        @Param("isAdmin") boolean isAdmin,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember,
        Pageable pageable
    );

    // Lightweight projection excluding large content and summary fields
    interface WikiPageMetadata {
        Long getId();
        String getTitle();
        String getSlug();
        String getWorkspaceId();
        String getTags();
        String getPageType();
        Integer getVersion();
        java.time.LocalDateTime getCreatedAt();
        java.time.LocalDateTime getUpdatedAt();
    }

    @Query("SELECT w FROM WikiPage w WHERE w.workspaceId = :workspaceId AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = 'PUBLIC' OR "
         + "(w.securityClassification = 'INTERNAL' AND (w.departmentId IS NULL OR w.departmentId = '')) OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND w.allowedRoles != 'HEAD')"
         + ")")
    List<WikiPageMetadata> findAccessibleMetadata(
        @Param("workspaceId") String workspaceId,
        @Param("isAdmin") boolean isAdmin,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    List<WikiPageMetadata> findProjectedByWorkspaceId(String workspaceId);
}
