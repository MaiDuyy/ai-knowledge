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

        List<org.springframework.ai.document.Document> relevantDocs = executeHybridSearchAndExpansion(
                payload.getQuery(),
                payload.getUserPermissions(),
                payload.getUserId(),
                maxResults,
                minScore
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
        String resolvedWorkspaceId = context.getWorkspaceId();
        if (resolvedWorkspaceId == null || resolvedWorkspaceId.trim().isEmpty() || "all".equalsIgnoreCase(resolvedWorkspaceId.trim())) {
            resolvedWorkspaceId = "default-workspace";
        }

        // 2. Fetch workspace departmentId using WorkspaceServiceClient
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
                        return "((workspaceId == '' || workspaceId == 'default-workspace' || workspaceId == 'all') && departmentId == '" + id + "')";
                    } else if ("workspace".equalsIgnoreCase(type)) {
                        return "workspaceId == '" + id + "'";
                    }
                } catch (Exception e) {
                    log.error("Failed to parse x-rag-scope: {}", ragScope, e);
                }
            }
            // Default Admin scope: all documents in the current workspace or the workspace's department
            if (workspaceDeptId != null && !workspaceDeptId.isEmpty()) {
                return "(workspaceId == '" + resolvedWorkspaceId + "' || ((workspaceId == '' || workspaceId == 'default-workspace' || workspaceId == 'all') && departmentId == '" + workspaceDeptId + "'))";
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
            if (workspaceDeptId != null && !workspaceDeptId.isEmpty()) {
                return "((workspaceId == '" + resolvedWorkspaceId + "' || ((workspaceId == '' || workspaceId == 'default-workspace' || workspaceId == 'all') && departmentId == '" + workspaceDeptId + "')) && (classification == 'PUBLIC' || securityClassification == 'PUBLIC'))";
            }
            return "workspaceId == '" + resolvedWorkspaceId + "' && (classification == 'PUBLIC' || securityClassification == 'PUBLIC')";
        }

        // 5. Normal Employee / Manager / HEAD
        // Build filter: current workspace OR department-specific docs OR company-wide docs

        // Resolve user role within the workspace's department
        String userRoleInDept = null;
        if (workspaceDeptId != null && !workspaceDeptId.isEmpty() && context.getUserDepartments() != null) {
            for (RAGQueryPayload.DepartmentRole deptRole : context.getUserDepartments()) {
                if (deptRole.getDepartmentId() != null && deptRole.getDepartmentId().equals(workspaceDeptId)) {
                    userRoleInDept = deptRole.getRole();
                    break;
                }
            }
        }

        boolean isLeader = "HEAD".equalsIgnoreCase(userRoleInDept) || "MANAGER".equalsIgnoreCase(userRoleInDept);

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

        // Clause 1: Current workspace documents (scoped to this workspace)
        orClauses.add("workspaceId == '" + resolvedWorkspaceId + "'");

        // Clause 2: Company-wide / global workspace documents (visible to all employees)
        orClauses.add("(workspaceId == 'default-workspace' && (allowedRoles == 'ALL' || allowedRoles == ''))");
        orClauses.add("(workspaceId == 'workspace-default' && (allowedRoles == 'ALL' || allowedRoles == ''))");

        // Clause 3: Department-scoped documents from the workspace's department
        if (workspaceDeptId != null && !workspaceDeptId.isEmpty()) {
            String deptClause = "(departmentId == '" + workspaceDeptId + "'";
            if (!isLeader) {
                // MEMBER: exclude HEAD-only documents
                deptClause += " && allowedRoles != 'HEAD'";
            }
            deptClause += ")";
            orClauses.add(deptClause);
        }

        // Clause 4: Cross-department access — other departments the user belongs to (HEAD or MEMBER)
        for (String deptId : userDeptIds) {
            if (deptId.equals(workspaceDeptId)) continue; // already handled above
            boolean isHeadInThisDept = userHeadDeptIds.contains(deptId);
            String crossDeptClause = "(departmentId == '" + deptId + "'";
            if (!isHeadInThisDept) {
                crossDeptClause += " && allowedRoles != 'HEAD'";
            }
            crossDeptClause += ")";
            orClauses.add(crossDeptClause);
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
        String resolvedWorkspaceId = context.getWorkspaceId();
        if (resolvedWorkspaceId == null || resolvedWorkspaceId.trim().isEmpty() || "all".equalsIgnoreCase(resolvedWorkspaceId.trim())) {
            resolvedWorkspaceId = "default-workspace";
        }
        
        // If page is not in the same workspace (and workspace is not 'all')
        if (page.getWorkspaceId() != null && !page.getWorkspaceId().equals(resolvedWorkspaceId) && !"all".equals(page.getWorkspaceId())) {
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
            return "PUBLIC".equalsIgnoreCase(page.getSecurityClassification());
        }
        
        // 4. Normal security classification check
        if ("PUBLIC".equalsIgnoreCase(page.getSecurityClassification())) {
            return true;
        }
        
        // Bypass department checks for workspace-specific pages when allowedRoles != HEAD
        if (page.getWorkspaceId() != null && !page.getWorkspaceId().isEmpty()
                && !"all".equals(page.getWorkspaceId()) && !"default-workspace".equals(page.getWorkspaceId())
                && !"HEAD".equalsIgnoreCase(page.getAllowedRoles())) {
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
        
        if (page.getDepartmentId() == null || page.getDepartmentId().trim().isEmpty()) {
            // INTERNAL company-wide pages are accessible by all internal users
            if ("INTERNAL".equalsIgnoreCase(page.getSecurityClassification())) {
                return true;
            }
        } else {
            String deptId = page.getDepartmentId();
            if (deptIdsWhereHead.contains(deptId)) {
                return true;
            }
            if (deptIdsWhereMember.contains(deptId) && !"HEAD".equalsIgnoreCase(page.getAllowedRoles())) {
                return true;
            }
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
        
        for (org.springframework.ai.document.Document doc : baseDocs) {
            String pageIdStr = getString(doc.getMetadata(), "wikiPageId", "");
            if (pageIdStr.isEmpty()) continue;
            
            try {
                Long pageId = Long.parseLong(pageIdStr);
                
                // Fetch the page to know its slug
                Optional<WikiPage> currentPageOpt = wikiPageRepository.findById(pageId);
                if (currentPageOpt.isPresent()) {
                    WikiPage currentPage = currentPageOpt.get();
                    if (!isPageAccessible(currentPage, permissions, userId)) continue;
                    
                    String currentSlug = currentPage.getSlug();
                    existingSlugs.add(currentSlug);
                    
                    // Outgoing links
                    List<WikiLink> outgoingLinks = wikiLinkRepository.findByFromPageId(pageId);
                    for (WikiLink link : outgoingLinks) {
                        String toSlug = link.getToSlug();
                        if (existingSlugs.contains(toSlug)) continue;
                        
                        Optional<WikiPage> linkedPageOpt = wikiPageRepository.fetchBySlugAndWorkspaceId(toSlug, currentPage.getWorkspaceId());
                        if (linkedPageOpt.isPresent()) {
                            WikiPage linkedPage = linkedPageOpt.get();
                            if (isPageAccessible(linkedPage, permissions, userId)) {
                                String summaryText = (linkedPage.getSummary() != null && !linkedPage.getSummary().isBlank()) 
                                        ? linkedPage.getSummary() 
                                        : (linkedPage.getContent() != null ? linkedPage.getContent() : "");
                                
                                org.springframework.ai.document.Document expandedDoc = new org.springframework.ai.document.Document(
                                    summaryText,
                                    Map.of(
                                        "wikiPageId", linkedPage.getId().toString(),
                                        "slug", linkedPage.getSlug(),
                                        "fileName", linkedPage.getTitle(),
                                        "type", "wiki-graph-extension"
                                    )
                                );
                                expandedDocs.add(expandedDoc);
                                existingSlugs.add(toSlug);
                            }
                        }
                    }
                    
                    // Incoming links
                    List<WikiLink> incomingLinks = wikiLinkRepository.findByToSlug(currentSlug);
                    for (WikiLink link : incomingLinks) {
                        Long fromPageId = link.getFromPageId();
                        Optional<WikiPage> linkedPageOpt = wikiPageRepository.findById(fromPageId);
                        if (linkedPageOpt.isPresent()) {
                            WikiPage linkedPage = linkedPageOpt.get();
                            String fromSlug = linkedPage.getSlug();
                            if (existingSlugs.contains(fromSlug)) continue;
                            
                            if (isPageAccessible(linkedPage, permissions, userId)) {
                                String summaryText = (linkedPage.getSummary() != null && !linkedPage.getSummary().isBlank()) 
                                        ? linkedPage.getSummary() 
                                        : (linkedPage.getContent() != null ? linkedPage.getContent() : "");
                                
                                org.springframework.ai.document.Document expandedDoc = new org.springframework.ai.document.Document(
                                    summaryText,
                                    Map.of(
                                        "wikiPageId", linkedPage.getId().toString(),
                                        "slug", linkedPage.getSlug(),
                                        "fileName", linkedPage.getTitle(),
                                        "type", "wiki-graph-extension"
                                    )
                                );
                                expandedDocs.add(expandedDoc);
                                existingSlugs.add(fromSlug);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error expanding wiki graph context for document: {}", doc.getId(), e);
            }
        }
        
        return expandedDocs;
    }

    public List<org.springframework.ai.document.Document> executeHybridSearchAndExpansion(
            String query,
            RAGQueryPayload.UserPermissionContext permissions,
            String userId,
            int maxResults,
            double minScore) {
        
        // 1. Vector Search
        SearchRequest.Builder searchRequestBuiler = SearchRequest.builder()
                .query(query)
                .topK(maxResults)
                .similarityThreshold(minScore);

        boolean[] partialResults = new boolean[]{false};
        String filterExpr = buildFilterExpression(permissions, userId, partialResults);
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
                meta.put("workspaceId", page.getWorkspaceId() != null ? page.getWorkspaceId() : "");
                meta.put("departmentId", page.getDepartmentId() != null ? page.getDepartmentId() : "");
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
