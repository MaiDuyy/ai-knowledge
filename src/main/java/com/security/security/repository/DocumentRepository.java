package com.security.security.repository;

import com.security.security.entity.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    Page<Document> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.status = 'COMPLETED' ORDER BY d.createdAt DESC")
    List<Document> findCompletedByOrderByCreatedAtDesc();

    @Query("SELECT d FROM Document d WHERE d.status = 'COMPLETED' ORDER BY d.createdAt DESC")
    Page<Document> findCompletedByOrderByCreatedAtDesc(Pageable pageable);

    // Workspace-scoped queries (Department → Workspace → Document flow)
    @Query("SELECT d FROM Document d WHERE " +
           "((?1 = 'ALL' OR ?1 = 'GLOBAL') AND (d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId IS NULL OR d.workspaceId = '' OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default') AND (d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL' OR d.departmentId IS NULL OR d.departmentId = '')) " +
           "OR ((?1 != 'ALL' AND ?1 != 'GLOBAL') AND d.workspaceId = ?1) ORDER BY d.createdAt DESC")
    List<Document> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    @Query("SELECT d FROM Document d WHERE " +
           "((?1 = 'ALL' OR ?1 = 'GLOBAL') AND (d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId IS NULL OR d.workspaceId = '' OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default') AND (d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL' OR d.departmentId IS NULL OR d.departmentId = '')) " +
           "OR ((?1 != 'ALL' AND ?1 != 'GLOBAL') AND d.workspaceId = ?1) ORDER BY d.createdAt DESC")
    Page<Document> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE d.workspaceId = ?1 OR " +
           "((d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId = '' OR d.workspaceId IS NULL OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default' OR d.workspaceId = 'all') " +
           "AND d.departmentId = ?2 AND d.departmentId != 'ALL' AND d.departmentId != 'GLOBAL' AND d.departmentId IS NOT NULL AND d.departmentId != '') ORDER BY d.createdAt DESC")
    List<Document> findByWorkspaceIdOrDepartmentIdAndWorkspaceIdEmpty(String workspaceId, String departmentId);

    @Query("SELECT d FROM Document d WHERE d.workspaceId = ?1 OR " +
           "((d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId = '' OR d.workspaceId IS NULL OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default' OR d.workspaceId = 'all') " +
           "AND d.departmentId = ?2 AND d.departmentId != 'ALL' AND d.departmentId != 'GLOBAL' AND d.departmentId IS NOT NULL AND d.departmentId != '') ORDER BY d.createdAt DESC")
    Page<Document> findByWorkspaceIdOrDepartmentIdAndWorkspaceIdEmpty(String workspaceId, String departmentId, Pageable pageable);

    @Query("SELECT d FROM Document d WHERE " +
           "(((?1 = 'ALL' OR ?1 = 'GLOBAL') AND (d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId IS NULL OR d.workspaceId = '' OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default') AND (d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL' OR d.departmentId IS NULL OR d.departmentId = '')) " +
           "OR ((?1 != 'ALL' AND ?1 != 'GLOBAL') AND d.workspaceId = ?1)) AND d.status = 'COMPLETED' ORDER BY d.createdAt DESC")
    List<Document> findCompletedByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    @Query("SELECT d FROM Document d WHERE " +
           "(((?1 = 'ALL' OR ?1 = 'GLOBAL') AND (d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId IS NULL OR d.workspaceId = '' OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default') AND (d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL' OR d.departmentId IS NULL OR d.departmentId = '')) " +
           "OR ((?1 != 'ALL' AND ?1 != 'GLOBAL') AND d.workspaceId = ?1)) AND d.status = 'COMPLETED' ORDER BY d.createdAt DESC")
    Page<Document> findCompletedByWorkspaceIdOrderByCreatedAtDesc(String workspaceId, Pageable pageable);

    List<Document> findByFileHashAndStatus(String fileHash, com.security.security.entity.enumeration.DocStatus status);

    @Modifying
    @Transactional
    @Query("UPDATE Document d SET d.pendingSubtasks = CASE WHEN d.pendingSubtasks > 0 THEN d.pendingSubtasks - 1 ELSE 0 END WHERE d.id = :id")
    int decrementPendingSubtasks(@Param("id") Long id);

    @Query("SELECT d FROM Document d WHERE (" +
           "  :isAdmin = true OR " +
           "  d.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR " +
           "  ((d.departmentId IS NULL OR d.departmentId = '' OR d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL') " +
           "    AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR " +
           "  (d.departmentId IN :deptIdsWhereHead) OR " +
           "  (d.departmentId IN :deptIdsWhereMember AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD'))" +
           ") ORDER BY d.createdAt DESC")
    List<Document> findAccessibleAllOrderByCreatedAtDesc(
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT d FROM Document d WHERE (" +
           "  :isAdmin = true OR " +
           "  d.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR " +
           "  ((d.departmentId IS NULL OR d.departmentId = '' OR d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL') " +
           "    AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR " +
           "  (d.departmentId IN :deptIdsWhereHead) OR " +
           "  (d.departmentId IN :deptIdsWhereMember AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD'))" +
           ") ORDER BY d.createdAt DESC")
    Page<Document> findAccessibleAllOrderByCreatedAtDesc(
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember,
        Pageable pageable
    );

    @Query("SELECT d FROM Document d WHERE (" +
           "  ((:workspaceId = 'ALL' OR :workspaceId = 'GLOBAL') AND (d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId IS NULL OR d.workspaceId = '' OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default') AND (d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL' OR d.departmentId IS NULL OR d.departmentId = '')) " +
           "  OR ((:workspaceId != 'ALL' AND :workspaceId != 'GLOBAL') AND d.workspaceId = :workspaceId)" +
           ") AND (" +
           "  :isAdmin = true OR " +
           "  d.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR " +
           "  ((d.departmentId IS NULL OR d.departmentId = '' OR d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL') " +
           "    AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR " +
           "  (d.departmentId IN :deptIdsWhereHead) OR " +
           "  (d.departmentId IN :deptIdsWhereMember AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD'))" +
           ") ORDER BY d.createdAt DESC")
    List<Document> findAccessibleByWorkspaceIdOrderByCreatedAtDesc(
        @Param("workspaceId") String workspaceId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT d FROM Document d WHERE (" +
           "  ((:workspaceId = 'ALL' OR :workspaceId = 'GLOBAL') AND (d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId IS NULL OR d.workspaceId = '' OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default') AND (d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL' OR d.departmentId IS NULL OR d.departmentId = '')) " +
           "  OR ((:workspaceId != 'ALL' AND :workspaceId != 'GLOBAL') AND d.workspaceId = :workspaceId)" +
           ") AND (" +
           "  :isAdmin = true OR " +
           "  d.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR " +
           "  ((d.departmentId IS NULL OR d.departmentId = '' OR d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL') " +
           "    AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR " +
           "  (d.departmentId IN :deptIdsWhereHead) OR " +
           "  (d.departmentId IN :deptIdsWhereMember AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD'))" +
           ") ORDER BY d.createdAt DESC")
    Page<Document> findAccessibleByWorkspaceIdOrderByCreatedAtDesc(
        @Param("workspaceId") String workspaceId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember,
        Pageable pageable
    );

    @Query("SELECT d FROM Document d WHERE (d.workspaceId = :workspaceId OR " +
           "  ((d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId = '' OR d.workspaceId IS NULL OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default' OR d.workspaceId = 'all') " +
           "  AND d.departmentId = :departmentId AND d.departmentId != 'ALL' AND d.departmentId != 'GLOBAL' AND d.departmentId IS NOT NULL AND d.departmentId != '')) " +
           "AND (" +
           "  :isAdmin = true OR " +
           "  d.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR " +
           "  ((d.departmentId IS NULL OR d.departmentId = '' OR d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL') " +
           "    AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR " +
           "  (d.departmentId IN :deptIdsWhereHead) OR " +
           "  (d.departmentId IN :deptIdsWhereMember AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD'))" +
           ") ORDER BY d.createdAt DESC")
    List<Document> findAccessibleByWorkspaceIdOrDepartmentIdAndWorkspaceIdEmpty(
        @Param("workspaceId") String workspaceId,
        @Param("departmentId") String departmentId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember
    );

    @Query("SELECT d FROM Document d WHERE (d.workspaceId = :workspaceId OR " +
           "  ((d.workspaceId = 'ALL' OR d.workspaceId = 'GLOBAL' OR d.workspaceId = '' OR d.workspaceId IS NULL OR d.workspaceId = 'default-workspace' OR d.workspaceId = 'workspace-default' OR d.workspaceId = 'all') " +
           "  AND d.departmentId = :departmentId AND d.departmentId != 'ALL' AND d.departmentId != 'GLOBAL' AND d.departmentId IS NOT NULL AND d.departmentId != '')) " +
           "AND (" +
           "  :isAdmin = true OR " +
           "  d.securityClassification = com.security.security.entity.enumeration.SecurityClassification.PUBLIC OR " +
           "  ((d.departmentId IS NULL OR d.departmentId = '' OR d.departmentId = 'ALL' OR d.departmentId = 'GLOBAL') " +
           "    AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD' OR :hasHeadRole = true)) OR " +
           "  (d.departmentId IN :deptIdsWhereHead) OR " +
           "  (d.departmentId IN :deptIdsWhereMember AND (d.allowedRoles IS NULL OR d.allowedRoles = '' OR d.allowedRoles != 'HEAD'))" +
           ") ORDER BY d.createdAt DESC")
    Page<Document> findAccessibleByWorkspaceIdOrDepartmentIdAndWorkspaceIdEmpty(
        @Param("workspaceId") String workspaceId,
        @Param("departmentId") String departmentId,
        @Param("isAdmin") boolean isAdmin,
        @Param("hasHeadRole") boolean hasHeadRole,
        @Param("deptIdsWhereHead") List<String> deptIdsWhereHead,
        @Param("deptIdsWhereMember") List<String> deptIdsWhereMember,
        Pageable pageable
    );
}

