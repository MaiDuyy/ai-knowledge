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
        log.info("[WikiDraftService] Approving draft ID: {} by {}", draftId, reviewerId);
        
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
                        .build();

                targetPage = wikiPageRepository.save(targetPage);
                draft.setWikiPageId(targetPage.getId());
            }
        }

        // Vectorize the newly saved WikiPage asynchronously (tách biệt transaction)
        revectorizeWikiPage(targetPage, isUpdate);

        // Refresh knowledge graph wiki links
        refreshLinks(targetPage.getId(), targetPage.getSlug(), targetPage.getContent(), targetPage.getWorkspaceId());

        // Update draft status
        draft.setStatus(WikiPageDraftStatus.APPROVED);
        draft.setReviewerNote("Approved and committed successfully.");
        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(), saved.getWorkspaceId(), "APPROVED", reviewerId);
        return saved;
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

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("wikiPageId", p.getId().toString());
                metadata.put("workspaceId", ScopeNormalizer.normalizeWorkspace(p.getWorkspaceId()));
                metadata.put("departmentId", ScopeNormalizer.normalizeDepartment(p.getDepartmentId()));
                metadata.put("allowedRoles", p.getAllowedRoles() != null ? p.getAllowedRoles() : "ALL");
                metadata.put("classification", p.getSecurityClassification() != null ? p.getSecurityClassification().name() : "INTERNAL");
                metadata.put("securityClassification", p.getSecurityClassification() != null ? p.getSecurityClassification().name() : "INTERNAL");
                metadata.put("type", "wiki");
                metadata.put("pageType", p.getPageType() != null ? p.getPageType().getValue() : "");
                metadata.put("slug", p.getSlug() != null ? p.getSlug() : "");
                metadata.put("sourceDocumentId", p.getSourceDocumentId() != null ? p.getSourceDocumentId().toString() : "");
                metadata.put("fileName", p.getTitle() != null ? p.getTitle() : "");

                Document vectorDoc = new Document(
                        "Tiêu đề: " + p.getTitle() + "\n\n" + p.getContent(),
                        metadata
                );
                String docId = java.util.UUID.nameUUIDFromBytes(
                        ("wiki-" + p.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                Document vectorDocWithId = new Document(docId, vectorDoc.getText(), vectorDoc.getMetadata());
                vectorStore.add(List.of(vectorDocWithId));
                log.info("[WikiDraftService] Vectorized WikiPage ID: {} (pageType={}, slug={}) successfully",
                        p.getId(), p.getPageType(), p.getSlug());
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

            Document vectorDoc = new Document(
                    "Tiêu đề: " + page.getTitle() + "\n\n" + page.getContent(),
                    metadata
            );
            String docId = java.util.UUID.nameUUIDFromBytes(
                    ("wiki-" + page.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            Document vectorDocWithId = new Document(docId, vectorDoc.getText(), vectorDoc.getMetadata());
            vectorStore.add(List.of(vectorDocWithId));
            log.info("[WikiDraftService] Sync vectorized WikiPage ID: {} successfully", page.getId());
        } catch (Exception e) {
            log.error("[WikiDraftService] Error syncing WikiPage ID {}: {}", page.getId(), e.getMessage(), e);
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

    private void refreshLinks(Long fromPageId, String fromSlug, String contentMd, String workspaceId) {
        try {
            wikiLinkRepository.deleteByFromPageId(fromPageId);
            List<String> targets = com.security.security.dto.WikiPageMetadataDto.extractLinks(contentMd);
            if (targets == null || targets.isEmpty()) {
                return;
            }

            String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
            List<WikiPage> allPages = new java.util.ArrayList<>();
            allPages.addAll(wikiPageRepository.findByWorkspaceId(normalizedWorkspaceId));
            if (!"ALL".equals(normalizedWorkspaceId) && !"GLOBAL".equals(normalizedWorkspaceId)) {
                allPages.addAll(wikiPageRepository.findByWorkspaceId("ALL"));
                allPages.addAll(wikiPageRepository.findByWorkspaceId("GLOBAL"));
            }
            
            java.util.Set<String> uniqueSlugs = new java.util.HashSet<>();
            for (String target : targets) {
                String trimmedTarget = target.trim();
                String targetSlug = slugify(trimmedTarget);
                
                // Resolve actual page slug
                String resolvedSlug = null;
                for (WikiPage p : allPages) {
                    if (p.getTitle().equalsIgnoreCase(trimmedTarget)) {
                        resolvedSlug = p.getSlug();
                        break;
                    }
                    if (p.getSlug().equalsIgnoreCase(targetSlug)) {
                        resolvedSlug = p.getSlug();
                        break;
                    }
                    if (p.getSlug().equalsIgnoreCase("source/" + targetSlug)) {
                        resolvedSlug = p.getSlug();
                        break;
                    }
                }
                
                if (resolvedSlug == null) {
                    resolvedSlug = targetSlug; // fallback
                }
                
                if (!resolvedSlug.isEmpty() && !resolvedSlug.equals(fromSlug)) {
                    uniqueSlugs.add(resolvedSlug);
                }
            }
            
            for (String tSlug : uniqueSlugs) {
                wikiLinkRepository.save(com.security.security.entity.WikiLink.builder()
                        .fromPageId(fromPageId)
                        .toSlug(tSlug)
                        .build());
            }
            log.info("[WikiDraftService] Refreshed {} graph links for page ID {}", uniqueSlugs.size(), fromPageId);
        } catch (Exception e) {
            log.error("[WikiDraftService] Error refreshing graph links for page ID {}: {}", fromPageId, e.getMessage(), e);
        }
    }

    private String slugify(String title) {
        if (title == null) return "";
        return title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .trim();
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
                normalizedWorkspaceId, null, perm.isAdmin(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
        if (pages.isEmpty()) {
            return content;
        }
        List<String> slugs = pages.stream().map(WikiPage::getSlug).collect(Collectors.toList());
        
        String systemPrompt = """
            You are an AI Co-Editor helping to insert internal wiki links.
            Your task is to analyze the provided markdown content and identify terms, concepts, or exact matches that correspond to the list of allowed slugs.
            For any identified keyword, wrap it in double brackets with its matching slug, like this: [[slug]].
            If a term matches a slug but is written differently in the text (e.g. capitalized, plural, or translated), wrap the text and reference the slug, like this: [[slug|original text]].
            Only link terms that correspond to the provided list of allowed slugs. Do not invent slugs.
            Do not add extra explanations or commentary, return ONLY the updated markdown content.
            
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
