package com.security.security.resource;

import com.security.security.entity.SourceCompilationPlan;
import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiPageDraft;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.enumeration.SourceCompilationStatus;
import com.security.security.entity.enumeration.WikiPageDraftStatus;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.WikiPageDraftRepository;
import com.security.security.repository.SourceCompilationPlanRepository;
import com.security.security.repository.DocumentRepository;
import com.security.security.service.MrpPipelineService;
import com.security.security.service.WikiDraftService;
import com.security.security.service.WikiGraphService;
import com.security.security.service.WikiHealthService;
import com.security.security.client.WorkspaceServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.dto.UserPermissionContext;
import com.security.security.service.PermissionUtils;
import com.security.security.service.ScopeNormalizer;

import java.util.List;
import java.util.Map;
import java.util.Optional;


@RestController
@RequestMapping("/api/mrp")
@RequiredArgsConstructor
@Slf4j
public class MrpController {

    private final MrpPipelineService mrpPipelineService;
    private final WikiDraftService wikiDraftService;
    private final WikiGraphService wikiGraphService;
    private final WikiHealthService wikiHealthService;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageDraftRepository wikiPageDraftRepository;
    private final SourceCompilationPlanRepository sourceCompilationPlanRepository;
    private final com.security.security.repository.WikiLinkRepository wikiLinkRepository;
    private final WorkspaceServiceClient workspaceServiceClient;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    /**
     * Helper method to validate user membership in workspace
     */
    private void validateWorkspaceAccess(String userId, String workspaceId) {
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        if ("ALL".equals(normalizedWorkspaceId) || "GLOBAL".equals(normalizedWorkspaceId) || "all".equals(normalizedWorkspaceId)) {
            return; // Allow public, default, or all-workspaces
        }
        var workspace = workspaceServiceClient.getWorkspace(normalizedWorkspaceId, userId);
        if (workspace.isEmpty()) {
            log.warn("[Security] Access denied or workspace not found: User {} in Workspace {}", userId, normalizedWorkspaceId);
            throw new AccessDeniedException("You do not have access to Workspace: " + normalizedWorkspaceId);
        }
    }

    private void checkPageAccess(WikiPage page, UserPermissionContext perm) {
        if (perm.isAdmin()) return;

        if (SecurityClassification.PUBLIC == page.getSecurityClassification()) return;

        String pageDeptId = ScopeNormalizer.normalizeDepartment(page.getDepartmentId());
        String pageWsId = ScopeNormalizer.normalizeWorkspace(page.getWorkspaceId());
        String allowed = page.getAllowedRoles();

        boolean isGlobalScope = ("ALL".equals(pageDeptId) || "GLOBAL".equals(pageDeptId)) 
            && ("ALL".equals(pageWsId) || "GLOBAL".equals(pageWsId));

        // Rule 1: Global scope → all users (unless restricted by role)
        if (isGlobalScope) {
            if ("HEAD".equalsIgnoreCase(allowed) && !perm.hasHeadRole()) {
                throw new AccessDeniedException("You do not have permission to access this wiki page.");
            }
            return;
        }

        // Rules 2+3+4: Department-scoped → must be a member of that department
        if (!"ALL".equals(pageDeptId) && !"GLOBAL".equals(pageDeptId)) {
            boolean isHead = perm.getDeptIdsWhereHead().contains(pageDeptId);
            boolean isMember = perm.getDeptIdsWhereMember().contains(pageDeptId);

            if (!isHead && !isMember) {
                throw new AccessDeniedException("You do not have permission to access this wiki page.");
            }
            if (allowed == null || allowed.isBlank() || "ALL".equalsIgnoreCase(allowed)) return;
            if ("HEAD".equalsIgnoreCase(allowed) && isHead) return;
            if ("MEMBER".equalsIgnoreCase(allowed) && (isMember || isHead)) return;

            throw new AccessDeniedException("You do not have permission to access this wiki page.");
        } else {
            // Workspace-scoped but no department restriction
            if ("HEAD".equalsIgnoreCase(allowed) && !perm.hasHeadRole()) {
                throw new AccessDeniedException("You do not have permission to access this wiki page.");
            }
        }
    }


