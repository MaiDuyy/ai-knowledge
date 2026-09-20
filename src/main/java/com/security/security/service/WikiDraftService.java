package com.security.security.service;

import com.security.security.dto.UserPermissionContext;
import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiPageDraft;
import com.security.security.event.NatsEventPublisher;
import com.security.security.repository.WikiPageDraftRepository;
import com.security.security.repository.WikiPageRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.security.security.entity.enumeration.WikiPageDraftStatus;
import com.security.security.entity.enumeration.SecurityClassification;

@Service
@Slf4j
@RequiredArgsConstructor
public class WikiDraftService {

    private final WikiPageDraftRepository wikiPageDraftRepository;
    private final WikiPageRepository wikiPageRepository;
    private final VectorStore vectorStore;
    private final com.security.security.repository.WikiLinkRepository wikiLinkRepository;
    private final NatsEventPublisher natsEventPublisher;
    private final ChatClient chatClient;
    private final WikiIssueService wikiIssueService;
    private final Optional<com.security.security.repository.mongo.MongoWikiPageRepository> mongoWikiPageRepository;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private WikiDraftService self;

    /**
     * Propose a new draft for a Wiki page
     */
    @Transactional
    public WikiPageDraft proposeDraft(WikiPageDraft draft) {
        String resolvedWorkspaceId = ScopeNormalizer.normalizeWorkspace(draft.getWorkspaceId());
        String targetDeptId = ScopeNormalizer.normalizeDepartment(draft.getDepartmentId());
        draft.setWorkspaceId(resolvedWorkspaceId);
        draft.setDepartmentId(targetDeptId);

        log.info("[WikiDraftService] Proposing draft for slug: {}, workspace: {}", draft.getSlug(), resolvedWorkspaceId);
        
        // Conflict Detection: check if another draft with status PENDING exists for the same slug and workspaceId
        List<WikiPageDraft> existingPendingDrafts = wikiPageDraftRepository.findBySlugAndWorkspaceId(draft.getSlug(), resolvedWorkspaceId);
        boolean hasPending = existingPendingDrafts.stream().anyMatch(d -> WikiPageDraftStatus.PENDING == d.getStatus());
        if (hasPending) {
            throw new IllegalStateException("A pending draft already exists for the slug: " + draft.getSlug() + " in this workspace.");
        }

        draft.setStatus(WikiPageDraftStatus.PENDING);
        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(), saved.getWorkspaceId(), saved.getStatus().name(), saved.getAuthorId());
        return saved;
    }

    /**
     * Approve a Wiki page draft and merge/commit it to the official WikiPage database & vector store.
     * Incorporates optimistic locking validation to avoid write-conflicts.
     */
    @Transactional
    public WikiPageDraft approveDraft(Long draftId, String reviewerId) {
        return approveDraft(draftId, reviewerId, false);
    }

    public WikiPageDraft approveDraft(Long draftId, String reviewerId, boolean skipIssueDetection) {
        log.info("[WikiDraftService] Approving draft ID: {} by {} (skipIssueDetection={})", draftId, reviewerId, skipIssueDetection);
        
        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        if (WikiPageDraftStatus.APPROVED == draft.getStatus()) {
            log.warn("[WikiDraftService] Draft {} is already approved", draftId);
            return draft;
        }

        WikiPage targetPage;
        boolean isUpdate = false;
        if (draft.getWikiPageId() != null) {
            isUpdate = true;
            // This is an UPDATE action
            targetPage = wikiPageRepository.findById(draft.getWikiPageId())
                    .orElseThrow(() -> new IllegalArgumentException("Target WikiPage not found with ID: " + draft.getWikiPageId()));

            // Optimistic lock check: Compare page current version with the draft base version
            if (draft.getBaseVersion() != null && !targetPage.getVersion().equals(draft.getBaseVersion())) {
                String errorMsg = String.format(
                        "Conflict detected! Page '%s' has been modified (version %d) since this draft was compiled (base version %d). Please re-compile/re-merge.",
                        targetPage.getTitle(), targetPage.getVersion(), draft.getBaseVersion()
                );
                log.error("[WikiDraftService] " + errorMsg);
                draft.setStatus(WikiPageDraftStatus.NEEDS_REVISION);
                draft.setReviewerNote(errorMsg);
                wikiPageDraftRepository.save(draft);
                natsEventPublisher.publishWikiDraftUpdated(draft.getId(), draft.getTitle(), draft.getSlug(), draft.getWorkspaceId(), "NEEDS_REVISION", reviewerId);
                throw new IllegalStateException(errorMsg);
            }

            String resolvedWorkspaceId = ScopeNormalizer.normalizeWorkspace(draft.getWorkspaceId());
            String targetDeptId = ScopeNormalizer.normalizeDepartment(draft.getDepartmentId());

            log.info("[WikiDraftService] Updating existing page '{}' (ID: {})", targetPage.getTitle(), targetPage.getId());
            targetPage.setTitle(draft.getTitle());
            targetPage.setContent(draft.getContent());
            targetPage.setTags(draft.getTags());
            targetPage.setPageType(draft.getPageType());
            targetPage.setSummary(draft.getSummary());
            targetPage.setWorkspaceId(resolvedWorkspaceId);
            targetPage.setDepartmentId(targetDeptId);
            targetPage.setAllowedRoles(draft.getAllowedRoles() != null ? draft.getAllowedRoles() : "ALL");
            targetPage.setSecurityClassification(draft.getSecurityClassification() != null ? draft.getSecurityClassification() : SecurityClassification.INTERNAL);
            if (draft.getSourceDocumentId() != null) {
                targetPage.setSourceDocumentId(draft.getSourceDocumentId());
            }
            
            // JPA handles @Version increments automatically
            targetPage = wikiPageRepository.save(targetPage);

        } else {
            // This is a CREATE action
            // Double check if a page with the same slug already exists in this workspace to prevent duplicates
            String resolvedWorkspaceId = ScopeNormalizer.normalizeWorkspace(draft.getWorkspaceId());
            String targetDeptId = ScopeNormalizer.normalizeDepartment(draft.getDepartmentId());

            Optional<WikiPage> existingPage = wikiPageRepository.fetchBySlugAndWorkspaceId(draft.getSlug(), resolvedWorkspaceId);
            if (existingPage.isPresent()) {
                isUpdate = true;
                targetPage = existingPage.get();
                log.info("[WikiDraftService] Matching slug '{}' already exists. Converting draft ID {} to UPDATE for page ID {}", 
                        draft.getSlug(), draftId, targetPage.getId());
                
                draft.setWikiPageId(targetPage.getId());
                draft.setBaseVersion(targetPage.getVersion());
                
                targetPage.setTitle(draft.getTitle());
                targetPage.setContent(draft.getContent());
                targetPage.setTags(draft.getTags());
                targetPage.setPageType(draft.getPageType());
                targetPage.setSummary(draft.getSummary());
                targetPage.setWorkspaceId(resolvedWorkspaceId);
                targetPage.setDepartmentId(targetDeptId);
                targetPage.setAllowedRoles(draft.getAllowedRoles() != null ? draft.getAllowedRoles() : "ALL");
                targetPage.setSecurityClassification(draft.getSecurityClassification() != null ? draft.getSecurityClassification() : SecurityClassification.INTERNAL);
                if (draft.getSourceDocumentId() != null) {
                    targetPage.setSourceDocumentId(draft.getSourceDocumentId());
                }
                targetPage = wikiPageRepository.save(targetPage);
            } else {
                log.info("[WikiDraftService] Creating new WikiPage '{}' from draft", draft.getTitle());
                targetPage = WikiPage.builder()
                        .title(draft.getTitle())
                        .slug(draft.getSlug())
                        .content(draft.getContent())
                        .tags(draft.getTags())
                        .workspaceId(resolvedWorkspaceId)
                        .departmentId(targetDeptId)
                        .allowedRoles(draft.getAllowedRoles() != null ? draft.getAllowedRoles() : "ALL")
                        .securityClassification(draft.getSecurityClassification() != null ? draft.getSecurityClassification() : SecurityClassification.INTERNAL)
                        .pageType(draft.getPageType())
                        .summary(draft.getSummary())
                        .sourceDocumentId(draft.getSourceDocumentId())
                        .build();

                targetPage = wikiPageRepository.save(targetPage);
                draft.setWikiPageId(targetPage.getId());
            }
        }

        // Vectorize the newly saved WikiPage asynchronously (tách biệt transaction)
        revectorizeWikiPage(targetPage, isUpdate);

        // Auto-detect quality issues in the newly saved WikiPage asynchronously.
        // Skipped during MRP pipeline batch runs to avoid exhausting the Gemini rate limit.
        if (!skipIssueDetection) {
            final WikiPage pageForIssueDetection = targetPage;
            CompletableFuture.runAsync(() -> {
                try {
                    wikiIssueService.detectIssuesForPage(pageForIssueDetection);
                } catch (Exception e) {
                    log.warn("[WikiDraftService] Issue detection failed asynchronously for page '{}': {}",
                            pageForIssueDetection.getSlug(), e.getMessage());
                }
            });
        }

        // Refresh knowledge graph wiki links
        refreshLinks(targetPage.getId(), targetPage.getSlug(), targetPage.getContent(), targetPage.getWorkspaceId());

        // Rebuild index page intro asynchronously
        rebuildIndexPage(targetPage.getWorkspaceId(), targetPage.getDepartmentId());

        // Update draft status
        draft.setStatus(WikiPageDraftStatus.APPROVED);
        draft.setReviewerNote("Approved and committed successfully.");
        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(), saved.getWorkspaceId(), "APPROVED", reviewerId);
        return saved;
    }

    /**
     * Rebuild the special overview wiki page with slug "index" for the workspace.
     * Uses LLM to generate/update the introductory paragraph summarizing recent wiki pages.
     */
    public void rebuildIndexPage(String workspaceId, String departmentId) {
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        String normalizedDeptId = ScopeNormalizer.normalizeDepartment(departmentId);

        log.info("[WikiDraftService] Rebuilding index page for workspace: {}, department: {}", normalizedWorkspaceId, normalizedDeptId);

        CompletableFuture.runAsync(() -> {
            try {
                // Fetch all wiki pages, keep only SOURCE type (original uploaded documents)
                List<WikiPage> pages = wikiPageRepository.findAccessiblePages(
                    normalizedWorkspaceId, normalizedDeptId, true, true, List.of(), List.of());

                List<WikiPage> sourcePages = pages.stream()
                    .filter(p -> !"index".equalsIgnoreCase(p.getSlug()))
                    .filter(p -> com.security.security.entity.enumeration.WikiPageType.SOURCE.equals(p.getPageType())
                              || (p.getSlug() != null && p.getSlug().startsWith("source/")))
                    .sorted(Comparator.comparing(WikiPage::getTitle, String.CASE_INSENSITIVE_ORDER))
                    .toList();

                if (sourcePages.isEmpty()) {
                    log.info("[WikiDraftService] No source wiki pages found. Skipping index page rebuild.");
                    return;
                }

                // Build index content: static header + [[slug|title]] links to source documents only.
                // No LLM needed — the index is a document library, not an AI summary.
                StringBuilder contentBuilder = new StringBuilder();
                contentBuilder.append("# Thư Viện Tài Liệu\n\n");
                contentBuilder.append("Danh sách các tài liệu gốc đã được xử lý và lập chỉ mục trong hệ thống tri thức:\n\n");
                for (WikiPage p : sourcePages) {
                    contentBuilder.append("- [[")
                        .append(p.getSlug())
                        .append("|")
                        .append(p.getTitle())
                        .append("]]\n");
                }
                String finalContent = contentBuilder.toString();

                // Create or update the "index" page
                Optional<WikiPage> existingIndexPageOpt = wikiPageRepository.fetchBySlugAndWorkspaceId("index", normalizedWorkspaceId, normalizedDeptId);
                WikiPage indexPage;
                if (existingIndexPageOpt.isPresent()) {
                    indexPage = existingIndexPageOpt.get();
                    indexPage.setContent(finalContent);
                    indexPage.setSummary("Danh sách tài liệu gốc trong hệ thống tri thức.");
                    log.info("[WikiDraftService] Updating existing index page with {} source documents.", sourcePages.size());
                } else {
                    indexPage = WikiPage.builder()
                        .title("Thư Viện Tài Liệu")
                        .slug("index")
                        .content(finalContent)
                        .summary("Danh sách tài liệu gốc trong hệ thống tri thức.")
                        .workspaceId(normalizedWorkspaceId)
                        .departmentId(normalizedDeptId)
                        .allowedRoles("ALL")
                        .securityClassification(SecurityClassification.INTERNAL)
                        .pageType(com.security.security.entity.enumeration.WikiPageType.CONCEPT)
                        .build();
                    log.info("[WikiDraftService] Creating new index page with {} source documents.", sourcePages.size());
                }

                WikiPage savedIndex = wikiPageRepository.save(indexPage);
                revectorizeWikiPageSync(savedIndex, existingIndexPageOpt.isPresent());
                // refreshLinks parses [[...]] from content → graph edges: index → source pages only
                refreshLinks(savedIndex.getId(), savedIndex.getSlug(), savedIndex.getContent(), savedIndex.getWorkspaceId());
                log.info("[WikiDraftService] Successfully rebuilt index page for workspace: {} ({} source docs)", normalizedWorkspaceId, sourcePages.size());
            } catch (Exception e) {
                log.error("[WikiDraftService] Error rebuilding index page: {}", e.getMessage(), e);
            }
        });
    }


    /**
     * Vectorize (or re-vectorize) a WikiPage in the VectorStore asynchronously.
     * Includes pageType, slug, and sourceDocumentId metadata for RAG filtering and lineage tracking.
     */
    public void revectorizeWikiPage(WikiPage page, boolean isUpdate) {
        final WikiPage p = page;
        CompletableFuture.runAsync(() -> {
            log.info("[WikiDraftService] Starting async VectorStore sync for WikiPage ID: {}, isUpdate: {}", p.getId(), isUpdate);
            try {
                if (isUpdate) {
                    try {
                        vectorStore.delete(String.format("wikiPageId == '%s'", p.getId()));
                        log.info("[WikiDraftService] Deleted old embedding for page ID {}", p.getId());
                    } catch (Exception ex) {
                        log.warn("[WikiDraftService] Could not delete old embedding for page ID {}: {}", p.getId(), ex.getMessage());
                    }
                }
                List<Document> documents = buildWikiDocuments(p);
                vectorStore.add(documents);
                log.info("[WikiDraftService] Vectorized WikiPage ID: {} in {} chunks (pageType={}, slug={}) successfully",
                        p.getId(), documents.size(), p.getPageType(), p.getSlug());
            } catch (Exception e) {
                log.error("[WikiDraftService] Error syncing WikiPage ID {} to VectorStore: {}", p.getId(), e.getMessage(), e);
            }
        });
    }

    /**
     * Synchronous variant for bulk operations — blocks until vectorization completes.
     */
    public void revectorizeWikiPageSync(WikiPage page, boolean isUpdate) {
        log.info("[WikiDraftService] Sync VectorStore update for WikiPage ID: {}", page.getId());
        try {
            if (isUpdate) {
                try {
                    vectorStore.delete(String.format("wikiPageId == '%s'", page.getId()));
                } catch (Exception ex) {
                    log.warn("[WikiDraftService] Could not delete old embedding for page ID {}: {}", page.getId(), ex.getMessage());
                }
            }
            List<Document> documents = buildWikiDocuments(page);
            vectorStore.add(documents);
            log.info("[WikiDraftService] Sync vectorized WikiPage ID: {} in {} chunks successfully", page.getId(), documents.size());
        } catch (Exception e) {
            log.error("[WikiDraftService] Error syncing WikiPage ID {}: {}", page.getId(), e.getMessage(), e);
        }
    }

    // gemini-embedding-001 limit: 8192 tokens. At worst-case 2 chars/token (Vietnamese),
    // 8000 chars ≈ 4000 tokens — safely below the limit even with the title prefix.
    private static final int EMBED_CHUNK_CHARS = 8000;

    /**
     * Splits wiki page content into embedding-safe Document objects.
     * Handles oversized paragraphs and long continuous text that lacks paragraph breaks.
     */
    private List<Document> buildWikiDocuments(WikiPage page) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("wikiPageId", page.getId().toString());
        metadata.put("workspaceId", ScopeNormalizer.normalizeWorkspace(page.getWorkspaceId()));
        metadata.put("departmentId", ScopeNormalizer.normalizeDepartment(page.getDepartmentId()));
        metadata.put("allowedRoles", page.getAllowedRoles() != null ? page.getAllowedRoles() : "ALL");
        metadata.put("classification", page.getSecurityClassification() != null ? page.getSecurityClassification().name() : "INTERNAL");
        metadata.put("securityClassification", page.getSecurityClassification() != null ? page.getSecurityClassification().name() : "INTERNAL");
        metadata.put("type", "wiki");
        metadata.put("pageType", page.getPageType() != null ? page.getPageType().getValue() : "");
        metadata.put("slug", page.getSlug() != null ? page.getSlug() : "");
        metadata.put("sourceDocumentId", page.getSourceDocumentId() != null ? page.getSourceDocumentId().toString() : "");
        metadata.put("fileName", page.getTitle() != null ? page.getTitle() : "");

        List<String> chunks = splitContentIntoChunks(
                page.getContent() != null ? page.getContent() : "");

        String titlePrefix = page.getTitle() != null ? page.getTitle() : "";
        List<Document> documents = new java.util.ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String prefix = (i == 0 ? "Tiêu đề: " : "Tiêu đề (Tiếp theo): ") + titlePrefix + "\n\n";
            String chunkContent = prefix + chunks.get(i);

            Map<String, Object> chunkMetadata = new HashMap<>(metadata);
            chunkMetadata.put("chunkIndex", String.valueOf(i));
            chunkMetadata.put("totalChunks", String.valueOf(chunks.size()));

            String docId = java.util.UUID.nameUUIDFromBytes(
                    ("wiki-" + page.getId() + "-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            documents.add(new Document(docId, chunkContent, chunkMetadata));
        }
        return documents;
    }

    /**
     * Splits content into chunks that fit within EMBED_CHUNK_CHARS.
     *
     * Strategy (in order):
     *   1. Split by PAGE_BREAK markers (multi-page documents)
     *   2. Split each part by double-newline (paragraphs)
     *   3. If a single paragraph still exceeds the limit, split by sentence boundaries
     *   4. If a single sentence still exceeds the limit, hard-cut at word boundaries
     */
    private List<String> splitContentIntoChunks(String content) {
        List<String> chunks = new java.util.ArrayList<>();
        if (content.isBlank()) {
            chunks.add("");
            return chunks;
        }

        String[] pageParts = content.split("\\s*<!--\\s*PAGE_BREAK:\\s*\\d+\\s*-->\\s*");
        for (String part : pageParts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() <= EMBED_CHUNK_CHARS) {
                chunks.add(trimmed);
                continue;
            }
            // Split by paragraph (double newline)
            String[] paragraphs = trimmed.split("\\r?\\n\\r?\\n");
            StringBuilder current = new StringBuilder();
            for (String para : paragraphs) {
                if (para.isBlank()) continue;
                // Each paragraph that fits in the remaining space goes into the current chunk
                if (current.length() > 0 && current.length() + 2 + para.length() > EMBED_CHUNK_CHARS) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                if (para.length() <= EMBED_CHUNK_CHARS) {
                    if (current.length() > 0) current.append("\n\n");
                    current.append(para);
                } else {
                    // Paragraph itself too large → flush current first, then split paragraph
                    if (current.length() > 0) {
                        chunks.add(current.toString().trim());
                        current.setLength(0);
                    }
                    splitLargeParagraph(para, chunks);
                }
            }
            if (current.length() > 0) chunks.add(current.toString().trim());
        }
        if (chunks.isEmpty()) chunks.add("");
        return chunks;
    }

    /** Split a paragraph that individually exceeds EMBED_CHUNK_CHARS by sentences, then by words. */
    private void splitLargeParagraph(String para, List<String> out) {
        // Try sentence boundaries: ". ", ".\n", "! ", "? ", "。"
        String[] sentences = para.split("(?<=[.!?。])[\\s]+");
        StringBuilder cur = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.isBlank()) continue;
            if (cur.length() > 0 && cur.length() + 1 + sentence.length() > EMBED_CHUNK_CHARS) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
            if (sentence.length() > EMBED_CHUNK_CHARS) {
                // Single sentence still too long → hard-cut at word boundaries
                if (cur.length() > 0) { out.add(cur.toString().trim()); cur.setLength(0); }
                hardCutAtWords(sentence, out);
            } else {
                if (cur.length() > 0) cur.append(" ");
                cur.append(sentence);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
    }

    /** Last-resort: split at word boundaries every EMBED_CHUNK_CHARS characters. */
    private void hardCutAtWords(String text, List<String> out) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + EMBED_CHUNK_CHARS, text.length());
            if (end < text.length()) {
                // Step back to nearest space to avoid cutting mid-word
                int space = text.lastIndexOf(' ', end);
                if (space > start) end = space;
            }
            out.add(text.substring(start, end).trim());
            start = end;
            while (start < text.length() && text.charAt(start) == ' ') start++;
        }
    }

    /**
     * Re-index all wiki pages in a workspace. Processes in batches of 10 synchronously.
     * Returns stats: { reindexed, errors, durationMs }.
     */
    public Map<String, Object> reindexAllPages(String workspaceId) {
        long start = System.currentTimeMillis();
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        List<WikiPage> pages = wikiPageRepository.findByWorkspaceId(normalizedWorkspaceId);
        int reindexed = 0;
        int errors = 0;
        int batchSize = 10;

        for (int i = 0; i < pages.size(); i += batchSize) {
            List<WikiPage> batch = pages.subList(i, Math.min(i + batchSize, pages.size()));
            for (WikiPage page : batch) {
                try {
                    revectorizeWikiPageSync(page, true);
                    reindexed++;
                } catch (Exception e) {
                    errors++;
                    log.error("[WikiDraftService] Reindex failed for page ID {}: {}", page.getId(), e.getMessage());
                }
            }
            log.info("[WikiDraftService] Reindex batch {}/{} complete ({} pages processed)",
                    Math.min(i + batchSize, pages.size()), pages.size(), reindexed);
        }

        long duration = System.currentTimeMillis() - start;
        log.info("[WikiDraftService] Reindex complete: {} pages, {} errors, {}ms", reindexed, errors, duration);

        Map<String, Object> result = new HashMap<>();
        result.put("reindexed", reindexed);
        result.put("errors", errors);
        result.put("durationMs", duration);
        result.put("total", pages.size());
        return result;
    }

    @Transactional
    public void refreshLinks(Long fromPageId, String fromSlug, String contentMd, String workspaceId) {
        refreshLinks(fromPageId, fromSlug, contentMd, workspaceId, true);
    }

    /**
     * @param includeImplicitMentions when false, only explicit {@code [[wikilink]]} syntax is resolved
     *                                (used by GERBIL benchmark scenarios with controlled manifest content)
     */
    @Transactional
    public void refreshLinks(Long fromPageId, String fromSlug, String contentMd, String workspaceId,
                             boolean includeImplicitMentions) {
        try {
            wikiLinkRepository.deleteByFromPageId(fromPageId);
            wikiLinkRepository.flush(); // Force database deletion immediately to avoid unique key conflicts during re-insert

            if (contentMd == null || contentMd.isBlank()) return;

            List<String> targets = com.security.security.dto.WikiPageMetadataDto.extractLinks(contentMd);

            String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
            List<WikiPage> allPages = new java.util.ArrayList<>();
            allPages.addAll(wikiPageRepository.findByWorkspaceId(normalizedWorkspaceId));
            if (!"ALL".equals(normalizedWorkspaceId) && !"GLOBAL".equals(normalizedWorkspaceId)) {
                allPages.addAll(wikiPageRepository.findByWorkspaceId("ALL"));
                allPages.addAll(wikiPageRepository.findByWorkspaceId("GLOBAL"));
            }
            
            // Build lookup indexes for O(1) resolution
            java.util.Map<String, String> slugToSlug = new java.util.HashMap<>();
            java.util.Map<String, String> titleToSlug = new java.util.HashMap<>();
            for (WikiPage p : allPages) {
                if (p.getSlug() != null) slugToSlug.put(p.getSlug().toLowerCase(), p.getSlug());
                if (p.getTitle() != null) titleToSlug.put(p.getTitle().toLowerCase(), p.getSlug());
            }

            java.util.Set<String> uniqueSlugs = new java.util.HashSet<>();

            // Pass 1: explicit [[wikilink]] / [[wikilink|display]] syntax
            for (String target : targets) {
                String trimmedTarget = target.trim();
                if (trimmedTarget.isEmpty()) continue;
                String resolved = resolveWikiTarget(trimmedTarget, slugToSlug, titleToSlug);
                if (resolved != null && !resolved.isEmpty() && !resolved.equals(fromSlug)) {
                    uniqueSlugs.add(resolved);
                }
            }

            if (includeImplicitMentions) {
                // Pass 2: implicit title-mention links — catches pages compiled without [[...]] syntax
                // Strips markdown formatting chars first, then does case-insensitive substring match.
                String contentForMention = contentMd.toLowerCase()
                        .replaceAll("[*_`#>\\[\\]|]", " ")
                        .replaceAll("\\s+", " ");
                for (java.util.Map.Entry<String, String> entry : titleToSlug.entrySet()) {
                    String titleKey = entry.getKey();   // already lowercase
                    String targetSlug = entry.getValue();
                    // Skip very short titles (high false-positive rate) and self-references
                    if (titleKey.length() < 5 || targetSlug.equals(fromSlug)) continue;
                    if (contentForMention.contains(titleKey)) {
                        uniqueSlugs.add(targetSlug);
                    }
                }
            }

            List<com.security.security.entity.WikiLink> linksToSave = new java.util.ArrayList<>();
            for (String tSlug : uniqueSlugs) {
                linksToSave.add(com.security.security.entity.WikiLink.builder()
                        .fromPageId(fromPageId)
                        .toSlug(tSlug)
                        .build());
            }

            if (!linksToSave.isEmpty()) {
                wikiLinkRepository.saveAll(linksToSave);
                wikiLinkRepository.flush(); // Flush saves immediately to keep the persistence context clean
            }

            if (mongoWikiPageRepository != null && mongoWikiPageRepository.isPresent()) {
                try {
                    var mongoRepo = mongoWikiPageRepository.get();
                    wikiPageRepository.findById(fromPageId).ifPresent(page -> {
                        var existingMongoPage = mongoRepo.findByPostgresWikiPageId(fromPageId)
                                .or(() -> mongoRepo.findBySlug(page.getSlug()))
                                .orElseGet(() -> com.security.security.entity.mongo.MongoWikiPage.builder()
                                        .postgresWikiPageId(page.getId())
                                        .title(page.getTitle())
                                        .slug(page.getSlug())
                                        .workspaceId(ScopeNormalizer.normalizeWorkspace(page.getWorkspaceId()))
                                        .departmentId(ScopeNormalizer.normalizeDepartment(page.getDepartmentId()))
                                        .build());

                        existingMongoPage.setOutboundSlugs(new java.util.ArrayList<>(uniqueSlugs));
                        existingMongoPage.setContent(page.getContent());
                        existingMongoPage.setTitle(page.getTitle());
                        mongoRepo.save(existingMongoPage);
                    });
                } catch (Exception ex) {
                    log.warn("[WikiDraftService] Non-blocking MongoWikiPage sync skipped: {}", ex.getMessage());
                }
            }

            log.info("[WikiDraftService] Refreshed {} graph links for page ID {}", uniqueSlugs.size(), fromPageId);
        } catch (Exception e) {
            log.error("[WikiDraftService] Error refreshing graph links for page ID {}: {}", fromPageId, e.getMessage(), e);
            throw e;
        }
    }
    public Map<String, Object> rebuildAllLinksAndIndex(String workspaceId, String departmentId) {
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        String normalizedDeptId = ScopeNormalizer.normalizeDepartment(departmentId);
        log.info("[WikiDraftService] Rebuilding all wiki links for workspace: {}", normalizedWorkspaceId);

        List<WikiPage> pages = wikiPageRepository.findAccessiblePages(
            normalizedWorkspaceId, normalizedDeptId, true, true, List.of(), List.of());

        int refreshed = 0;
        int errors = 0;
        WikiDraftService rebuilder = (self != null) ? self : this;
        for (WikiPage page : pages) {
            try {
                if (page.getContent() != null && !page.getContent().isBlank()) {
                    rebuilder.refreshLinks(page.getId(), page.getSlug(), page.getContent(), page.getWorkspaceId());
                    refreshed++;
                }
            } catch (Exception e) {
                errors++;
                log.error("[WikiDraftService] refreshLinks failed for page {}: {}", page.getSlug(), e.getMessage());
            }
        }

        log.info("[WikiDraftService] Refreshed links for {}/{} pages ({} errors). Now rebuilding index page.", refreshed, pages.size(), errors);
        rebuildIndexPage(normalizedWorkspaceId, normalizedDeptId);

        Map<String, Object> result = new HashMap<>();
        result.put("pagesRefreshed", refreshed);
        result.put("pagesTotal", pages.size());
        result.put("errors", errors);
        result.put("workspaceId", normalizedWorkspaceId);
        return result;
    }

    /**
     * Resolve a wikilink target to an actual page slug.
     * Target may be: a full slug ("concept/jwt-auth"), a title ("JWT Authentication"),
     * or a slugified title ("jwt-authentication").
     * Resolution order: direct slug → title → slugified title → type-prefixed slug → original.
     */
    private String resolveWikiTarget(
            String target,
            java.util.Map<String, String> slugToSlug,
            java.util.Map<String, String> titleToSlug) {
        // 1. Direct slug match — handles [[concept/jwt-auth]] inserted by MarkdownEditor
        String bySlug = slugToSlug.get(target.toLowerCase());
        if (bySlug != null) return bySlug;

        // 2. Title match — handles [[JWT Authentication]] generated by LLM
        String byTitle = titleToSlug.get(target.toLowerCase());
        if (byTitle != null) return byTitle;

        // 3. Slugified title match — handles [[jwt authentication]] or [[jwt-authentication]]
        String slugified = slugifyTitle(target);
        String bySlugified = slugToSlug.get(slugified);
        if (bySlugified != null) return bySlugified;

        // 4. Type-prefixed slug match — handles bare slug "jwt-auth" when page is "concept/jwt-auth"
        for (String prefix : List.of("concept/", "entity/", "topic/", "source/")) {
            String prefixed = slugToSlug.get(prefix + slugified);
            if (prefixed != null) return prefixed;
        }

        // 5. If target already looks like a valid slug path, keep it as-is (dangling link to future page)
        if (target.contains("/") && target.matches("[a-z0-9][a-z0-9/_-]*")) {
            return target.toLowerCase();
        }

        // 6. Last resort: return slugified form only if non-empty
        return slugified.isEmpty() ? null : slugified;
    }

    /** Slugify a plain text title — strips special chars except hyphens. Does NOT strip path separators. */
    private String slugifyTitle(String title) {
        if (title == null) return "";
        return title.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}\\s-/]", "")
                .replaceAll("[\\s_]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "")
                .trim();
    }

    /** @deprecated use resolveWikiTarget() + slugifyTitle() instead */
    private String slugify(String title) {
        return slugifyTitle(title);
    }

    /**
     * Reject a Wiki page draft with reviewer feedback
     */
    @Transactional
    public WikiPageDraft rejectDraft(Long draftId, String reviewerId, String note) {
        log.info("[WikiDraftService] Rejecting draft ID: {} by {}", draftId, reviewerId);
        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        draft.setStatus(WikiPageDraftStatus.REJECTED);
        draft.setReviewerNote(note);
        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(), saved.getWorkspaceId(), "REJECTED", reviewerId);
        return saved;
    }

    /**
     * Send back draft to user for revisions
     */
    @Transactional
    public WikiPageDraft requestChanges(Long draftId, String reviewerId, String note) {
        log.info("[WikiDraftService] Requesting changes for draft ID: {} by {}", draftId, reviewerId);
        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        draft.setStatus(WikiPageDraftStatus.NEEDS_REVISION);
        draft.setReviewerNote(note);
        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(), saved.getWorkspaceId(), "NEEDS_REVISION", reviewerId);
        return saved;
    }

    /**
     * Author resubmits a NEEDS_REVISION draft back to PENDING for another review cycle
     */
    @Transactional
    public WikiPageDraft submitRevision(Long draftId, String authorId, String newContent, String revisionNote) {
        log.info("[WikiDraftService] Submitting revision for draft ID: {} by {}", draftId, authorId);
        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        if (draft.getStatus() != WikiPageDraftStatus.NEEDS_REVISION) {
            throw new IllegalStateException("Only NEEDS_REVISION drafts can be resubmitted. Current status: " + draft.getStatus());
        }

        if (newContent != null && !newContent.isBlank()) {
            draft.setContent(newContent);
        }
        draft.setStatus(WikiPageDraftStatus.PENDING);
        draft.setReviewerNote(null);
        String existingNote = draft.getNote() != null ? draft.getNote() : "";
        if (revisionNote != null && !revisionNote.isBlank()) {
            draft.setNote(existingNote.isBlank() ? revisionNote : existingNote + " | " + revisionNote);
        }

        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(),
                saved.getWorkspaceId(), "PENDING", authorId);
        return saved;
    }

    /**
     * Author withdraws a draft (sets to WITHDRAWN, preventing further review)
     */
    @Transactional
    public WikiPageDraft withdrawDraft(Long draftId, String authorId) {
        log.info("[WikiDraftService] Withdrawing draft ID: {} by {}", draftId, authorId);
        WikiPageDraft draft = wikiPageDraftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("Draft not found with ID: " + draftId));

        draft.setStatus(WikiPageDraftStatus.WITHDRAWN);
        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(),
                saved.getWorkspaceId(), "WITHDRAWN", authorId);
        return saved;
    }

    /**
     * Retrieve all pending drafts
     */
    public List<WikiPageDraft> getPendingDrafts() {
        return wikiPageDraftRepository.findByStatus(WikiPageDraftStatus.PENDING);
    }

    /**
     * Retrieve all pending drafts with pagination
     */
    public Page<WikiPageDraft> getPendingDrafts(Pageable pageable) {
        return wikiPageDraftRepository.findByStatus(WikiPageDraftStatus.PENDING, pageable);
    }

    /**
     * Retrieve drafts by workspace
     */
    public List<WikiPageDraft> getDraftsByWorkspace(String workspaceId) {
        return wikiPageDraftRepository.findByWorkspaceId(ScopeNormalizer.normalizeWorkspace(workspaceId));
    }

    /**
     * Auto link draft content by inserting double bracket links around keywords matching existing slugs
     */
    public String autoLinkDraftContent(String content, String workspaceId, UserPermissionContext perm) {
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        List<WikiPage> pages = wikiPageRepository.findAccessiblePages(
                normalizedWorkspaceId, null, perm.isAdmin(), perm.hasHeadRole(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
        if (pages.isEmpty()) {
            return content;
        }
        List<String> slugs = pages.stream().map(WikiPage::getSlug).collect(Collectors.toList());
        
        String systemPrompt = """
            You are an AI Co-Editor helping to insert internal wiki links.
            Your task is to analyze the provided markdown content and identify terms, concepts, or exact matches that correspond to the list of allowed slugs.
            
            WIKILINKING RULES:
            1. For any identified keyword, wrap it in double brackets with its matching slug, like this: [[slug]].
            2. If a term matches a slug but is written differently in the text (e.g. capitalized, plural, or translated alias), wrap the text and reference the slug, like this: [[slug|original text]]. (For example: if slug is "entity/jwt-auth" and the text is "JWT Authentication", write [[entity/jwt-auth|JWT Authentication]]).
            3. Only link terms that correspond exactly to the provided list of allowed slugs. Do NOT invent slugs.
            4. **No Link Spamming**: Only wrap the first significant occurrence of a term in each major section. Do NOT link the same term repeatedly in every sentence.
            5. Do NOT add extra explanations or commentary, do NOT wrap the output in markdown code blocks (such as ```markdown ... ```). Return ONLY the updated markdown content.
            
            Allowed slugs:
            %s
            """.formatted(slugs.toString());

        return chatClient.prompt()
                .system(systemPrompt)
                .user(content)
                .call()
                .content();
    }
}
