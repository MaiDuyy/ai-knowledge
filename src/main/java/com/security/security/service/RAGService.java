package com.security.security.service;

import com.security.security.dto.RAGResponseDTO;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.Document;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class RAGService {

    private static final String RAG_RESPONSE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "summary": { "type": "string" },
                "details": { "type": "array", "items": { "type": "string" } },
                "sources": { "type": "array", "items": { "type": "string" } }
              },
              "required": ["summary", "details", "sources"]
            }
            """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final DocumentService documentService;
    private final ConversationService conversationService;
    private final ChatMemory chatMemory;

    /**
     * Perform a RAG query based on knowledge-service request
     */
    public RAGResponseDTO performRAGQuery(RAGQueryPayload payload) {
        log.info("Performing permission-aware RAG query for user: {}", payload.getUserId());

        int maxResults = payload.getOptions() != null ? payload.getOptions().getMaxResults() : TOP_K;
        double minScore = payload.getOptions() != null ? payload.getOptions().getMinScore() : SIMILARITY_THRESHOLD;

        SearchRequest.Builder searchRequestBuiler = SearchRequest.builder()
                .query(payload.getQuery())
                .topK(maxResults)
                .similarityThreshold(minScore);

        String filterExpr = buildFilterExpression(payload.getUserPermissions(), payload.getUserId());
        if (filterExpr != null && !filterExpr.trim().isEmpty()) {
            log.info("Applying RAG metadata filter expression: {}", filterExpr);
            searchRequestBuiler.filterExpression(filterExpr);
        }

        SearchRequest searchRequest = searchRequestBuiler.build();

        List<org.springframework.ai.document.Document> relevantDocs = vectorStore.similaritySearch(searchRequest);
        log.info("Found {} relevant documents for RAG", relevantDocs.size());

        if (relevantDocs.isEmpty()) {
            return RAGResponseDTO.builder()
                    .answer("I couldn't find any relevant information in the internal documents I have access to.")
                    .sources(Collections.emptyList())
                    .build();
        }

        String context = formatContext(relevantDocs);
        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(payload.getQuery(), context);

        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .options(GoogleGenAiChatOptions.builder()
                        .responseMimeType("application/json")
                        .responseSchema(RAG_RESPONSE_SCHEMA)
                        .temperature(0.0)
                        .build())
                .call()
                .chatResponse();

        String answer = cleanResponse(
                response.getResult().getOutput().getText());

        List<RAGResponseDTO.SourceDTO> sources = relevantDocs.stream()
                .map(doc -> {
                    Map<String, Object> meta = doc.getMetadata();
                    String fileName = meta.getOrDefault("fileName", "unknown").toString();
                    String keywords = meta.getOrDefault("excerpt_terms", "").toString();
                    String chunkTitle = meta.getOrDefault("chunkTitle", "").toString();
                    String topic = !keywords.isBlank() ? keywords
                            : !chunkTitle.isBlank() ? chunkTitle : fileName;
                    String title = fileName + " > " + topic;

                    return RAGResponseDTO.SourceDTO.builder()
                            .documentId(meta.getOrDefault("documentId", "unknown").toString())
                            .documentTitle(title)
                            .chunkId(doc.getId())
                            .content(doc.getText())
                            .score(doc.getScore() != null ? doc.getScore() : 0.0)
                            .build();
                })
                .collect(Collectors.toList());

        return RAGResponseDTO.builder()
                .answer(answer)
                .sources(sources)
                .build();
    }

    private static final int TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.2;

    /**
     * Generate answer using RAG + Chat Memory + Streaming
     */
    public Flux<String> generateAnswerStream(Long conversationId, String question, String userId, RAGQueryPayload.UserPermissionContext permissions) {
        log.info("Generating RAG answer (with memory) for: {}", question);
        long startTime = System.currentTimeMillis();

        try {
            // 1. Check documents
            List<Document> documents = documentService.getCompletedDocuments(userId);
            if (documents.isEmpty()) {
                return Flux.just("Hệ thống chưa có tài liệu nội bộ nào được upload.");
            }

            // 2. Vector Search
            SearchRequest.Builder searchRequestBuiler = SearchRequest.builder()
                    .query(question)
                    .topK(TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD);

            String filterExpr = buildFilterExpression(permissions, userId);
            if (filterExpr != null && !filterExpr.trim().isEmpty()) {
                log.info("Applying RAG metadata filter expression for stream: {}", filterExpr);
                searchRequestBuiler.filterExpression(filterExpr);
            }

            SearchRequest searchRequest = searchRequestBuiler.build();

            List<org.springframework.ai.document.Document> relevantDocs = vectorStore.similaritySearch(searchRequest);

            if (relevantDocs.isEmpty()) {
                return Flux.just("Không tìm thấy thông tin liên quan.");
            }

            // 3. Build context
            String context = formatContext(relevantDocs);

            // 4. Prompt
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(question, context);

            // 5. Save user message (DB)
            conversationService.saveMessage(conversationId, "user", question, null, null);

            StringBuilder fullResponse = new StringBuilder();

            MessageChatMemoryAdvisor advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                    .conversationId(conversationId.toString())
                    .build();

            java.util.concurrent.atomic.AtomicBoolean jsonStarted = new java.util.concurrent.atomic.AtomicBoolean(false);

            // 6. CALL LLM WITH MEMORY 🔥
            return Flux.from(
                    chatClient.prompt()
                            .system(systemPrompt)
                            .user(userPrompt)
                            .advisors(advisor)
                            .options(GoogleGenAiChatOptions.builder()
                                    .responseMimeType("application/json")
                                    .responseSchema(RAG_RESPONSE_SCHEMA)
                                    .temperature(0.0)
                                    .build())
                            .stream()
                            .content())
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
                    .doOnNext(token -> fullResponse.append(token))
                    .doOnComplete(() -> {
                        long duration = System.currentTimeMillis() - startTime;

                        // Save assistant message
                        conversationService.saveMessage(
                                conversationId,
                                "assistant",
                                fullResponse.toString(),
                                null,
                                (int) duration);

                        // Auto-generate title if it's the first message pair
                        List<com.security.security.entity.Message> msgs = conversationService.getMessages(conversationId);
                        if (msgs.size() <= 2) {
                            conversationService.updateConversationTitle(conversationId, question);
                        }
                    })
                    .doOnError(e -> log.error("Error generating response", e));

        } catch (Exception e) {
            log.error("Error in RAG pipeline", e);
            return Flux.error(e);
        }
    }

    /**
     * Format retrieved documents as context string
     */
    private String formatContext(List<org.springframework.ai.document.Document> docs) {
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < docs.size(); i++) {
            org.springframework.ai.document.Document doc = docs.get(i);
            Map<String, Object> meta = doc.getMetadata();

            String fileName = getString(meta, "fileName", "Unknown");
            String keywords = getString(meta, "excerpt_terms", "");
            String chunkTitle = getString(meta, "chunkTitle", "");

            // Build source label: prefer LLM keywords, fallback to chunkTitle, then
            // fileName
            String topic = !keywords.isBlank() ? keywords
                    : !chunkTitle.isBlank() ? chunkTitle
                            : fileName;

            context.append(String.format("[Nguồn %d — %s > %s]\n%s\n\n",
                    i + 1, fileName, topic, doc.getText()));
        }

        return context.toString();
    }

    private String getString(Map<String, Object> meta, String key, String fallback) {
        if (meta == null)
            return fallback;
        Object v = meta.get(key);
        return (v != null && !v.toString().isBlank()) ? v.toString() : fallback;
    }

    /**
     * Build system prompt
     */
    private String buildSystemPrompt() {
        return """
                Bạn là AI trợ lý trả lời dựa trên tài liệu nội bộ.
                
                ## NGUYÊN TẮC TỐI THƯỢNG:
                - CHỈ TRẢ VỀ JSON. Bắt đầu bằng '{' và kết thúc bằng '}'.
                - TUYỆT ĐỐI KHÔNG giải thích, KHÔNG reasoning, KHÔNG nói gì ngoài JSON.
                - Nếu không tìm thấy thông tin phù hợp, hãy trả về JSON "Không tìm thấy".
                
                ## ĐỊNH DẠNG JSON:
                {
                  "summary": "Tóm tắt câu trả lời (tiếng Việt)",
                  "details": ["Chi tiết 1", "Chi tiết 2", "..."],
                  "sources": ["Tên tài liệu"]
                }
                """;
    }

    /**
     * Build user prompt with context
     */
    private String buildUserPrompt(String question, String context) {
        return """
                Thông tin:
                %s
                
                Câu hỏi:
                %s
                
                Trả về JSON duy nhất.
                """.formatted(context, question);
    }

    private String buildFilterExpression(RAGQueryPayload.UserPermissionContext context, String userId) {
        if (context == null) {
            return "collectionId == 'none'";
        }

        // 1. If Super Admin or Admin, bypass filtering (scoped by workspaceId)
        List<String> roles = context.getRoles();
        Integer roleLevel = context.getRoleLevel();
        boolean isAdmin = false;
        if (roles != null) {
            if (roles.contains("SUPER_ADMIN") || roles.contains("ADMIN") || roles.contains("ORG_ADMIN")) {
                isAdmin = true;
            }
        }
        if (roleLevel != null && roleLevel <= 1) {
            isAdmin = true;
        }

        String resolvedWorkspaceId = context.getWorkspaceId();
        if (resolvedWorkspaceId == null || resolvedWorkspaceId.trim().isEmpty() || "all".equalsIgnoreCase(resolvedWorkspaceId.trim())) {
            resolvedWorkspaceId = "default-workspace";
        }

        StringBuilder filter = new StringBuilder();
        filter.append("(workspaceId == '").append(resolvedWorkspaceId).append("' || workspaceId == '' || workspaceId == 'default-workspace')");

        if (isAdmin) {
            return filter.toString();
        }

        // 2. Check if user is Guest
        boolean isGuest = false;
        if (roles != null && roles.contains("EXTERNAL_GUEST")) {
            isGuest = true;
        }
        if (roleLevel != null && roleLevel >= 6) {
            isGuest = true;
        }

        if (isGuest) {
            // Guest can only access PUBLIC documents in current workspace
            filter.append(" && (classification == 'PUBLIC' || securityClassification == 'PUBLIC')");
            return filter.toString();
        }

        // 3. Normal Employee / Manager
        filter.append(" && (");

        // 3.1. General company-wide public/internal documents
        filter.append("((classification == 'PUBLIC' || classification == 'INTERNAL' || securityClassification == 'PUBLIC' || securityClassification == 'INTERNAL') && (departmentId == ''))");

        // 3.2. Documents uploaded by the user themselves
        if (userId != null && !userId.trim().isEmpty()) {
            filter.append(" || uploadedBy == '").append(userId).append("'");
        }

        // 3.3. Department & Role boundaries
        if (context.getUserDepartments() != null && !context.getUserDepartments().isEmpty()) {
            for (RAGQueryPayload.DepartmentRole dept : context.getUserDepartments()) {
                filter.append(" || (departmentId == '").append(dept.getDepartmentId()).append("'");
                if ("MEMBER".equalsIgnoreCase(dept.getRole())) {
                    filter.append(" && allowedRoles != 'HEAD'");
                }
                filter.append(")");
            }
        }

        // 3.4. Collections they have explicit access to (backward compatibility)
        List<String> collections = context.getAccessibleCollections();
        if (collections != null && !collections.isEmpty()) {
            filter.append(" || collectionId in [");
            for (int i = 0; i < collections.size(); i++) {
                filter.append("'").append(collections.get(i)).append("'");
                if (i < collections.size() - 1) {
                    filter.append(",");
                }
            }
            filter.append("]");
        }

        filter.append(")");
        return filter.toString();
    }

    private String cleanResponse(String raw) {
        return raw
                .replaceAll("(?s)Question:.*?\\n", "")
                .replaceAll("(?s)Constraint:.*?\\n", "")
                .replaceAll("(?s)Keyword search:.*?\\n", "")
                .replaceAll("(?s)Found in:.*?\\n", "")
                .trim();
    }
}
