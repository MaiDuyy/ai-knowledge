package com.security.security.resource;

import com.security.security.entity.SourceCompilationPlan;
import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiPageDraft;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.WikiPageDraftRepository;
import com.security.security.repository.SourceCompilationPlanRepository;
import com.security.security.repository.DocumentRepository;
import com.security.security.service.MrpPipelineService;
import com.security.security.service.WikiDraftService;
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
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mrp")
@RequiredArgsConstructor
@Slf4j
public class MrpController {

    private final MrpPipelineService mrpPipelineService;
    private final WikiDraftService wikiDraftService;
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
        if (workspaceId == null || workspaceId.trim().isEmpty() || "default-workspace".equals(workspaceId)) {
            return; // Allow public or default
        }
        var workspace = workspaceServiceClient.getWorkspace(workspaceId, userId);
        if (workspace.isEmpty()) {
            log.warn("[Security] Access denied or workspace not found: User {} in Workspace {}", userId, workspaceId);
            throw new AccessDeniedException("You do not have access to Workspace: " + workspaceId);
        }
    }

    private static class ParsedUserPermissions {
        boolean isAdmin = false;
        java.util.List<String> deptIdsWhereHead = new java.util.ArrayList<>();
        java.util.List<String> deptIdsWhereMember = new java.util.ArrayList<>();
    }

    private ParsedUserPermissions parseUserPermissions(String userRolesHeader, String userDepartmentsHeader) {
        ParsedUserPermissions permissions = new ParsedUserPermissions();
        
        // 1. Check admin status from roles
        if (userRolesHeader != null) {
            String upper = userRolesHeader.toUpperCase();
            if (upper.contains("SUPER_ADMIN") || upper.contains("ADMIN") || upper.contains("ORG_ADMIN")) {
                permissions.isAdmin = true;
            }
        }
        
        // 2. Parse departments and roles
        if (userDepartmentsHeader != null && !userDepartmentsHeader.trim().isEmpty()) {
            try {
                // Parse [{"departmentId": "...", "role": "..."}]
                java.util.List<java.util.Map<String, String>> depts = objectMapper.readValue(
                    userDepartmentsHeader, 
                    new TypeReference<java.util.List<java.util.Map<String, String>>>() {}
                );
                for (java.util.Map<String, String> dept : depts) {
                    String deptId = dept.get("departmentId");
                    String role = dept.get("role");
                    if (deptId != null && !deptId.trim().isEmpty()) {
                        if ("HEAD".equalsIgnoreCase(role) || "MANAGER".equalsIgnoreCase(role)) {
                            permissions.deptIdsWhereHead.add(deptId);
                        } else {
                            permissions.deptIdsWhereMember.add(deptId);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse x-user-departments header: {}", e.getMessage());
            }
        }
        
        // 3. Fallback dummy values to prevent empty lists in JPA IN clause (prevents SQL errors)
        if (permissions.deptIdsWhereHead.isEmpty()) {
            permissions.deptIdsWhereHead.add("DUMMY_DEPT_ID");
        }
        if (permissions.deptIdsWhereMember.isEmpty()) {
            permissions.deptIdsWhereMember.add("DUMMY_DEPT_ID");
        }
        
        return permissions;
    }

    private void checkPageAccess(WikiPage page, ParsedUserPermissions perm) {
        if (perm.isAdmin) {
            return;
        }
        if ("PUBLIC".equalsIgnoreCase(page.getSecurityClassification())) {
            return;
        }
        if (page.getDepartmentId() == null || page.getDepartmentId().trim().isEmpty()) {
            // INTERNAL company-wide pages are accessible by all internal users
            if ("INTERNAL".equalsIgnoreCase(page.getSecurityClassification())) {
                return;
            }
        } else {
            String deptId = page.getDepartmentId();
            if (perm.deptIdsWhereHead.contains(deptId)) {
                return;
            }
            if (perm.deptIdsWhereMember.contains(deptId) && !"HEAD".equalsIgnoreCase(page.getAllowedRoles())) {
                return;
            }
        }
        throw new AccessDeniedException("You do not have permission to access this wiki page.");
    }

    /**
     * Khởi tạo quy trình MRP Compile (Map & Reduce Phase).
     * Phân tách tài liệu song song bằng Virtual Threads, tổng hợp, dedup, đối soát và trả về Plan.
     * POST /api/mrp/compile?documentId=...&workspaceId=...&autoApprove=true/false
     */
    @PostMapping("/compile")
    public ResponseEntity<SourceCompilationPlan> compileDocument(
            @RequestParam Long documentId,
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestParam(defaultValue = "false") boolean autoApprove,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRoles) {
        
        log.info("[MrpController] Compiling document ID: {}, workspace: {}, autoApprove: {}, user: {}", 
                documentId, workspaceId, autoApprove, userId);
        
        boolean isAdmin = userRoles != null && (userRoles.contains("SUPER_ADMIN") || userRoles.contains("ADMIN"));
        if (!isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }
        
        SourceCompilationPlan plan = mrpPipelineService.initiateCompile(documentId, workspaceId, userId, autoApprove);
        populateDocumentName(plan);
        return ResponseEntity.ok(plan);
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
            @RequestHeader(value = "x-user-roles", required = false) String userRoles) {
        
        log.info("[MrpController] Approving plan ID: {}, runAutoApproveDrafts: {}, user: {}", planId, runAutoApproveDrafts, userId);
        
        boolean isAdmin = userRoles != null && (userRoles.contains("SUPER_ADMIN") || userRoles.contains("ADMIN"));
        if (!isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }
        
        mrpPipelineService.executeCompilationPlan(planId, workspaceId, userId, runAutoApproveDrafts);
        
        return ResponseEntity.ok(Map.of("message", "Compilation plan approved and executed. Drafts are generated."));
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
        log.info("[MrpController] Fetching pending drafts. page: {}, size: {}, user: {}", page, size, userId);
        
        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        if (!perm.isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }

        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<WikiPageDraft> pagedDrafts = wikiPageDraftRepository.findAccessibleDraftsByStatus(
                workspaceId, "PENDING", perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember, pageable);
            return ResponseEntity.ok(pagedDrafts);
        }
        
        List<WikiPageDraft> drafts = wikiPageDraftRepository.findAccessibleDraftsByStatus(
            workspaceId, "PENDING", perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember);
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
        log.info("[MrpController] Fetching drafts for workspace ID: {} by user: {}", workspaceId, userId);
        
        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        if (!perm.isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }
        
        List<WikiPageDraft> drafts = wikiPageDraftRepository.findAccessibleDrafts(
            workspaceId, perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember);
        return ResponseEntity.ok(drafts);
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

        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        if (!perm.isAdmin) {
            validateWorkspaceAccess(userId, draft.getWorkspaceId());
        }

        boolean isAuthorized = perm.isAdmin;
        if (!isAuthorized && draft.getDepartmentId() != null && !draft.getDepartmentId().trim().isEmpty()) {
            isAuthorized = perm.deptIdsWhereHead.contains(draft.getDepartmentId());
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

        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        if (!perm.isAdmin) {
            validateWorkspaceAccess(userId, draft.getWorkspaceId());
        }

        boolean isAuthorized = perm.isAdmin;
        if (!isAuthorized && draft.getDepartmentId() != null && !draft.getDepartmentId().trim().isEmpty()) {
            isAuthorized = perm.deptIdsWhereHead.contains(draft.getDepartmentId());
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

        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        if (!perm.isAdmin) {
            validateWorkspaceAccess(userId, draft.getWorkspaceId());
        }

        boolean isAuthorized = perm.isAdmin;
        if (!isAuthorized && draft.getDepartmentId() != null && !draft.getDepartmentId().trim().isEmpty()) {
            isAuthorized = perm.deptIdsWhereHead.contains(draft.getDepartmentId());
        }
        if (!isAuthorized) {
            throw new AccessDeniedException("You do not have permission to review this wiki page draft.");
        }
        
        WikiPageDraft revisedDraft = wikiDraftService.requestChanges(draftId, userId, reviewerNote);
        return ResponseEntity.ok(revisedDraft);
    }

    // ==================== WIKI VIEW ENDPOINTS ====================

    /**
     * Lấy các trang Wiki trong Workspace, có hỗ trợ phân trang server-side nếu cung cấp page và size.
     * GET /api/mrp/wiki?workspaceId=...&page=0&size=10
     */
    @GetMapping("/wiki")
    public ResponseEntity<?> getWikiPages(
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        log.info("[MrpController] Fetching wiki pages for workspace: {}, page: {}, size: {}, user: {}", workspaceId, page, size, userId);
        
        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        if (!perm.isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }
        
        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
            Page<WikiPage> pagedWiki = wikiPageRepository.findAccessiblePages(
                workspaceId, perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember, pageable);
            return ResponseEntity.ok(pagedWiki);
        }
        List<WikiPage> pages = wikiPageRepository.findAccessiblePages(
            workspaceId, perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember);
        return ResponseEntity.ok(pages);
    }

    /**
     * Lấy danh sách metadata siêu nhẹ của toàn bộ các trang Wiki trong Workspace (không kèm content/summary).
     * GET /api/mrp/wiki/metadata?workspaceId=...
     */
    @GetMapping("/wiki/metadata")
    public ResponseEntity<List<com.security.security.dto.WikiPageMetadataDto>> getWikiMetadata(
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        log.info("[MrpController] Fetching lightweight wiki metadata with parsed links for workspace: {} by user: {}", workspaceId, userId);
        
        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        if (!perm.isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }
        
        List<WikiPageRepository.WikiPageMetadata> pages = wikiPageRepository.findAccessibleMetadata(
            workspaceId, perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember);
            
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
                    .build();
        }).toList();
        return ResponseEntity.ok(dtos);
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
        log.info("[MrpController] Fetching wiki page slug: {} for workspace: {} by user: {}", slug, workspaceId, userId);
        
        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        if (!perm.isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }
        
        String cleanSlug = slug;
        if (cleanSlug != null && cleanSlug.startsWith("/")) {
            cleanSlug = cleanSlug.substring(1);
        }
        
        WikiPage page = wikiPageRepository.findBySlugAndWorkspaceId(cleanSlug, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Wiki page not found with slug: " + slug));
                
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
        
        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        
        WikiPage page = wikiPageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Wiki page not found with ID: " + id));
                
        if (!perm.isAdmin) {
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
        log.info("[MrpController] Fetching source compilation plans for workspace: {}. page: {}, size: {}, roles: {}", workspaceId, page, size, userRoles);
        
        boolean isAdmin = userRoles != null && (userRoles.contains("SUPER_ADMIN") || userRoles.contains("ADMIN"));
        
        if ("all".equals(workspaceId)) {
            if (!isAdmin) {
                throw new AccessDeniedException("Only system administrators can access compilation plans across all workspaces.");
            }
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

        validateWorkspaceAccess(userId, workspaceId);

        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<SourceCompilationPlan> pagedPlans = sourceCompilationPlanRepository.findByWorkspaceId(workspaceId, pageable);
            pagedPlans.forEach(this::populateDocumentName);
            return ResponseEntity.ok(pagedPlans);
        }
        List<SourceCompilationPlan> plans = sourceCompilationPlanRepository.findByWorkspaceId(workspaceId);
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
}
