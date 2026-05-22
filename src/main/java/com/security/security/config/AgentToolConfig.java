package com.security.security.config;

import com.security.security.client.MessagingServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import com.security.security.repository.WikiPageRepository;
import com.security.security.entity.WikiPage;

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
    private final String userId;

    public AgentToolConfig(VectorStore vectorStore, MessagingServiceClient messagingClient, WikiPageRepository wikiPageRepository, String userId) {
        this.vectorStore = vectorStore;
        this.messagingClient = messagingClient;
        this.wikiPageRepository = wikiPageRepository;
        this.userId = userId;
    }

    public record KnowledgeSearchInput(String query) {}
    public record KnowledgeSearchOutput(List<String> results, int count) {}

    @Tool(name = "searchKnowledge", description = "Search the internal knowledge base documents using semantic similarity.")
    public KnowledgeSearchOutput searchKnowledge(KnowledgeSearchInput input) {
        log.info("[Agent Tool] searchKnowledge: query='{}'", input.query());
        try {
            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(input.query())
                            .topK(5)
                            .similarityThreshold(0.2)
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

    public record CreateTaskInput(String chatId, String title, String description) {}
    public record CreateTaskOutput(boolean success, String taskId, String message) {}

    @Tool(name = "createTask", description = "Create a new task in the current chat.")
    public CreateTaskOutput createTask(CreateTaskInput input) {
        log.info("[Agent Tool] createTask: chatId='{}', title='{}', userId='{}'", input.chatId(), input.title(), userId);
        if (input.chatId() == null || input.chatId().isBlank()) {
            return new CreateTaskOutput(false, null, "Không thể tạo task: không có chatId.");
        }
        String taskId = messagingClient.createTask(input.chatId(), input.title(), input.description(), userId);
        if (taskId != null) {
            return new CreateTaskOutput(true, taskId, "✅ Task \"" + input.title() + "\" đã được tạo thành công.");
        }
        return new CreateTaskOutput(false, null, "Không thể tạo task. Vui lòng thử lại.");
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
            var docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(input.query())
                            .topK(5)
                            .filterExpression("type == 'wiki'")
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
                .map(page -> new ReadWikiPageOutput(page.getTitle(), page.getContent(), page.getTags()))
                .orElse(new ReadWikiPageOutput("Not Found", "Không tìm thấy trang wiki với ID này.", ""));
    }

    public record ListWikiPagesInput(String workspaceId) {}
    public record ListWikiPagesOutput(List<String> pages) {}

    @Tool(name = "list_wiki_pages", description = "List all available Wiki pages in the workspace.")
    public ListWikiPagesOutput listWikiPages(ListWikiPagesInput input) {
        log.info("[Agent Tool] listWikiPages: workspaceId={}", input.workspaceId());
        String wsId = input.workspaceId() != null ? input.workspaceId() : "default-workspace";
        List<String> pages = wikiPageRepository.findByWorkspaceId(wsId).stream()
                .map(p -> String.format("ID: %d | Title: %s", p.getId(), p.getTitle()))
                .collect(Collectors.toList());
        return new ListWikiPagesOutput(pages);
    }

    public record CreateWikiPageInput(String title, String content, String tags, String workspaceId) {}
    public record CreateWikiPageOutput(boolean success, Long id, String message) {}

    @Tool(name = "create_wiki_page", description = "Create a new Knowledge Wiki page.")
    public CreateWikiPageOutput createWikiPage(CreateWikiPageInput input) {
        log.info("[Agent Tool] createWikiPage: title='{}'", input.title());
        try {
            WikiPage page = WikiPage.builder()
                    .title(input.title())
                    .content(input.content())
                    .tags(input.tags())
                    .workspaceId(input.workspaceId() != null ? input.workspaceId() : "default-workspace")
                    .pageType("concept")
                    .build();
            WikiPage saved = wikiPageRepository.save(page);
            return new CreateWikiPageOutput(true, saved.getId(), "Tạo trang wiki thành công");
        } catch (Exception e) {
            log.error("[Agent Tool] createWikiPage error", e);
            return new CreateWikiPageOutput(false, null, "Lỗi khi tạo trang wiki: " + e.getMessage());
        }
    }

    public record EditWikiPageInput(Long id, String title, String content, String tags) {}
    public record EditWikiPageOutput(boolean success, String message) {}

    @Tool(name = "edit_wiki_page", description = "Edit an existing Knowledge Wiki page.")
    public EditWikiPageOutput editWikiPage(EditWikiPageInput input) {
        log.info("[Agent Tool] editWikiPage: id={}", input.id());
        return wikiPageRepository.findById(input.id()).map(page -> {
            if (input.title() != null) page.setTitle(input.title());
            if (input.content() != null) page.setContent(input.content());
            if (input.tags() != null) page.setTags(input.tags());
            wikiPageRepository.save(page);
            return new EditWikiPageOutput(true, "Cập nhật trang wiki thành công");
        }).orElse(new EditWikiPageOutput(false, "Không tìm thấy trang wiki để cập nhật"));
    }
}
