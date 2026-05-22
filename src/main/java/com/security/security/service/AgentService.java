package com.security.security.service;

import com.security.security.config.AgentToolConfig;
import com.security.security.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import com.security.security.client.MessagingServiceClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import com.security.security.provider.LlmFactory;
import com.security.security.provider.LlmProvider;
import com.security.security.entity.AgentSkill;
import com.security.security.service.AgentSkillService;
import com.security.security.repository.WikiPageRepository;

/**
 * Phase 2 — Autonomous AI Agent Service.
 *
 * Uses Spring AI Function Calling (tool use) to let Gemini autonomously
 * decide which tools to invoke based on the user's query.
 *
 * Registered tools (from AgentToolConfig):
 * - searchKnowledge : RAG vector search
 * - summarizeChat : fetch + summarize recent messages
 * - createTask : create task in messaging-service
 * - getChatInfo : get chat metadata
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentService {

        private final LlmFactory llmFactory;
        private final ConversationService conversationService;
        private final AgentSkillService agentSkillService;
        private final VectorStore vectorStore;
        private final MessagingServiceClient messagingClient;
        private final WikiPageRepository wikiPageRepository;

        private static final String AGENT_SYSTEM_PROMPT = """
                            Bạn là AI Assistant của OTT Chat Platform. Bạn có khả năng truy cập công cụ để hỗ trợ người dùng.
                
                            ## CÔNG CỤ CỦA BẠN
                            - **searchKnowledge**: Tìm kiếm tài liệu bằng vector (RAG).
                            - **search_wiki**: Tìm kiếm các trang Wiki (Knowledge Graph).
                            - **read_wiki_page**: Đọc chi tiết nội dung 1 trang Wiki.
                            - **list_wiki_pages**: Xem danh sách các trang Wiki hiện có.
                            - **create_wiki_page**: Tạo trang Wiki mới.
                            - **edit_wiki_page**: Chỉnh sửa trang Wiki.
                            - **summarizeChat**: Tóm tắt tin nhắn gần đây.
                            - **createTask**: Tạo task công việc.
                            - **getChatInfo**: Lấy thông tin nhóm/chat.
                
                            ## NGUYÊN TẮC TỐI THƯỢNG:
                            - CHỈ TRẢ VỀ JSON. Bắt đầu bằng '{' và kết thúc bằng '}'.
                            - KHÔNG giải thích, KHÔNG lập kế hoạch (Plan), KHÔNG suy nghĩ (Reasoning).
                            - KHÔNG markdown, KHÔNG ```json.
                            - Nếu vi phạm, hệ thống sẽ lỗi. Hãy cẩn thận.
                
                            ## ĐỊNH DẠNG JSON:
                            {
                              "summary": "Nội dung tiếng Việt",
                              "details": ["Chi tiết 1", "..."],
                              "sources": ["Nguồn"]
                            }
                            """;

        /**
         * Run the agent with Function Calling enabled.
         * Streams response tokens as they arrive from Gemini.
         *
         * @param conversationId chat memory conversation ID
         * @param message        user's natural-language query
         * @param userId         authenticated user ID
         * @param chatId         current chat room context (passed to tools via system
         *                       prompt)
         * @param providerName   name of the LLM provider to use (e.g., gemini, openai)
         * @param skillId        optional ID of custom agent skill
         * @return Flux of text tokens for SSE streaming
         */
        public Flux<String> runAgent(Long conversationId, String message, String userId, String chatId, String providerName, Long skillId) {
                log.info("[Agent] Running for userId={}, chatId={}, skillId={}, query='{}'", userId, chatId, skillId, message);

                String basePrompt = AGENT_SYSTEM_PROMPT;
                if (skillId != null) {
                        basePrompt = agentSkillService.getSkillById(skillId)
                                .map(AgentSkill::getSystemPrompt)
                                .orElse(AGENT_SYSTEM_PROMPT);
                }

                // Inject chatId into system context so tools can reference it without asking
                // LLM to extract it
                String systemWithContext = basePrompt + "\n\n## Context\nChatId hiện tại: " + chatId
                                + "\nUserId: " + userId;

                // Save user message to conversation history
                conversationService.saveMessage(conversationId, "user", message, null, null);

                // Instantiate tool config with the current user ID
                AgentToolConfig toolConfig = new AgentToolConfig(vectorStore, messagingClient, wikiPageRepository, userId);

                StringBuilder fullResponse = new StringBuilder();

                // Strict instruction appended to user message to prevent reasoning/plans
                String strictUserMessage = message + "\n\n(Chỉ trả về JSON, không giải thích, không lập kế hoạch)";

                java.util.concurrent.atomic.AtomicBoolean jsonStarted = new java.util.concurrent.atomic.AtomicBoolean(false);

                LlmProvider provider = llmFactory.getProvider(providerName);

                return Flux.from(provider.streamChat(systemWithContext, strictUserMessage, toolConfig, conversationId.toString()))
                                .map(token -> {
                                        if (jsonStarted.get())
                                                return token;
                                        int braceIdx = token.indexOf("{");
                                        if (braceIdx != -1) {
                                                jsonStarted.set(true);
                                                return token.substring(braceIdx);
                                        }
                                        return "";
                                })
                                .filter(token -> !token.isEmpty())
                                .doOnNext(fullResponse::append)
                                .doOnComplete(() -> {
                                        // Persist assistant response to DB
                                        conversationService.saveMessage(
                                                        conversationId,
                                                        "assistant",
                                                        fullResponse.toString(),
                                                        null,
                                                        null);

                                        // Auto-generate title if it's the first message pair
                                        java.util.List<com.security.security.entity.Message> msgs = conversationService.getMessages(conversationId);
                                        if (msgs.size() <= 2) {
                                                conversationService.updateConversationTitle(conversationId, message);
                                        }

                                        log.info("[Agent] Completed. chars={}", fullResponse.length());
                                })
                                .doOnError(e -> log.error("[Agent] Error during agent run", e));
        }
}
