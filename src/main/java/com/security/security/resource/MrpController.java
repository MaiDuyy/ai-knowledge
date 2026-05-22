package com.security.security.resource;

import com.security.security.entity.SourceCompilationPlan;
import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiPageDraft;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.SourceCompilationPlanRepository;
import com.security.security.service.MrpPipelineService;
import com.security.security.service.WikiDraftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final SourceCompilationPlanRepository sourceCompilationPlanRepository;

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
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        
        log.info("[MrpController] Compiling document ID: {}, workspace: {}, autoApprove: {}, user: {}", 
                documentId, workspaceId, autoApprove, userId);
        
        SourceCompilationPlan plan = mrpPipelineService.initiateCompile(documentId, workspaceId, userId, autoApprove);
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
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        
        log.info("[MrpController] Approving plan ID: {}, runAutoApproveDrafts: {}, user: {}", planId, runAutoApproveDrafts, userId);
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
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        log.info("[MrpController] Fetching pending drafts. page: {}, size: {}", page, size);
        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<WikiPageDraft> pagedDrafts = wikiDraftService.getPendingDrafts(pageable);
            return ResponseEntity.ok(pagedDrafts);
        }
        List<WikiPageDraft> drafts = wikiDraftService.getPendingDrafts();
        return ResponseEntity.ok(drafts);
    }

    /**
     * Lấy danh sách Bản thảo (Drafts) của một Workspace.
     * GET /api/mrp/drafts/workspace/{workspaceId}
     */
    @GetMapping("/drafts/workspace/{workspaceId}")
    public ResponseEntity<List<WikiPageDraft>> getDraftsByWorkspace(@PathVariable String workspaceId) {
        log.info("[MrpController] Fetching drafts for workspace ID: {}", workspaceId);
        List<WikiPageDraft> drafts = wikiDraftService.getDraftsByWorkspace(workspaceId);
        return ResponseEntity.ok(drafts);
    }

    /**
     * Phê duyệt Bản thảo nháp, trộn nội dung nâng cao (Prompt Merge) và đồng bộ VectorStore.
     * POST /api/mrp/drafts/{draftId}/approve
     */
    @PostMapping("/drafts/{draftId}/approve")
    public ResponseEntity<WikiPageDraft> approveDraft(
            @PathVariable Long draftId,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        
        log.info("[MrpController] Approving draft ID: {} by user: {}", draftId, userId);
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
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        
        String reviewerNote = payload.getOrDefault("note", "Rejected by reviewer.");
        log.info("[MrpController] Rejecting draft ID: {} by user: {}, reason: {}", draftId, userId, reviewerNote);
        
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
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        
        String reviewerNote = payload.getOrDefault("note", "Requires revision.");
        log.info("[MrpController] Requesting changes for draft ID: {} by user: {}, reason: {}", draftId, userId, reviewerNote);
        
        WikiPageDraft revisedDraft = wikiDraftService.requestChanges(draftId, userId, reviewerNote);
        return ResponseEntity.ok(revisedDraft);
    }

    // ==================== OFFICIAL WIKI PAGE ENDPOINTS ====================

    /**
     * Lấy các trang Wiki trong Workspace, có hỗ trợ phân trang server-side nếu cung cấp page và size.
     * GET /api/mrp/wiki?workspaceId=...&page=0&size=10
     */
    @GetMapping("/wiki")
    public ResponseEntity<?> getWikiPages(
            @RequestParam(defaultValue = "default-workspace") String workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        log.info("[MrpController] Fetching wiki pages for workspace: {}, page: {}, size: {}", workspaceId, page, size);
        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
            Page<WikiPage> pagedWiki = wikiPageRepository.findByWorkspaceId(workspaceId, pageable);
            return ResponseEntity.ok(pagedWiki);
        }
        List<WikiPage> pages = wikiPageRepository.findByWorkspaceId(workspaceId);
        return ResponseEntity.ok(pages);
    }

    /**
     * Lấy danh sách metadata siêu nhẹ của toàn bộ các trang Wiki trong Workspace (không kèm content/summary).
     * GET /api/mrp/wiki/metadata?workspaceId=...
     */
    @GetMapping("/wiki/metadata")
    public ResponseEntity<List<com.security.security.dto.WikiPageMetadataDto>> getWikiMetadata(
            @RequestParam(defaultValue = "default-workspace") String workspaceId) {
        log.info("[MrpController] Fetching lightweight wiki metadata with parsed links for workspace: {}", workspaceId);
        List<WikiPage> pages = wikiPageRepository.findByWorkspaceId(workspaceId);
        List<com.security.security.dto.WikiPageMetadataDto> dtos = pages.stream().map(page -> 
            com.security.security.dto.WikiPageMetadataDto.builder()
                    .id(page.getId())
                    .title(page.getTitle())
                    .slug(page.getSlug())
                    .workspaceId(page.getWorkspaceId())
                    .tags(page.getTags())
                    .pageType(page.getPageType())
                    .version(page.getVersion())
                    .createdAt(page.getCreatedAt())
                    .updatedAt(page.getUpdatedAt())
                    .links(com.security.security.dto.WikiPageMetadataDto.extractLinks(page.getContent()))
                    .build()
        ).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Lấy chi tiết một trang Wiki theo slug.
     * GET /api/mrp/wiki/slug/{slug}?workspaceId=...
     */
    @GetMapping("/wiki/slug/{slug}")
    public ResponseEntity<WikiPage> getWikiPageBySlug(
            @PathVariable String slug,
            @RequestParam(defaultValue = "default-workspace") String workspaceId) {
        log.info("[MrpController] Fetching wiki page slug: {} for workspace: {}", slug, workspaceId);
        WikiPage page = wikiPageRepository.findBySlugAndWorkspaceId(slug, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Wiki page not found with slug: " + slug));
        return ResponseEntity.ok(page);
    }

    /**
     * Lấy chi tiết một trang Wiki theo ID.
     * GET /api/mrp/wiki/id/{id}
     */
    @GetMapping("/wiki/id/{id}")
    public ResponseEntity<WikiPage> getWikiPageById(@PathVariable Long id) {
        log.info("[MrpController] Fetching wiki page ID: {}", id);
        WikiPage page = wikiPageRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Wiki page not found with ID: " + id));
        return ResponseEntity.ok(page);
    }

    /**
     * Lấy các Kế hoạch biên soạn (SourceCompilationPlan) có phân trang.
     * GET /api/mrp/plans?page=0&size=10
     */
    @GetMapping("/plans")
    public ResponseEntity<?> getAllPlans(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        log.info("[MrpController] Fetching source compilation plans. page: {}, size: {}", page, size);
        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<SourceCompilationPlan> pagedPlans = sourceCompilationPlanRepository.findAll(pageable);
            return ResponseEntity.ok(pagedPlans);
        }
        List<SourceCompilationPlan> plans = sourceCompilationPlanRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
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
        return ResponseEntity.ok(plan);
    }
}
