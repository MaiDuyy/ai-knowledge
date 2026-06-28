package com.security.security.resource;

import com.security.security.dto.WikiIssueDTO;
import com.security.security.dtorequest.CreateWikiIssueRequest;
import com.security.security.dtorequest.UpdateWikiIssueRequest;
import com.security.security.service.WikiIssueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mrp/wiki/issues")
@CrossOrigin("*")
@RequiredArgsConstructor
@Slf4j
public class WikiIssueController {

    private final WikiIssueService wikiIssueService;

    /**
     * GET /api/mrp/wiki/issues?slug={slug}&workspaceId={workspaceId}
     * Get all issues for a specific wiki page.
     */
    @GetMapping
    public ResponseEntity<List<WikiIssueDTO>> getIssuesByPage(
            @RequestParam String slug,
            @RequestParam String workspaceId) {
        log.info("[WikiIssueController] GET issues for slug={}, workspaceId={}", slug, workspaceId);
        return ResponseEntity.ok(wikiIssueService.getIssuesByPage(slug, workspaceId));
    }

    /**
     * GET /api/mrp/wiki/issues/all?workspaceId={workspaceId}&status={status}
     * Get all issues for a workspace, optionally filtered by status.
     */
    @GetMapping("/all")
    public ResponseEntity<List<WikiIssueDTO>> getAllIssues(
            @RequestParam String workspaceId,
            @RequestParam(required = false) String status) {
        log.info("[WikiIssueController] GET all issues for workspaceId={}, status={}", workspaceId, status);
        return ResponseEntity.ok(wikiIssueService.getAllIssues(workspaceId, status));
    }

    /**
     * GET /api/mrp/wiki/issues/count?slug={slug}
     * Get open issue count for a wiki page.
     */
    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> countOpenIssues(@RequestParam String slug) {
        log.info("[WikiIssueController] GET issue count for slug={}", slug);
        long count = wikiIssueService.countOpenIssues(slug);
        return ResponseEntity.ok(Map.of("openIssues", count));
    }

    /**
     * POST /api/mrp/wiki/issues
     * Manually create a wiki issue.
     */
    @PostMapping
    public ResponseEntity<WikiIssueDTO> createIssue(
            @RequestBody CreateWikiIssueRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        log.info("[WikiIssueController] POST create issue for slug={}, type={}, user={}",
                request.getWikiPageSlug(), request.getIssueType(), userId);
        WikiIssueDTO created = wikiIssueService.createIssue(request, userId);
        return ResponseEntity.ok(created);
    }

    /**
     * PATCH /api/mrp/wiki/issues/{id}
     * Update the status of a wiki issue.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<WikiIssueDTO> updateIssue(
            @PathVariable Long id,
            @RequestBody UpdateWikiIssueRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        log.info("[WikiIssueController] PATCH update issue id={}, status={}, user={}", id, request.getStatus(), userId);
        WikiIssueDTO updated = wikiIssueService.updateIssue(id, request, userId);
        return ResponseEntity.ok(updated);
    }

    /**
     * DELETE /api/mrp/wiki/issues/{id}
     * Delete a wiki issue.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteIssue(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        log.info("[WikiIssueController] DELETE issue id={} by user={}", id, userId);
        wikiIssueService.deleteIssue(id);
        return ResponseEntity.ok(Map.of("message", "Issue deleted successfully."));
    }
}
