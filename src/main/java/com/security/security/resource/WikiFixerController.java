package com.security.security.resource;

import com.security.security.service.WikiFixerService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * REST controller for the Wiki Fixer Agent.
 * Exposes a streaming SSE endpoint that runs the agent and emits fix progress tokens.
 */
@RestController
@RequestMapping("/api/mrp/wiki/fixer")
@CrossOrigin("*")
@RequiredArgsConstructor
@Slf4j
public class WikiFixerController {

    private final WikiFixerService wikiFixerService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WikiFixerRequest {
        private String wikiPageSlug;
        private Long issueId;
        private String message;
        private String workspaceId;
    }

    /**
     * POST /api/mrp/wiki/fixer/chat
     *
     * Streams the Wiki Fixer Agent response as SSE tokens.
     * The agent reads the page, detects issues, applies fixes, and resolves issues.
     *
     * Request body: { "wikiPageSlug": "...", "issueId": 123, "message": "...", "workspaceId": "..." }
     * Response: text/event-stream
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> fixerChat(
            @RequestBody WikiFixerRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        log.info("[WikiFixerController] userId={}, slug={}, issueId={}, message='{}'",
                userId, request.getWikiPageSlug(), request.getIssueId(), request.getMessage());

        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Flux.just("Vui lòng cung cấp yêu cầu cụ thể để Wiki Fixer Agent có thể hỗ trợ.");
        }

        String workspaceId = request.getWorkspaceId() != null ? request.getWorkspaceId() : "default-workspace";

        return wikiFixerService.runFixer(
                request.getWikiPageSlug(),
                request.getIssueId(),
                request.getMessage(),
                workspaceId,
                userId
        ).onErrorResume(e -> {
            log.error("[WikiFixerController] Error: {}", e.getMessage());
            return Flux.just("Đã xảy ra lỗi khi xử lý yêu cầu. Vui lòng thử lại.");
        });
    }
}