    /**
     * Khởi tạo quy trình MRP Compile (Map & Reduce Phase).
     * Phân tách tài liệu song song bằng Virtual Threads, tổng hợp, dedup, đối soát và trả về Plan.
     * POST /api/mrp/compile?documentId=...&workspaceId=...&autoApprove=true/false
     */
    @PostMapping("/compile")
    public ResponseEntity<SourceCompilationPlan> compileDocument(
            @RequestParam Long documentId,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(defaultValue = "false") boolean autoApprove,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRoles,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {

        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        log.info("[MrpController] Compiling document ID: {}, workspace: {}, autoApprove: {}, user: {}",
                documentId, normalizedWorkspaceId, autoApprove, userId);

        UserPermissionContext perm = PermissionUtils.parse(userRoles, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }
        
        SourceCompilationPlan plan = mrpPipelineService.initiateCompile(documentId, normalizedWorkspaceId, userId, autoApprove);
        populateDocumentName(plan);
        return ResponseEntity.accepted().body(plan);
    }

    /**
     * Phê duyệt Kế hoạch biên soạn và tiến hành Refine & Commit (tạo/trộn các Draft).
     * POST /api/mrp/plan/{planId}/approve?workspaceId=...&runAutoApproveDrafts=true/false
     */
    @PostMapping("/plan/{planId}/approve")
    public ResponseEntity<Map<String, String>> approvePlan(
            @PathVariable Long planId,
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestParam(defaultValue = "false") boolean runAutoApproveDrafts,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRoles,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {

        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        log.info("[MrpController] Approving plan ID: {}, runAutoApproveDrafts: {}, user: {}", planId, runAutoApproveDrafts, userId);

        UserPermissionContext perm = PermissionUtils.parse(userRoles, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }

        SourceCompilationPlan plan = sourceCompilationPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found with ID: " + planId));

        boolean isAuthorized = perm.isAdmin();
        if (!isAuthorized && plan.getDepartmentId() != null && !plan.getDepartmentId().trim().isEmpty()) {
            isAuthorized = perm.getDeptIdsWhereHead().contains(plan.getDepartmentId());
        } else if (!isAuthorized) {
            isAuthorized = perm.hasWorkspaceRole("WORKSPACE_MANAGER")
                    || perm.hasWorkspaceRole("WORKSPACE_ADMIN");
        }
        if (!isAuthorized) {
            throw new AccessDeniedException("You do not have permission to approve this compilation plan.");
        }

        mrpPipelineService.executeCompilationPlan(planId, normalizedWorkspaceId, userId, runAutoApproveDrafts);

        return ResponseEntity.ok(Map.of("message", "Compilation plan approved and executed. Drafts are generated."));
    }

    /**
     * Từ chối Kế hoạch biên soạn (Admin only).
     * POST /api/mrp/plan/{planId}/reject?workspaceId=...
     */
    @PostMapping("/plan/{planId}/reject")
    public ResponseEntity<SourceCompilationPlan> rejectPlan(
            @PathVariable Long planId,
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRoles,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {

        String reviewNote = payload.getOrDefault("note", "Rejected by reviewer.");
        log.info("[MrpController] Rejecting plan ID: {}, user: {}, reason: {}", planId, userId, reviewNote);

        UserPermissionContext perm = PermissionUtils.parse(userRoles, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException("Only administrators can reject compilation plans.");
        }

        SourceCompilationPlan plan = sourceCompilationPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found with ID: " + planId));

        plan.setStatus(SourceCompilationStatus.REJECTED);
        plan.setReviewNote(reviewNote);
        plan.setReviewedBy(userId);
        plan.setReviewedAt(java.time.LocalDateTime.now());
        SourceCompilationPlan saved = sourceCompilationPlanRepository.save(plan);
        populateDocumentName(saved);
        return ResponseEntity.ok(saved);
    }

    // ==================== DRAFT & REVIEW ENDPOINTS ====================

    /**
     * Lấy danh sách các Bản thảo (Drafts) đang chờ phê duyệt.
     * GET /api/mrp/drafts?page=0&size=10
     */
    @GetMapping("/drafts")
    public ResponseEntity<?> getPendingDrafts(
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        log.info("[MrpController] Fetching pending drafts. page: {}, size: {}, user: {}", page, size, userId);
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }

        // Admins and SuperAdmins can view all pending drafts across all workspaces
        if (perm.isAdmin()) {
            if (page != null && size != null) {
                Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
                Page<WikiPageDraft> pagedDrafts = wikiPageDraftRepository.findByStatus(WikiPageDraftStatus.PENDING, pageable);
                return ResponseEntity.ok(pagedDrafts);
            }
            List<WikiPageDraft> drafts = wikiPageDraftRepository.findByStatus(WikiPageDraftStatus.PENDING);
            return ResponseEntity.ok(drafts);
        }

        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<WikiPageDraft> pagedDrafts = wikiPageDraftRepository.findAccessibleDraftsByStatus(
                normalizedWorkspaceId, WikiPageDraftStatus.PENDING, perm.isAdmin(), perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember(), pageable);
            return ResponseEntity.ok(pagedDrafts);
        }
        
        List<WikiPageDraft> drafts = wikiPageDraftRepository.findAccessibleDraftsByStatus(
            normalizedWorkspaceId, WikiPageDraftStatus.PENDING, perm.isAdmin(), perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
        return ResponseEntity.ok(drafts);
    }

    /**
     * Lấy danh sách Bản thảo (Drafts) của một Workspace.
     * GET /api/mrp/drafts/workspace/{workspaceId}
     */
    @GetMapping("/drafts/workspace/{workspaceId}")
    public ResponseEntity<List<WikiPageDraft>> getDraftsByWorkspace(
            @PathVariable String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        log.info("[MrpController] Fetching drafts for workspace ID: {} by user: {}", normalizedWorkspaceId, userId);
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }
        
        List<WikiPageDraft> drafts = wikiPageDraftRepository.findAccessibleDrafts(
            normalizedWorkspaceId, perm.isAdmin(), perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
        return ResponseEntity.ok(drafts);
    }

    /**
     * Lấy chi tiết một Bản thảo theo ID (Admin/Reviewer).
     * GET /api/mrp/drafts/{draftId}
     */
    @GetMapping("/drafts/{draftId}")
    public ResponseEntity<WikiPageDraft> getDraftById(
            @PathVariable Long draftId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {

        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, draft.getWorkspaceId());
        }
        return ResponseEntity.ok(draft);
    }

    /**
     * Chỉnh sửa trực tiếp nội dung Bản thảo (Admin only).
     * PATCH /api/mrp/drafts/{draftId}
     */
    @PatchMapping("/drafts/{draftId}")
    public ResponseEntity<WikiPageDraft> updateDraft(
            @PathVariable Long draftId,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {

        log.info("[MrpController] Admin directly editing draft ID: {} by user: {}", draftId, userId);

        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException("Only administrators can directly edit draft content.");
        }

        if (payload.containsKey("content") && payload.get("content") != null) draft.setContent(payload.get("content"));
        if (payload.containsKey("title") && payload.get("title") != null) draft.setTitle(payload.get("title"));
        if (payload.containsKey("summary") && payload.get("summary") != null) draft.setSummary(payload.get("summary"));
        if (payload.containsKey("tags") && payload.get("tags") != null) draft.setTags(payload.get("tags"));
        if (payload.containsKey("note") && payload.get("note") != null) draft.setNote(payload.get("note"));

        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        return ResponseEntity.ok(saved);
    }

    /**
     * Phê duyệt Bản thảo nháp, trộn nội dung nâng cao (Prompt Merge) và đồng bộ VectorStore.
     * POST /api/mrp/drafts/{draftId}/approve
     */
    @PostMapping("/drafts/{draftId}/approve")
    public ResponseEntity<WikiPageDraft> approveDraft(
            @PathVariable Long draftId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        
        log.info("[MrpController] Approving draft ID: {} by user: {}", draftId, userId);
        
        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, draft.getWorkspaceId());
        }

