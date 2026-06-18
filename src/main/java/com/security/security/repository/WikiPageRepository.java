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

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    void deleteBySourceDocumentId(Long sourceDocumentId);

    List<WikiPage> findByWorkspaceId(String workspaceId);
    Page<WikiPage> findByWorkspaceId(String workspaceId, Pageable pageable);

    @Query("SELECT w FROM WikiPage w WHERE w.slug = :slug AND ("
         + "(:workspaceId = 'GLOBAL' AND (w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'GLOBAL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND :workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId AND w.departmentId != 'GLOBAL')"
         + "))) ORDER BY CASE WHEN w.workspaceId = :workspaceId THEN 0 ELSE 1 END ASC")
    List<WikiPage> findBySlugAndWorkspaceIdInternal(
        @Param("slug") String slug, 
        @Param("workspaceId") String workspaceId, 
        @Param("workspaceDeptId") String workspaceDeptId,
        Pageable pageable
    );

    default Optional<WikiPage> fetchBySlugAndWorkspaceId(String slug, String workspaceId) {
        return fetchBySlugAndWorkspaceId(slug, workspaceId, null);
    }

    default Optional<WikiPage> fetchBySlugAndWorkspaceId(String slug, String workspaceId, String workspaceDeptId) {
        List<WikiPage> results = findBySlugAndWorkspaceIdInternal(slug, workspaceId, workspaceDeptId, org.springframework.data.domain.PageRequest.of(0, 1));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Query("SELECT w FROM WikiPage w WHERE ("
         + "(:workspaceId = 'GLOBAL' AND (w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'GLOBAL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND :workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId AND w.departmentId != 'GLOBAL')"
         + "))) AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = 'PUBLIC' OR "
         + "(w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'GLOBAL') OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ")")
    List<WikiPage> findAccessiblePages(
        @Param("workspaceId") String workspaceId,
        @Param("workspaceDeptId") String workspaceDeptId,
        @Param("isAdmin") boolean isAdmin,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT w FROM WikiPage w WHERE ("
         + "(:workspaceId = 'GLOBAL' AND (w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'GLOBAL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND :workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId AND w.departmentId != 'GLOBAL')"
         + "))) AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = 'PUBLIC' OR "
         + "(w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'GLOBAL') OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ")")
    Page<WikiPage> findAccessiblePages(
        @Param("workspaceId") String workspaceId,
        @Param("workspaceDeptId") String workspaceDeptId,
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
        String getDepartmentId();
        String getAllowedRoles();
        String getSecurityClassification();
    }

    @Query("SELECT w FROM WikiPage w WHERE ("
         + "(:workspaceId = 'GLOBAL' AND (w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'GLOBAL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND :workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId AND w.departmentId != 'GLOBAL')"
         + "))) AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = 'PUBLIC' OR "
         + "(w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'GLOBAL') OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ")")
    List<WikiPageMetadata> findAccessibleMetadata(
        @Param("workspaceId") String workspaceId,
        @Param("workspaceDeptId") String workspaceDeptId,
        @Param("isAdmin") boolean isAdmin,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    List<WikiPageMetadata> findProjectedByWorkspaceId(String workspaceId);

    @Query("SELECT w FROM WikiPage w WHERE ("
         + "(:workspaceId = 'GLOBAL' AND (w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'GLOBAL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND :workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId AND w.departmentId != 'GLOBAL')"
         + "))) AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = 'PUBLIC' OR "
         + "(w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'GLOBAL') OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ") AND (LOWER(w.title) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(w.content) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<WikiPage> searchAccessiblePagesByKeyword(
        @Param("workspaceId") String workspaceId,
        @Param("workspaceDeptId") String workspaceDeptId,
        @Param("isAdmin") boolean isAdmin,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember,
        @Param("query") String query
    );
}
