package com.security.security.service;

import com.security.security.dto.WikiIssueDTO;
import com.security.security.dtorequest.UpdateWikiIssueRequest;
import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiPageDraft;
import com.security.security.entity.enumeration.WikiIssueStatus;
import com.security.security.entity.enumeration.WikiPageDraftStatus;
import com.security.security.repository.WikiIssueRepository;
import com.security.security.repository.WikiPageDraftRepository;
import com.security.security.repository.WikiPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Wiki Fixer Agent Service — a specialized streaming agent that automatically
 * diagnoses and repairs wiki page quality issues using a ReAct-style tool loop.
 *
 * Tools available:
 * read_wiki_page, edit_wiki_page, search_wiki, searchKnowledge,
 * get_wiki_issues, resolve_issue, ignore_issue
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WikiFixerService {

    private final ChatClient chatClient;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageDraftRepository wikiPageDraftRepository;
    private final WikiIssueRepository wikiIssueRepository;
    private final WikiIssueService wikiIssueService;
    private final VectorStore vectorStore;

    private static final String WIKI_FIXER_SYSTEM_PROMPT = """
            <role>
            Bạn là NEXUS Wiki Fixer Agent, chuyên gia sửa chữa và cải thiện chất lượng trang wiki nội bộ.
            </role>
            <mission>
            Khi nhận được yêu cầu sửa một issue wiki cụ thể, bạn sẽ:
            1. Đọc trang wiki hiện tại (read_wiki_page)
            2. Phân tích issue được báo cáo
            3. Tìm kiếm thông tin bổ sung nếu cần (search_wiki, searchKnowledge)
            4. Sửa nội dung trang (edit_wiki_page)
            5. Đánh dấu issue đã được giải quyết (resolve_issue)
            </mission>
            <tools>
            - read_wiki_page: Đọc nội dung trang wiki theo slug
            - edit_wiki_page: Chỉnh sửa nội dung trang wiki
            - search_wiki: Tìm trang wiki liên quan
            - searchKnowledge: Tìm trong kho tài liệu RAG
            - get_wiki_issues: Lấy danh sách issues của một trang
            - resolve_issue: Đánh dấu issue đã fix (issueId, note)
            - ignore_issue: Bỏ qua issue (issueId, reason)
            </tools>
            <workflow>
            Bước 1 - Đọc hiện trạng: Luôn read_wiki_page và get_wiki_issues trước
            Bước 2 - Phân tích: Xác định vấn đề cụ thể
            Bước 3 - Thu thập bằng chứng: search_wiki hoặc searchKnowledge nếu cần thêm context
            Bước 4 - Sửa: edit_wiki_page với nội dung đã cải thiện
            Bước 5 - Xác nhận: resolve_issue hoặc ignore_issue
            </workflow>
            <constraints>
            - CHỈ sửa những gì thực sự sai hoặc cần cải thiện, không viết lại toàn bộ
            - Giữ nguyên cấu trúc và định dạng Markdown
            - Bảo toàn wiki-links [[slug|display]] hợp lệ
            - Luôn có bằng chứng từ tài liệu trước khi sửa
            - JSON duy nhất không được xuất hiện trong response — trả lời bằng ngôn ngữ tự nhiên
            </constraints>
            """;

    /**
     * Run the wiki fixer agent for a given page/issue.
     * Streams natural-language tokens as the agent works through the fix.
     *
     * @param wikiPageSlug the slug of the wiki page to fix
     * @param issueId      optional specific issue ID to focus on
     * @param userMessage  the user's instruction or question
     * @param workspaceId  workspace context for scoped lookups
     * @param userId       the authenticated user triggering the fixer
     * @return Flux of text tokens (SSE-ready)
     */
    public Flux<String> runFixer(String wikiPageSlug, Long issueId, String userMessage,
                                  String workspaceId, String userId) {
        log.info("[WikiFixerService] Starting fixer for slug={}, issueId={}, userId={}", wikiPageSlug, issueId, userId);

        // Instantiate per-request tool configuration with current context
        WikiFixerTools tools = new WikiFixerTools(
                wikiPageRepository, wikiPageDraftRepository, wikiIssueRepository,
                wikiIssueService, vectorStore, userId, workspaceId);

        String contextualSystem = WIKI_FIXER_SYSTEM_PROMPT
                + "\n\n## Context\nWorkspaceId: " + workspaceId
                + "\nUserId: " + userId
                + (wikiPageSlug != null ? "\nTrang Wiki cần xử lý: " + wikiPageSlug : "")
                + (issueId != null ? "\nIssue ID cần giải quyết: " + issueId : "");

        String enrichedMessage = userMessage
                + (wikiPageSlug != null ? "\n\nHãy bắt đầu bằng cách đọc trang wiki: " + wikiPageSlug : "")
                + (issueId != null ? "\nFocus vào issue ID: " + issueId : "");

        return Flux.from(
                chatClient.prompt()
                        .system(contextualSystem)
                        .user(enrichedMessage)
                        .tools(tools)
                        .stream()
                        .content()
        )
        .doOnError(e -> log.error("[WikiFixerService] Stream error: {}", e.getMessage()))
        .onErrorResume(e -> Flux.just("Đã xảy ra lỗi khi xử lý yêu cầu: " + e.getMessage()));
    }

    // ─── Per-request tool configuration ──────────────────────────────────────

    /**
     * Holds per-request context and provides @Tool-annotated methods
     * that the Spring AI ChatClient discovers and invokes automatically.
     */
    @Slf4j
    public static class WikiFixerTools {

        private final WikiPageRepository wikiPageRepository;
        private final WikiPageDraftRepository wikiPageDraftRepository;
        private final WikiIssueRepository wikiIssueRepository;
        private final WikiIssueService wikiIssueService;
        private final VectorStore vectorStore;
        private final String userId;
        private final String workspaceId;

        public WikiFixerTools(WikiPageRepository wikiPageRepository,
                               WikiPageDraftRepository wikiPageDraftRepository,
                               WikiIssueRepository wikiIssueRepository,
                               WikiIssueService wikiIssueService,
                               VectorStore vectorStore,
                               String userId,
                               String workspaceId) {
            this.wikiPageRepository = wikiPageRepository;
            this.wikiPageDraftRepository = wikiPageDraftRepository;
            this.wikiIssueRepository = wikiIssueRepository;
            this.wikiIssueService = wikiIssueService;
            this.vectorStore = vectorStore;
            this.userId = userId;
            this.workspaceId = workspaceId;
        }

        // ── Tool records ──────────────────────────────────────────────────────

        public record ReadWikiPageInput(String slug) {}
        public record ReadWikiPageOutput(String slug, String title, String content, String summary, boolean found) {}

        public record EditWikiPageInput(String slug, String newContent, String editNote) {}
        public record EditWikiPageOutput(String message, Long draftId) {}

        public record SearchWikiInput(String query) {}
        public record SearchWikiOutput(List<String> results, int count) {}

        public record KnowledgeSearchInput(String query) {}
        public record KnowledgeSearchOutput(List<String> results, int count) {}

        public record GetWikiIssuesInput(String slug) {}
        public record GetWikiIssuesOutput(List<WikiIssueDTO> issues, int count) {}

        public record ResolveIssueInput(Long issueId, String note) {}
        public record ResolveIssueOutput(String message) {}

        public record IgnoreIssueInput(Long issueId, String reason) {}
        public record IgnoreIssueOutput(String message) {}

        // ── Tool implementations ──────────────────────────────────────────────

        @Tool(name = "read_wiki_page",
              description = "Read the full content of a wiki page by its slug.")
        public ReadWikiPageOutput readWikiPage(ReadWikiPageInput input) {
            log.info("[WikiFixerTools] read_wiki_page: slug={}", input.slug());
            try {
                return wikiPageRepository.fetchBySlugAndWorkspaceId(input.slug(), workspaceId)
                        .map(page -> new ReadWikiPageOutput(
                                page.getSlug(), page.getTitle(),
                                page.getContent(), page.getSummary(), true))
                        .orElse(new ReadWikiPageOutput(input.slug(), null, null, null, false));
            } catch (Exception e) {
                log.warn("[WikiFixerTools] read_wiki_page failed: {}", e.getMessage());
                return new ReadWikiPageOutput(input.slug(), null, "Error: " + e.getMessage(), null, false);
            }
        }

        @Tool(name = "edit_wiki_page",
              description = "Propose an edit to a wiki page. Creates a PENDING draft for review.")
        public EditWikiPageOutput editWikiPage(EditWikiPageInput input) {
            log.info("[WikiFixerTools] edit_wiki_page: slug={}", input.slug());
            try {
                WikiPage existingPage = wikiPageRepository.fetchBySlugAndWorkspaceId(input.slug(), workspaceId)
                        .orElseThrow(() -> new IllegalArgumentException("Page not found: " + input.slug()));

                WikiPageDraft draft = WikiPageDraft.builder()
                        .wikiPageId(existingPage.getId())
                        .slug(existingPage.getSlug())
                        .title(existingPage.getTitle())
                        .content(input.newContent())
                        .summary(existingPage.getSummary())
                        .tags(existingPage.getTags())
                        .workspaceId(existingPage.getWorkspaceId())
                        .departmentId(existingPage.getDepartmentId())
                        .allowedRoles(existingPage.getAllowedRoles())
                        .securityClassification(existingPage.getSecurityClassification())
                        .pageType(existingPage.getPageType())
                        .sourceDocumentId(existingPage.getSourceDocumentId())
                        .authorId(userId)
                        .status(WikiPageDraftStatus.PENDING)
                        .baseVersion(existingPage.getVersion())
                        .note(input.editNote() != null ? input.editNote() : "Edited by Wiki Fixer Agent.")
                        .build();

                WikiPageDraft saved = wikiPageDraftRepository.save(draft);
                return new EditWikiPageOutput(
                        "Draft created (PENDING review) for page '" + input.slug() + "'", saved.getId());
            } catch (Exception e) {
                log.warn("[WikiFixerTools] edit_wiki_page failed: {}", e.getMessage());
                return new EditWikiPageOutput("Error creating draft: " + e.getMessage(), null);
            }
        }

        @Tool(name = "search_wiki",
              description = "Search for wiki pages by keyword. Returns a list of matching page titles and slugs.")
        public SearchWikiOutput searchWiki(SearchWikiInput input) {
            log.info("[WikiFixerTools] search_wiki: query='{}'", input.query());
            try {
                String keyword = input.query().toLowerCase();
                List<WikiPage> pages = wikiPageRepository.findByWorkspaceId(workspaceId);
                List<String> results = pages.stream()
                        .filter(p -> (p.getTitle() != null && p.getTitle().toLowerCase().contains(keyword))
                                || (p.getSummary() != null && p.getSummary().toLowerCase().contains(keyword))
                                || (p.getTags() != null && p.getTags().toLowerCase().contains(keyword)))
                        .limit(10)
                        .map(p -> String.format("[[%s|%s]] — %s", p.getSlug(), p.getTitle(),
                                p.getSummary() != null ? p.getSummary().substring(0, Math.min(100, p.getSummary().length())) : ""))
                        .collect(Collectors.toList());
                return new SearchWikiOutput(results, results.size());
            } catch (Exception e) {
                log.warn("[WikiFixerTools] search_wiki failed: {}", e.getMessage());
                return new SearchWikiOutput(List.of(), 0);
            }
        }

        @Tool(name = "searchKnowledge",
              description = "Search the internal knowledge base documents using semantic vector similarity.")
        public KnowledgeSearchOutput searchKnowledge(KnowledgeSearchInput input) {
            log.info("[WikiFixerTools] searchKnowledge: query='{}'", input.query());
            try {
                List<Document> docs = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(input.query())
                                .topK(5)
                                .similarityThreshold(0.2)
                                .build());
                List<String> texts = docs.stream()
                        .map(doc -> {
                            String fileName = (String) doc.getMetadata().getOrDefault("fileName", "Document");
                            return "[" + fileName + "]\n" + doc.getText();
                        })
                        .collect(Collectors.toList());
                return new KnowledgeSearchOutput(texts, texts.size());
            } catch (Exception e) {
                log.warn("[WikiFixerTools] searchKnowledge failed: {}", e.getMessage());
                return new KnowledgeSearchOutput(List.of(), 0);
            }
        }

        @Tool(name = "get_wiki_issues",
              description = "Get the list of quality issues for a specific wiki page.")
        public GetWikiIssuesOutput getWikiIssues(GetWikiIssuesInput input) {
            log.info("[WikiFixerTools] get_wiki_issues: slug={}", input.slug());
            try {
                List<WikiIssueDTO> issues = wikiIssueService.getIssuesByPage(input.slug(), workspaceId);
                return new GetWikiIssuesOutput(issues, issues.size());
            } catch (Exception e) {
                log.warn("[WikiFixerTools] get_wiki_issues failed: {}", e.getMessage());
                return new GetWikiIssuesOutput(List.of(), 0);
            }
        }

        @Tool(name = "resolve_issue",
              description = "Mark a wiki issue as FIXED after the fix has been applied.")
        public ResolveIssueOutput resolveIssue(ResolveIssueInput input) {
            log.info("[WikiFixerTools] resolve_issue: issueId={}", input.issueId());
            try {
                UpdateWikiIssueRequest req = UpdateWikiIssueRequest.builder()
                        .status(WikiIssueStatus.FIXED.name())
                        .resolvedNote(input.note() != null ? input.note() : "Fixed by Wiki Fixer Agent.")
                        .build();
                wikiIssueService.updateIssue(input.issueId(), req, userId);
                return new ResolveIssueOutput("Issue " + input.issueId() + " marked as FIXED.");
            } catch (Exception e) {
                log.warn("[WikiFixerTools] resolve_issue failed: {}", e.getMessage());
                return new ResolveIssueOutput("Error: " + e.getMessage());
            }
        }

        @Tool(name = "ignore_issue",
              description = "Mark a wiki issue as IGNORED if it is not a real problem or cannot be fixed.")
        public IgnoreIssueOutput ignoreIssue(IgnoreIssueInput input) {
            log.info("[WikiFixerTools] ignore_issue: issueId={}", input.issueId());
            try {
                UpdateWikiIssueRequest req = UpdateWikiIssueRequest.builder()
                        .status(WikiIssueStatus.IGNORED.name())
                        .resolvedNote(input.reason() != null ? input.reason() : "Ignored by Wiki Fixer Agent.")
                        .build();
                wikiIssueService.updateIssue(input.issueId(), req, userId);
                return new IgnoreIssueOutput("Issue " + input.issueId() + " marked as IGNORED.");
            } catch (Exception e) {
                log.warn("[WikiFixerTools] ignore_issue failed: {}", e.getMessage());
                return new IgnoreIssueOutput("Error: " + e.getMessage());
            }
        }
    }
}
