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

    List<WikiPage> findBySummaryContaining(String summaryPattern);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    void deleteBySourceDocumentId(Long sourceDocumentId);

    List<WikiPage> findByWorkspaceId(String workspaceId);
    Page<WikiPage> findByWorkspaceId(String workspaceId, Pageable pageable);

    @Query("SELECT w FROM WikiPage w WHERE w.slug = :slug AND ("
         + "(:workspaceId = 'ALL' AND (w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'ALL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = '' "
         + "             OR (:workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId)))"
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

    /**
     * Admin-only: find a page by slug across ALL workspaces.
     * When multiple workspaces share the same slug, the most recently updated page wins.
     * If targetWorkspaceId is provided (non-null, non-empty), we prefer that workspace.
     */
    @Query("SELECT w FROM WikiPage w WHERE w.slug = :slug "
         + "ORDER BY "
         + "CASE WHEN (:targetWorkspaceId IS NOT NULL AND :targetWorkspaceId != '' AND w.workspaceId = :targetWorkspaceId) THEN 0 ELSE 1 END ASC, "
         + "w.updatedAt DESC")
    List<WikiPage> findBySlugGlobalInternal(
        @Param("slug") String slug,
        @Param("targetWorkspaceId") String targetWorkspaceId,
        Pageable pageable
    );

    default Optional<WikiPage> findBySlugGlobal(String slug, String preferredWorkspaceId) {
        String target = (preferredWorkspaceId != null && !preferredWorkspaceId.isBlank() 
                         && !"ALL".equalsIgnoreCase(preferredWorkspaceId) && !"GLOBAL".equalsIgnoreCase(preferredWorkspaceId)) ? preferredWorkspaceId : "";
        List<WikiPage> results = findBySlugGlobalInternal(slug, target, org.springframework.data.domain.PageRequest.of(0, 1));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Query("SELECT w FROM WikiPage w WHERE ("
         + "(:workspaceId = 'ALL' AND (w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'ALL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = '' "
         + "             OR (:workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId)))"
         + "))) AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR "
         + "((w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL') "
         + "  AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ")")
    List<WikiPage> findAccessiblePages(
        @Param("workspaceId") String workspaceId,
        @Param("workspaceDeptId") String workspaceDeptId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT w FROM WikiPage w WHERE ("
         + "(:workspaceId = 'ALL' AND (w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'ALL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = '' "
         + "             OR (:workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId)))"
         + "))) AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR "
         + "((w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL') "
         + "  AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ")")
    Page<WikiPage> findAccessiblePages(
        @Param("workspaceId") String workspaceId,
        @Param("workspaceDeptId") String workspaceDeptId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
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
        com.security.security.entity.enumeration.WikiPageType getPageType();
        Integer getVersion();
        java.time.LocalDateTime getCreatedAt();
        java.time.LocalDateTime getUpdatedAt();
        String getDepartmentId();
        String getAllowedRoles();
        com.security.security.entity.enumeration.SecurityClassification getSecurityClassification();
    }

    @Query("SELECT w FROM WikiPage w WHERE ("
         + "(:workspaceId = 'ALL' AND (w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'ALL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = '' "
         + "             OR (:workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId)))"
         + "))) AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR "
         + "((w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL') "
         + "  AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ")")
    List<WikiPageMetadata> findAccessibleMetadata(
        @Param("workspaceId") String workspaceId,
        @Param("workspaceDeptId") String workspaceDeptId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT w FROM WikiPage w WHERE ("
         + ":isAdmin = true OR "
         + "w.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR "
         + "((w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL') "
         + "  AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ")")
    List<WikiPageMetadata> findAllAccessibleMetadata(
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    List<WikiPageMetadata> findProjectedByWorkspaceId(String workspaceId);

    @Query("SELECT w FROM WikiPage w WHERE ("
         + "(:workspaceId = 'ALL' AND (w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'ALL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = '' "
         + "             OR (:workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId)))"
         + "))) AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR "
         + "((w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL') "
         + "  AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ") AND (LOWER(w.title) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(w.content) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<WikiPage> searchAccessiblePagesByKeyword(
        @Param("workspaceId") String workspaceId,
        @Param("workspaceDeptId") String workspaceDeptId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember,
        @Param("query") String query
    );

    @Query("SELECT w FROM WikiPage w WHERE ("
         + "(:workspaceId = 'ALL' AND (w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = ''))"
         + "OR (:workspaceId != 'ALL' AND ("
         + "    w.workspaceId = :workspaceId "
         + "    OR (w.workspaceId = 'all') "
         + "    OR ((w.workspaceId = 'ALL' OR w.workspaceId = 'GLOBAL' OR w.workspaceId = '' OR w.workspaceId IS NULL OR w.workspaceId = 'default-workspace' OR w.workspaceId = 'workspace-default') "
         + "        AND (w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL' OR w.departmentId IS NULL OR w.departmentId = '' "
         + "             OR (:workspaceDeptId IS NOT NULL AND :workspaceDeptId != '' AND w.departmentId = :workspaceDeptId)))"
         + "))) AND ("
         + ":isAdmin = true OR "
         + "w.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR "
         + "((w.departmentId IS NULL OR w.departmentId = '' OR w.departmentId = 'ALL' OR w.departmentId = 'GLOBAL') "
         + "  AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR "
         + "(w.departmentId IN :deptIdsWhereHead) OR "
         + "(w.departmentId IN :deptIdsWhereMember AND (w.allowedRoles IS NULL OR w.allowedRoles = '' OR w.allowedRoles != 'HEAD'))"
         + ") AND w.pageType = :pageType")
    Page<WikiPage> findAccessiblePagesByType(
        @Param("workspaceId") String workspaceId,
        @Param("workspaceDeptId") String workspaceDeptId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember,
        @Param("pageType") com.security.security.entity.enumeration.WikiPageType pageType,
        Pageable pageable
    );
}

