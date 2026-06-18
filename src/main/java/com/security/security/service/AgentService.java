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
import com.security.security.repository.WikiPageDraftRepository;

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
        private final WikiPageDraftRepository wikiPageDraftRepository;

        private static final String AGENT_SYSTEM_PROMPT = """
                            Bạn là AI Assistant của OTT Chat Platform. Bạn có khả năng truy cập công cụ để hỗ trợ người dùng.

                            ## CÔNG CỤ CỦA BẠN
                            - **searchKnowledge**: Tìm kiếm tài liệu bằng vector (RAG). Hãy luôn sử dụng công cụ này khi người dùng hỏi các câu hỏi tra cứu tài liệu, quy trình, chính sách, hướng dẫn nội bộ hoặc thông tin nghiệp vụ của tổ chức.
                            - **search_wiki**: Tìm kiếm các trang Wiki (Knowledge Graph).
                            - **read_wiki_page**: Đọc chi tiết nội dung 1 trang Wiki bằng ID.
                            - **list_wiki_pages**: Xem danh sách các trang Wiki hiện có.
                            - **create_wiki_page**: Đề xuất tạo trang Wiki mới. Kết quả luôn là bản thảo PENDING chờ Admin phê duyệt — không publish trực tiếp. Hãy điền trường `note` để giải thích lý do tạo trang.
                            - **edit_wiki_page**: Đề xuất chỉnh sửa trang Wiki theo ID. Kết quả luôn là bản thảo PENDING chờ Admin phê duyệt — không thay đổi nội dung trực tiếp. Hãy điền trường `note` để giải thích lý do chỉnh sửa.
                            - **summarizeChat**: Tóm tắt tin nhắn gần đây.
                            - **createTask**: Tạo task công việc trong cuộc hội thoại hiện tại.
                            - **listTasks**: Lấy danh sách tất cả các task công việc/kế hoạch trong phòng chat hiện tại.
                            - **updateTaskStatus**: Cập nhật trạng thái của một task công việc cụ thể (ví dụ: hoàn thành, đang làm, hủy). Các trạng thái hợp lệ: TODO, IN_PROGRESS, DONE, CANCELLED.
                            - **createPoll**: Tạo một cuộc bình chọn/khảo sát trực tiếp (poll) trong phòng chat hiện tại (yêu cầu ít nhất 2 lựa chọn, tối đa 10). Tham số `endsAt` (ISO 8601 string) là tùy chọn: CHỈ truyền `endsAt` khi người dùng yêu cầu rõ ràng thời gian kết thúc (ví dụ: "trong 10 phút", "hết ngày"). Nếu người dùng không nhắc đến thời gian kết thúc, hãy để `endsAt` là null hoặc chuỗi trống để cuộc bình chọn mở vô hạn (không giới hạn thời gian). TUYỆT ĐỐI không tự ý lấy thời gian hiện tại gán cho `endsAt` vì sẽ gây hết hạn ngay lập tức!
                            - **togglePinMessage**: Ghim hoặc bỏ ghim một tin nhắn bất kỳ trong cuộc hội thoại dựa trên messageId (thực hiện ghim nếu chưa ghim, bỏ ghim nếu đã ghim).
                            - **getPinnedMessages**: Lấy danh sách toàn bộ các tin nhắn đã được ghim/quan trọng trong phòng chat hiện tại.
                            - **searchMessages**: Tìm kiếm các tin nhắn cũ trong lịch sử trò chuyện của phòng chat hiện tại dựa trên từ khóa tìm kiếm (query).
                            - **getChatInfo**: Lấy thông tin nhóm/chat hiện tại (tên nhóm, số lượng thành viên).

                            ## NGUYÊN TẮC VẬN HÀNH:
                            1. Khi người dùng yêu cầu tìm kiếm, tra cứu tài liệu, hỏi về quy trình hoặc chính sách nội bộ, bạn BẮT BUỘC phải gọi công cụ `searchKnowledge` đầu tiên để có dữ liệu chính xác trước khi trả lời.
                            2. Kết hợp thông tin lấy được từ các công cụ để biên soạn câu trả lời đầy đủ, chi tiết. Điền tên các tài liệu tìm được vào trường `"sources"`.
                            3. CHỈ TRẢ VỀ JSON. Bắt đầu bằng '{' và kết thúc bằng '}'.
                            4. TUYỆT ĐỐI KHÔNG giải thích dông dài bên ngoài JSON, KHÔNG lập kế hoạch (Plan), KHÔNG tự suy nghĩ (Reasoning) bằng ngôn từ tự do bên ngoài cấu trúc JSON.
                            5. KHÔNG viết định dạng markdown (ví dụ: không dùng ```json và ```).

                            ## ĐỊNH DẠNG JSON BẮT BUỘC:
                            {
                              "summary": "Tóm tắt câu trả lời (bằng tiếng Việt)",
                              "details": ["Chi tiết 1", "Chi tiết 2", "..."],
                              "sources": ["Tên tài liệu hoặc nguồn gốc thông tin"]
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
        public Flux<String> runAgent(Long conversationId, String message, String userId, String chatId, String providerName, Long skillId, String workspaceId) {
                log.info("[Agent] Running for userId={}, chatId={}, workspaceId={}, skillId={}, query='{}'", userId, chatId, workspaceId, skillId, message);

                String basePrompt = AGENT_SYSTEM_PROMPT;
                if (skillId != null) {
                        basePrompt = agentSkillService.getSkillById(skillId)
                                .map(AgentSkill::getSystemPrompt)
                                .orElse(AGENT_SYSTEM_PROMPT);
                }

                // Inject chatId and workspaceId into system context so tools can reference it without asking LLM
                String systemWithContext = basePrompt + "\n\n## Context\nChatId hiện tại: " + chatId
                                + "\nWorkspaceId hiện tại: " + (workspaceId != null ? workspaceId : "Không có")
                                + "\nUserId: " + userId;

                // Save user message to conversation history
                conversationService.saveMessage(conversationId, "user", message, null, null);

                // Instantiate tool config with the current user ID and workspace ID
                AgentToolConfig toolConfig = new AgentToolConfig(vectorStore, messagingClient, wikiPageRepository, wikiPageDraftRepository, userId, workspaceId);

                StringBuilder fullResponse = new StringBuilder();

                // Strict instruction appended to user message to prevent reasoning/plans
                String strictUserMessage = message + "\n\n(Chỉ trả về JSON, không giải thích, không lập kế hoạch)";

                java.util.concurrent.atomic.AtomicBoolean jsonStarted = new java.util.concurrent.atomic.AtomicBoolean(false);

                LlmProvider provider = llmFactory.getProvider(providerName);

                final Long finalConversationId = conversationId;

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
                                .doOnError(e -> log.error("[Agent] Stream error: {}", e.getMessage()))
                                .onErrorResume(e -> Flux.empty())
                                .doFinally(signal -> {
                                        if (fullResponse.length() > 0) {
                                                conversationService.saveMessage(
                                                                finalConversationId,
                                                                "assistant",
                                                                fullResponse.toString(),
                                                                null,
                                                                null);
                                        }

                                        java.util.List<com.security.security.entity.Message> msgs = conversationService.getMessages(finalConversationId);
                                        if (msgs.size() <= 2) {
                                                conversationService.updateConversationTitle(finalConversationId, message);
                                        }

                                        log.info("[Agent] Finished (signal={}). chars={}", signal, fullResponse.length());
                                });
        }
}
