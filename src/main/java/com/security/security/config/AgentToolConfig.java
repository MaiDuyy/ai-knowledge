package com.security.security.config;

import com.security.security.client.MessagingServiceClient;
import com.security.security.entity.WikiPageDraft;
import com.security.security.repository.WikiPageDraftRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import com.security.security.repository.WikiPageRepository;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.entity.enumeration.WikiPageDraftStatus;
import com.security.security.service.RAGService;
import com.security.security.dtorequest.RAGQueryPayload;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 2 — Agent Tool Registry using @Tool annotation.
 * This class is instantiated per-request in AgentService to hold the current user context.
 */
@Slf4j
public class AgentToolConfig {

    private final VectorStore vectorStore;
    private final MessagingServiceClient messagingClient;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageDraftRepository wikiPageDraftRepository;
    private final RAGService ragService;
    private final String userId;
    private final String workspaceId;
    private final RAGQueryPayload.UserPermissionContext permissions;

    public AgentToolConfig(
            VectorStore vectorStore,
            MessagingServiceClient messagingClient,
            WikiPageRepository wikiPageRepository,
            WikiPageDraftRepository wikiPageDraftRepository,
            RAGService ragService,
            String userId,
            String workspaceId,
            RAGQueryPayload.UserPermissionContext permissions) {
        this.vectorStore = vectorStore;
        this.messagingClient = messagingClient;
        this.wikiPageRepository = wikiPageRepository;
        this.wikiPageDraftRepository = wikiPageDraftRepository;
        this.ragService = ragService;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.permissions = permissions;
    }

    public record KnowledgeSearchInput(String query) {}
    public record KnowledgeSearchOutput(List<String> results, int count) {}