        boolean isAuthorized = perm.isAdmin();
        if (!isAuthorized && draft.getDepartmentId() != null && !draft.getDepartmentId().trim().isEmpty()) {
            isAuthorized = perm.getDeptIdsWhereHead().contains(draft.getDepartmentId());
        } else if (!isAuthorized) {
            isAuthorized = perm.hasWorkspaceRole("WORKSPACE_MANAGER")
                    || perm.hasWorkspaceRole("WORKSPACE_ADMIN");
        }
        if (!isAuthorized) {
            throw new AccessDeniedException("You do not have permission to review this wiki page draft.");
        }

        WikiPageDraft approvedDraft = wikiDraftService.approveDraft(draftId, userId);
        return ResponseEntity.ok(approvedDraft);
    }

    /**
     * Từ chối Bản thảo nháp kèm note nhận xét.
     * POST /api/mrp/drafts/{draftId}/reject
     */
    @PostMapping("/drafts/{draftId}/reject")
    public ResponseEntity<WikiPageDraft> rejectDraft(
            @PathVariable Long draftId,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        
        String reviewerNote = payload.getOrDefault("note", "Rejected by reviewer.");
        log.info("[MrpController] Rejecting draft ID: {} by user: {}, reason: {}", draftId, userId, reviewerNote);
        
        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, draft.getWorkspaceId());
        }

        boolean isAuthorized = perm.isAdmin();
        if (!isAuthorized && draft.getDepartmentId() != null && !draft.getDepartmentId().trim().isEmpty()) {
            isAuthorized = perm.getDeptIdsWhereHead().contains(draft.getDepartmentId());
        } else if (!isAuthorized) {
            isAuthorized = perm.hasWorkspaceRole("WORKSPACE_MANAGER")
                    || perm.hasWorkspaceRole("WORKSPACE_ADMIN");
        }
        if (!isAuthorized) {
            throw new AccessDeniedException("You do not have permission to review this wiki page draft.");
        }

        WikiPageDraft rejectedDraft = wikiDraftService.rejectDraft(draftId, userId, reviewerNote);
        return ResponseEntity.ok(rejectedDraft);
    }

    /**
     * Yêu cầu sửa đổi Bản thảo nháp kèm note phản hồi.
     * POST /api/mrp/drafts/{draftId}/request-changes
     */
    @PostMapping("/drafts/{draftId}/request-changes")
    public ResponseEntity<WikiPageDraft> requestChanges(
            @PathVariable Long draftId,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        
        String reviewerNote = payload.getOrDefault("note", "Requires revision.");
        log.info("[MrpController] Requesting changes for draft ID: {} by user: {}, reason: {}", draftId, userId, reviewerNote);
        
        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, draft.getWorkspaceId());
        }

        boolean isAuthorized = perm.isAdmin();
        if (!isAuthorized && draft.getDepartmentId() != null && !draft.getDepartmentId().trim().isEmpty()) {
            isAuthorized = perm.getDeptIdsWhereHead().contains(draft.getDepartmentId());
        } else if (!isAuthorized) {
            isAuthorized = perm.hasWorkspaceRole("WORKSPACE_MANAGER")
                    || perm.hasWorkspaceRole("WORKSPACE_ADMIN");
        }
        if (!isAuthorized) {
            throw new AccessDeniedException("You do not have permission to review this wiki page draft.");
        }

        WikiPageDraft revisedDraft = wikiDraftService.requestChanges(draftId, userId, reviewerNote);
        return ResponseEntity.ok(revisedDraft);
    }

    /**
     * Tự động chèn link liên kết nội bộ [[slug]] vào bản thảo markdown.
     * POST /api/mrp/wiki/drafts/auto-link?workspaceId=...
     */
    @PostMapping("/wiki/drafts/auto-link")
    public ResponseEntity<Map<String, String>> autoLinkDraft(
            @RequestBody Map<String, String> payload,
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {

        String content = payload.get("content");
        if (content == null || content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Content cannot be empty"));
        }

        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }

        String linkedContent = wikiDraftService.autoLinkDraftContent(content, normalizedWorkspaceId, perm);
        return ResponseEntity.ok(Map.of("linkedContent", linkedContent));
    }

    // ==================== WIKI ADMIN ENDPOINTS ====================

    @PostMapping("/wiki/reindex")
    public ResponseEntity<?> reindexWikiPages(
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        var perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required for wiki reindex"));
        }
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        log.info("[MrpController] Triggering bulk wiki reindex for workspace: {}", normalizedWorkspaceId);
        Map<String, Object> result = wikiDraftService.reindexAllPages(normalizedWorkspaceId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/wiki/health")
    public ResponseEntity<?> getWikiHealth(
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        var perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required for wiki health"));
        }
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        return ResponseEntity.ok(wikiHealthService.getHealth(normalizedWorkspaceId));
    }

    // ==================== WIKI VIEW ENDPOINTS ====================

    /**
     * Lấy các trang Wiki trong Workspace, có hỗ trợ phân trang server-side nếu cung cấp page và size.
     * GET /api/mrp/wiki?workspaceId=...&page=0&size=10
     */
    @GetMapping("/wiki")
    public ResponseEntity<?> getWikiPages(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        String resolvedWsId = (workspaceId == null || workspaceId.isBlank() || "all".equalsIgnoreCase(workspaceId) || "GLOBAL".equalsIgnoreCase(workspaceId) || "ALL".equalsIgnoreCase(workspaceId))
                ? "default-workspace" : workspaceId;
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(resolvedWsId);
        log.info("[MrpController] Fetching wiki pages for workspace: {}, page: {}, size: {}, user: {}", normalizedWorkspaceId, page, size, userId);
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }
        
        String workspaceDeptId = null;
        if (!"ALL".equals(normalizedWorkspaceId) && !"GLOBAL".equals(normalizedWorkspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(normalizedWorkspaceId, userId);
                if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("[MrpController] Could not resolve department for workspace: {}", normalizedWorkspaceId, e);
            }
        }
        String normalizedDeptId = ScopeNormalizer.normalizeDepartment(workspaceDeptId);
        
        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
            Page<WikiPage> pagedWiki = wikiPageRepository.findAccessiblePages(
                normalizedWorkspaceId, normalizedDeptId, perm.isAdmin(), perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember(), pageable);
            return ResponseEntity.ok(pagedWiki);
        }
        List<WikiPage> pages = wikiPageRepository.findAccessiblePages(
            normalizedWorkspaceId, normalizedDeptId, perm.isAdmin(), perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
        return ResponseEntity.ok(pages);
    }

    /**
     * Lấy danh sách metadata siêu nhẹ của toàn bộ các trang Wiki trong Workspace (không kèm content/summary).
     * GET /api/mrp/wiki/metadata?workspaceId=...
     */
    @GetMapping("/wiki/metadata")
    public ResponseEntity<List<com.security.security.dto.WikiPageMetadataDto>> getWikiMetadata(
            @RequestParam(required = false) String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        String resolvedWsId = (workspaceId == null || workspaceId.isBlank() || "all".equalsIgnoreCase(workspaceId) || "GLOBAL".equalsIgnoreCase(workspaceId) || "ALL".equalsIgnoreCase(workspaceId))
                ? "default-workspace" : workspaceId;
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(resolvedWsId);
        log.info("[MrpController] Fetching lightweight wiki metadata with parsed links for workspace: {} by user: {}", normalizedWorkspaceId, userId);
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }
        
        String workspaceDeptId = null;
        if (!"ALL".equals(normalizedWorkspaceId) && !"GLOBAL".equals(normalizedWorkspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(normalizedWorkspaceId, userId);
                if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("[MrpController] Could not resolve department for workspace: {}", normalizedWorkspaceId, e);
            }
        }
        String normalizedDeptId = ScopeNormalizer.normalizeDepartment(workspaceDeptId);

        List<WikiPageRepository.WikiPageMetadata> pages = wikiPageRepository.findAccessibleMetadata(
            normalizedWorkspaceId, normalizedDeptId, perm.isAdmin(), perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
            
        List<com.security.security.dto.WikiPageMetadataDto> dtos = pages.stream().map(page -> {
            List<String> dbLinks = wikiLinkRepository.findByFromPageId(page.getId()).stream()
                    .map(com.security.security.entity.WikiLink::getToSlug)
                    .toList();

            return com.security.security.dto.WikiPageMetadataDto.builder()
                    .id(page.getId())
                    .title(page.getTitle())
                    .slug(page.getSlug())
                    .workspaceId(page.getWorkspaceId())
                    .tags(page.getTags())
                    .pageType(page.getPageType())
                    .version(page.getVersion())
                    .createdAt(page.getCreatedAt())
                    .updatedAt(page.getUpdatedAt())
                    .links(dbLinks)
                    .departmentId(page.getDepartmentId())
                    .allowedRoles(page.getAllowedRoles())
                    .securityClassification(page.getSecurityClassification())
                    .build();
        }).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Lấy đồ thị liên kết tri thức (Wiki Graph) của Workspace.
     * GET /api/mrp/wiki/graph?workspaceId=...
     */
    @GetMapping("/wiki/graph")
    public ResponseEntity<com.security.security.dto.WikiGraphDto> getWikiGraph(
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        log.info("[MrpController] Fetching wiki link graph for workspace: {} by user: {}", normalizedWorkspaceId, userId);
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }
        
        String workspaceDeptId = null;
        if (!"ALL".equals(normalizedWorkspaceId) && !"GLOBAL".equals(normalizedWorkspaceId) && !"all".equals(normalizedWorkspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(normalizedWorkspaceId, userId);
                if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("[MrpController] Could not resolve department for workspace: {}", normalizedWorkspaceId, e);
            }
        }
        String normalizedDeptId = ScopeNormalizer.normalizeDepartment(workspaceDeptId);

        // 1. Fetch accessible pages (lightweight metadata)
        List<WikiPageRepository.WikiPageMetadata> pages = wikiPageRepository.findAccessibleMetadata(
            normalizedWorkspaceId, normalizedDeptId, perm.isAdmin(), perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
            
        // 2. Map pages to Node DTOs and build a set of accessible slugs
        java.util.Set<String> accessibleSlugs = new java.util.HashSet<>();
        List<com.security.security.dto.WikiGraphDto.NodeDto> nodes = pages.stream().map(page -> {
            accessibleSlugs.add(page.getSlug());
            return com.security.security.dto.WikiGraphDto.NodeDto.builder()
                .slug(page.getSlug())
                .title(page.getTitle())
                .pageType(page.getPageType())
                .build();
        }).collect(java.util.stream.Collectors.toList());
        
        // 3. Fetch all edges for the accessible pages
        List<Long> pageIds = pages.stream().map(WikiPageRepository.WikiPageMetadata::getId).collect(java.util.stream.Collectors.toList());
        List<com.security.security.dto.WikiGraphDto.EdgeDto> edges = new java.util.ArrayList<>();
        
        if (!pageIds.isEmpty()) {
            List<com.security.security.entity.WikiLink> dbLinks = wikiLinkRepository.findByFromPageIdIn(pageIds);
            
            // Map the source page IDs to their slugs for building edges
            Map<Long, String> pageIdToSlugMap = pages.stream()
                .collect(java.util.stream.Collectors.toMap(WikiPageRepository.WikiPageMetadata::getId, WikiPageRepository.WikiPageMetadata::getSlug));
                
            for (com.security.security.entity.WikiLink link : dbLinks) {
                String fromSlug = pageIdToSlugMap.get(link.getFromPageId());
                String toSlug = link.getToSlug();
                
                // Only include the edge if both source and target pages are accessible in the current workspace
                if (fromSlug != null && accessibleSlugs.contains(toSlug)) {
                    edges.add(com.security.security.dto.WikiGraphDto.EdgeDto.builder()
                        .from(fromSlug)
                        .to(toSlug)
                        .build());
                }
            }
        }
        
        return ResponseEntity.ok(com.security.security.dto.WikiGraphDto.builder()
            .nodes(nodes)
            .edges(edges)
            .build());
    }

    @GetMapping("/wiki/graph/communities")
    public ResponseEntity<com.security.security.dto.WikiGraphCommunityDto> getGraphCommunities(
            @RequestParam(defaultValue = "default-workspace") String workspaceId) {
        return ResponseEntity.ok(wikiGraphService.detectCommunities(ScopeNormalizer.normalizeWorkspace(workspaceId)));
    }

    /**
     * Lấy chi tiết một trang Wiki theo slug.
     * GET /api/mrp/wiki/slug/{slug}?workspaceId=...
     */
    @GetMapping("/wiki/slug/{*slug}")
    public ResponseEntity<WikiPage> getWikiPageBySlug(
            @PathVariable String slug,
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        log.info("[MrpController] Fetching wiki page slug: {} for workspace: {} by user: {}", slug, normalizedWorkspaceId, userId);
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }
        
        String cleanSlug = slug;
        if (cleanSlug != null && cleanSlug.startsWith("/")) {
            cleanSlug = cleanSlug.substring(1);
        }
        
        String workspaceDeptId = null;
        if (!"ALL".equals(normalizedWorkspaceId) && !"GLOBAL".equals(normalizedWorkspaceId) && !"all".equals(normalizedWorkspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(normalizedWorkspaceId, userId);
                if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("[MrpController] Could not resolve department for workspace: {}", normalizedWorkspaceId, e);
            }
        }
        String normalizedDeptId = ScopeNormalizer.normalizeDepartment(workspaceDeptId);

        Optional<WikiPage> pageOpt = wikiPageRepository.fetchBySlugAndWorkspaceId(cleanSlug, normalizedWorkspaceId, normalizedDeptId);
        if (pageOpt.isEmpty() && "index".equalsIgnoreCase(cleanSlug)) {
            // Return virtual default index page to avoid 404/500 crash on new workspaces
            WikiPage defaultIndex = WikiPage.builder()
                .title("Wiki Index Overview")
                .slug("index")
                .content("Chào mừng bạn đến với hệ thống quản lý tri thức. Vui lòng phê duyệt các bản thảo (Drafts) hoặc biên soạn tài liệu để hiển thị nội dung chi tiết tại đây.")
                .summary("Default wiki index page.")
                .workspaceId(normalizedWorkspaceId)
                .departmentId(normalizedDeptId != null ? normalizedDeptId : "ALL")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.PUBLIC)
                .pageType(com.security.security.entity.enumeration.WikiPageType.CONCEPT)
                .version(1)
                .build();
            return ResponseEntity.ok(defaultIndex);
        }

        WikiPage page = pageOpt.orElseThrow(() -> new IllegalArgumentException("Wiki page not found with slug: " + slug));
                
        checkPageAccess(page, perm);
        
        return ResponseEntity.ok(page);
    }

    /**
     * Lấy chi tiết một trang Wiki theo ID.
     * GET /api/mrp/wiki/id/{id}
     */
    @GetMapping("/wiki/id/{id}")
    public ResponseEntity<WikiPage> getWikiPageById(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        log.info("[MrpController] Fetching wiki page ID: {} by user: {}", id, userId);
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        
        WikiPage page = wikiPageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Wiki page not found with ID: " + id));
                
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, page.getWorkspaceId());
        }
        
        checkPageAccess(page, perm);
        
        return ResponseEntity.ok(page);
    }

    /**
     * Lấy các Kế hoạch biên soạn (SourceCompilationPlan) trong Workspace, có phân trang.
     * GET /api/mrp/plans?workspaceId=...&page=0&size=10
     */
    @GetMapping("/plans")
    public ResponseEntity<?> getAllPlans(
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRoles) {
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        log.info("[MrpController] Fetching source compilation plans for workspace: {}. page: {}, size: {}, roles: {}", normalizedWorkspaceId, page, size, userRoles);
        
        boolean isAdmin = userRoles != null && (userRoles.contains("SUPER_ADMIN") || userRoles.contains("ADMIN"));
        
        if (("ALL".equals(normalizedWorkspaceId) || "GLOBAL".equals(normalizedWorkspaceId)) && isAdmin) {
            if (page != null && size != null) {
                Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
                Page<SourceCompilationPlan> pagedPlans = sourceCompilationPlanRepository.findAll(pageable);
                pagedPlans.forEach(this::populateDocumentName);
                return ResponseEntity.ok(pagedPlans);
            }
            List<SourceCompilationPlan> plans = sourceCompilationPlanRepository.findAll();
            plans.forEach(this::populateDocumentName);
            return ResponseEntity.ok(plans);
        }

        validateWorkspaceAccess(userId, normalizedWorkspaceId);

        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<SourceCompilationPlan> pagedPlans = sourceCompilationPlanRepository.findByWorkspaceId(normalizedWorkspaceId, pageable);
            pagedPlans.forEach(this::populateDocumentName);
            return ResponseEntity.ok(pagedPlans);
        }
        List<SourceCompilationPlan> plans = sourceCompilationPlanRepository.findByWorkspaceId(normalizedWorkspaceId);
        plans.forEach(this::populateDocumentName);
        return ResponseEntity.ok(plans);
    }

    /**
     * Lấy chi tiết một Kế hoạch biên soạn.
     * GET /api/mrp/plans/{planId}
     */
    @GetMapping("/plans/{planId}")
    public ResponseEntity<SourceCompilationPlan> getPlanById(@PathVariable Long planId) {
        log.info("[MrpController] Fetching plan ID: {}", planId);
        SourceCompilationPlan plan = sourceCompilationPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Source compilation plan not found with ID: " + planId));
        populateDocumentName(plan);
        return ResponseEntity.ok(plan);
    }

    private void populateDocumentName(SourceCompilationPlan plan) {
        if (plan != null && plan.getSourceDocumentId() != null) {
            documentRepository.findById(plan.getSourceDocumentId())
                    .ifPresent(doc -> plan.setSourceDocumentName(doc.getFileName()));
        }
    }

    // ==================== ADMIN ENDPOINTS ====================

    /**
     * Lấy toàn bộ các trang Wiki trong hệ thống (Admin only)
     */
    @GetMapping("/admin/wiki")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<?> getAdminWikiPages(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
            Page<WikiPage> pagedWiki = wikiPageRepository.findAll(pageable);
            return ResponseEntity.ok(pagedWiki);
        }
        List<WikiPage> pages = wikiPageRepository.findAll();
        return ResponseEntity.ok(pages);
    }

    /**
     * Lấy danh sách metadata siêu nhẹ của toàn bộ các trang Wiki trong hệ thống (Admin only)
     */
    @GetMapping("/admin/wiki/metadata")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<com.security.security.dto.WikiPageMetadataDto>> getAdminWikiMetadata(
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        List<WikiPageRepository.WikiPageMetadata> pages = wikiPageRepository.findAllAccessibleMetadata(
                true, perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
        
        List<com.security.security.dto.WikiPageMetadataDto> dtos = pages.stream().map(page -> {
            List<String> dbLinks = wikiLinkRepository.findByFromPageId(page.getId()).stream()
                    .map(com.security.security.entity.WikiLink::getToSlug)
                    .toList();

            return com.security.security.dto.WikiPageMetadataDto.builder()
                    .id(page.getId())
                    .title(page.getTitle())
                    .slug(page.getSlug())
                    .workspaceId(page.getWorkspaceId())
                    .tags(page.getTags())
                    .pageType(page.getPageType())
                    .version(page.getVersion())
                    .createdAt(page.getCreatedAt())
                    .updatedAt(page.getUpdatedAt())
                    .links(dbLinks)
                    .departmentId(page.getDepartmentId())
                    .allowedRoles(page.getAllowedRoles())
                    .securityClassification(page.getSecurityClassification())
                    .build();
        }).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Lấy đồ thị liên kết tri thức (Wiki Graph) của toàn bộ hệ thống (Admin only)
     */
    @GetMapping("/admin/wiki/graph")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<com.security.security.dto.WikiGraphDto> getAdminWikiGraph(
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        log.info("[MrpController] Fetching system-wide admin wiki link graph by user: {}", userId);
        
        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        
        // Fetch all pages (metadata) system-wide
        List<WikiPageRepository.WikiPageMetadata> pages = wikiPageRepository.findAllAccessibleMetadata(
                true, perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
                
        // Map pages to Node DTOs and build a set of accessible slugs
        java.util.Set<String> accessibleSlugs = new java.util.HashSet<>();
        List<com.security.security.dto.WikiGraphDto.NodeDto> nodes = pages.stream().map(page -> {
            accessibleSlugs.add(page.getSlug());
            return com.security.security.dto.WikiGraphDto.NodeDto.builder()
                .slug(page.getSlug())
                .title(page.getTitle())
                .pageType(page.getPageType())
                .build();
        }).collect(java.util.stream.Collectors.toList());
        
        // Fetch all edges for the pages
        List<Long> pageIds = pages.stream().map(WikiPageRepository.WikiPageMetadata::getId).collect(java.util.stream.Collectors.toList());
        List<com.security.security.dto.WikiGraphDto.EdgeDto> edges = new java.util.ArrayList<>();
        
        if (!pageIds.isEmpty()) {
            List<com.security.security.entity.WikiLink> dbLinks = wikiLinkRepository.findByFromPageIdIn(pageIds);
            
            Map<Long, String> pageIdToSlugMap = pages.stream()
                .collect(java.util.stream.Collectors.toMap(WikiPageRepository.WikiPageMetadata::getId, WikiPageRepository.WikiPageMetadata::getSlug));
                
            for (com.security.security.entity.WikiLink link : dbLinks) {
                String fromSlug = pageIdToSlugMap.get(link.getFromPageId());
                String toSlug = link.getToSlug();
                
                if (fromSlug != null && accessibleSlugs.contains(toSlug)) {
                    edges.add(com.security.security.dto.WikiGraphDto.EdgeDto.builder()
                        .from(fromSlug)
                        .to(toSlug)
                        .build());
                }
            }
        }
        
        return ResponseEntity.ok(com.security.security.dto.WikiGraphDto.builder()
            .nodes(nodes)
            .edges(edges)
            .build());
    }

    /**
     * Admin-only: Lấy chi tiết một trang Wiki theo slug từ toàn bộ hệ thống.
     * GET /api/mrp/admin/wiki/slug/{slug}?workspaceId=... (tùy chọn, để ưu tiên workspace)
     */
    @GetMapping("/admin/wiki/slug/{*slug}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<WikiPage> getAdminWikiPageBySlug(
            @PathVariable String slug,
            @RequestParam(required = false) String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {

        log.info("[MrpController] ADMIN fetching wiki page slug: {} (preferred workspace: {}) by user: {}", slug, workspaceId, userId);

        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            // Fallback safety — @PreAuthorize should already block this
            return ResponseEntity.status(403).build();
        }

        String cleanSlug = slug;
        if (cleanSlug != null && cleanSlug.startsWith("/")) {
            cleanSlug = cleanSlug.substring(1);
        }

        // Determine preferred workspace — if "all" or null/empty, pass null to get latest globally
        String preferredWorkspaceId = (workspaceId != null && !workspaceId.isBlank()
                && !"all".equalsIgnoreCase(workspaceId.trim())
                && !"GLOBAL".equalsIgnoreCase(workspaceId.trim())
                && !"ALL".equalsIgnoreCase(workspaceId.trim()))
                ? workspaceId.trim()
                : null;

        Optional<WikiPage> pageOpt = wikiPageRepository.findBySlugGlobal(cleanSlug, preferredWorkspaceId);
        if (pageOpt.isEmpty() && "index".equalsIgnoreCase(cleanSlug)) {
            // Return virtual default index page to avoid 404/500 crash for admin
            WikiPage defaultIndex = WikiPage.builder()
                .title("Wiki Index Overview")
                .slug("index")
                .content("Chào mừng bạn đến với hệ thống quản lý tri thức. Vui lòng phê duyệt các bản thảo (Drafts) hoặc biên soạn tài liệu để hiển thị nội dung chi tiết tại đây.")
                .summary("Default wiki index page.")
                .workspaceId(preferredWorkspaceId != null ? preferredWorkspaceId : "default-workspace")
                .departmentId("ALL")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.PUBLIC)
                .pageType(com.security.security.entity.enumeration.WikiPageType.CONCEPT)
                .version(1)
                .build();
            return ResponseEntity.ok(defaultIndex);
        }

        WikiPage page = pageOpt.orElseThrow(() -> new IllegalArgumentException("Wiki page not found with slug: " + slug));

        return ResponseEntity.ok(page);
    }

    /**
     * Admin-only: Tái tạo toàn bộ wiki graph links và index page cho một workspace.
     * POST /api/mrp/admin/wiki/rebuild-links?workspaceId=...&departmentId=...
     */
    @org.springframework.web.bind.annotation.PostMapping("/admin/wiki/rebuild-links")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Map<String, Object>> rebuildWikiLinks(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) String departmentId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        log.info("[MrpController] ADMIN rebuild wiki links for workspace: {} by user: {}", workspaceId, userId);
        String resolvedWsId = (workspaceId == null || workspaceId.isBlank() || "all".equalsIgnoreCase(workspaceId))
                ? "default-workspace" : workspaceId;
        Map<String, Object> result = wikiDraftService.rebuildAllLinksAndIndex(resolvedWsId, departmentId);
        return ResponseEntity.ok(result);
    }

    /**
     * Lấy structured index view cho wiki. Trả về intro (từ page slug="index") và danh sách các trang phân nhóm.
     * GET /api/mrp/wiki/index?workspaceId=...&types=...&limit=...&cursor=...
     */
    @GetMapping("/wiki/index")
    public ResponseEntity<com.security.security.dto.WikiIndexResponse> getWikiIndex(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) List<String> types,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {

        String resolvedWsId = (workspaceId == null || workspaceId.isBlank() || "all".equalsIgnoreCase(workspaceId) || "GLOBAL".equalsIgnoreCase(workspaceId) || "ALL".equalsIgnoreCase(workspaceId))
                ? "default-workspace" : workspaceId;
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(resolvedWsId);
        log.info("[MrpController] Fetching wiki index for workspace: {}, types: {}, limit: {}, cursor: {}, user: {}", normalizedWorkspaceId, types, limit, cursor, userId);

        UserPermissionContext perm = PermissionUtils.parse(userRolesHeader, userDepartmentsHeader, objectMapper);
        if (!perm.isAdmin()) {
            validateWorkspaceAccess(userId, normalizedWorkspaceId);
        }

        String workspaceDeptId = null;
        if (!"ALL".equals(normalizedWorkspaceId) && !"GLOBAL".equals(normalizedWorkspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(normalizedWorkspaceId, userId);
                if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("[MrpController] Could not resolve department for workspace: {}", normalizedWorkspaceId, e);
            }
        }
        String normalizedDeptId = ScopeNormalizer.normalizeDepartment(workspaceDeptId);

        // 1. Fetch index page with slug = "index"
        Optional<WikiPage> indexPageOpt = wikiPageRepository.fetchBySlugAndWorkspaceId("index", normalizedWorkspaceId, normalizedDeptId);
        String intro = "";
        int version = 0;
        if (indexPageOpt.isPresent()) {
            WikiPage indexPage = indexPageOpt.get();
            checkPageAccess(indexPage, perm);
            intro = indexPage.getContent();
            if (intro == null || intro.trim().isEmpty()) {
                intro = indexPage.getSummary();
            }
            version = indexPage.getVersion();
        }

        // 2. Parse pagination limit & cursor (offset)
        int cappedLimit = Math.min(Math.max(1, limit), 200);
        int offset = 0;
        if (cursor != null && !cursor.trim().isEmpty()) {
            try {
                offset = Integer.parseInt(cursor.trim());
            } catch (NumberFormatException e) {
                log.warn("[MrpController] Invalid cursor format: {}", cursor);
            }
        }

        // 3. Resolve selected types
        List<com.security.security.entity.enumeration.WikiPageType> selectedTypes = new java.util.ArrayList<>();
        if (types != null && !types.isEmpty()) {
            for (String t : types) {
                com.security.security.entity.enumeration.WikiPageType pt = com.security.security.entity.enumeration.WikiPageType.fromValue(t);
                if (pt != null) {
                    selectedTypes.add(pt);
                }
            }
        } else {
            // Default to all known types
            selectedTypes.addAll(List.of(
                com.security.security.entity.enumeration.WikiPageType.CONCEPT,
                com.security.security.entity.enumeration.WikiPageType.ENTITY,
                com.security.security.entity.enumeration.WikiPageType.TOPIC,
                com.security.security.entity.enumeration.WikiPageType.SOURCE
            ));
        }

        // 4. Build groups
        List<com.security.security.dto.WikiIndexGroup> groups = new java.util.ArrayList<>();
        Pageable pageable = PageRequest.of(offset / cappedLimit, cappedLimit, Sort.by(Sort.Direction.ASC, "title"));

        for (com.security.security.entity.enumeration.WikiPageType pt : selectedTypes) {
            Page<WikiPage> pagedResult = wikiPageRepository.findAccessiblePagesByType(
                normalizedWorkspaceId, normalizedDeptId, perm.isAdmin(), perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember(), pt, pageable);

            List<com.security.security.dto.WikiIndexEntry> entries = pagedResult.getContent().stream().map(page -> {
                // Populate category path from tags (splitting tags by comma)
                List<String> categoryPath = new java.util.ArrayList<>();
                if (page.getTags() != null && !page.getTags().trim().isEmpty()) {
                    for (String tag : page.getTags().split(",")) {
                        String cleanTag = tag.trim();
                        if (!cleanTag.isEmpty()) {
                            categoryPath.add(cleanTag);
                        }
                    }
                }
                
                String display = page.getTitle() != null ? page.getTitle().trim() : "";
                if (display.isEmpty()) {
                    display = page.getSlug();
                }

                // Assembles the wikiPath: "type/cat.../title"
                java.util.List<String> pathParts = new java.util.ArrayList<>();
                pathParts.add(pt.getValue());
                pathParts.addAll(categoryPath);
                pathParts.add(display);
                String wikiPath = String.join("/", pathParts);

                return com.security.security.dto.WikiIndexEntry.builder()
                    .slug(page.getSlug())
                    .title(page.getTitle())
                    .summary(page.getSummary() != null ? page.getSummary() : "")
                    .categoryPath(categoryPath)
                    .wikiPath(wikiPath)
                    .depth(categoryPath.size())
                    .sortOrder(0)
                    .build();
            }).toList();

            String nextCursor = null;
            if (pagedResult.hasNext()) {
                nextCursor = String.valueOf(offset + cappedLimit);
            }

            groups.add(com.security.security.dto.WikiIndexGroup.builder()
                .type(pt.getValue())
                .total(pagedResult.getTotalElements())
                .items(entries)
                .nextCursor(nextCursor)
                .build());
        }

        return ResponseEntity.ok(com.security.security.dto.WikiIndexResponse.builder()
            .intro(intro)
            .version(version)
            .groups(groups)
            .build());
    }

    @GetMapping("/wiki/stats")
    public ResponseEntity<Map<String, Object>> getWikiStats(
            @RequestParam(required = false) String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        String resolvedWsId = ScopeNormalizer.normalizeWorkspace(
            (workspaceId == null || workspaceId.isBlank() || "all".equalsIgnoreCase(workspaceId))
                ? "default-workspace" : workspaceId);

        long totalPages = wikiPageRepository.countByWorkspaceId(resolvedWsId);
        long pendingDrafts = wikiPageDraftRepository.countByWorkspaceIdAndStatus(resolvedWsId,
            com.security.security.entity.enumeration.WikiPageDraftStatus.PENDING);

        long processingDocs = documentRepository.findProcessing().stream()
            .filter(d -> resolvedWsId.equals(ScopeNormalizer.normalizeWorkspace(d.getWorkspaceId())))
            .count();

        long finalizingDocs = documentRepository.findAll().stream()
            .filter(d -> "FINALIZING".equals(d.getProcessingStage()))
            .filter(d -> resolvedWsId.equals(ScopeNormalizer.normalizeWorkspace(d.getWorkspaceId()))
                || "ALL".equals(resolvedWsId) || "GLOBAL".equals(resolvedWsId))
            .count();

        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalPages", totalPages);
        stats.put("pendingDrafts", pendingDrafts);
        stats.put("activeCompilations", processingDocs);
        stats.put("finalizingDocs", finalizingDocs);
        stats.put("isIndexing", processingDocs > 0 || finalizingDocs > 0);

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/wiki/activity")
    public ResponseEntity<List<Map<String, Object>>> getWikiActivity(
            @RequestParam(required = false) String workspaceId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        String resolvedWsId = ScopeNormalizer.normalizeWorkspace(
            (workspaceId == null || workspaceId.isBlank() || "all".equalsIgnoreCase(workspaceId))
                ? "default-workspace" : workspaceId);

        List<Map<String, Object>> activities = new java.util.ArrayList<>();

        // Recent wiki pages (created/updated)
        Pageable recentPageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<WikiPage> recentPages = wikiPageRepository.findByWorkspaceId(resolvedWsId, recentPageable);
        for (WikiPage p : recentPages.getContent()) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            boolean isNew = p.getCreatedAt() != null && p.getUpdatedAt() != null
                && java.time.Duration.between(p.getCreatedAt(), p.getUpdatedAt()).toMinutes() < 2;
            entry.put("action", isNew ? "PAGE_CREATED" : "PAGE_UPDATED");
            entry.put("pageId", p.getId());
            entry.put("title", p.getTitle());
            entry.put("slug", p.getSlug());
            entry.put("pageType", p.getPageType() != null ? p.getPageType().getValue() : null);
            entry.put("timestamp", p.getUpdatedAt().toString());
            entry.put("version", p.getVersion());
            activities.add(entry);
        }

        // Recent drafts
        List<com.security.security.entity.WikiPageDraft> recentDrafts =
            wikiPageDraftRepository.findByWorkspaceIdOrderByUpdatedAtDesc(resolvedWsId);
        for (var d : recentDrafts.stream().limit(limit).toList()) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("action", "DRAFT_" + d.getStatus().name());
            entry.put("draftId", d.getId());
            entry.put("title", d.getTitle());
            entry.put("slug", d.getSlug());
            entry.put("authorId", d.getAuthorId());
            entry.put("timestamp", d.getUpdatedAt().toString());
            entry.put("revisionRound", d.getRevisionRound());
            if (d.getReviewerNote() != null) entry.put("reviewerNote", d.getReviewerNote());
            activities.add(entry);
        }

        // Sort all by timestamp desc, limit
        activities.sort((a, b) -> String.valueOf(b.get("timestamp")).compareTo(String.valueOf(a.get("timestamp"))));
        if (activities.size() > limit) {
            activities = activities.subList(0, limit);
        }

        return ResponseEntity.ok(activities);
    }
}

