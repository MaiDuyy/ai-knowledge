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
    private final WikiPageRepository wikiPageRepository;
    private final WikiLinkRepository wikiLinkRepository;

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
        String pageType = payload.getOptions() != null ? payload.getOptions().getPageType() : null;

        List<org.springframework.ai.document.Document> relevantDocs = executeHybridSearchAndExpansion(
                payload.getQuery(),
                payload.getUserPermissions(),
                payload.getUserId(),
                maxResults,
                minScore,
                pageType
        );
        log.info("Found {} relevant documents for RAG", relevantDocs.size());

        boolean[] partialResults = new boolean[]{false};
        buildFilterExpression(payload.getUserPermissions(), payload.getUserId(), partialResults);

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

            // 2. Hybrid Search & Graph Context Expansion
            List<org.springframework.ai.document.Document> relevantDocs = executeHybridSearchAndExpansion(
                    question,
                    permissions,
                    userId,
                    topK,
                    similarityThreshold
            );

            boolean[] partialResults = new boolean[]{false};
            buildFilterExpression(permissions, userId, partialResults);

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

            // 6. CALL LLM WITH MEMORY
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

        // 1. Resolve workspaceId
        String resolvedWorkspaceId = ScopeNormalizer.normalizeWorkspace(context.getWorkspaceId());

        // 2. Fetch workspace departmentId using WorkspaceServiceClient
        boolean isServiceFailure = false;
        String workspaceDeptId = "GLOBAL";
        if (!"GLOBAL".equals(resolvedWorkspaceId)) {
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
            return "workspaceId == '" + resolvedWorkspaceId + "'";
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
                        return "(workspaceId == 'GLOBAL' && departmentId == '" + id + "')";
                    } else if ("workspace".equalsIgnoreCase(type)) {
                        return "workspaceId == '" + id + "'";
                    }
                } catch (Exception e) {
                    log.error("Failed to parse x-rag-scope: {}", ragScope, e);
                }
            }
            if ("GLOBAL".equals(resolvedWorkspaceId)) {
                return "workspaceId == 'GLOBAL' && departmentId == 'GLOBAL'";
            }
            // Default Admin scope: all documents in the current workspace or the workspace's department
            if (!"GLOBAL".equals(workspaceDeptId)) {
                return "(workspaceId == '" + resolvedWorkspaceId + "' || (workspaceId == 'GLOBAL' && departmentId == '" + workspaceDeptId + "'))";
            }
            return "workspaceId == '" + resolvedWorkspaceId + "'";
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
            if ("GLOBAL".equals(resolvedWorkspaceId)) {
                return "workspaceId == 'GLOBAL' && departmentId == 'GLOBAL' && (classification == 'PUBLIC' || securityClassification == 'PUBLIC')";
            }
            if (!"GLOBAL".equals(workspaceDeptId)) {
                return "((workspaceId == '" + resolvedWorkspaceId + "' || (workspaceId == 'GLOBAL' && departmentId == '" + workspaceDeptId + "')) && (classification == 'PUBLIC' || securityClassification == 'PUBLIC'))";
            }
            return "workspaceId == '" + resolvedWorkspaceId + "' && (classification == 'PUBLIC' || securityClassification == 'PUBLIC')";
        }

        // 5. Normal Employee / Manager / HEAD
        // Build filter: current workspace OR department-specific docs OR company-wide docs

        // Collect all department IDs where the user has any role (for multi-dept access)
        List<String> userDeptIds = new ArrayList<>();
        List<String> userHeadDeptIds = new ArrayList<>();
        if (context.getUserDepartments() != null) {
            for (RAGQueryPayload.DepartmentRole dr : context.getUserDepartments()) {
                if (dr.getDepartmentId() != null && !dr.getDepartmentId().isBlank()) {
                    userDeptIds.add(dr.getDepartmentId());
                    if ("HEAD".equalsIgnoreCase(dr.getRole()) || "MANAGER".equalsIgnoreCase(dr.getRole())) {
                        userHeadDeptIds.add(dr.getDepartmentId());
                    }
                }
            }
        }

        List<String> orClauses = new ArrayList<>();

        if ("GLOBAL".equals(resolvedWorkspaceId)) {
            // Global workspace query: Only retrieve company-wide GLOBAL documents
            orClauses.add("(workspaceId == 'GLOBAL' && departmentId == 'GLOBAL')");
        } else {
            // Clause 1: Current workspace documents with no department restriction
            orClauses.add("(workspaceId == '" + resolvedWorkspaceId + "' && departmentId == 'GLOBAL')");
            // Clause 2: Current workspace documents with the workspace department restriction
            if (!"GLOBAL".equals(workspaceDeptId) && userDeptIds.contains(workspaceDeptId)) {
                boolean isHeadInThisDept = userHeadDeptIds.contains(workspaceDeptId);
                String deptClause = "(workspaceId == '" + resolvedWorkspaceId + "' && departmentId == '" + workspaceDeptId + "'";
                if (!isHeadInThisDept) {
                    deptClause += " && allowedRoles != 'HEAD'";
                }
                deptClause += ")";
                orClauses.add(deptClause);

                // Also include department shared docs (workspaceId = GLOBAL, departmentId = workspaceDeptId)
                String sharedClause = "(workspaceId == 'GLOBAL' && departmentId == '" + workspaceDeptId + "'";
                if (!isHeadInThisDept) {
                    sharedClause += " && allowedRoles != 'HEAD'";
                }
                sharedClause += ")";
                orClauses.add(sharedClause);
            }
        }

        return "(" + String.join(" || ", orClauses) + ")";
    }

    private String cleanResponse(String raw) {
        return raw
                .replaceAll("(?s)Question:.*?\\n", "")
                .replaceAll("(?s)Constraint:.*?\\n", "")
                .replaceAll("(?s)Keyword search:.*?\\n", "")
                .replaceAll("(?s)Found in:.*?\\n", "")
                .trim();
    }

    private boolean isPageAccessible(WikiPage page, RAGQueryPayload.UserPermissionContext context, String userId) {
        // 1. Check workspace access
        String resolvedWorkspaceId = ScopeNormalizer.normalizeWorkspace(context.getWorkspaceId());
        String pageWsId = ScopeNormalizer.normalizeWorkspace(page.getWorkspaceId());
        
        // If page is not in the same workspace (and workspace is not GLOBAL)
        if (!"GLOBAL".equals(pageWsId) && !pageWsId.equals(resolvedWorkspaceId)) {
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
                    if ("HEAD".equalsIgnoreCase(role) || "MANAGER".equalsIgnoreCase(role)) {
                        deptIdsWhereHead.add(deptId);
                    } else {
                        deptIdsWhereMember.add(deptId);
                    }
                }
            }
        }

        // 5. Department-scoped pages: user must belong to that department
        String pageDeptId = ScopeNormalizer.normalizeDepartment(page.getDepartmentId());
        if (!"GLOBAL".equals(pageDeptId)) {
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
        if (!"GLOBAL".equals(pageWsId)) {
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

        // 1. Vector Search
        SearchRequest.Builder searchRequestBuiler = SearchRequest.builder()
                .query(query)
                .topK(maxResults)
                .similarityThreshold(minScore);

        boolean[] partialResults = new boolean[]{false};
        String filterExpr = buildFilterExpression(permissions, userId, partialResults);
        if (pageType != null && !pageType.trim().isEmpty()) {
            String ptFilter = "pageType == '" + pageType.trim() + "'";
            filterExpr = (filterExpr != null && !filterExpr.trim().isEmpty())
                    ? "(" + filterExpr + ") && " + ptFilter
                    : ptFilter;
        }
        if (filterExpr != null && !filterExpr.trim().isEmpty()) {
            searchRequestBuiler.filterExpression(filterExpr);
        }

        List<org.springframework.ai.document.Document> vectorDocs = new ArrayList<>();
        try {
            vectorDocs = vectorStore.similaritySearch(searchRequestBuiler.build());
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
                        if ("HEAD".equalsIgnoreCase(role) || "MANAGER".equalsIgnoreCase(role)) {
                            deptIdsWhereHead.add(deptId);
                        } else {
                            deptIdsWhereMember.add(deptId);
                        }
                    }
                }
            }
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
            if (resolvedWorkspaceId == null || resolvedWorkspaceId.trim().isEmpty() || "all".equalsIgnoreCase(resolvedWorkspaceId.trim())) {
                resolvedWorkspaceId = "default-workspace";
            }

            String workspaceDeptId = null;
            if (resolvedWorkspaceId != null && !"default-workspace".equals(resolvedWorkspaceId)) {
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
                resolvedWorkspaceId, workspaceDeptId, isAdmin, deptIdsWhereHead, deptIdsWhereMember, query
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

        // Process Keyword results
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

        // 4. Graph Context Expansion
        return expandContextWithWikiGraph(blendedDocs, permissions, userId);
    }
}
