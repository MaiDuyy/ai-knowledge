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
        if (workspaceId == null || workspaceId.trim().isEmpty() || "default-workspace".equals(workspaceId) || "all".equals(workspaceId)) {
            return; // Allow public, default, or all-workspaces
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
        if (perm.isAdmin) return;

        if ("PUBLIC".equalsIgnoreCase(page.getSecurityClassification())) return;

        String pageDeptId = page.getDepartmentId();
        String pageWsId = page.getWorkspaceId();
        String allowed = page.getAllowedRoles();

        boolean isGlobalScope = (pageDeptId == null || pageDeptId.isBlank())
            && (pageWsId == null || pageWsId.isBlank()
                || "default-workspace".equalsIgnoreCase(pageWsId)
                || "workspace-default".equalsIgnoreCase(pageWsId)
                || "all".equalsIgnoreCase(pageWsId));

        // Rule 1: Global scope → all users
        if (isGlobalScope) return;

        // INTERNAL company-wide pages without department restriction
        if ((pageDeptId == null || pageDeptId.isBlank())
                && "INTERNAL".equalsIgnoreCase(page.getSecurityClassification())) {
            return;
        }

        // Rules 2+3+4: Department-scoped → must be a member of that department
        if (pageDeptId != null && !pageDeptId.isBlank()) {
            boolean isHead = perm.deptIdsWhereHead.contains(pageDeptId);
            boolean isMember = perm.deptIdsWhereMember.contains(pageDeptId);

            if (!isHead && !isMember) {
                throw new AccessDeniedException("You do not have permission to access this wiki page.");
            }
            if (allowed == null || allowed.isBlank() || "ALL".equalsIgnoreCase(allowed)) return;
            if ("HEAD".equalsIgnoreCase(allowed) && isHead) return;
            if ("MEMBER".equalsIgnoreCase(allowed) && (isMember || isHead)) return;

            throw new AccessDeniedException("You do not have permission to access this wiki page.");
        }

        // Workspace-specific but no department → workspace access already validated upstream
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
            @RequestHeader(value = "x-user-roles", required = false) String userRoles) {
        
        log.info("[MrpController] Compiling document ID: {}, workspace: {}, autoApprove: {}, user: {}", 
                documentId, workspaceId, autoApprove, userId);
        
        boolean isAdmin = userRoles != null && (userRoles.contains("SUPER_ADMIN") || userRoles.contains("ADMIN"));
        if (!isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }
        
        SourceCompilationPlan plan = mrpPipelineService.initiateCompile(documentId, workspaceId, userId, autoApprove);
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

        // Admins and SuperAdmins can view all pending drafts across all workspaces
        if (perm.isAdmin) {
            if (page != null && size != null) {
                Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
                Page<WikiPageDraft> pagedDrafts = wikiPageDraftRepository.findByStatus("PENDING", pageable);
                return ResponseEntity.ok(pagedDrafts);
            }
            List<WikiPageDraft> drafts = wikiPageDraftRepository.findByStatus("PENDING");
            return ResponseEntity.ok(drafts);
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

    /**
     * Tự động chèn link liên kết nội bộ [[slug]] vào bản thảo markdown.
     * POST /api/mrp/wiki/drafts/auto-link?workspaceId=...
     */
    @PostMapping("/wiki/drafts/auto-link")
    public ResponseEntity<Map<String, String>> autoLinkDraft(
            @RequestBody Map<String, String> payload,
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader) {
        
        String content = payload.get("content");
        if (content == null || content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Content cannot be empty"));
        }
        
        boolean isAdmin = userRolesHeader != null && (userRolesHeader.contains("SUPER_ADMIN") || userRolesHeader.contains("ADMIN"));
        if (!isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }
        
        String linkedContent = wikiDraftService.autoLinkDraftContent(content, workspaceId);
        return ResponseEntity.ok(Map.of("linkedContent", linkedContent));
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
        
        String workspaceDeptId = null;
        if (workspaceId != null && !"default-workspace".equals(workspaceId) && !"all".equals(workspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(workspaceId, userId);
                if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("[MrpController] Could not resolve department for workspace: {}", workspaceId, e);
            }
        }
        
        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
            Page<WikiPage> pagedWiki = wikiPageRepository.findAccessiblePages(
                workspaceId, workspaceDeptId, perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember, pageable);
            return ResponseEntity.ok(pagedWiki);
        }
        List<WikiPage> pages = wikiPageRepository.findAccessiblePages(
            workspaceId, workspaceDeptId, perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember);
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
        
        String workspaceDeptId = null;
        if (workspaceId != null && !"default-workspace".equals(workspaceId) && !"all".equals(workspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(workspaceId, userId);
                if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("[MrpController] Could not resolve department for workspace: {}", workspaceId, e);
            }
        }

        List<WikiPageRepository.WikiPageMetadata> pages = wikiPageRepository.findAccessibleMetadata(
            workspaceId, workspaceDeptId, perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember);
            
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
     * Lấy đồ thị liên kết tri thức (Wiki Graph) của Workspace.
     * GET /api/mrp/wiki/graph?workspaceId=...
     */
    @GetMapping("/wiki/graph")
    public ResponseEntity<com.security.security.dto.WikiGraphDto> getWikiGraph(
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-roles", required = false) String userRolesHeader,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartmentsHeader) {
        log.info("[MrpController] Fetching wiki link graph for workspace: {} by user: {}", workspaceId, userId);
        
        ParsedUserPermissions perm = parseUserPermissions(userRolesHeader, userDepartmentsHeader);
        if (!perm.isAdmin) {
            validateWorkspaceAccess(userId, workspaceId);
        }
        
        String workspaceDeptId = null;
        if (workspaceId != null && !"default-workspace".equals(workspaceId) && !"all".equals(workspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(workspaceId, userId);
                if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("[MrpController] Could not resolve department for workspace: {}", workspaceId, e);
            }
        }

        // 1. Fetch accessible pages (lightweight metadata)
        List<WikiPageRepository.WikiPageMetadata> pages = wikiPageRepository.findAccessibleMetadata(
            workspaceId, workspaceDeptId, perm.isAdmin, perm.deptIdsWhereHead, perm.deptIdsWhereMember);
            
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
        
        String workspaceDeptId = null;
        if (workspaceId != null && !"default-workspace".equals(workspaceId) && !"all".equals(workspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(workspaceId, userId);
                if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("[MrpController] Could not resolve department for workspace: {}", workspaceId, e);
            }
        }

        WikiPage page = wikiPageRepository.fetchBySlugAndWorkspaceId(cleanSlug, workspaceId, workspaceDeptId)
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
