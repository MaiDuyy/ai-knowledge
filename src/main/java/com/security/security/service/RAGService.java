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
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import java.util.stream.Collectors;
import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiLink;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.EmbeddingRepository;
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.entity.enumeration.SecurityClassification;

@Service
@Slf4j
@RequiredArgsConstructor
public class RAGService {

    private static final String RAG_RESPONSE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "summary":            { "type": "string" },
                "details":            { "type": "array", "items": { "type": "string" } },
                "sources":            { "type": "array", "items": { "type": "string" } },
                "confidence":         { "type": "string", "enum": ["HIGH","MEDIUM","LOW","NONE"] },
                "confidenceScore":    { "type": "number" },
                "suggestedFollowUps": { "type": "array", "items": { "type": "string" }, "maxItems": 3 }
              },
              "required": ["summary", "details", "sources", "confidence", "confidenceScore", "suggestedFollowUps"]
            }
            """;

    // Similarity score thresholds for confidence classification
    private static final double CONFIDENCE_HIGH   = 0.65;
    private static final double CONFIDENCE_MEDIUM = 0.40;
    // Below LOW_GUARD → skip LLM, return "I don't know" immediately
    private static final double CONFIDENCE_LOW_GUARD = 0.25;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final DocumentService documentService;
    private final ConversationService conversationService;
    private final ChatMemory chatMemory;
    private final WorkspaceServiceClient workspaceServiceClient;
    private final ObjectMapper objectMapper;
    private final WikiPageRepository wikiPageRepository;
    private final WikiLinkRepository wikiLinkRepository;
    private final EmbeddingRepository embeddingRepository;
    private final KeywordSearchService keywordSearchService;
    private final RerankService rerankService;

    @Value("${rag.top-k:5}")
    private int topK;

    @Value("${rag.similarity-threshold:0.2}")
    private double similarityThreshold;

    /**
     * Perform a RAG query based on knowledge-service request
     */
    public RAGResponseDTO performRAGQuery(RAGQueryPayload payload) {
        log.info("Performing permission-aware RAG query for user: {}", payload.getUserId());

        // Rewrite follow-up questions into standalone queries using conversation history
        String effectiveQuery = rewriteQueryWithContext(payload.getQuery(), payload.getConversationId());

        int maxResults = payload.getOptions() != null ? payload.getOptions().getMaxResults() : topK;
        double minScore = payload.getOptions() != null ? payload.getOptions().getMinScore() : similarityThreshold;
        String pageType = payload.getOptions() != null ? payload.getOptions().getPageType() : null;

        List<org.springframework.ai.document.Document> relevantDocs = executeHybridSearchAndExpansion(
                effectiveQuery,
                payload.getUserPermissions(),
                payload.getUserId(),
                maxResults,
                minScore,
                pageType
        );
        log.info("Found {} relevant documents for RAG", relevantDocs.size());

        boolean[] partialResults = new boolean[]{false};
        buildFilterExpression(payload.getUserPermissions(), payload.getUserId(), partialResults);

        // ── "I don't know" guard ──────────────────────────────────────────────
        ConfidenceResult conf = computeConfidence(relevantDocs);
        log.info("[RAG] confidence={} score={} docs={}", conf.level(), conf.score(), relevantDocs.size());

        if (conf.level().equals("NONE") || conf.score() < CONFIDENCE_LOW_GUARD) {
            log.info("[RAG] Guard triggered — skipping LLM, returning no-context response");
            return iDontKnowResponse(partialResults[0]);
        }
        // ─────────────────────────────────────────────────────────────────────

        String context = formatContext(relevantDocs);
        String systemPrompt = buildSystemPrompt(conf.level(), conf.score());
        String userPrompt = buildUserPrompt(effectiveQuery, context);

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

        String rawAnswer = cleanResponse(response.getResult().getOutput().getText());

        // Parse suggestedFollowUps from the LLM JSON response
        List<String> followUps = extractFollowUps(rawAnswer);

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
        if (partialResults[0]) metadata.put("partial_results", true);
        metadata.put("confidence", conf.level());
        metadata.put("confidenceScore", conf.score());

        return RAGResponseDTO.builder()
                .answer(rawAnswer)
                .sources(sources)
                .metadata(metadata)
                .confidence(conf.level())
                .confidenceScore(conf.score())
                .suggestedFollowUps(followUps)
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

            // 2. Rewrite follow-up questions into standalone queries using conversation history
            // effectiveQuestion is used for search + LLM; original question is saved to DB
            String effectiveQuestion = rewriteQueryWithContext(question, conversationId);

            // 3. Hybrid Search & Graph Context Expansion
            List<org.springframework.ai.document.Document> relevantDocs = executeHybridSearchAndExpansion(
                    effectiveQuestion,
                    permissions,
                    userId,
                    topK,
                    similarityThreshold
            );

            boolean[] partialResults = new boolean[]{false};
            buildFilterExpression(permissions, userId, partialResults);

            // ── "I don't know" guard ──────────────────────────────────────────
            ConfidenceResult conf = computeConfidence(relevantDocs);
            log.info("[RAG stream] confidence={} score={} docs={}", conf.level(), conf.score(), relevantDocs.size());

            if (conf.level().equals("NONE") || conf.score() < CONFIDENCE_LOW_GUARD) {
                log.info("[RAG stream] Guard triggered — returning no-context JSON");
                String fallback = iDontKnowResponse(partialResults[0]).getAnswer();
                return Flux.just(fallback);
            }
            // ─────────────────────────────────────────────────────────────────

            // 4. Build context
            String context = formatContext(relevantDocs);

            // 5. Prompt (use effectiveQuestion + computed confidence)
            String systemPrompt = buildSystemPrompt(conf.level(), conf.score());
            String userPrompt = buildUserPrompt(effectiveQuestion, context);

            // 6. Save original user question to DB (not the rewritten one — user sees what they typed)
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

            // 7. CALL LLM WITH MEMORY
            return answerStream
                    .doOnNext(token -> fullResponse.append(token))
                    .doOnError(e -> log.error("RAG stream error: {}", e.getMessage()))
                    .onErrorResume(e -> Flux.empty())
                    .doFinally(signal -> {
                        long duration = System.currentTimeMillis() - startTime;

                        if (fullResponse.length() > 0) {
                            conversationService.saveMessage(
                                    conversationId,
                                    "assistant",
                                    fullResponse.toString(),
                                    null,
                                    (int) duration);
                        }

                        List<com.security.security.entity.Message> msgs = conversationService.getMessages(conversationId);
                        if (msgs.size() <= 2) {
                            conversationService.updateConversationTitle(conversationId, question);
                        }

                        log.info("RAG finished (signal={}). chars={}, duration={}ms", signal, fullResponse.length(), duration);
                    });

        } catch (Exception e) {
            log.error("Error in RAG pipeline", e);
            return Flux.error(e);
        }
    }

    /**
     * Format retrieved documents as context string with direct search results and related graph-expanded pages.
     */
    private String formatContext(List<org.springframework.ai.document.Document> docs) {
        StringBuilder context = new StringBuilder();
        List<org.springframework.ai.document.Document> directDocs = new ArrayList<>();
        List<org.springframework.ai.document.Document> relatedDocs = new ArrayList<>();
        
        for (org.springframework.ai.document.Document doc : docs) {
            if ("wiki-graph-extension".equals(getString(doc.getMetadata(), "type", ""))) {
                relatedDocs.add(doc);
            } else {
                directDocs.add(doc);
            }
        }

        context.append("[Direct Search Results]\n");
        for (int i = 0; i < directDocs.size(); i++) {
            org.springframework.ai.document.Document doc = directDocs.get(i);
            Map<String, Object> meta = doc.getMetadata();
            String fileName = getString(meta, "fileName", "Unknown");
            String keywords = getString(meta, "excerpt_terms", "");
            String chunkTitle = getString(meta, "chunkTitle", "");
            String topic = !keywords.isBlank() ? keywords : !chunkTitle.isBlank() ? chunkTitle : fileName;
            context.append(String.format("[Nguồn %d — %s > %s]\n%s\n\n", i + 1, fileName, topic, doc.getText()));
        }

        if (!relatedDocs.isEmpty()) {
            context.append("[Related Wiki Pages]\n");
            for (int i = 0; i < relatedDocs.size(); i++) {
                org.springframework.ai.document.Document doc = relatedDocs.get(i);
                Map<String, Object> meta = doc.getMetadata();
                String title = getString(meta, "fileName", "Unknown");
                context.append(String.format("- %s: %s\n", title, doc.getText()));
            }
            context.append("\n");
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
     * Extract suggestedFollowUps array from the LLM's JSON response string.
     * Returns empty list on any parse failure — never throws.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractFollowUps(String jsonAnswer) {
        if (jsonAnswer == null || jsonAnswer.isBlank()) return Collections.emptyList();
        try {
            JsonNode root = objectMapper.readTree(jsonAnswer);
            JsonNode node = root.path("suggestedFollowUps");
            if (node.isArray()) {
                List<String> result = new ArrayList<>();
                node.forEach(el -> {
                    String text = el.asText("").strip();
                    if (!text.isBlank()) result.add(text);
                });
                return result;
            }
        } catch (Exception e) {
            log.debug("[RAG] Could not parse suggestedFollowUps from response: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Build system prompt, injecting retrieval confidence so the LLM echoes it in the JSON output.
     * The LLM does not compute confidence — it just copies the pre-computed values into the schema.
     */
    private String buildSystemPrompt(String confidenceLevel, double confidenceScore) {
        return """
                Bạn là AI trợ lý trả lời dựa trên tài liệu nội bộ.

                ## NGUYÊN TẮC TỐI THƯỢNG:
                - CHỈ TRẢ VỀ JSON. Bắt đầu bằng '{' và kết thúc bằng '}'.
                - TUYỆT ĐỐI KHÔNG giải thích, KHÔNG reasoning, KHÔNG nói gì ngoài JSON.
                - Chỉ trả lời dựa trên thông tin ngữ cảnh được cung cấp. Không tự suy diễn.

                ## ĐỘ TIN CẬY TRUY XUẤT:
                Hệ thống đã tính sẵn: confidence="%s", confidenceScore=%.3f.
                Đặt ĐÚNG hai giá trị này vào JSON output — không được thay đổi.

                ## CÂU HỎI GỢI Ý (suggestedFollowUps):
                Dựa trên ngữ cảnh tài liệu và câu hỏi vừa trả lời, hãy sinh 2-3 câu hỏi tiếp theo
                mà người dùng có thể muốn hỏi. Câu hỏi phải ngắn gọn, cụ thể, và liên quan trực tiếp
                đến chủ đề vừa thảo luận. Không lặp lại câu hỏi gốc.

                ## ĐỊNH DẠNG JSON BẮT BUỘC:
                {
                  "summary": "Tóm tắt câu trả lời (tiếng Việt)",
                  "details": ["Chi tiết 1", "Chi tiết 2"],
                  "sources": ["Tên tài liệu"],
                  "confidence": "%s",
                  "confidenceScore": %.3f,
                  "suggestedFollowUps": ["Câu hỏi gợi ý 1?", "Câu hỏi gợi ý 2?", "Câu hỏi gợi ý 3?"]
                }
                """.formatted(confidenceLevel, confidenceScore, confidenceLevel, confidenceScore);
    }

    // Keep zero-arg overload for callers that don't have confidence yet (unused paths)
    private String buildSystemPrompt() {
        return buildSystemPrompt("MEDIUM", 0.5);
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

    // ── Confidence helpers ────────────────────────────────────────────────────

    private record ConfidenceResult(String level, double score) {}

    /**
     * Compute retrieval confidence from similarity scores of retrieved documents.
     * Uses max score as primary signal and doc count as secondary signal.
     */
    private ConfidenceResult computeConfidence(List<org.springframework.ai.document.Document> docs) {
        if (docs.isEmpty()) return new ConfidenceResult("NONE", 0.0);

        double maxScore = docs.stream()
                .mapToDouble(d -> d.getScore() != null ? d.getScore() : 0.0)
                .max().orElse(0.0);
        double avgScore = docs.stream()
                .mapToDouble(d -> d.getScore() != null ? d.getScore() : 0.0)
                .average().orElse(0.0);

        // Blend max (70%) + avg (30%) to reduce outlier bias
        double blended = maxScore * 0.7 + avgScore * 0.3;
        double rounded = Math.round(blended * 1000.0) / 1000.0;

        String level;
        if (blended >= CONFIDENCE_HIGH && docs.size() >= 2) level = "HIGH";
        else if (blended >= CONFIDENCE_MEDIUM)              level = "MEDIUM";
        else                                                level = "LOW";

        return new ConfidenceResult(level, rounded);
    }

    /**
     * Build a structured "I don't know" RAGResponseDTO (no LLM call).
     */
    private RAGResponseDTO iDontKnowResponse(boolean partialResults) {
        Map<String, Object> meta = new HashMap<>();
        if (partialResults) meta.put("partial_results", true);
        meta.put("guard", "no_relevant_context");

        String answer = """
                {"summary":"Không tìm thấy thông tin liên quan trong tài liệu nội bộ.",\
                "details":["Câu hỏi của bạn không khớp với nội dung nào trong kho tài liệu hiện có.",\
                "Hãy thử đặt câu hỏi theo cách khác hoặc kiểm tra lại từ khoá."],\
                "sources":[],"confidence":"NONE","confidenceScore":0.0,\
                "suggestedFollowUps":[]}""";

        return RAGResponseDTO.builder()
                .answer(answer)
                .sources(Collections.emptyList())
                .metadata(meta)
                .confidence("NONE")
                .confidenceScore(0.0)
                .suggestedFollowUps(Collections.emptyList())
                .build();
    }

    // ── Query rewriting ───────────────────────────────────────────────────────

    /**
     * Rewrite a follow-up question into a standalone query using recent conversation history.
     * Returns the original question unchanged if there is no history or rewriting fails.
     */
    private String rewriteQueryWithContext(String originalQuery, Long conversationId) {
        if (conversationId == null) return originalQuery;

        List<com.security.security.entity.Message> history =
                conversationService.getRecentMessages(conversationId, 6);
        // Need at least one prior exchange (user + assistant) before rewriting makes sense
        if (history.size() < 2) return originalQuery;

        StringBuilder historyStr = new StringBuilder();
        for (com.security.security.entity.Message msg : history) {
            String role = "user".equals(msg.getRole()) ? "User" : "Assistant";
            String content = msg.getContent();
            // Assistant messages are JSON — extract only the "summary" field for concise context
            if ("assistant".equals(msg.getRole())) {
                try {
                    JsonNode node = objectMapper.readTree(content);
                    String summary = node.path("summary").asText("");
                    content = summary.isBlank()
                            ? (content.length() > 250 ? content.substring(0, 250) + "..." : content)
                            : summary;
                } catch (Exception ignored) {
                    if (content.length() > 250) content = content.substring(0, 250) + "...";
                }
            }
            historyStr.append(role).append(": ").append(content).append("\n");
        }

        String prompt = """
                Given the conversation history below, rewrite the follow-up question as a complete, \
                standalone question that captures the full intent without needing the history context.
                If the question is already self-contained, return it unchanged.
                Return ONLY the rewritten question — no explanation, no quotes.

                Conversation history:
                %s
                Follow-up question: %s
                Standalone question:""".formatted(historyStr, originalQuery);

        try {
            String rewritten = chatClient.prompt()
                    .user(prompt)
                    .options(GoogleGenAiChatOptions.builder().temperature(0.0).build())
                    .call()
                    .content();

            if (rewritten == null || rewritten.isBlank()) return originalQuery;

            String cleaned = rewritten.strip().replaceAll("^[\"']|[\"']$", "").strip();
            if (!cleaned.isEmpty() && !cleaned.equals(originalQuery)) {
                log.info("[QueryRewrite] '{}' → '{}'", originalQuery, cleaned);
            }
            return cleaned.isEmpty() ? originalQuery : cleaned;
        } catch (Exception e) {
            log.warn("[QueryRewrite] Failed, using original query. Error: {}", e.getMessage());
            return originalQuery;
        }
    }

    private String formatFilter(org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op op) {
        return formatExpression(op.build());
    }

    private String formatExpression(org.springframework.ai.vectorstore.filter.Filter.Expression expr) {
        if (expr == null) return "";
        
        org.springframework.ai.vectorstore.filter.Filter.ExpressionType type = expr.type();
        org.springframework.ai.vectorstore.filter.Filter.Operand left = expr.left();
        org.springframework.ai.vectorstore.filter.Filter.Operand right = expr.right();
        
        String opStr = "";
        switch (type) {
            case AND -> opStr = " && ";
            case OR -> opStr = " || ";
            case EQ -> opStr = " == ";
            case NE -> opStr = " != ";
            case GT -> opStr = " > ";
            case GTE -> opStr = " >= ";
            case LT -> opStr = " < ";
            case LTE -> opStr = " <= ";
            case IN -> opStr = " in ";
            case NIN -> opStr = " nin ";
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        }
        
        String leftStr = formatOperand(left);
        String rightStr = formatOperand(right);
        
        if (type == org.springframework.ai.vectorstore.filter.Filter.ExpressionType.AND || type == org.springframework.ai.vectorstore.filter.Filter.ExpressionType.OR) {
            return "(" + leftStr + opStr + rightStr + ")";
        }
        return leftStr + opStr + rightStr;
    }

    private String formatOperand(org.springframework.ai.vectorstore.filter.Filter.Operand operand) {
        if (operand instanceof org.springframework.ai.vectorstore.filter.Filter.Expression subExpr) {
            return formatExpression(subExpr);
        } else if (operand instanceof org.springframework.ai.vectorstore.filter.Filter.Key key) {
            return key.key();
        } else if (operand instanceof org.springframework.ai.vectorstore.filter.Filter.Value val) {
            Object v = val.value();
            if (v instanceof String) {
                return "'" + v + "'";
            }
            return String.valueOf(v);
        } else if (operand instanceof org.springframework.ai.vectorstore.filter.Filter.Group group) {
            return "(" + formatOperand(group.content()) + ")";
        }
        return "";
    }

    public org.springframework.ai.vectorstore.filter.Filter.Expression getFilterExpressionAST(RAGQueryPayload.UserPermissionContext context, String userId) {
        boolean[] partialResults = new boolean[]{false};
        return buildFilterExpressionAST(context, userId, partialResults);
    }

    public String getFilterExpressionStr(RAGQueryPayload.UserPermissionContext context, String userId) {
        boolean[] partialResults = new boolean[]{false};
        org.springframework.ai.vectorstore.filter.Filter.Expression expr = buildFilterExpressionAST(context, userId, partialResults);
        return formatExpression(expr);
    }

    private org.springframework.ai.vectorstore.filter.Filter.Expression buildFilterExpressionAST(RAGQueryPayload.UserPermissionContext context, String userId, boolean[] partialResults) {
        org.springframework.ai.vectorstore.filter.FilterExpressionBuilder b = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder();

        if (context == null) {
            return b.eq("collectionId", "none").build();
        }

        // 1. Resolve workspaceId
        String resolvedWorkspaceId = ScopeNormalizer.normalizeWorkspace(context.getWorkspaceId());

        // 2. Fetch workspace departmentId using WorkspaceServiceClient
        boolean isServiceFailure = false;
        String workspaceDeptId = "ALL";
        if (!"ALL".equals(resolvedWorkspaceId) && !"GLOBAL".equals(resolvedWorkspaceId)) {
            try {
                Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(resolvedWorkspaceId, userId);
                if (workspaceInfo == null || workspaceInfo.isEmpty()) {
                    isServiceFailure = true;
                } else {
                    workspaceDeptId = ScopeNormalizer.normalizeDepartment((String) workspaceInfo.get("departmentId"));
                }
            } catch (Exception e) {
                log.error("Failed to query workspace department from messaging-service for workspaceId={}", resolvedWorkspaceId, e);
                isServiceFailure = true;
            }
        }

        if (isServiceFailure) {
            partialResults[0] = true;
            return b.eq("workspaceId", resolvedWorkspaceId).build();
        }

        // 3. Check Admin / Super Admin status from roles/level
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

        if (isAdmin) {
            // Admin can selection-filter the search scope via x-rag-scope header (in context.getRagScope())
            String ragScope = context.getRagScope();
            if (ragScope != null && !ragScope.trim().isEmpty()) {
                try {
                    JsonNode node = objectMapper.readTree(ragScope);
                    String type = node.path("type").asText("");
                    String id = node.path("id").asText("");
                    if ("department".equalsIgnoreCase(type)) {
                        return b.and(
                            b.or(b.eq("workspaceId", "ALL"), b.eq("workspaceId", "GLOBAL")),
                            b.eq("departmentId", id)
                        ).build();
                    } else if ("workspace".equalsIgnoreCase(type)) {
                        return b.eq("workspaceId", id).build();
                    }
                } catch (Exception e) {
                    log.error("Failed to parse x-rag-scope: {}", ragScope, e);
                }
            }
            if ("ALL".equals(resolvedWorkspaceId) || "GLOBAL".equals(resolvedWorkspaceId)) {
                return b.and(
                    b.or(b.eq("workspaceId", "ALL"), b.eq("workspaceId", "GLOBAL")),
                    b.or(b.eq("departmentId", "ALL"), b.eq("departmentId", "GLOBAL"))
                ).build();
            }
            // Default Admin scope: all documents in the current workspace or the workspace's department
            if (!"ALL".equals(workspaceDeptId) && !"GLOBAL".equals(workspaceDeptId)) {
                return b.or(
                    b.eq("workspaceId", resolvedWorkspaceId),
                    b.and(
                        b.or(b.eq("workspaceId", "ALL"), b.eq("workspaceId", "GLOBAL")),
                        b.eq("departmentId", workspaceDeptId)
                    )
                ).build();
            }
            return b.eq("workspaceId", resolvedWorkspaceId).build();
        }

        // 4. Check if user is Guest
        boolean isGuest = false;
        if (roles != null && roles.contains("EXTERNAL_GUEST")) {
            isGuest = true;
        }
        if (roleLevel != null && roleLevel >= 6) {
            isGuest = true;
        }

        if (isGuest) {
            // Guest can only access PUBLIC documents in current workspace or its department
            org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op publicClause = b.or(
                b.eq("classification", "PUBLIC"),
                b.eq("securityClassification", "PUBLIC")
            );

            if ("ALL".equals(resolvedWorkspaceId) || "GLOBAL".equals(resolvedWorkspaceId)) {
                return b.and(
                    b.and(
                        b.or(b.eq("workspaceId", "ALL"), b.eq("workspaceId", "GLOBAL")),
                        b.or(b.eq("departmentId", "ALL"), b.eq("departmentId", "GLOBAL"))
                    ),
                    publicClause
                ).build();
            }
            if (!"ALL".equals(workspaceDeptId) && !"GLOBAL".equals(workspaceDeptId)) {
                return b.and(
                    b.or(
                        b.eq("workspaceId", resolvedWorkspaceId),
                        b.and(
                            b.or(b.eq("workspaceId", "ALL"), b.eq("workspaceId", "GLOBAL")),
                            b.eq("departmentId", workspaceDeptId)
                        )
                    ),
                    publicClause
                ).build();
            }
            return b.and(b.eq("workspaceId", resolvedWorkspaceId), publicClause).build();
        }

        // 5. Normal Employee / Manager / HEAD
        // Build filter: current workspace OR department-specific docs OR company-wide docs
        List<String> userDeptIds = new ArrayList<>();
        List<String> userHeadDeptIds = new ArrayList<>();
        if (context.getUserDepartments() != null) {
            for (RAGQueryPayload.DepartmentRole dr : context.getUserDepartments()) {
                if (dr.getDepartmentId() != null && !dr.getDepartmentId().isBlank()) {
                    userDeptIds.add(dr.getDepartmentId());
                    if (PermissionUtils.isHeadOrDeputy(dr.getRole())) {
                        userHeadDeptIds.add(dr.getDepartmentId());
                    }
                }
            }
        }

        boolean hasHeadRole = !userHeadDeptIds.isEmpty();
        List<org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op> exprList = new ArrayList<>();

        if ("ALL".equals(resolvedWorkspaceId) || "GLOBAL".equals(resolvedWorkspaceId)) {
            // Global workspace query: retrieve company-wide documents
            org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op globalDocs = b.and(
                b.or(b.eq("workspaceId", "ALL"), b.eq("workspaceId", "GLOBAL")),
                b.or(b.eq("departmentId", "ALL"), b.eq("departmentId", "GLOBAL"))
            );
            if (!hasHeadRole) {
                globalDocs = b.and(globalDocs, b.ne("allowedRoles", "HEAD"));
            }
            exprList.add(globalDocs);

            // Also include department shared docs (workspace = ALL) for all departments the user belongs to
            for (String deptId : userDeptIds) {
                boolean isHeadInDept = userHeadDeptIds.contains(deptId);
                org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op sharedClause = b.and(
                    b.or(b.eq("workspaceId", "ALL"), b.eq("workspaceId", "GLOBAL")),
                    b.eq("departmentId", deptId)
                );
                if (!isHeadInDept) {
                    sharedClause = b.and(sharedClause, b.ne("allowedRoles", "HEAD"));
                }
                exprList.add(sharedClause);
            }
        } else {
            // 1. Company-wide (ALL/ALL) documents
            org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op companyDocs = b.and(
                b.or(b.eq("workspaceId", "ALL"), b.eq("workspaceId", "GLOBAL")),
                b.or(b.eq("departmentId", "ALL"), b.eq("departmentId", "GLOBAL"))
            );
            if (!hasHeadRole) {
                companyDocs = b.and(companyDocs, b.ne("allowedRoles", "HEAD"));
            }
            exprList.add(companyDocs);

            // 2. Current workspace documents with no department restriction
            org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op wsDocs = b.and(
                b.eq("workspaceId", resolvedWorkspaceId),
                b.or(b.eq("departmentId", "ALL"), b.eq("departmentId", "GLOBAL"))
            );
            if (!hasHeadRole) {
                wsDocs = b.and(wsDocs, b.ne("allowedRoles", "HEAD"));
            }
            exprList.add(wsDocs);

            // 3. Department-restricted documents for all departments the user belongs to
            for (String deptId : userDeptIds) {
                boolean isHeadInDept = userHeadDeptIds.contains(deptId);
                
                // Department documents in the current workspace
                org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op deptClause = b.and(
                    b.eq("workspaceId", resolvedWorkspaceId),
                    b.eq("departmentId", deptId)
                );
                if (!isHeadInDept) {
                    deptClause = b.and(deptClause, b.ne("allowedRoles", "HEAD"));
                }
                exprList.add(deptClause);

                // Department shared documents (workspaceId = ALL)
                org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op sharedClause = b.and(
                    b.or(b.eq("workspaceId", "ALL"), b.eq("workspaceId", "GLOBAL")),
                    b.eq("departmentId", deptId)
                );
                if (!isHeadInDept) {
                    sharedClause = b.and(sharedClause, b.ne("allowedRoles", "HEAD"));
                }
                exprList.add(sharedClause);
            }
        }

        // Fold the expression list using OR
        org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op combined = null;
        for (org.springframework.ai.vectorstore.filter.FilterExpressionBuilder.Op op : exprList) {
            if (combined == null) {
                combined = op;
            } else {
                combined = b.or(combined, op);
            }
        }

        if (combined == null) {
            return b.eq("collectionId", "none").build();
        }

        return combined.build();
    }

    private String buildFilterExpression(RAGQueryPayload.UserPermissionContext context, String userId, boolean[] partialResults) {
        org.springframework.ai.vectorstore.filter.Filter.Expression expr = buildFilterExpressionAST(context, userId, partialResults);
        return formatExpression(expr);
    }


    private String cleanResponse(String raw) {
        return raw
                .replaceAll("(?s)Question:.*?\\n", "")
                .replaceAll("(?s)Constraint:.*?\\n", "")
                .replaceAll("(?s)Keyword search:.*?\\n", "")
                .replaceAll("(?s)Found in:.*?\\n", "")
                .trim();
    }

    public boolean isPageAccessible(WikiPage page, RAGQueryPayload.UserPermissionContext context, String userId) {
        // 1. Check workspace access
        String resolvedWorkspaceId = ScopeNormalizer.normalizeWorkspace(context.getWorkspaceId());
        String pageWsId = ScopeNormalizer.normalizeWorkspace(page.getWorkspaceId());
        
        // If page is not in the same workspace (and workspace is not ALL/GLOBAL)
        if (!"ALL".equals(pageWsId) && !"GLOBAL".equals(pageWsId) && !pageWsId.equals(resolvedWorkspaceId)) {
            return false;
        }
        
        // 2. Resolve roles and admin status
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
        
        if (isAdmin) {
            return true;
        }
        
        // 3. Guest check
        boolean isGuest = false;
        if (roles != null && roles.contains("EXTERNAL_GUEST")) {
            isGuest = true;
        }
        if (roleLevel != null && roleLevel >= 6) {
            isGuest = true;
        }
        
        if (isGuest) {
            return SecurityClassification.PUBLIC == page.getSecurityClassification();
        }
        
        // 4. PUBLIC pages are visible to all internal users
        if (SecurityClassification.PUBLIC == page.getSecurityClassification()) {
            return true;
        }

        // Parse user departments
        List<String> deptIdsWhereHead = new ArrayList<>();
        List<String> deptIdsWhereMember = new ArrayList<>();
        List<RAGQueryPayload.DepartmentRole> userDepts = context.getUserDepartments();
        if (userDepts != null) {
            for (RAGQueryPayload.DepartmentRole dept : userDepts) {
                String deptId = dept.getDepartmentId();
                String role = dept.getRole();
                if (deptId != null && !deptId.trim().isEmpty()) {
                    if (PermissionUtils.isHeadOrDeputy(role)) {
                        deptIdsWhereHead.add(deptId);
                        deptIdsWhereMember.add(deptId);
                    } else {
                        deptIdsWhereMember.add(deptId);
                    }
                }
            }
        }

        boolean hasHeadRole = !deptIdsWhereHead.isEmpty();
        if ("HEAD".equalsIgnoreCase(page.getAllowedRoles()) && !hasHeadRole) {
            return false;
        }

        // 5. Department-scoped pages: user must belong to that department
        String pageDeptId = ScopeNormalizer.normalizeDepartment(page.getDepartmentId());
        if (!"ALL".equals(pageDeptId) && !"GLOBAL".equals(pageDeptId)) {
            if (deptIdsWhereHead.contains(pageDeptId)) {
                return true;
            }
            if (deptIdsWhereMember.contains(pageDeptId) && !"HEAD".equalsIgnoreCase(page.getAllowedRoles())) {
                return true;
            }
            return false;
        }

        // 6. No department restriction — workspace-only or INTERNAL company-wide
        if (SecurityClassification.INTERNAL == page.getSecurityClassification()) {
            return true;
        }

        // Workspace-specific pages without department: accessible to workspace members
        if (!"ALL".equals(pageWsId) && !"GLOBAL".equals(pageWsId)) {
            return true;
        }

        return false;
    }

    public List<org.springframework.ai.document.Document> expandContextWithWikiGraph(
            List<org.springframework.ai.document.Document> baseDocs,
            RAGQueryPayload.UserPermissionContext permissions,
            String userId) {
        List<org.springframework.ai.document.Document> expandedDocs = new ArrayList<>(baseDocs);
        
        // Keep track of slugs we've already included in baseDocs to avoid duplicates
        Set<String> existingSlugs = new HashSet<>();
        for (org.springframework.ai.document.Document doc : baseDocs) {
            if (doc.getMetadata().containsKey("slug")) {
                existingSlugs.add(doc.getMetadata().get("slug").toString());
            }
        }
        
        // Collect seed pages from baseDocs
        List<WikiPage> seedPages = new ArrayList<>();
        for (org.springframework.ai.document.Document doc : baseDocs) {
            String pageIdStr = getString(doc.getMetadata(), "wikiPageId", "");
            if (pageIdStr.isEmpty()) continue;
            try {
                Long pageId = Long.parseLong(pageIdStr);
                wikiPageRepository.findById(pageId).ifPresent(page -> {
                    if (isPageAccessible(page, permissions, userId)) {
                        existingSlugs.add(page.getSlug());
                        seedPages.add(page);
                    }
                });
            } catch (Exception ignored) {}
        }

        // Collect candidates from outgoing + incoming links of seed pages
        Map<String, WikiPage> candidatePages = new HashMap<>();
        Map<String, Double> candidateScores = new HashMap<>();

        for (WikiPage seedPage : seedPages) {
            // Outgoing links
            List<WikiLink> outgoing = wikiLinkRepository.findByFromPageId(seedPage.getId());
            for (WikiLink link : outgoing) {
                if (existingSlugs.contains(link.getToSlug())) continue;
                if (candidatePages.containsKey(link.getToSlug())) continue;
                wikiPageRepository.fetchBySlugAndWorkspaceId(link.getToSlug(), seedPage.getWorkspaceId())
                        .filter(p -> isPageAccessible(p, permissions, userId))
                        .ifPresent(p -> candidatePages.put(p.getSlug(), p));
            }

            // Incoming links
            List<WikiLink> incoming = wikiLinkRepository.findByToSlug(seedPage.getSlug());
            for (WikiLink link : incoming) {
                wikiPageRepository.findById(link.getFromPageId())
                        .filter(p -> !existingSlugs.contains(p.getSlug()) && !candidatePages.containsKey(p.getSlug()))
                        .filter(p -> isPageAccessible(p, permissions, userId))
                        .ifPresent(p -> candidatePages.put(p.getSlug(), p));
            }
        }

        // Score candidates with 4-signal model
        for (Map.Entry<String, WikiPage> entry : candidatePages.entrySet()) {
            WikiPage candidate = entry.getValue();
            double bestScore = 0.0;

            for (WikiPage seed : seedPages) {
                double score = 0.0;

                // Signal 1: Direct link (×3.0)
                score += 3.0;

                // Signal 2: Source overlap (×4.0) — same sourceDocumentId
                if (seed.getSourceDocumentId() != null && candidate.getSourceDocumentId() != null
                        && seed.getSourceDocumentId().equals(candidate.getSourceDocumentId())) {
                    score += 4.0;
                }

                // Signal 3: Type affinity (×1.0)
                if (seed.getPageType() != null && seed.getPageType() == candidate.getPageType()) {
                    score += 1.0;
                }

                bestScore = Math.max(bestScore, score);
            }

            candidateScores.put(entry.getKey(), bestScore);
        }

        // Sort by score, take top 5
        List<String> topCandidates = candidateScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

        for (String slug : topCandidates) {
            WikiPage page = candidatePages.get(slug);
            String summaryText = (page.getSummary() != null && !page.getSummary().isBlank())
                    ? page.getSummary()
                    : (page.getContent() != null ? page.getContent() : "");

            Map<String, Object> meta = new HashMap<>();
            meta.put("wikiPageId", page.getId().toString());
            meta.put("slug", page.getSlug());
            meta.put("fileName", page.getTitle());
            meta.put("type", "wiki-graph-extension");
            meta.put("relevanceScore", String.valueOf(candidateScores.get(slug)));

            expandedDocs.add(new org.springframework.ai.document.Document(summaryText, meta));
            existingSlugs.add(slug);
        }

        log.info("[RAGService] Graph expansion: {} seed pages → {} candidates → {} selected (top 5 by relevance)",
                seedPages.size(), candidatePages.size(), topCandidates.size());
        return expandedDocs;
    }

    public List<org.springframework.ai.document.Document> executeHybridSearchAndExpansion(
            String query,
            RAGQueryPayload.UserPermissionContext permissions,
            String userId,
            int maxResults,
            double minScore) {
        return executeHybridSearchAndExpansion(query, permissions, userId, maxResults, minScore, null);
    }

    public List<org.springframework.ai.document.Document> executeHybridSearchAndExpansion(
            String query,
            RAGQueryPayload.UserPermissionContext permissions,
            String userId,
            int maxResults,
            double minScore,
            String pageType) {

        log.info("[RAGService] executeHybridSearchAndExpansion for query='{}', userId={}, workspaceId={}", 
                query, userId, permissions != null ? permissions.getWorkspaceId() : "null");
        if (permissions != null) {
            log.info("[RAGService] User permissions: roles={}, userDepartments={}", 
                    permissions.getRoles(), permissions.getUserDepartments());
        }

        // 1. Vector Search
        SearchRequest.Builder searchRequestBuiler = SearchRequest.builder()
                .query(query)
                .topK(maxResults)
                .similarityThreshold(minScore);

        boolean[] partialResults = new boolean[]{false};
        org.springframework.ai.vectorstore.filter.Filter.Expression finalExpr = buildFilterExpressionAST(permissions, userId, partialResults);
        if (pageType != null && !pageType.trim().isEmpty()) {
            org.springframework.ai.vectorstore.filter.FilterExpressionBuilder b = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder();
            finalExpr = new org.springframework.ai.vectorstore.filter.Filter.Expression(
                org.springframework.ai.vectorstore.filter.Filter.ExpressionType.AND,
                finalExpr,
                b.eq("pageType", pageType.trim()).build()
            );
        }
        String filterExpr = formatExpression(finalExpr);
        log.info("[RAGService] Final filter expression: {}", filterExpr);
        if (filterExpr != null && !filterExpr.trim().isEmpty()) {
            searchRequestBuiler.filterExpression(filterExpr);
        }

        List<org.springframework.ai.document.Document> vectorDocs = new ArrayList<>();
        try {
            vectorDocs = vectorStore.similaritySearch(searchRequestBuiler.build());
            log.info("[RAGService] Vector search returned {} documents", vectorDocs.size());
        } catch (Exception e) {
            log.error("Vector search failed", e);
        }

        // 2. Keyword Search
        List<WikiPage> keywordPages = new ArrayList<>();
        try {
            // Resolve permissions for keyword search
            List<String> deptIdsWhereHead = new ArrayList<>();
            List<String> deptIdsWhereMember = new ArrayList<>();
            
            List<RAGQueryPayload.DepartmentRole> userDepts = permissions.getUserDepartments();
            if (userDepts != null) {
                for (RAGQueryPayload.DepartmentRole dept : userDepts) {
                    String deptId = dept.getDepartmentId();
                    String role = dept.getRole();
                    if (deptId != null && !deptId.trim().isEmpty()) {
                        if (PermissionUtils.isHeadOrDeputy(role)) {
                            deptIdsWhereHead.add(deptId);
                            deptIdsWhereMember.add(deptId);
                        } else {
                            deptIdsWhereMember.add(deptId);
                        }
                    }
                }
            }
            boolean hasHeadRole = !deptIdsWhereHead.isEmpty();

            if (deptIdsWhereHead.isEmpty()) deptIdsWhereHead.add("DUMMY_DEPT_ID");
            if (deptIdsWhereMember.isEmpty()) deptIdsWhereMember.add("DUMMY_DEPT_ID");

            List<String> roles = permissions.getRoles();
            Integer roleLevel = permissions.getRoleLevel();
            boolean isAdmin = false;
            if (roles != null) {
                if (roles.contains("SUPER_ADMIN") || roles.contains("ADMIN") || roles.contains("ORG_ADMIN")) {
                    isAdmin = true;
                }
            }
            if (roleLevel != null && roleLevel <= 1) {
                isAdmin = true;
            }

            String resolvedWorkspaceId = permissions.getWorkspaceId();
            if (resolvedWorkspaceId == null || resolvedWorkspaceId.trim().isEmpty() 
                    || "all".equalsIgnoreCase(resolvedWorkspaceId.trim())
                    || "GLOBAL".equalsIgnoreCase(resolvedWorkspaceId.trim())
                    || "ALL".equalsIgnoreCase(resolvedWorkspaceId.trim())) {
                resolvedWorkspaceId = "ALL";
            }

            String workspaceDeptId = null;
            if (resolvedWorkspaceId != null && !"ALL".equals(resolvedWorkspaceId) && !"default-workspace".equals(resolvedWorkspaceId)) {
                try {
                    Map<String, Object> workspaceInfo = workspaceServiceClient.getWorkspace(resolvedWorkspaceId, userId);
                    if (workspaceInfo != null && workspaceInfo.containsKey("departmentId")) {
                        workspaceDeptId = (String) workspaceInfo.get("departmentId");
                    }
                } catch (Exception e) {
                    log.warn("[RAGService] Could not resolve department for workspace: {}", resolvedWorkspaceId, e);
                }
            }

            keywordPages = wikiPageRepository.searchAccessiblePagesByKeyword(
                resolvedWorkspaceId, workspaceDeptId, isAdmin, hasHeadRole, deptIdsWhereHead, deptIdsWhereMember, query
            );
            if (pageType != null && !pageType.trim().isEmpty()) {
                final String pt = pageType.trim();
                keywordPages = keywordPages.stream()
                        .filter(p -> p.getPageType() != null && pt.equalsIgnoreCase(p.getPageType().getValue()))
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.error("Keyword search failed", e);
        }

        // 3. RRF Blending
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, org.springframework.ai.document.Document> docMap = new HashMap<>();

        // Process Vector results
        for (int i = 0; i < vectorDocs.size(); i++) {
            org.springframework.ai.document.Document doc = vectorDocs.get(i);
            String id = doc.getMetadata().containsKey("wikiPageId")
                ? "wiki-" + doc.getMetadata().get("wikiPageId").toString()
                : "doc-" + doc.getId();
            
            double score = 1.0 / (60.0 + (i + 1));
            rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
            docMap.put(id, doc);
        }

        // Process chunk-level keyword search results (tsvector on embeddings table)
        try {
            String kwWorkspaceId = permissions != null ? ScopeNormalizer.normalizeWorkspace(permissions.getWorkspaceId()) : "default-workspace";
            List<com.security.security.entity.Embedding> chunkKeywordResults = keywordSearchService.search(query, kwWorkspaceId, maxResults);
            for (int i = 0; i < chunkKeywordResults.size(); i++) {
                com.security.security.entity.Embedding emb = chunkKeywordResults.get(i);
                String id = "chunk-kw-" + emb.getDocumentId() + "-" + emb.getChunkIndex();
                double score = 0.5 / (60.0 + (i + 1));
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
                if (!docMap.containsKey(id)) {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("documentId", emb.getDocumentId().toString());
                    meta.put("chunkIndex", String.valueOf(emb.getChunkIndex()));
                    meta.put("chunkTitle", emb.getChunkTitle());
                    meta.put("workspaceId", emb.getWorkspaceId());
                    if (emb.getContextHeader() != null) {
                        meta.put("contextHeader", emb.getContextHeader());
                    }
                    if (emb.getChunkType() != null) {
                        meta.put("chunkType", emb.getChunkType().name());
                    }
                    if (emb.getParentId() != null) {
                        meta.put("parentId", emb.getParentId().toString());
                    }
                    docMap.put(id, new org.springframework.ai.document.Document(emb.getChunkText(), meta));
                }
            }
            log.info("[RAGService] Chunk keyword search returned {} results", chunkKeywordResults.size());
        } catch (Exception e) {
            log.warn("[RAGService] Chunk keyword search failed: {}", e.getMessage());
        }

        // Process WikiPage keyword results
        for (int i = 0; i < keywordPages.size(); i++) {
            WikiPage page = keywordPages.get(i);
            String id = "wiki-" + page.getId();
            
            double score = 1.0 / (60.0 + (i + 1));
            rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + score);
            
            if (!docMap.containsKey(id)) {
                // Map WikiPage to Document
                Map<String, Object> meta = new HashMap<>();
                meta.put("wikiPageId", page.getId().toString());
                meta.put("workspaceId", ScopeNormalizer.normalizeWorkspace(page.getWorkspaceId()));
                meta.put("departmentId", ScopeNormalizer.normalizeDepartment(page.getDepartmentId()));
                meta.put("allowedRoles", page.getAllowedRoles() != null ? page.getAllowedRoles() : "ALL");
                meta.put("classification", page.getSecurityClassification() != null ? page.getSecurityClassification() : "INTERNAL");
                meta.put("securityClassification", page.getSecurityClassification() != null ? page.getSecurityClassification() : "INTERNAL");
                meta.put("type", "wiki");
                meta.put("slug", page.getSlug() != null ? page.getSlug() : "");
                meta.put("fileName", page.getTitle());

                org.springframework.ai.document.Document doc = new org.springframework.ai.document.Document(
                    "Tiêu đề: " + page.getTitle() + "\n\n" + page.getContent(),
                    meta
                );
                docMap.put(id, doc);
            }
        }

        // Sort by RRF score
        List<String> sortedIds = rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        List<org.springframework.ai.document.Document> blendedDocs = new ArrayList<>();
        for (int i = 0; i < Math.min(maxResults, sortedIds.size()); i++) {
            String id = sortedIds.get(i);
            blendedDocs.add(docMap.get(id));
        }

        // Parent-Child Context Expansion
        List<org.springframework.ai.document.Document> expandedParentChildDocs = expandParentChildContext(blendedDocs);

        // 4. Graph Context Expansion
        List<org.springframework.ai.document.Document> graphExpandedDocs = expandContextWithWikiGraph(expandedParentChildDocs, permissions, userId);

        // 6. LLM Reranking — score and select top-N most relevant documents
        return rerankService.rerank(query, graphExpandedDocs);
    }

    private List<org.springframework.ai.document.Document> expandParentChildContext(List<org.springframework.ai.document.Document> docs) {
        List<org.springframework.ai.document.Document> expanded = new ArrayList<>();
        for (org.springframework.ai.document.Document doc : docs) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta != null && meta.containsKey("parentId")) {
                try {
                    Long parentId = Long.parseLong(meta.get("parentId").toString());
                    Optional<com.security.security.entity.Embedding> parentOpt = embeddingRepository.findById(parentId);
                    if (parentOpt.isPresent()) {
                        String parentText = parentOpt.get().getChunkText();
                        // Copy metadata but use the parent's full text
                        Map<String, Object> newMeta = new HashMap<>(meta);
                        // Also update chunkTitle to parent's title if helpful
                        if (parentOpt.get().getChunkTitle() != null) {
                            newMeta.put("chunkTitle", parentOpt.get().getChunkTitle());
                        }
                        expanded.add(new org.springframework.ai.document.Document(parentText, newMeta));
                        log.info("[RAGService] Expanded child chunk to parent chunk (id={}) text size={}", parentId, parentText.length());
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("[RAGService] Failed to expand parent-child context for parentId={}: {}", meta.get("parentId"), e.getMessage());
                }
            }
            expanded.add(doc);
        }
        return expanded;
    }
}