    @Tool(name = "searchKnowledge", description = "Search the internal knowledge base documents using semantic similarity.")
    public KnowledgeSearchOutput searchKnowledge(KnowledgeSearchInput input) {
        log.info("[Agent Tool] searchKnowledge: query='{}'", input.query());
        try {
            String filterExprStr = ragService.getFilterExpressionStr(permissions, userId);
            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(input.query())
                            .topK(5)
                            .similarityThreshold(0.2)
                            .filterExpression(filterExprStr)
                            .build()
            );
            List<String> texts = docs.stream()
                    .map(doc -> {
                        String fileName = (String) doc.getMetadata().getOrDefault("fileName", "Document");
                        return "[" + fileName + "]\n" + doc.getText();
                    })
                    .collect(Collectors.toList());
            return new KnowledgeSearchOutput(texts, texts.size());
        } catch (Exception e) {
            log.error("[Agent Tool] searchKnowledge error", e);
            return new KnowledgeSearchOutput(List.of(), 0);
        }
    }

    public record SummarizeChatInput(String chatId, int messageCount) {}
    public record SummarizeChatOutput(String summary, int messagesSummarized) {}

    @Tool(name = "summarizeChat", description = "Fetch and summarize the most recent messages from a chat conversation.")
    public SummarizeChatOutput summarizeChat(SummarizeChatInput input) {
        int limit = Math.min(Math.max(input.messageCount(), 10), 100);
        log.info("[Agent Tool] summarizeChat: chatId='{}', limit={}, userId='{}'", input.chatId(), limit, userId);
        List<String> messages = messagingClient.getRecentMessages(input.chatId(), limit, userId);
        if (messages.isEmpty()) {
            return new SummarizeChatOutput("Không có tin nhắn nào trong cuộc trò chuyện này.", 0);
        }
        String joined = String.join("\n", messages);
        return new SummarizeChatOutput(joined, messages.size());
    }

    public record CreateTaskInput(
            String chatId,
            String title,
            String description,
            String deadlineAt,
            List<String> assigneeIds
    ) {}
    public record CreateTaskOutput(boolean success, String taskId, String message) {}

    @Tool(name = "createTask", description = "Create a new task in the current chat conversation. You can optionally specify deadlineAt (ISO 8601 string) and assigneeIds (list of account IDs from getChatInfo).")
    public CreateTaskOutput createTask(CreateTaskInput input) {
        log.info("[Agent Tool] createTask: chatId='{}', title='{}', userId='{}'", input.chatId(), input.title(), userId);
        if (input.chatId() == null || input.chatId().isBlank()) {
            return new CreateTaskOutput(false, null, "Không thể tạo task: không có chatId.");
        }
        Map<String, Object> result = messagingClient.createTask(
                input.chatId(),
                input.title(),
                input.description(),
                input.deadlineAt(),
                input.assigneeIds(),
                userId
        );
        boolean success = (Boolean) result.getOrDefault("success", false);
        String taskId = (String) result.getOrDefault("taskId", "");
        String message = (String) result.getOrDefault("message", "Không thể tạo task.");

        if (success) {
            return new CreateTaskOutput(true, taskId, "✅ Task \"" + input.title() + "\" đã được tạo thành công.");
        }
        return new CreateTaskOutput(false, null, "❌ Không thể tạo task: " + message);
    }

    public record CreatePollInput(
            String chatId,
            String title,
            List<String> options,
            String endsAt
    ) {}
    public record CreatePollOutput(boolean success, String pollId, String message) {}

    @Tool(name = "createPoll", description = "Create a new poll/survey in the current chat room. The poll has a title and a list of options (at least 2 options, up to 10). The parameter 'endsAt' (ISO 8601 string) is strictly optional. Only provide endsAt if the user explicitly requested a specific end time (e.g., 'in 10 minutes', 'until tomorrow'). If the user did not mention any deadline, leave endsAt null or empty so the poll stays open indefinitely. NEVER set endsAt to the current time.")
    public CreatePollOutput createPoll(CreatePollInput input) {
        log.info("[Agent Tool] createPoll: chatId='{}', title='{}', userId='{}'", input.chatId(), input.title(), userId);
        if (input.chatId() == null || input.chatId().isBlank()) {
            return new CreatePollOutput(false, null, "Không thể tạo cuộc bình chọn: không có chatId.");
        }
        if (input.title() == null || input.title().isBlank()) {
            return new CreatePollOutput(false, null, "Không thể tạo cuộc bình chọn: tiêu đề trống.");
        }
        if (input.options() == null || input.options().size() < 2) {
            return new CreatePollOutput(false, null, "Không thể tạo cuộc bình chọn: phải có ít nhất 2 lựa chọn.");
        }

        Map<String, Object> result = messagingClient.createPoll(
                input.chatId(),
                input.title(),
                input.options(),
                input.endsAt(),
                userId
        );
        boolean success = (Boolean) result.getOrDefault("success", false);
        String pollId = (String) result.getOrDefault("pollId", "");
        String message = (String) result.getOrDefault("message", "Không thể tạo cuộc bình chọn.");

        if (success) {
            return new CreatePollOutput(true, pollId, "✅ Cuộc bình chọn \"" + input.title() + "\" đã được tạo thành công.");
        }
        return new CreatePollOutput(false, null, "❌ Không thể tạo cuộc bình chọn: " + message);
    }

    public record GetChatInfoInput(String chatId) {}
    public record GetChatInfoOutput(String name, boolean isGroup, int participantCount) {}

    @Tool(name = "getChatInfo", description = "Get information about the current chat or group.")
    public GetChatInfoOutput getChatInfo(GetChatInfoInput input) {
        log.info("[Agent Tool] getChatInfo: chatId='{}', userId='{}'", input.chatId(), userId);
        Map<String, Object> info = messagingClient.getChatInfo(input.chatId(), userId);
        return new GetChatInfoOutput(
                (String) info.getOrDefault("name", "Unknown"),
                (Boolean) info.getOrDefault("isGroup", false),
                (Integer) info.getOrDefault("participantCount", 0)
        );
    }

    public record SearchWikiInput(String query) {}
    public record SearchWikiOutput(List<String> results) {}

    @Tool(name = "search_wiki", description = "Search the Knowledge Wiki pages by keyword or semantic similarity.")
    public SearchWikiOutput searchWiki(SearchWikiInput input) {
        log.info("[Agent Tool] searchWiki: query='{}'", input.query());
        try {
            String permExprStr = ragService.getFilterExpressionStr(permissions, userId);
            String finalExprStr = "type == 'wiki' && (" + permExprStr + ")";

            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(input.query())
                            .topK(5)
                            .filterExpression(finalExprStr)
                            .build()
            );
            List<String> texts = docs.stream()
                    .map(doc -> doc.getText())
                    .collect(Collectors.toList());
            return new SearchWikiOutput(texts);
        } catch (Exception e) {
            log.error("[Agent Tool] searchWiki error", e);
            return new SearchWikiOutput(List.of());
        }
    }

    public record ReadWikiPageInput(Long id) {}
    public record ReadWikiPageOutput(String title, String content, String tags) {}

    @Tool(name = "read_wiki_page", description = "Read the full content of a specific Wiki page by ID.")
    public ReadWikiPageOutput readWikiPage(ReadWikiPageInput input) {
        log.info("[Agent Tool] readWikiPage: id={}", input.id());
        return wikiPageRepository.findById(input.id())
                .map(page -> {
                    // RBAC: check same permission model as RAGService pipeline
                    if (!ragService.isPageAccessible(page, permissions, userId)) {
                        log.warn("[Agent Tool] readWikiPage: access denied for userId={} on page id={}", userId, input.id());
                        return new ReadWikiPageOutput("Access Denied", "Bạn không có quyền xem trang wiki này.", "");
                    }
                    return new ReadWikiPageOutput(page.getTitle(), page.getContent(), page.getTags());
                })
                .orElse(new ReadWikiPageOutput("Not Found", "Không tìm thấy trang wiki với ID này.", ""));
    }

    public record ListWikiPagesInput(String workspaceId) {}
    public record ListWikiPagesOutput(List<String> pages) {}

    @Tool(name = "list_wiki_pages", description = "List all available Wiki pages in the workspace.")
    public ListWikiPagesOutput listWikiPages(ListWikiPagesInput input) {
        log.info("[Agent Tool] listWikiPages: workspaceId={}", input.workspaceId());
        String wsId = input.workspaceId() != null && !input.workspaceId().isBlank()
                ? input.workspaceId()
                : (this.workspaceId != null && !this.workspaceId.isBlank() ? this.workspaceId : "default-workspace");
        // RBAC: filter pages the current user is allowed to see
        List<String> pages = wikiPageRepository.findByWorkspaceId(wsId).stream()
                .filter(p -> ragService.isPageAccessible(p, permissions, userId))
                .map(p -> String.format("ID: %d | Title: %s", p.getId(), p.getTitle()))
                .collect(Collectors.toList());
        return new ListWikiPagesOutput(pages);
    }

    public record CreateWikiPageInput(String title, String content, String tags, String workspaceId, String note) {}
    public record CreateWikiPageOutput(boolean success, Long draftId, String message) {}

    @Tool(name = "create_wiki_page", description = "Propose a new Knowledge Wiki page. This always creates a PENDING draft that requires admin approval before being published. Never directly publishes content.")
    public CreateWikiPageOutput createWikiPage(CreateWikiPageInput input) {
        log.info("[Agent Tool] createWikiPage (draft): title='{}', userId='{}'", input.title(), userId);
        try {
            String wsId = input.workspaceId() != null && !input.workspaceId().isBlank()
                    ? input.workspaceId()
                    : (this.workspaceId != null && !this.workspaceId.isBlank() ? this.workspaceId : "default-workspace");

            // Generate a slug from title (simple ASCII-safe slugify)
            String slug = input.title().toLowerCase()
                    .replaceAll("[^a-z0-9\\s-]", "")
                    .replaceAll("\\s+", "-")
                    .replaceAll("-+", "-")
                    .trim();

            // Create a draft (PENDING) — never publish directly
            WikiPageDraft draft = WikiPageDraft.builder()
                    .title(input.title())
                    .slug(slug)
                    .content(input.content())
                    .tags(input.tags())
                    .workspaceId(wsId)
                    .authorId(userId)
                    .pageType(WikiPageType.CONCEPT)
                    .status(WikiPageDraftStatus.PENDING)
                    .note(input.note() != null ? input.note() : "Đề xuất từ AI Agent")
                    .build();

            WikiPageDraft saved = wikiPageDraftRepository.save(draft);
            return new CreateWikiPageOutput(true, saved.getId(),
                    "Bản thảo wiki \"" + input.title() + "\" đã được gửi chờ Admin phê duyệt (Draft ID: " + saved.getId() + ").");
        } catch (Exception e) {
            log.error("[Agent Tool] createWikiPage draft error", e);
            return new CreateWikiPageOutput(false, null, "Lỗi khi tạo bản thảo wiki: " + e.getMessage());
        }
    }

    public record EditWikiPageInput(Long id, String title, String content, String tags, String note) {}
    public record EditWikiPageOutput(boolean success, Long draftId, String message) {}

    @Tool(name = "edit_wiki_page", description = "Propose an edit to an existing Knowledge Wiki page by its ID. This always creates a PENDING draft for admin review, not a direct edit.")
    public EditWikiPageOutput editWikiPage(EditWikiPageInput input) {
        log.info("[Agent Tool] editWikiPage (draft): id={}, userId='{}'", input.id(), userId);
        return wikiPageRepository.findById(input.id()).map(page -> {
            // RBAC: user must be able to read the page before proposing an edit
            if (!ragService.isPageAccessible(page, permissions, userId)) {
                log.warn("[Agent Tool] editWikiPage: access denied for userId={} on page id={}", userId, input.id());
                return new EditWikiPageOutput(false, null, "Bạn không có quyền chỉnh sửa trang wiki này.");
            }
            try {
                // Build draft based on current page, applying requested changes
                WikiPageDraft draft = WikiPageDraft.builder()
                        .wikiPageId(page.getId())
                        .slug(page.getSlug())
                        .title(input.title() != null ? input.title() : page.getTitle())
                        .content(input.content() != null ? input.content() : page.getContent())
                        .tags(input.tags() != null ? input.tags() : page.getTags())
                        .workspaceId(page.getWorkspaceId())
                        .pageType(page.getPageType())
                        .authorId(userId)
                        .status(WikiPageDraftStatus.PENDING)
                        .baseVersion(page.getVersion())
                        .note(input.note() != null ? input.note() : "Chỉnh sửa đề xuất từ AI Agent")
                        .build();

                WikiPageDraft saved = wikiPageDraftRepository.save(draft);
                return new EditWikiPageOutput(true, saved.getId(),
                        "Bản thảo chỉnh sửa trang \"" + page.getTitle() + "\" đã gửi chờ Admin phê duyệt (Draft ID: " + saved.getId() + ").");
            } catch (Exception e) {
                log.error("[Agent Tool] editWikiPage draft error", e);
                return new EditWikiPageOutput(false, null, "Lỗi khi tạo bản thảo chỉnh sửa: " + e.getMessage());
            }
        }).orElse(new EditWikiPageOutput(false, null, "Không tìm thấy trang wiki với ID: " + input.id()));
    }

    public record ListTasksInput(String chatId) {}
    public record ListTasksOutput(boolean success, List<Map<String, Object>> tasks, String message) {}

    @Tool(name = "listTasks", description = "Get the list of all active plans/tasks in the current chat room.")
    public ListTasksOutput listTasks(ListTasksInput input) {
        log.info("[Agent Tool] listTasks: chatId='{}'", input.chatId());
        if (input.chatId() == null || input.chatId().isBlank()) {
            return new ListTasksOutput(false, List.of(), "Không thể lấy danh sách task: không có chatId.");
        }
        List<Map<String, Object>> tasks = messagingClient.getTasks(input.chatId(), userId);
        return new ListTasksOutput(true, tasks, "Thành công");
    }

    public record UpdateTaskStatusInput(String taskId, String status, String chatId) {}
    public record UpdateTaskStatusOutput(boolean success, String message) {}

    @Tool(name = "updateTaskStatus", description = "Update the status of a specific task. Allowed status values: TODO, IN_PROGRESS, DONE, CANCELLED.")
    public UpdateTaskStatusOutput updateTaskStatus(UpdateTaskStatusInput input) {
        log.info("[Agent Tool] updateTaskStatus: taskId='{}', status='{}', chatId='{}'", input.taskId(), input.status(), input.chatId());
        if (input.taskId() == null || input.taskId().isBlank()) {
            return new UpdateTaskStatusOutput(false, "Không thể cập nhật task: không có taskId.");
        }
        if (input.status() == null || input.status().isBlank()) {
            return new UpdateTaskStatusOutput(false, "Không thể cập nhật task: trạng thái mới trống.");
        }
        Map<String, Object> res = messagingClient.updateTaskStatus(input.taskId(), input.status().toUpperCase(), input.chatId(), userId);
        boolean success = (Boolean) res.getOrDefault("success", false);
        String msg = (String) res.getOrDefault("message", "Lỗi không xác định.");

        return new UpdateTaskStatusOutput(success, msg);
    }

    public record TogglePinMessageInput(String messageId) {}
    public record TogglePinMessageOutput(boolean success, boolean pin, String message) {}

    @Tool(name = "togglePinMessage", description = "Toggle pin state of a specific message (pins if unpinned, unpins if pinned) by its unique messageId.")
    public TogglePinMessageOutput togglePinMessage(TogglePinMessageInput input) {
        log.info("[Agent Tool] togglePinMessage: messageId='{}', userId='{}'", input.messageId(), userId);
        if (input.messageId() == null || input.messageId().isBlank()) {
            return new TogglePinMessageOutput(false, false, "Không thể ghim tin nhắn: không có messageId.");
        }
        Map<String, Object> result = messagingClient.togglePinMessage(input.messageId(), userId);
        boolean success = (Boolean) result.getOrDefault("success", false);
        boolean pin = (Boolean) result.getOrDefault("pin", false);
        String message = (String) result.getOrDefault("message", "Lỗi xử lý.");

        return new TogglePinMessageOutput(success, pin, message);
    }

    public record GetPinnedMessagesInput(String chatId) {}
    public record GetPinnedMessagesOutput(boolean success, List<Map<String, Object>> pinnedMessages, String message) {}

    @Tool(name = "getPinnedMessages", description = "Get list of all pinned/important messages in the current chat room.")
    public GetPinnedMessagesOutput getPinnedMessages(GetPinnedMessagesInput input) {
        log.info("[Agent Tool] getPinnedMessages: chatId='{}'", input.chatId());
        if (input.chatId() == null || input.chatId().isBlank()) {
            return new GetPinnedMessagesOutput(false, List.of(), "Không thể lấy tin nhắn ghim: không có chatId.");
        }
        List<Map<String, Object>> messages = messagingClient.getPinnedMessages(input.chatId(), userId);
        return new GetPinnedMessagesOutput(true, messages, "Thành công");
    }

    public record SearchMessagesInput(String chatId, String query) {}
    public record SearchMessagesOutput(boolean success, List<Map<String, Object>> messages, String message) {}

    @Tool(name = "searchMessages", description = "Search chat history messages in the current chat room by keyword query.")
    public SearchMessagesOutput searchMessages(SearchMessagesInput input) {
        log.info("[Agent Tool] searchMessages: chatId='{}', query='{}'", input.chatId(), input.query());
        if (input.chatId() == null || input.chatId().isBlank()) {
            return new SearchMessagesOutput(false, List.of(), "Không thể tìm kiếm: không có chatId.");
        }
        if (input.query() == null || input.query().isBlank()) {
            return new SearchMessagesOutput(false, List.of(), "Không thể tìm kiếm: từ khóa trống.");
        }
        List<Map<String, Object>> messages = messagingClient.searchMessages(input.chatId(), input.query(), userId);
        return new SearchMessagesOutput(true, messages, "Thành công");
    }
}
