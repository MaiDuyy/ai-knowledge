package com.security.security.repository;

import com.security.security.entity.WikiPageDraft;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

@Repository
public interface WikiPageDraftRepository extends JpaRepository<WikiPageDraft, Long> {
    List<WikiPageDraft> findByStatus(String status);
    Page<WikiPageDraft> findByStatus(String status, Pageable pageable);
    List<WikiPageDraft> findByWorkspaceId(String workspaceId);
    List<WikiPageDraft> findByWikiPageId(Long wikiPageId);
    List<WikiPageDraft> findBySlugAndWorkspaceId(String slug, String workspaceId);

    @Query("SELECT wd FROM WikiPageDraft wd WHERE (wd.workspaceId = :workspaceId OR (wd.workspaceId = 'all' AND :workspaceId != 'default-workspace')) AND ("
         + ":isAdmin = true OR "
         + "wd.securityClassification = 'PUBLIC' OR "
         + "(wd.workspaceId = :workspaceId AND wd.allowedRoles != 'HEAD') OR "
         + "(wd.securityClassification = 'INTERNAL' AND (wd.departmentId IS NULL OR wd.departmentId = '')) OR "
         + "(wd.departmentId IN :deptIdsWhereHead) OR "
         + "(wd.departmentId IN :deptIdsWhereMember AND wd.allowedRoles != 'HEAD')"
         + ")")
    List<WikiPageDraft> findAccessibleDrafts(
        @Param("workspaceId") String workspaceId,
        @Param("isAdmin") boolean isAdmin,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT wd FROM WikiPageDraft wd WHERE (wd.workspaceId = :workspaceId OR (wd.workspaceId = 'all' AND :workspaceId != 'default-workspace')) AND wd.status = :status AND ("
         + ":isAdmin = true OR "
         + "wd.securityClassification = 'PUBLIC' OR "
         + "(wd.workspaceId = :workspaceId AND wd.allowedRoles != 'HEAD') OR "
         + "(wd.securityClassification = 'INTERNAL' AND (wd.departmentId IS NULL OR wd.departmentId = '')) OR "
         + "(wd.departmentId IN :deptIdsWhereHead) OR "
         + "(wd.departmentId IN :deptIdsWhereMember AND wd.allowedRoles != 'HEAD')"
         + ")")
    List<WikiPageDraft> findAccessibleDraftsByStatus(
        @Param("workspaceId") String workspaceId,
        @Param("status") String status,
        @Param("isAdmin") boolean isAdmin,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT wd FROM WikiPageDraft wd WHERE (wd.workspaceId = :workspaceId OR (wd.workspaceId = 'all' AND :workspaceId != 'default-workspace')) AND wd.status = :status AND ("
         + ":isAdmin = true OR "
         + "wd.securityClassification = 'PUBLIC' OR "
         + "(wd.workspaceId = :workspaceId AND wd.allowedRoles != 'HEAD') OR "
         + "(wd.securityClassification = 'INTERNAL' AND (wd.departmentId IS NULL OR wd.departmentId = '')) OR "
         + "(wd.departmentId IN :deptIdsWhereHead) OR "
         + "(wd.departmentId IN :deptIdsWhereMember AND wd.allowedRoles != 'HEAD')"
         + ")")
    Page<WikiPageDraft> findAccessibleDraftsByStatus(
        @Param("workspaceId") String workspaceId,
        @Param("status") String status,
        @Param("isAdmin") boolean isAdmin,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember,
        Pageable pageable
    );
}
