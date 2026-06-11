package com.security.security.service;

import com.security.security.dto.RAGResponseDTO;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.Document;
import com.security.security.client.WorkspaceServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
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
    private final WorkspaceServiceClient workspaceServiceClient;
    private final ObjectMapper objectMapper;

    @Value("${rag.top-k:5}")
    private int topK;

    @Value("${rag.similarity-threshold:0.2}")
    private double similarityThreshold;

    /**
     * Perform a RAG query based on knowledge-service request
     */
    public RAGResponseDTO performRAGQuery(RAGQueryPayload payload) {
        log.info("Performing permission-aware RAG query for user: {}", payload.getUserId());

        int maxResults = payload.getOptions() != null ? payload.getOptions().getMaxResults() : topK;
        double minScore = payload.getOptions() != null ? payload.getOptions().getMinScore() : similarityThreshold;

        SearchRequest.Builder searchRequestBuiler = SearchRequest.builder()
                .query(payload.getQuery())
                .topK(maxResults)
                .similarityThreshold(minScore);

        boolean[] partialResults = new boolean[]{false};
        String filterExpr = buildFilterExpression(payload.getUserPermissions(), payload.getUserId(), partialResults);
        if (filterExpr != null && !filterExpr.trim().isEmpty()) {
            log.info("Applying RAG metadata filter expression: {}", filterExpr);
            searchRequestBuiler.filterExpression(filterExpr);
        }

        SearchRequest searchRequest = searchRequestBuiler.build();

        List<org.springframework.ai.document.Document> relevantDocs = vectorStore.similaritySearch(searchRequest);
        log.info("Found {} relevant documents for RAG", relevantDocs.size());

        if (relevantDocs.isEmpty()) {
            Map<String, Object> metadata = new HashMap<>();
            if (partialResults[0]) {
                metadata.put("partial_results", true);
            }
            return RAGResponseDTO.builder()
                    .answer("I couldn't find any relevant information in the internal documents I have access to.")
                    .sources(Collections.emptyList())
                    .metadata(metadata)
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

        Map<String, Object> metadata = new HashMap<>();
        if (partialResults[0]) {
            metadata.put("partial_results", true);
        }

        return RAGResponseDTO.builder()
                .answer(answer)
                .sources(sources)
                .metadata(metadata)
                .build();
    }

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
                    .topK(topK)
                    .similarityThreshold(similarityThreshold);

            boolean[] partialResults = new boolean[]{false};
            String filterExpr = buildFilterExpression(permissions, userId, partialResults);
            if (filterExpr != null && !filterExpr.trim().isEmpty()) {
                log.info("Applying RAG metadata filter expression for stream: {}", filterExpr);
                searchRequestBuiler.filterExpression(filterExpr);
            }

            SearchRequest searchRequest = searchRequestBuiler.build();

            List<org.springframework.ai.document.Document> relevantDocs = vectorStore.similaritySearch(searchRequest);

            if (relevantDocs.isEmpty()) {
                if (partialResults[0]) {
                    return Flux.just("Không tìm thấy thông tin liên quan.\n\n*Chú ý: Hệ thống quản lý phòng ban hiện đang bảo trì. Kết quả tìm kiếm chỉ truy xuất dữ liệu trong Workspace này.*");
                }
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

            Flux<String> answerStream = Flux.from(
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
                    .filter(token -> !token.isEmpty());

            if (partialResults[0]) {
                answerStream = answerStream.concatWith(Flux.just("\n\n*Chú ý: Hệ thống quản lý phòng ban hiện đang bảo trì. Kết quả tìm kiếm chỉ truy xuất dữ liệu trong Workspace này.*"));
            }

            // 6. CALL LLM WITH MEMORY 🔥
            return answerStream
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

    private String buildFilterExpression(RAGQueryPayload.UserPermissionContext context, String userId, boolean[] partialResults) {
        if (context == null) {
            return "collectionId == 'none'";
        }

        // 1. If Super Admin or Admin, check roles/level
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

        if (isAdmin) {
            // Admin can selection-filter the search scope via x-rag-scope header (in context.getRagScope())
            String ragScope = context.getRagScope();
            if (ragScope != null && !ragScope.trim().isEmpty()) {
                try {
                    JsonNode node = objectMapper.readTree(ragScope);
                    String type = node.path("type").asText("");
                    String id = node.path("id").asText("");
                    if ("department".equalsIgnoreCase(type)) {
                        return "((workspaceId == '' || workspaceId == 'default-workspace' || workspaceId == 'all') && departmentId == '" + id + "')";
                    } else if ("workspace".equalsIgnoreCase(type)) {
                        return "workspaceId == '" + id + "'";
                    }
                } catch (Exception e) {
                    log.error("Failed to parse x-rag-scope: {}", ragScope, e);
                }
            }
            // Default Admin scope: public in current workspace only
            return "workspaceId == '" + resolvedWorkspaceId + "' && (classification == 'PUBLIC' || securityClassification == 'PUBLIC')";
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
            return "workspaceId == '" + resolvedWorkspaceId + "' && (classification == 'PUBLIC' || securityClassification == 'PUBLIC')";
        }

        // 3. Normal Employee / Manager / HEAD
        // Fetch workspace metadata / departmentId using WorkspaceServiceClient
        boolean isServiceFailure = false;
        String workspaceDeptId = null;
        if (resolvedWorkspaceId != null && !resolvedWorkspaceId.isEmpty() && !"default-workspace".equals(resolvedWorkspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(resolvedWorkspaceId, userId);
                if (workspaceInfo == null || workspaceInfo.isEmpty()) {
                    isServiceFailure = true;
                } else {
                    workspaceDeptId = (String) workspaceInfo.get("departmentId");
                }
            } catch (Exception e) {
                log.error("Failed to query workspace department from messaging-service for workspaceId={}", resolvedWorkspaceId, e);
                isServiceFailure = true;
            }
        }

        if (isServiceFailure) {
            partialResults[0] = true;
            return "workspaceId == '" + resolvedWorkspaceId + "'";
        }

        // Build normal filter: current workspace OR department-wide public/role files
        String userRoleInDept = null;
        if (workspaceDeptId != null && !workspaceDeptId.isEmpty() && context.getUserDepartments() != null) {
            for (RAGQueryPayload.DepartmentRole deptRole : context.getUserDepartments()) {
                if (deptRole.getDepartmentId() != null && deptRole.getDepartmentId().equals(workspaceDeptId)) {
                    userRoleInDept = deptRole.getRole();
                    break;
                }
            }
        }

        StringBuilder filter = new StringBuilder();
        filter.append("(workspaceId == '").append(resolvedWorkspaceId).append("'");
        
        if (workspaceDeptId != null && !workspaceDeptId.isEmpty()) {
            filter.append(" || ((workspaceId == '' || workspaceId == 'default-workspace' || workspaceId == 'all') && departmentId == '").append(workspaceDeptId).append("'");
            boolean isLeader = "HEAD".equalsIgnoreCase(userRoleInDept) || "MANAGER".equalsIgnoreCase(userRoleInDept);
            if (!isLeader) {
                filter.append(" && allowedRoles != 'HEAD'");
            }
            filter.append(")");
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
