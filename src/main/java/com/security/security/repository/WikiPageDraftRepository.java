package com.security.security.repository;

import com.security.security.entity.WikiPageDraft;
import com.security.security.entity.enumeration.WikiPageDraftStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Repository
public interface WikiPageDraftRepository extends JpaRepository<WikiPageDraft, Long> {
    List<WikiPageDraft> findByStatus(WikiPageDraftStatus status);
    Page<WikiPageDraft> findByStatus(WikiPageDraftStatus status, Pageable pageable);
    List<WikiPageDraft> findByWorkspaceId(String workspaceId);
    List<WikiPageDraft> findByWikiPageId(Long wikiPageId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    void deleteByWikiPageId(Long wikiPageId);

    List<WikiPageDraft> findBySlugAndWorkspaceId(String slug, String workspaceId);

    @Query("SELECT wd FROM WikiPageDraft wd WHERE ((wd.workspaceId = :workspaceId OR (:workspaceId IN ('default-workspace', 'workspace-default') AND (wd.workspaceId = '' OR wd.workspaceId IS NULL OR wd.workspaceId = 'default-workspace' OR wd.workspaceId = 'workspace-default')))"
         + " OR (wd.workspaceId = 'all' AND :workspaceId != 'default-workspace')) AND ("
         + ":isAdmin = true OR "
         + "wd.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR "
         + "((wd.departmentId IS NULL OR wd.departmentId = '' OR wd.departmentId = 'ALL' OR wd.departmentId = 'GLOBAL') "
         + "  AND (wd.allowedRoles IS NULL OR wd.allowedRoles = '' OR wd.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR "
         + "(wd.departmentId IN :deptIdsWhereHead) OR "
         + "(wd.departmentId IN :deptIdsWhereMember AND wd.allowedRoles != 'HEAD')"
         + ")")
    List<WikiPageDraft> findAccessibleDrafts(
        @Param("workspaceId") String workspaceId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT wd FROM WikiPageDraft wd WHERE ((wd.workspaceId = :workspaceId OR (:workspaceId IN ('default-workspace', 'workspace-default') AND (wd.workspaceId = '' OR wd.workspaceId IS NULL OR wd.workspaceId = 'default-workspace' OR wd.workspaceId = 'workspace-default')))"
         + " OR (wd.workspaceId = 'all' AND :workspaceId != 'default-workspace')) AND wd.status = :status AND ("
         + ":isAdmin = true OR "
         + "wd.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR "
         + "((wd.departmentId IS NULL OR wd.departmentId = '' OR wd.departmentId = 'ALL' OR wd.departmentId = 'GLOBAL') "
         + "  AND (wd.allowedRoles IS NULL OR wd.allowedRoles = '' OR wd.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR "
         + "(wd.departmentId IN :deptIdsWhereHead) OR "
         + "(wd.departmentId IN :deptIdsWhereMember AND wd.allowedRoles != 'HEAD')"
         + ")")
    List<WikiPageDraft> findAccessibleDraftsByStatus(
        @Param("workspaceId") String workspaceId,
        @Param("status") WikiPageDraftStatus status,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT wd FROM WikiPageDraft wd WHERE ((wd.workspaceId = :workspaceId OR (:workspaceId IN ('default-workspace', 'workspace-default') AND (wd.workspaceId = '' OR wd.workspaceId IS NULL OR wd.workspaceId = 'default-workspace' OR wd.workspaceId = 'workspace-default')))"
         + " OR (wd.workspaceId = 'all' AND :workspaceId != 'default-workspace')) AND wd.status = :status AND ("
         + ":isAdmin = true OR "
         + "wd.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR "
         + "((wd.departmentId IS NULL OR wd.departmentId = '' OR wd.departmentId = 'ALL' OR wd.departmentId = 'GLOBAL') "
         + "  AND (wd.allowedRoles IS NULL OR wd.allowedRoles = '' OR wd.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR "
         + "(wd.departmentId IN :deptIdsWhereHead) OR "
         + "(wd.departmentId IN :deptIdsWhereMember AND wd.allowedRoles != 'HEAD')"
         + ")")
    Page<WikiPageDraft> findAccessibleDraftsByStatus(
        @Param("workspaceId") String workspaceId,
        @Param("status") WikiPageDraftStatus status,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember,
        Pageable pageable
    );
}
