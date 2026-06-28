package com.security.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.security.security.entity.*;
import com.security.security.entity.enumeration.SourceChunkStatus;
import com.security.security.entity.enumeration.SourceCompilationStatus;
import com.security.security.entity.enumeration.WikiPageDraftStatus;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.repository.*;
import com.security.security.provider.LlmFactory;
import com.security.security.provider.LlmProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import com.security.security.event.NatsEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MrpPipelineService {

    private static final String MAP_PHASE_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "entities": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "name": { "type": "string" },
                  "type": { "type": "string" },
                  "description": { "type": "string" }
                },
                "required": ["name", "type", "description"]
              }
            },
            "concepts": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "name": { "type": "string" },
                  "description": { "type": "string" }
                },
                "required": ["name", "description"]
              }
            },
            "claims": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "subject": { "type": "string" },
                  "claim": { "type": "string" },
                  "sourceContext": { "type": "string" }
                },
                "required": ["subject", "claim", "sourceContext"]
              }
            },
            "contradictions": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "subject": { "type": "string" },
                  "claim_a": { "type": "string" },
                  "claim_b": { "type": "string" },
                  "resolution": { "type": "string" }
                },
                "required": ["subject", "claim_a", "claim_b"]
              }
            },
            "recommendations": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "title": { "type": "string" },
                  "description": { "type": "string" },
                  "priority": { "type": "string" }
                },
                "required": ["title", "description", "priority"]
              }
            }
          },
          "required": ["entities", "concepts", "claims", "contradictions", "recommendations"]
        }
        """;

    private static final String REDUCE_PHASE_SCHEMA = """
        {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "title": { "type": "string" },
              "slug": { "type": "string" },
              "action": { "type": "string" },
              "wikiPageId": { "type": "integer", "nullable": true },
              "pageType": { "type": "string" },
              "tags": {
                "type": "array",
                "items": { "type": "string" }
              },
              "reason": { "type": "string" },
              "keyClaims": {
                "type": "array",
                "items": { "type": "string" }
              },
              "crossReferences": {
                "type": "array",
                "items": { "type": "string" }
              },
              "reviewItems": {
                "type": "array",
                "items": { "type": "string" }
              }
            },
            "required": ["title", "slug", "action", "pageType", "tags", "reason", "keyClaims"]
          }
        }
        """;

    private final LlmFactory llmFactory;
    private final WikiPageRepository wikiPageRepository;
    private final SourceChunkExtractRepository sourceChunkExtractRepository;
    private final SourceCompilationPlanRepository sourceCompilationPlanRepository;
    private final WikiPageDraftRepository wikiPageDraftRepository;
    private final DocumentRepository documentRepository;
    private final WikiDraftService wikiDraftService;
    private final ExecutorService mrpVirtualThreadExecutor;
    private final ObjectMapper objectMapper;
    private final NatsEventPublisher natsEventPublisher;

    public MrpPipelineService(
            LlmFactory llmFactory,
            WikiPageRepository wikiPageRepository,
            SourceChunkExtractRepository sourceChunkExtractRepository,
            SourceCompilationPlanRepository sourceCompilationPlanRepository,
            WikiPageDraftRepository wikiPageDraftRepository,
            DocumentRepository documentRepository,
            WikiDraftService wikiDraftService,
            @Qualifier("mrpVirtualThreadExecutor") ExecutorService mrpVirtualThreadExecutor,
            ObjectMapper objectMapper,
            NatsEventPublisher natsEventPublisher) {
        this.llmFactory = llmFactory;
        this.wikiPageRepository = wikiPageRepository;
        this.sourceChunkExtractRepository = sourceChunkExtractRepository;
        this.sourceCompilationPlanRepository = sourceCompilationPlanRepository;
        this.wikiPageDraftRepository = wikiPageDraftRepository;
        this.documentRepository = documentRepository;
        this.wikiDraftService = wikiDraftService;
        this.mrpVirtualThreadExecutor = mrpVirtualThreadExecutor;
        this.objectMapper = objectMapper;
        this.natsEventPublisher = natsEventPublisher;
    }

    /**
     * Backward-compatible wrapper method called by existing services (e.g. DocumentService).
     * Automatically triggers the advanced MRP compilation pipeline and auto-approves generated drafts.
     */
    @Transactional
    public int compileToWiki(String markdownContent, Long sourceDocumentId, String workspaceId, String userId) {
        log.info("[MRP Pipeline] Backward-compatible compileToWiki called for document ID: {}", sourceDocumentId);
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        // We first initiate the compile pipeline with autoApprove=true (executes Map, Reduce, Plan, Refine & Auto-Approve drafts)
        SourceCompilationPlan plan = initiateCompile(sourceDocumentId, normalizedWorkspaceId, userId, true);
        try {
            List<Map<String, Object>> planItems = parsePlanItems(plan.getPlanJson());
            return planItems.size();
        } catch (Exception e) {
            log.error("[MRP Pipeline] Error counting pages from compilation plan: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Tự động tổng hợp Markdown thành các trang Wiki (MRP Compile)
     * Hỗ trợ autoApprove (luồng tự động) hoặc manual (tạo Plan chờ duyệt).
     */
    @Transactional
    public SourceCompilationPlan initiateCompile(Long documentId, String workspaceId, String userId, boolean autoApprove) {
        log.info("[MRP Pipeline] Bắt đầu khởi tạo biên dịch tài liệu ID: {}, autoApprove={}", documentId, autoApprove);

        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found with ID: " + documentId));

        // Kế thừa workspaceId trực tiếp từ tài liệu gốc (Document) để bảo mật và đồng bộ dữ liệu theo đúng flow
        String finalWorkspaceId = ScopeNormalizer.normalizeWorkspace(doc.getWorkspaceId());
        String docDeptId = ScopeNormalizer.normalizeDepartment(doc.getDepartmentId());
        if ("ALL".equals(finalWorkspaceId)) {
            if (!"ALL".equals(docDeptId)) {
                // Department-scoped document, finalWorkspaceId is already ALL
            } else {
                finalWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
            }
        }
        final String targetWorkspaceId = finalWorkspaceId;

        log.info("[MRP Pipeline] Workspace được xác định cho tiến trình: {}", targetWorkspaceId);

        // Create or update SourceCompilationPlan record
        SourceCompilationPlan plan = sourceCompilationPlanRepository.findBySourceDocumentId(documentId)
                .orElseGet(() -> SourceCompilationPlan.builder()
                        .sourceDocumentId(documentId)
                        .build());

        // Copy security attributes from Document
        plan.setDepartmentId(docDeptId);
        plan.setAllowedRoles(doc.getAllowedRoles() != null ? doc.getAllowedRoles() : "ALL");
        plan.setSecurityClassification(doc.getSecurityClassification() != null ? doc.getSecurityClassification() : SecurityClassification.INTERNAL);

        plan.setStatus(SourceCompilationStatus.PROCESSING);
        plan.setPlanJson(null); // Clear previous plan if any, as we are re-compiling
        plan = sourceCompilationPlanRepository.save(plan);

        // Publish event that status is PROCESSING
        natsEventPublisher.publishCompilationPlanUpdated(plan.getId(), plan.getSourceDocumentId(), targetWorkspaceId, plan.getStatus().name(), userId);

        final Long planId = plan.getId();
        final String docContent = doc.getMarkdownContent();
        
        // Trigger async pipeline compilation after transaction commit
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        CompletableFuture.runAsync(() -> {
                            runCompilationProcess(planId, documentId, docContent, targetWorkspaceId, userId, autoApprove);
                        }, mrpVirtualThreadExecutor);
                    }
                }
            );
        } else {
            CompletableFuture.runAsync(() -> {
                runCompilationProcess(planId, documentId, docContent, targetWorkspaceId, userId, autoApprove);
            }, mrpVirtualThreadExecutor);
        }

        return plan;
    }

    /**
     * Quy trình xử lý biên dịch bất đồng bộ (Map, Reduce & Auto-Approve if configured).
     */
    public void runCompilationProcess(Long planId, Long documentId, String content, String finalWorkspaceId, String userId, boolean autoApprove) {
        log.info("[MRP Pipeline] [Async] Bắt đầu xử lý biên dịch bất đồng bộ cho plan ID: {}, document ID: {}", planId, documentId);
        
        String actualContent = content;
        if (actualContent == null || actualContent.isBlank()) {
            actualContent = "Document details";
        }

        try {
            // --- PHASE 1: MAP PHASE (Song song sử dụng Virtual Threads) ---
            log.info("[MRP Pipeline] [Map Phase] Bắt đầu phân tách tài liệu thành các khối để trích xuất song song...");
            List<String> chunks = splitIntoChunks(actualContent, 20000, 1000);
            log.info("[MRP Pipeline] [Map Phase] Phân tách thành {} chunks.", chunks.size());

            List<CompletableFuture<SourceChunkExtract>> futures = new ArrayList<>();
            LlmProvider provider = llmFactory.getProvider("gemini");

            for (int i = 0; i < chunks.size(); i++) {
                final int index = i;
                final String chunkText = chunks.get(i);

                CompletableFuture<SourceChunkExtract> future = CompletableFuture.supplyAsync(() -> {
                    // Resume-on-crash: Check if chunk extract already exists and is DONE
                    Optional<SourceChunkExtract> existing = sourceChunkExtractRepository
                            .findBySourceDocumentIdAndChunkIndex(documentId, index);
                    
                    String chunkHash = computeSHA256(chunkText);
                    if (existing.isPresent() && SourceChunkStatus.DONE == existing.get().getStatus()
                            && chunkHash.equals(existing.get().getContentHash())) {
                        log.info("[MRP Pipeline] [Map Phase] Chunk {} unchanged (hash match), skipping.", index);
                        return existing.get();
                    }
                    if (existing.isPresent() && SourceChunkStatus.DONE == existing.get().getStatus()
                            && existing.get().getContentHash() == null) {
                        log.info("[MRP Pipeline] [Map Phase] Chunk {} DONE but no hash (legacy), skipping.", index);
                        return existing.get();
                    }

                    SourceChunkExtract extract = existing.orElseGet(() -> SourceChunkExtract.builder()
                            .sourceDocumentId(documentId)
                            .chunkIndex(index)
                            .startChar(index * 19000)
                            .endChar(index * 19000 + chunkText.length())
                            .status(SourceChunkStatus.PENDING)
                            .build());

                    extract.setStatus(SourceChunkStatus.PROCESSING);
                    sourceChunkExtractRepository.save(extract);

                    try {
                        // Stagger start slightly to avoid bursting the API gateway/rate limits
                        if (index > 0) {
                            try {
                                Thread.sleep(1500L * index);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Map Phase chunk staggered start interrupted", ie);
                            }
                        }

                        String mapSystemPrompt = """
                            You are an expert enterprise knowledge extraction agent.
                            Your job is to read the provided text chunk and extract key structured details.

                            CRITICAL GROUNDEDNESS DIRECTIVES:
                            - You must ONLY extract entities, concepts, and claims that are explicitly mentioned in the provided text chunk.
                            - Do NOT use any external background knowledge, prior assumptions, or web search facts to write descriptions or definitions.
                            - The description/definition of each entity or concept MUST be constructed solely from the facts provided in the text. If the text does not describe the entity, use a minimal description derived strictly from the text context, or leave it brief.
                            - Every claim's 'claim' and 'sourceContext' fields MUST correspond to the exact facts and sentences in the text chunk. Do NOT extrapolate or assume anything.

                            You must extract:
                            1. Entities: Organizations, products, technologies, tools, platforms, or people. Give each a clear description.
                            2. Concepts: Core paradigms, frameworks, architectural designs, procedures, rules, policies. Define each precisely.
                            3. Claims: Facts, guidelines, configurations, assertions, metrics, or requirements. Detail each claim and link it to the subject.
                            4. Contradictions: Cases where the text contains conflicting claims about the same subject. Note both sides and any resolution if the text provides one. If none found, return empty array.
                            5. Recommendations: Actionable suggestions, improvement proposals, or best practices found in text. Classify priority as HIGH, MEDIUM, or LOW. If none found, return empty array.

                            You must return ONLY a valid JSON object. Do NOT wrap the response in markdown blocks (such as ```json). Do NOT add any conversational text before or after the JSON.
                            {
                              "entities": [
                                {"name": "Entity Name", "type": "organization/technology/etc", "description": "Concise description of the entity"}
                              ],
                              "concepts": [
                                {"name": "Concept Name", "description": "Precise definition of this concept"}
                              ],
                              "claims": [
                                {"subject": "Entity/Concept name", "claim": "Fact, metric, assertion, or config", "sourceContext": "Exact text sentence or clear context"}
                              ],
                              "contradictions": [
                                {"subject": "Topic", "claim_a": "First conflicting claim", "claim_b": "Second conflicting claim", "resolution": "Resolution if any"}
                              ],
                              "recommendations": [
                                {"title": "Action title", "description": "What should be done", "priority": "HIGH/MEDIUM/LOW"}
                              ]
                            }
                            """;

                        log.info("[MRP Pipeline] [Map Phase] Đang gửi yêu cầu LLM trích xuất cho chunk {}...", index);
                        String response = callChatWithRetry(provider, mapSystemPrompt, chunkText, MAP_PHASE_SCHEMA, "mrp-map-" + documentId + "-" + index);
                        log.info("[MRP Pipeline] [Map Phase] Raw LLM response for chunk {}: \n{}", index, response);

                        String cleanedJson = cleanJsonResponse(response, true);
                        log.info("[MRP Pipeline] [Map Phase] Cleaned JSON for chunk {}: \n{}", index, cleanedJson);
                        
                        // Validate JSON parsing and ensure non-empty structure
                        JsonNode rootNode = objectMapper.readTree(cleanedJson);
                        if (rootNode.isEmpty()) {
                            throw new RuntimeException("LLM returned an empty or invalid extract structure");
                        }

                        extract.setExtractJson(cleanedJson);
                        extract.setStatus(SourceChunkStatus.DONE);
                        extract.setContentHash(chunkHash);
                        extract.setErrorMessage(null);
                        log.info("[MRP Pipeline] [Map Phase] Trích xuất thành công chunk {}.", index);
                    } catch (Exception e) {
                        log.error("[MRP Pipeline] [Map Phase] Lỗi trích xuất chunk {}: {}", index, e.getMessage());
                        extract.setStatus(SourceChunkStatus.ERROR);
                        extract.setErrorMessage(e.getMessage());
                    }

                    return sourceChunkExtractRepository.save(extract);
                }, mrpVirtualThreadExecutor);

                futures.add(future);
            }

            // Wait for all Map jobs to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("[MRP Pipeline] [Map Phase] Đã hoàn tất Map Phase.");

            // --- PHASE 2: REDUCE PHASE (Tổng hợp, Deduplicate & Reconcile) ---
            log.info("[MRP Pipeline] [Reduce Phase] Bắt đầu tổng hợp tri thức trích xuất...");
            List<SourceChunkExtract> doneChunks = sourceChunkExtractRepository.findBySourceDocumentIdAndStatus(documentId, SourceChunkStatus.DONE);
            
            List<Map<String, Object>> allEntities = new ArrayList<>();
            List<Map<String, Object>> allConcepts = new ArrayList<>();
            List<Map<String, Object>> allClaims = new ArrayList<>();
            List<Map<String, Object>> allContradictions = new ArrayList<>();
            List<Map<String, Object>> allRecommendations = new ArrayList<>();

            for (SourceChunkExtract chunk : doneChunks) {
                try {
                    Map<String, Object> data = objectMapper.readValue(chunk.getExtractJson(), new TypeReference<>() {});
                    if (data.containsKey("entities")) {
                        allEntities.addAll((List<Map<String, Object>>) data.get("entities"));
                    }
                    if (data.containsKey("concepts")) {
                        allConcepts.addAll((List<Map<String, Object>>) data.get("concepts"));
                    }
                    if (data.containsKey("claims")) {
                        allClaims.addAll((List<Map<String, Object>>) data.get("claims"));
                    }
                    if (data.containsKey("contradictions")) {
                        allContradictions.addAll((List<Map<String, Object>>) data.get("contradictions"));
                    }
                    if (data.containsKey("recommendations")) {
                        allRecommendations.addAll((List<Map<String, Object>>) data.get("recommendations"));
                    }
                } catch (Exception e) {
                    log.warn("[MRP Pipeline] Không thể parse JSON chunk {}: {}", chunk.getChunkIndex(), e.getMessage());
                }
            }

            // Deduplicate entities & concepts, matching and grouping claims
            Map<String, String> entityDescriptions = new HashMap<>();
            Map<String, String> conceptDescriptions = new HashMap<>();
            Map<String, List<String>> subjectClaims = new HashMap<>();

            for (Map<String, Object> entity : allEntities) {
                String name = (String) entity.get("name");
                String desc = (String) entity.get("description");
                if (name != null && !name.isBlank()) {
                    entityDescriptions.merge(name.trim(), desc != null ? desc : "", (o, n) -> o.length() > n.length() ? o : n);
                }
            }

            for (Map<String, Object> concept : allConcepts) {
                String name = (String) concept.get("name");
                String desc = (String) concept.get("description");
                if (name != null && !name.isBlank()) {
                    conceptDescriptions.merge(name.trim(), desc != null ? desc : "", (o, n) -> o.length() > n.length() ? o : n);
                }
            }

            for (Map<String, Object> claim : allClaims) {
                String subject = (String) claim.get("subject");
                String assertion = (String) claim.get("claim");
                String sourceContext = (String) claim.get("sourceContext");
                if (subject != null && assertion != null && !subject.isBlank() && !assertion.isBlank()) {
                    String claimDetail = assertion.trim();
                    if (sourceContext != null && !sourceContext.isBlank()) {
                        claimDetail += " [Source Context: " + sourceContext.trim() + "]";
                    }
                    subjectClaims.computeIfAbsent(subject.trim(), k -> new ArrayList<>()).add(claimDetail);
                }
            }

            // Reconcile with existing WikiPages via Slug & Workspace
            List<Map<String, Object>> planningItems = new ArrayList<>();
            Set<String> uniqueSubjects = new HashSet<>();
            uniqueSubjects.addAll(entityDescriptions.keySet());
            uniqueSubjects.addAll(conceptDescriptions.keySet());

            List<WikiPage> existingPages = new ArrayList<>();
            existingPages.addAll(wikiPageRepository.findByWorkspaceId(finalWorkspaceId));
            if (!"ALL".equals(finalWorkspaceId) && !"GLOBAL".equals(finalWorkspaceId)) {
                existingPages.addAll(wikiPageRepository.findByWorkspaceId("ALL"));
                existingPages.addAll(wikiPageRepository.findByWorkspaceId("GLOBAL"));
            }

            StringBuilder existingPagesContext = new StringBuilder();
            Set<String> existingFolders = new HashSet<>();
            for (WikiPage page : existingPages) {
                existingPagesContext.append(String.format("- ID: %d | [[%s]] = %s (Type: %s, Current Tags: %s)\n", 
                    page.getId(), page.getSlug(), page.getTitle(), page.getPageType() != null ? page.getPageType().getValue() : "unknown", 
                    page.getTags() != null ? page.getTags() : "None"));
                if (page.getTags() != null && !page.getTags().trim().isEmpty()) {
                    for (String tag : page.getTags().split(",")) {
                        if (!tag.trim().isEmpty()) {
                            existingFolders.add(tag.trim());
                        }
                    }
                }
            }
            String existingFoldersContext = existingFolders.isEmpty() ? "None" : String.join(", ", existingFolders);

            for (String subject : uniqueSubjects) {
                String slug = slugify(subject);
                Optional<WikiPage> existingPage = wikiPageRepository.fetchBySlugAndWorkspaceId(slug, finalWorkspaceId);

                Map<String, Object> planItem = new HashMap<>();
                planItem.put("title", subject);
                planItem.put("slug", slug);
                
                if (existingPage.isPresent()) {
                    planItem.put("action", "UPDATE");
                    planItem.put("wikiPageId", existingPage.get().getId());
                    planItem.put("baseVersion", existingPage.get().getVersion());
                } else {
                    planItem.put("action", "CREATE");
                    planItem.put("wikiPageId", null);
                }

                planItem.put("pageType", entityDescriptions.containsKey(subject) ? "entity" : "concept");
                planItem.put("reason", String.format("Extracted from source document. Deduped & compiled."));
                
                List<String> claimsList = subjectClaims.getOrDefault(subject, new ArrayList<>());
                if (claimsList.isEmpty()) {
                    String desc = entityDescriptions.containsKey(subject) ? entityDescriptions.get(subject) : conceptDescriptions.get(subject);
                    if (desc != null && !desc.isBlank()) {
                        claimsList.add(desc);
                    }
                }
                planItem.put("keyClaims", claimsList);

                planningItems.add(planItem);
            }

            // --- ADD SOURCE PLAN ITEM ---
            Optional<Document> existingDoc = documentRepository.findById(documentId);
            if (existingDoc.isPresent()) {
                Document doc = existingDoc.get();
                String sourceTitle = doc.getFileName();
                if (sourceTitle == null || sourceTitle.isBlank()) {
                    sourceTitle = "Source Document " + documentId;
                } else {
                    int lastDot = sourceTitle.lastIndexOf('.');
                    if (lastDot > 0) {
                        sourceTitle = sourceTitle.substring(0, lastDot);
                    }
                }
                sourceTitle = sourceTitle.trim();

                String sourceSlug = "source/" + slugify(sourceTitle);
                Optional<WikiPage> existingSourcePage = wikiPageRepository.fetchBySlugAndWorkspaceId(sourceSlug, finalWorkspaceId);

                Map<String, Object> sourcePlanItem = new HashMap<>();
                sourcePlanItem.put("title", sourceTitle);
                sourcePlanItem.put("slug", sourceSlug);
                
                if (existingSourcePage.isPresent()) {
                    sourcePlanItem.put("action", "UPDATE");
                    sourcePlanItem.put("wikiPageId", existingSourcePage.get().getId());
                    sourcePlanItem.put("baseVersion", existingSourcePage.get().getVersion());
                } else {
                    sourcePlanItem.put("action", "CREATE");
                    sourcePlanItem.put("wikiPageId", null);
                }
                
                sourcePlanItem.put("pageType", "source");
                sourcePlanItem.put("reason", "Source document compiled as a reference entry linking to all extracted topics.");
                
                List<String> sourceClaims = new ArrayList<>();
                sourceClaims.add("Tài liệu gốc: " + doc.getFileName() + " [Source Context: " + doc.getFileName() + "]");
                sourceClaims.add("Tài liệu chứa thông tin chi tiết về các chủ đề chính: " + 
                    uniqueSubjects.stream().collect(Collectors.joining(", ")) + " [Source Context: " + doc.getFileName() + "]");
                sourcePlanItem.put("keyClaims", sourceClaims);

                planningItems.add(sourcePlanItem);
            }

            // Generate robust plan JSON via LLM compilation plan optimization
            String planJson;
            try {
                String rawItemsJson = objectMapper.writeValueAsString(planningItems);
                
                String contradictionsJson = "";
                String recommendationsJson = "";
                try {
                    if (!allContradictions.isEmpty()) {
                        contradictionsJson = objectMapper.writeValueAsString(allContradictions);
                    }
                    if (!allRecommendations.isEmpty()) {
                        recommendationsJson = objectMapper.writeValueAsString(allRecommendations);
                    }
                } catch (Exception e) {
                    log.warn("[MRP Pipeline] Error serializing contradictions/recommendations: {}", e.getMessage());
                }

                String planSystemPrompt = String.format("""
                    You are a Senior Technical Knowledge Architect.
                    You are given:
                    1. A list of existing wiki pages in the workspace (title, slug, type, current tags/folders, and database ID).
                    2. A list of existing folders (categories) in the workspace.
                    3. A rough list of newly extracted topics (entities/concepts) and their associated claims/facts.

                    Your job is to structure this into a professional, cohesive Wiki compilation plan.

                    CRITICAL DEDUPLICATION DIRECTIVES:
                    - Compare the newly extracted topics in the input list with the existing pages in the <existing_pages> block.
                    - If a newly extracted topic is semantically the same as (or highly related/synonymous with) an existing page (e.g. "JWT" vs "JSON Web Token", or "Docker Containers" vs "Docker"), you MUST deduplicate them:
                      * Reuse the existing page's title and slug.
                      * Set "action" to "UPDATE".
                      * Set "wikiPageId" to the database ID of the existing page (which you can find by checking the existing page's list in <existing_pages>).
                    - If the newly extracted topic is NOT in the existing pages, set "action" to "CREATE" and "wikiPageId" to null.

                    CRITICAL TAXONOMY PLANNING DIRECTIVES:
                    - Assign folder categories ("tags") to each plan item so the pages are organized into a navigation directory.
                    - Try to REUSE the existing folder labels from <existing_folders> character-for-character if they fit.
                    - If no existing folder fits, you can CREATE new, broad, durable folders (e.g. "Security", "Configuration", "Infrastructure", "General"). Group items of the same kind under the same folder.
                    - Each plan item can have 1 to 3 tags (hierarchical categories from broad to narrow, e.g. "Security" or "Security, Authentication").

                    Other guidelines:
                    1. Consolidate topics that are highly related to avoid a cluttered Wiki.
                    2. Verify names and write precise slugs.
                    3. Ensure that keyClaims are prioritized and concise.
                    4. Crucial: Do NOT modify or remove the "[Source Context: ...]" suffix of any claim, as these contain the original reference sentences.
                    5. Crucial: Do NOT introduce, expand, or add any new entities, concepts, facts, or details that are not explicitly present in the input list. You must only organize and structure what is provided.
                    6. For each plan item, suggest "crossReferences" — an array of slugs of OTHER plan items that are closely related and should be [[wikilinked]] together.
                    7. If contradictions were found during extraction, include them in "reviewItems" for the relevant plan item so human reviewers can resolve them.

                    <existing_folders>
                    %s
                    </existing_folders>

                    <existing_pages>
                    %s
                    </existing_pages>

                    You MUST return a valid JSON array of Plan Items matching this schema exactly.
                    [
                      {
                        "title": "Cohesive Title",
                        "slug": "url-friendly-slug",
                        "action": "CREATE" or "UPDATE",
                        "wikiPageId": 123 (if UPDATE, otherwise null),
                        "pageType": "entity" or "concept" or "topic" or "source",
                        "tags": ["Category 1", "Category 2"],
                        "reason": "Why this page needs creation or update",
                        "keyClaims": ["Detailed claim 1 [Source Context: ...]", "Detailed claim 2 [Source Context: ...]"],
                        "crossReferences": ["related-slug-1", "related-slug-2"],
                        "reviewItems": ["Contradiction: ..."]
                      }
                    ]
                    """, existingFoldersContext, existingPagesContext.toString());

                String reduceInput = rawItemsJson;
                if (!contradictionsJson.isEmpty() || !recommendationsJson.isEmpty()) {
                    StringBuilder sb = new StringBuilder(rawItemsJson);
                    sb.append("\n\n--- ADDITIONAL CONTEXT ---\n");
                    if (!contradictionsJson.isEmpty()) {
                        sb.append("Contradictions found during extraction:\n").append(contradictionsJson).append("\n");
                    }
                    if (!recommendationsJson.isEmpty()) {
                        sb.append("Recommendations found during extraction:\n").append(recommendationsJson).append("\n");
                    }
                    reduceInput = sb.toString();
                }

                log.info("[MRP Pipeline] [Reduce Phase] Đang gọi LLM tối ưu hóa Kế hoạch Biên soạn...");
                String planResponse = callChatWithRetry(provider, planSystemPrompt, reduceInput, REDUCE_PHASE_SCHEMA, "mrp-plan-" + documentId);
                log.info("[MRP Pipeline] [Reduce Phase] Raw LLM response: \n{}", planResponse);

                planJson = cleanJsonResponse(planResponse, false);
                log.info("[MRP Pipeline] [Reduce Phase] Cleaned JSON: \n{}", planJson);
                
                // Verify valid JSON and non-empty content
                JsonNode rootNode = objectMapper.readTree(planJson);
                if (rootNode.isEmpty()) {
                    throw new RuntimeException("LLM response parsed to an empty JSON structure");
                }
            } catch (Exception e) {
                log.error("[MRP Pipeline] [Reduce Phase] Gặp lỗi tối ưu hóa kế hoạch qua LLM, sử dụng cấu trúc thô: {}", e.getMessage());
                try {
                    planJson = objectMapper.writeValueAsString(planningItems);
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to serialize planning items: " + ex.getMessage());
                }
            }

            // Update SourceCompilationPlan record
            SourceCompilationPlan plan = sourceCompilationPlanRepository.findById(planId)
                    .orElseThrow(() -> new IllegalArgumentException("Plan not found with ID: " + planId));

            plan.setPlanJson(planJson);
            plan.setStatus(autoApprove ? SourceCompilationStatus.APPROVED : SourceCompilationStatus.PENDING_REVIEW);
            sourceCompilationPlanRepository.save(plan);
            natsEventPublisher.publishCompilationPlanUpdated(plan.getId(), plan.getSourceDocumentId(), finalWorkspaceId, plan.getStatus().name(), userId);

            log.info("[MRP Pipeline] [Reduce Phase] Đã hoàn thành Kế hoạch biên soạn ID: {}, trạng thái: {}", plan.getId(), plan.getStatus());

            if (autoApprove) {
                // Automatically execute Refine & Commit Phase
                executeCompilationPlan(plan.getId(), finalWorkspaceId, userId, true);
            }
        } catch (Exception e) {
            log.error("[MRP Pipeline] [Async] Error in background compilation process: {}", e.getMessage(), e);
            sourceCompilationPlanRepository.findById(planId).ifPresent(plan -> {
                plan.setStatus(SourceCompilationStatus.FAILED);
                plan.setReviewNote("Compilation failed: " + e.getMessage());
                sourceCompilationPlanRepository.save(plan);
                natsEventPublisher.publishCompilationPlanUpdated(plan.getId(), plan.getSourceDocumentId(), finalWorkspaceId, "FAILED", userId);
            });
        }
    }

    /**
     * Chạy quy trình biên soạn thực tế dựa trên Plan đã duyệt.
     * Tạo hoặc Cập nhật các Draft cho từng Wiki Page.
     * Nếu runAutoApproveDrafts=true, tự động commit trực tiếp các Draft thành trang Wiki chính thức.
     */
    @Transactional
    public void executeCompilationPlan(Long planId, String workspaceId, String userId, boolean runAutoApproveDrafts) {
        log.info("[MRP Pipeline] [Refine Phase] Đang thực thi Kế hoạch Biên soạn ID: {}, runAutoApproveDrafts={}", planId, runAutoApproveDrafts);

        SourceCompilationPlan plan = sourceCompilationPlanRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan not found with ID: " + planId));

        LlmProvider provider = llmFactory.getProvider("gemini");

        try {
            List<Map<String, Object>> planItems = parsePlanItems(plan.getPlanJson());
            
            Document doc = documentRepository.findById(plan.getSourceDocumentId()).orElse(null);
            String fullText = doc != null ? doc.getMarkdownContent() : "";

            // Lấy workspaceId trực tiếp từ document gốc để đảm bảo tính đồng bộ tuyệt đối trong toàn bộ flow
            String finalWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
            if (doc != null) {
                String docWs = ScopeNormalizer.normalizeWorkspace(doc.getWorkspaceId());
                String docDept = ScopeNormalizer.normalizeDepartment(doc.getDepartmentId());
                if (!"ALL".equals(docWs)) {
                    finalWorkspaceId = docWs;
                } else if (!"ALL".equals(docDept)) {
                    finalWorkspaceId = "ALL";
                }
            }

            log.info("[MRP Pipeline] [Refine Phase] Workspace được xác định để tạo Draft: {}", finalWorkspaceId);
            
            // Build the list of available titles for wikilinks
            List<WikiPage> existingPages = wikiPageRepository.findByWorkspaceId(finalWorkspaceId);
            Set<String> allAvailableTitles = new HashSet<>();
            for (Map<String, Object> item : planItems) {
                String t = (String) item.get("title");
                if (t != null) allAvailableTitles.add(t.trim());
            }
            for (WikiPage p : existingPages) {
                allAvailableTitles.add(p.getTitle().trim());
            }
            String availableTitlesList = allAvailableTitles.stream()
                .map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(", "));

            for (Map<String, Object> item : planItems) {
                String title = (String) item.get("title");
                String slug = (String) item.get("slug");
                String action = (String) item.get("action");
                String pageType = (String) item.get("pageType");
                if (pageType == null) {
                    pageType = (String) item.get("page_type");
                }
                List<String> keyClaims = (List<String>) item.get("keyClaims");
                if (keyClaims == null) {
                    keyClaims = (List<String>) item.get("key_claims");
                }
                
                Number wikiPageIdNum = (Number) item.get("wikiPageId");
                if (wikiPageIdNum == null) {
                    wikiPageIdNum = (Number) item.get("wiki_page_id");
                }
                Long wikiPageId = wikiPageIdNum != null ? wikiPageIdNum.longValue() : null;

                List<String> tagsList = (List<String>) item.get("tags");
                if (tagsList == null) {
                    tagsList = (List<String>) item.get("categoryPath");
                }
                String tagsString = null;
                if (tagsList != null && !tagsList.isEmpty()) {
                    tagsString = tagsList.stream()
                        .filter(t -> t != null && !t.trim().isEmpty())
                        .map(String::trim)
                        .collect(Collectors.joining(", "));
                }

                String claimsText = keyClaims != null 
                    ? keyClaims.stream().map(c -> "- " + c).collect(Collectors.joining("\n"))
                    : "";

                String generatedContent = "";
                Integer baseVersion = null;

                // Normalize page type
                String normalizedPageType = "concept";
                if (pageType != null) {
                    String pt = pageType.trim().toLowerCase();
                    if (pt.contains("entity") || pt.contains("organization") || pt.contains("technology") || pt.contains("tool") || pt.contains("product") || pt.contains("platform") || pt.contains("person") || pt.contains("people") || pt.contains("device") || pt.contains("system") || pt.contains("thực thể")) {
                        normalizedPageType = "entity";
                    } else if (pt.contains("concept") || pt.contains("paradigm") || pt.contains("framework") || pt.contains("procedure") || pt.contains("rule") || pt.contains("policy") || pt.contains("design") || pt.contains("definition") || pt.contains("khái niệm")) {
                        normalizedPageType = "concept";
                    } else if (pt.contains("topic") || pt.contains("subject") || pt.contains("theme") || pt.contains("area") || pt.contains("tag") || pt.contains("category") || pt.contains("chủ đề")) {
                        normalizedPageType = "topic";
                    } else if (pt.contains("source") || pt.contains("document") || pt.contains("file") || pt.contains("article") || pt.contains("reference") || pt.contains("news") || pt.contains("report") || pt.contains("nguồn tin") || pt.contains("nguồn")) {
                        normalizedPageType = "source";
                    }
                }

                // ── DIRECTION 3: SOURCE page = full document markdown + injected wiki links ──
                if ("source".equals(normalizedPageType)) {
                    String rawMarkdown = (doc != null && doc.getMarkdownContent() != null)
                            ? doc.getMarkdownContent() : "";
                    if (rawMarkdown.isBlank()) {
                        rawMarkdown = keyClaims != null
                                ? keyClaims.stream().map(c -> "- " + c).collect(Collectors.joining("\n"))
                                : "";
                    }
                    // Add document title heading if the markdown doesn't already start with #
                    String docTitle = title;
                    String finalMarkdown = rawMarkdown.stripLeading().startsWith("#")
                            ? rawMarkdown
                            : "# " + docTitle + "\n\n" + rawMarkdown;
                    generatedContent = injectWikilinks(finalMarkdown, allAvailableTitles);
                    log.info("[MRP Pipeline] [Refine Phase] SOURCE page '{}': using full document markdown ({} chars)", title, generatedContent.length());

                    if ("UPDATE".equals(action) && wikiPageId != null) {
                        WikiPage page = wikiPageRepository.findById(wikiPageId).orElse(null);
                        if (page != null) baseVersion = page.getVersion();
                    }

                } else if ("UPDATE".equals(action) && wikiPageId != null) {
                    WikiPage page = wikiPageRepository.findById(wikiPageId)
                            .orElseThrow(() -> new IllegalArgumentException("Target WikiPage not found ID: " + wikiPageId));
                    
                    baseVersion = page.getVersion();

                    // --- PROMPT MERGE ---
                    String mergeSystemPrompt = String.format("""
                        You are an expert technical wiki compiler. Your task is to merge new facts/content into an existing wiki page.
                        
                        CRITICAL GROUNDEDNESS & CITATION DIRECTIVES:
                        - You are strictly prohibited from generating any information, claims, assertions, details, metrics, or instructions that are not explicitly present in the "NEW CLAIMS TO MERGE" (check the "[Source Context: ...]" sections).
                        - Do NOT add external assumptions, background knowledge, or explanations outside what is explicitly provided.
                        - **Preserve Citations**: When merging new information with existing content, you MUST strictly preserve all existing inline chunk citations (e.g., [c003] or [Source Context: ...]).
                        - **Mandatory Tracing**: Any newly added factual claim, entity, or numerical data MUST be followed by its inline citation to the appropriate source chunk (e.g., [Source Context: ...]).
                        - **Close to Source Wording**: Stay close to the source wording. Reuse the source's own sentences; you may lightly reorder, deduplicate, and join related sentences, but do NOT rephrase for style, do NOT expand short statements into longer ones, and do NOT invent transitional sentences.
                        - **Do NOT Over-Structure**: Only introduce a section heading (##, ###) if the source itself uses that heading OR the page already has one from existing content. Avoid inventing a hierarchy of empty subsections.
                        - **Do NOT add rhetorical filler**: Phrases like "nhằm mục đích...", "cam kết mang lại...", "có ý nghĩa quan trọng", "nhằm giúp...", "designed to...", "aims to provide..." MUST NOT appear unless they are literally present in the source chunks.
                        
                        CONTRADICTIONS DIRECTIVES:
                        - If a new claim directly contradicts existing content, check if the newer info clearly supersedes it. If so, update the text to reflect the newer cited information AND add a brief "Contradictions / Updates" section at the bottom summarizing the change with citations.
                        - If the conflict is ambiguous or unresolved, do not overwrite the existing content; instead, add a "Contradictions / Updates" section describing the conflict with citations.

                        WIKILINK DIRECTIVES (CRITICAL FOR KNOWLEDGE GRAPH):
                        - You must scan the generated text to identify all mentions of the topics in this list: [%s].
                        - When you mention any topic listed, you MUST wrap it in double brackets like [[Topic Title]] on its first significant mention in the text.
                        - If the topic is written in a different grammatical variation (e.g. plural, lower-cased, possessive suffix, or translated alias), you MUST use the piped syntax: [[Topic Title|grammatical variation]] (for example: [[JWT Authentication|JWT authentications]], [[Docker|Docker's containers]], or [[Thực thể|thực thể]]).
                        - ONLY link to topics that are exactly in the provided list. Do NOT create links to pages that are not in this list.
                        - This is critical for connecting nodes on the visual map. If a topic is in the list, you must link to it.
                        
                        IMAGE DIRECTIVES (CRITICAL FOR INLINE IMAGES):
                        - You MUST preserve all image markers of the form ![caption](image://<uuid>) exactly as they are written in the new claims if they are relevant to this topic. Do NOT invent new UUIDs or change the image:// prefix.
                        
                        You MUST:
                        1. Carefully integrate all new claims/facts into the appropriate sections of the existing page content.
                        2. Maintain the structured, professional markdown style (titles, tables, bold text).
                        3. DO NOT delete, truncate, or lose any valuable context or sections from the original article.
                        4. Output ONLY the completed, fully updated markdown content.
                        """, availableTitlesList, availableTitlesList);

                    String imageSection = "";
                    List<String> imageMarkers = collectRelevantImageMarkers(keyClaims, fullText, normalizedPageType);
                    if (!imageMarkers.isEmpty()) {
                        imageSection = "\n## Images near this page's evidence\n"
                            + "The following image markers appear near the evidence for this page. "
                            + "Embed each marker VERBATIM in the most contextually appropriate section, "
                            + "or omit if not relevant. Do NOT invent image UUIDs.\n\n"
                            + String.join("\n", imageMarkers.stream().map(m -> "- " + m).toList())
                            + "\n";
                    }

                    String userContent = String.format(
                            "--- ORIGINAL WIKI CONTENT ---\n%s\n\n--- NEW CLAIMS TO MERGE ---\n%s\n\n%s", 
                            page.getContent(), claimsText, imageSection
                    );

                    log.info("[MRP Pipeline] [Refine Phase] Trộn nội dung (Prompt Merge) cho trang ID: {}", wikiPageId);
                    String mergedContent = provider.streamChat(mergeSystemPrompt, userContent, null, "mrp-merge-" + wikiPageId)
                             .collectList()
                             .map(list -> String.join("", list))
                             .block();

                    if (mergedContent != null) {
                        mergedContent = mergedContent.trim();
                        int maxInputLen = Math.max(page.getContent().length(), claimsText.length());
                        int minAcceptable = (int) (maxInputLen * 0.7);
                        if (mergedContent.length() < minAcceptable) {
                            log.warn("[MRP Pipeline] [Refine Phase] Merge rejected for page ID {} due to truncation (size: {}, threshold: {}). Falling back to new claims.", 
                                    wikiPageId, mergedContent.length(), minAcceptable);
                            generatedContent = claimsText;
                        } else {
                            generatedContent = mergedContent;
                        }
                    } else {
                        generatedContent = claimsText;
                    }

                } else {
                    // --- CREATE NEW PAGE ---
                    String createSystemPrompt = String.format("""
                        You are an expert enterprise wiki compiler. Your job is to draft a comprehensive and structured markdown wiki page based STRICTLY on the provided key claims.
                        
                        CRITICAL GROUNDEDNESS & CITATION DIRECTIVES:
                        - You are strictly prohibited from generating, expanding, or assuming any information, claims, assertions, metrics, details, or instructions that are not explicitly mentioned in the "Key Claims" (check the "[Source Context: ...]" sections).
                        - Every single fact, number, and name MUST be directly grounded in the source context provided.
                        - Do NOT extrapolate. If the context is very brief, write a very short, concise, but accurate wiki entry rather than adding hallucinated details or external knowledge.
                        - **Mandatory Tracing**: Any factual claim, entity, or numerical data MUST be followed by its inline citation to the appropriate source chunk (e.g. [Source Context: ...]).
                        - **Close to Source Wording**: Stay close to the source wording. Reuse the source's own sentences; you may lightly reorder, deduplicate, and join related sentences, but do NOT rephrase for style, do NOT expand short statements into longer ones, and do NOT invent transitional sentences.
                        - **Do NOT Over-Structure**: Only introduce a section heading (##, ###) if the source itself uses that heading. For flat source text, a single "# {Topic}" heading plus 1-2 short paragraphs and a flat list of facts is preferred over inventing a hierarchy of empty subsections.
                        - **Do NOT add rhetorical filler**: Phrases like "nhằm mục đích...", "cam kết mang lại...", "có ý nghĩa quan trọng", "nhằm giúp...", "designed to...", "aims to provide..." MUST NOT appear unless they are literally present in the source chunks.
                        
                        WIKILINK DIRECTIVES (CRITICAL FOR KNOWLEDGE GRAPH):
                        - You must scan the generated text to identify all mentions of the topics in this list: [%s].
                        - When you mention any topic listed, you MUST wrap it in double brackets like [[Topic Title]] on its first significant mention in the text.
                        - If the topic is written in a different grammatical variation (e.g. plural, lower-cased, possessive suffix, or translated alias), you MUST use the piped syntax: [[Topic Title|grammatical variation]] (for example: [[JWT Authentication|JWT authentications]], [[Docker|Docker's containers]], or [[Thực thể|thực thể]]).
                        - ONLY link to topics that are exactly in the provided list. Do NOT create links to pages that are not in this list.
                        - This is critical for connecting nodes on the visual map. If a topic is in the list, you must link to it.
                        
                        IMAGE DIRECTIVES (CRITICAL FOR INLINE IMAGES):
                        - You MUST preserve all image markers of the form ![caption](image://<uuid>) exactly as they are written in the key claims if they are relevant to this topic. Do NOT invent new UUIDs or change the image:// prefix.
                        
                        You MUST:
                        1. Structure the content logically with heading blocks (#, ##, ###).
                        2. Start with a solid opening definition block detailing what this topic is, relying only on the provided context.
                        3. Thoroughly structure the key claims/facts into clean markdown paragraphs or lists, citing the details.
                        4. Use markdown formatting like bold text, lists, and tables where suitable to maximize readability.
                        5. Output ONLY the raw markdown content.
                        """, availableTitlesList, availableTitlesList);

                    String imageSection = "";
                    List<String> imageMarkers = collectRelevantImageMarkers(keyClaims, fullText, normalizedPageType);
                    if (!imageMarkers.isEmpty()) {
                        imageSection = "\n## Images near this page's evidence\n"
                            + "The following image markers appear near the evidence for this page. "
                            + "Embed each marker VERBATIM in the most contextually appropriate section, "
                            + "or omit if not relevant. Do NOT invent image UUIDs.\n\n"
                            + String.join("\n", imageMarkers.stream().map(m -> "- " + m).toList())
                            + "\n";
                    }

                    String userContent = String.format(
                            "Topic: %s\nType: %s\nKey Claims:\n%s\n\n%s", 
                            title, normalizedPageType, claimsText, imageSection
                    );

                    log.info("[MRP Pipeline] [Refine Phase] Biên soạn trang mới: {}", title);
                    generatedContent = provider.streamChat(createSystemPrompt, userContent, null, "mrp-create-" + slug)
                            .collectList()
                            .map(list -> String.join("", list))
                            .block();
                }

                // Create a WikiPageDraft in PENDING state
                WikiPageDraft draft = WikiPageDraft.builder()
                        .wikiPageId(wikiPageId)
                        .sourceDocumentId(plan.getSourceDocumentId())
                        .slug(slug)
                        .title(title)
                        .pageType(WikiPageType.fromValue(normalizedPageType))
                        .content(generatedContent)
                        .summary(String.format("Compiled from document ID: %d", plan.getSourceDocumentId()))
                        .workspaceId(finalWorkspaceId)
                        .departmentId(plan.getDepartmentId())
                        .allowedRoles(plan.getAllowedRoles() != null ? plan.getAllowedRoles() : "ALL")
                        .securityClassification(plan.getSecurityClassification() != null ? plan.getSecurityClassification() : SecurityClassification.INTERNAL)
                        .authorId(userId)
                        .status(WikiPageDraftStatus.PENDING)
                        .baseVersion(baseVersion)
                        .tags(tagsString)
                        .note("Automatically compiled from MRP pipeline.")
                        .build();

                wikiPageDraftRepository.save(draft);
                natsEventPublisher.publishWikiDraftUpdated(draft.getId(), draft.getTitle(), draft.getSlug(), draft.getWorkspaceId(), draft.getStatus().name(), userId);
                log.info("[MRP Pipeline] [Refine Phase] Đã tạo Draft nháp ID: {} cho trang '{}'", draft.getId(), title);

                if (runAutoApproveDrafts) {
                    try {
                        // skipIssueDetection=true: avoid flooding Gemini with per-page LLM calls
                        // during batch pipeline runs (issue detection can be triggered manually later)
                        wikiDraftService.approveDraft(draft.getId(), "SYSTEM", true);
                        log.info("[MRP Pipeline] [Refine Phase] Auto-approved Draft ID: {}", draft.getId());
                    } catch (Exception e) {
                        log.error("[MRP Pipeline] [Refine Phase] Lỗi tự động duyệt Draft ID {}: {}", draft.getId(), e.getMessage());
                    }
                }
            }

            // Update plan status to DONE
            plan.setStatus(SourceCompilationStatus.DONE);
            plan.setReviewedBy(userId);
            plan.setReviewedAt(LocalDateTime.now());
            plan.setReviewNote("Plan executed successfully.");
            sourceCompilationPlanRepository.save(plan);
            natsEventPublisher.publishCompilationPlanUpdated(plan.getId(), plan.getSourceDocumentId(), finalWorkspaceId, "DONE", userId);

            // After auto-approve, rebuild all wiki links and index page so graph and index stay current
            if (runAutoApproveDrafts) {
                try {
                    wikiDraftService.rebuildAllLinksAndIndex(finalWorkspaceId, plan.getDepartmentId());
                    log.info("[MRP Pipeline] Rebuilt wiki links + index for workspace: {}", finalWorkspaceId);
                } catch (Exception e) {
                    log.warn("[MRP Pipeline] rebuildAllLinksAndIndex failed (non-fatal): {}", e.getMessage());
                }
            }

            log.info("[MRP Pipeline] Hoàn tất thực thi Kế hoạch Biên soạn ID: {}", planId);

        } catch (Exception e) {
            log.error("[MRP Pipeline] Gặp lỗi khi thực thi Kế hoạch Biên soạn: {}", e.getMessage(), e);
            throw new RuntimeException("Plan execution failed: " + e.getMessage(), e);
        }
    }

    // --- UTILITIES ---

    private List<Map<String, Object>> parsePlanItems(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return new ArrayList<>();
        }
        try {
            JsonNode rootNode = objectMapper.readTree(planJson);
            if (rootNode.isArray()) {
                return objectMapper.convertValue(rootNode, new TypeReference<List<Map<String, Object>>>() {});
            } else if (rootNode.isObject()) {
                // If it's a wrapper object (e.g. { "plans": [...] } or { "items": [...] })
                // find the first field that is an array
                Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
                boolean foundArray = false;
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (field.getValue().isArray()) {
                        return objectMapper.convertValue(field.getValue(), new TypeReference<List<Map<String, Object>>>() {});
                    }
                }
                // If it's not a wrapper but a single plan item (e.g. { "title": "..." })
                Map<String, Object> singleItem = objectMapper.convertValue(rootNode, new TypeReference<Map<String, Object>>() {});
                if (singleItem.containsKey("title") || singleItem.containsKey("slug")) {
                    List<Map<String, Object>> singleList = new ArrayList<>();
                    singleList.add(singleItem);
                    return singleList;
                }
            }
        } catch (Exception e) {
            log.error("[MRP Pipeline] Failed to parse robust plan JSON: {}", e.getMessage());
        }

        // Fallback to direct readValue if all parsing heuristics fail
        try {
            return objectMapper.readValue(planJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            log.error("[MRP Pipeline] Absolute fallback failed for: {}", planJson);
            return new ArrayList<>();
        }
    }

    private static String computeSHA256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Injects [[wiki links]] into markdown content for each matching topic title.
     * - Skips code fences (``` blocks) and existing [[...]] spans and image tags.
     * - Sorts titles longest-first to prevent partial overlaps (e.g. "BFI" clobbering "Baseflow Index (BFI)").
     * - Links each title at most once per document (first occurrence).
     */
    private String injectWikilinks(String markdown, Set<String> titles) {
        if (markdown == null || markdown.isBlank() || titles == null || titles.isEmpty()) {
            return markdown == null ? "" : markdown;
        }

        // Split markdown into segments: code-fence zones (protected) vs linkable zones
        // Pattern: ``` ... ``` blocks — we do NOT touch their contents
        java.util.regex.Pattern codeFencePattern = java.util.regex.Pattern.compile("(?s)(```.*?```)");
        java.util.regex.Matcher fenceMatcher = codeFencePattern.matcher(markdown);

        List<String> segments = new ArrayList<>();
        List<Boolean> isCode = new ArrayList<>();
        int lastEnd = 0;
        while (fenceMatcher.find()) {
            if (fenceMatcher.start() > lastEnd) {
                segments.add(markdown.substring(lastEnd, fenceMatcher.start()));
                isCode.add(false);
            }
            segments.add(fenceMatcher.group(1));
            isCode.add(true);
            lastEnd = fenceMatcher.end();
        }
        if (lastEnd < markdown.length()) {
            segments.add(markdown.substring(lastEnd));
            isCode.add(false);
        }

        // Sort titles by length descending — prevents shorter names from matching inside longer ones
        List<String> sortedTitles = new ArrayList<>(titles);
        sortedTitles.sort((a, b) -> b.length() - a.length());

        // Track which titles have been linked (link each at most once total)
        Set<String> linked = new HashSet<>();

        // Process each non-code segment
        for (int si = 0; si < segments.size(); si++) {
            if (Boolean.TRUE.equals(isCode.get(si))) continue;

            String seg = segments.get(si);
            for (String title : sortedTitles) {
                if (linked.contains(title) || title.isBlank() || title.length() < 3) continue;

                // Escape special regex chars in title for pattern matching
                String escaped = java.util.regex.Pattern.quote(title);
                // Match the title only when it is NOT already inside [[...]]
                // Negative lookbehind: not preceded by [[
                // Negative lookahead: not followed by ]]
                java.util.regex.Pattern titlePattern = java.util.regex.Pattern.compile(
                    "(?<!\\[\\[)(?<!\\[)" + escaped + "(?!\\]\\])(?!\\])",
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher m = titlePattern.matcher(seg);
                if (m.find()) {
                    // Replace only the FIRST occurrence in the entire document
                    seg = m.replaceFirst("[[" + title + "]]");
                    linked.add(title);
                }
            }
            segments.set(si, seg);
        }

        return String.join("", segments);
    }

    private String slugify(String title) {
        if (title == null) return "";
        return title.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}\\s-/]", "")
                .replaceAll("[\\s_]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .trim();
    }

    /**
     * Extracts the first valid JSON object ({...}) or array ([...]) from a raw LLM response.
     * This is intentionally model-agnostic: it does not rely on responseMimeType or prompt
     * instructions being followed. It handles:
     *   - Markdown code fences: ```json ... ```
     *   - Preamble text: "Here is the result: {...}"
     *   - Postamble text: "{...} Let me know if you need more."
     *   - Bullet lists wrapping JSON (Reduce phase: "* {...}")
     */
    private String cleanJsonResponse(String response) {
        return cleanJsonResponse(response, true);
    }

    private String cleanJsonResponse(String response, boolean isMapPhase) {
        if (response == null || response.isBlank()) return "{}";

        if (isMapPhase) {
            // Try to reconstruct if it is a list format for Map phase
            String reconstructed = reconstructMapPhaseJson(response);
            if (reconstructed != null) {
                return reconstructed;
            }
        }

        // Try extracting from markdown code blocks first
        String blockContent = extractMarkdownCodeBlock(response);
        if (blockContent != null) {
            String cleaned = cleanBasicJsonGarbage(blockContent);
            if (isValidJson(cleaned)) {
                return cleaned;
            }
        }

        // Search for '{' or '[' and try to find a valid JSON substring
        int len = response.length();
        for (int i = 0; i < len; i++) {
            char c = response.charAt(i);
            if (c == '{' || c == '[') {
                char closeChar = (c == '{') ? '}' : ']';
                int lastIdx = response.lastIndexOf(closeChar);
                while (lastIdx > i) {
                    String sub = response.substring(i, lastIdx + 1);
                    String cleaned = cleanBasicJsonGarbage(sub);
                    if (isValidJson(cleaned)) {
                        return cleaned;
                    }
                    // Try to find the next last index of closeChar before lastIdx
                    lastIdx = response.substring(0, lastIdx).lastIndexOf(closeChar);
                }
            }
        }

        // Fallback: original naive method
        return naiveCleanJsonResponse(response);
    }

    private String extractMarkdownCodeBlock(String response) {
        int jsonBlockStart = response.indexOf("```json");
        if (jsonBlockStart >= 0) {
            int contentStart = jsonBlockStart + 7;
            int blockEnd = response.indexOf("```", contentStart);
            if (blockEnd > contentStart) {
                return response.substring(contentStart, blockEnd);
            }
        }
        int genericBlockStart = response.indexOf("```");
        if (genericBlockStart >= 0) {
            int contentStart = genericBlockStart + 3;
            int blockEnd = response.indexOf("```", contentStart);
            if (blockEnd > contentStart) {
                return response.substring(contentStart, blockEnd);
            }
        }
        return null;
    }

    private String cleanBasicJsonGarbage(String str) {
        if (str == null) return "";
        String cleaned = str.trim();
        while (cleaned.startsWith("`") && cleaned.endsWith("`")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private boolean isValidJson(String str) {
        if (str == null || str.isBlank()) return false;
        try {
            objectMapper.readTree(str);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String naiveCleanJsonResponse(String response) {
        if (response == null || response.isBlank()) return "{}";
        
        String cleanedResp = response.replaceAll("```json", "")
                                     .replaceAll("```", "")
                                     .trim();

        int objStart = cleanedResp.indexOf('{');
        int arrStart = cleanedResp.indexOf('[');

        int start;
        char openChar;
        char closeChar;
        if (objStart >= 0 && (arrStart < 0 || objStart <= arrStart)) {
            start = objStart;
            openChar = '{';
            closeChar = '}';
        } else if (arrStart >= 0) {
            start = arrStart;
            openChar = '[';
            closeChar = ']';
        } else {
            log.warn("[MRP Pipeline] naiveCleanJsonResponse: no JSON structure found in response snippet: [{}]",
                    response.length() > 200 ? response.substring(0, 200) + "..." : response);
            return "{}";
        }

        int end = cleanedResp.lastIndexOf(closeChar);
        if (end <= start) {
            log.warn("[MRP Pipeline] naiveCleanJsonResponse: found open '{}' at {} but no matching '{}' after it",
                    openChar, start, closeChar);
            return cleanedResp.substring(start);
        }

        String cleaned = cleanedResp.substring(start, end + 1).trim();
        if (cleaned.contains("```")) {
            cleaned = cleaned.replaceAll("```json", "").replaceAll("```", "").trim();
        }
        return cleaned;
    }

    private List<String> splitIntoChunks(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return chunks;
        }
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            if (end == text.length()) {
                break;
            }
            start = end - overlap;
            if (start < 0) {
                start = 0;
            }
        }
        return chunks;
    }

    /**
     * Call the LLM provider with auto-retry and exponential backoff on HTTP 429 Rate Limits / Quotas.
     */
    private String callChatWithRetry(LlmProvider provider, String systemPrompt, String userMessage, String responseSchema, String conversationId) {
        int maxRetries = 5;
        long backoffMs = 2000;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                return provider.callChat(systemPrompt, userMessage, responseSchema, conversationId);
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "";
                boolean isRateLimit = errorMsg.contains("429") 
                        || errorMsg.toLowerCase().contains("rate limit") 
                        || errorMsg.toLowerCase().contains("quota exceeded") 
                        || errorMsg.toLowerCase().contains("too many requests");
                        
                if (isRateLimit && attempt < maxRetries) {
                    long sleepMs = backoffMs * (long) Math.pow(2, attempt - 1);
                    log.warn("[MRP Pipeline] Rate limit hit for conversation {}. Retrying attempt {}/{} after {}ms. Error: {}", 
                            conversationId, attempt, maxRetries, sleepMs, errorMsg);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Retry interrupted", ie);
                    }
                } else {
                    throw e; // Rethrow other exceptions, or rate limit if max retries exceeded
                }
            }
        }
        throw new RuntimeException("Failed to call LLM after " + maxRetries + " attempts due to rate limit.");
    }

    private String reconstructMapPhaseJson(String response) {
        if (response == null) return null;
        String trimmed = response.trim();
        String lower = trimmed.toLowerCase();
        
        if (trimmed.startsWith("{")) {
            return null;
        }
        
        int keyCount = 0;
        if (lower.contains("entities")) keyCount++;
        if (lower.contains("concepts")) keyCount++;
        if (lower.contains("claims")) keyCount++;
        
        // If it starts with an array symbol and contains claims/concepts, it's highly likely a Map phase list
        if (keyCount >= 2 || (trimmed.startsWith("[") && lower.contains("concepts") && lower.contains("claims"))) {
            String entities = extractArrayAfterKey(response, "entities");
            String concepts = extractArrayAfterKey(response, "concepts");
            String claims = extractArrayAfterKey(response, "claims");
            
            String reconstructed = String.format(
                "{\n  \"entities\": %s,\n  \"concepts\": %s,\n  \"claims\": %s\n}",
                entities, concepts, claims
            );
            if (isValidJson(reconstructed)) {
                log.info("[MRP Pipeline] Reconstructed Map Phase JSON successfully from list/bullet format.");
                return reconstructed;
            }
        }
        return null;
    }

    private String extractArrayAfterKey(String text, String key) {
        int keyIdx = text.toLowerCase().indexOf(key.toLowerCase());
        if (keyIdx < 0) {
            if ("entities".equalsIgnoreCase(key)) {
                int firstBracket = text.indexOf('[');
                if (firstBracket >= 0) {
                    return extractArrayFromIndex(text, firstBracket);
                }
            }
            return "[]";
        }
        
        int startIdx = text.indexOf('[', keyIdx);
        if (startIdx < 0) return "[]";
        
        return extractArrayFromIndex(text, startIdx);
    }

    private String extractArrayFromIndex(String text, int startIdx) {
        int bracketCount = 0;
        for (int i = startIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') bracketCount++;
            else if (c == ']') {
                bracketCount--;
                if (bracketCount == 0) {
                    return text.substring(startIdx, i + 1);
                }
            }
        }
        return "[]";
    }

    private List<String> collectRelevantImageMarkers(List<String> keyClaims, String fullText, String pageType) {
        List<String> ordered = new ArrayList<>();
        if (fullText == null || fullText.isEmpty()) {
            return ordered;
        }
        Set<String> seen = new HashSet<>();
        java.util.regex.Pattern imgPattern = java.util.regex.Pattern.compile("!\\[([^\\]]*)\\]\\(image://([0-9a-fA-F-]+)\\)");

        boolean grabAll = "source".equalsIgnoreCase(pageType) || keyClaims == null || keyClaims.isEmpty();

        if (!grabAll) {
            int window = 1500;
            for (String claim : keyClaims) {
                String sourceContext = "";
                int scIndex = claim.indexOf("[Source Context:");
                if (scIndex >= 0) {
                    sourceContext = claim.substring(scIndex + 16, claim.length() - 1).trim();
                }
                if (sourceContext.isEmpty()) {
                    continue;
                }

                int offset = fullText.indexOf(sourceContext);
                if (offset < 0) {
                    continue;
                }

                int start = Math.max(0, offset - window);
                int end = Math.min(fullText.length(), offset + sourceContext.length() + window);
                String windowText = fullText.substring(start, end);

                java.util.regex.Matcher matcher = imgPattern.matcher(windowText);
                while (matcher.find()) {
                    String marker = matcher.group(0);
                    if (!seen.contains(marker)) {
                        seen.add(marker);
                        ordered.add(marker);
                    }
                }
            }
        } else {
            java.util.regex.Matcher matcher = imgPattern.matcher(fullText);
            while (matcher.find()) {
                String marker = matcher.group(0);
                if (!seen.contains(marker)) {
                    seen.add(marker);
                    ordered.add(marker);
                }
            }
        }
        return ordered;
    }
}
