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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
        log.info("[WikiDraftService] Proposing draft for slug: {}, workspace: {}", draft.getSlug(), draft.getWorkspaceId());
        
        // Conflict Detection: check if another draft with status PENDING exists for the same slug and workspaceId
        List<WikiPageDraft> existingPendingDrafts = wikiPageDraftRepository.findBySlugAndWorkspaceId(draft.getSlug(), draft.getWorkspaceId());
        boolean hasPending = existingPendingDrafts.stream().anyMatch(d -> "PENDING".equals(d.getStatus()));
        if (hasPending) {
            throw new IllegalStateException("A pending draft already exists for the slug: " + draft.getSlug() + " in this workspace.");
        }

        draft.setStatus("PENDING");
        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(), saved.getWorkspaceId(), saved.getStatus(), saved.getAuthorId());
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

        if ("APPROVED".equals(draft.getStatus())) {
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
                draft.setStatus("NEEDS_REVISION");
                draft.setReviewerNote(errorMsg);
                wikiPageDraftRepository.save(draft);
                natsEventPublisher.publishWikiDraftUpdated(draft.getId(), draft.getTitle(), draft.getSlug(), draft.getWorkspaceId(), "NEEDS_REVISION", reviewerId);
                throw new IllegalStateException(errorMsg);
            }

            log.info("[WikiDraftService] Updating existing page '{}' (ID: {})", targetPage.getTitle(), targetPage.getId());
            targetPage.setTitle(draft.getTitle());
            targetPage.setContent(draft.getContent());
            targetPage.setTags(draft.getTags());
            targetPage.setPageType(draft.getPageType());
            targetPage.setSummary(draft.getSummary());
            targetPage.setDepartmentId(draft.getDepartmentId());
            targetPage.setAllowedRoles(draft.getAllowedRoles() != null ? draft.getAllowedRoles() : "ALL");
            targetPage.setSecurityClassification(draft.getSecurityClassification() != null ? draft.getSecurityClassification() : "INTERNAL");
            
            // JPA handles @Version increments automatically
            targetPage = wikiPageRepository.save(targetPage);

        } else {
            // This is a CREATE action
            // Double check if a page with the same slug already exists in this workspace to prevent duplicates
            Optional<WikiPage> existingPage = wikiPageRepository.fetchBySlugAndWorkspaceId(draft.getSlug(), draft.getWorkspaceId());
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
                targetPage.setDepartmentId(draft.getDepartmentId());
                targetPage.setAllowedRoles(draft.getAllowedRoles() != null ? draft.getAllowedRoles() : "ALL");
                targetPage.setSecurityClassification(draft.getSecurityClassification() != null ? draft.getSecurityClassification() : "INTERNAL");
                targetPage = wikiPageRepository.save(targetPage);
            } else {
                log.info("[WikiDraftService] Creating new WikiPage '{}' from draft", draft.getTitle());
                targetPage = WikiPage.builder()
                        .title(draft.getTitle())
                        .slug(draft.getSlug())
                        .content(draft.getContent())
                        .tags(draft.getTags())
                        .workspaceId(draft.getWorkspaceId())
                        .departmentId(draft.getDepartmentId())
                        .allowedRoles(draft.getAllowedRoles() != null ? draft.getAllowedRoles() : "ALL")
                        .securityClassification(draft.getSecurityClassification() != null ? draft.getSecurityClassification() : "INTERNAL")
                        .pageType(draft.getPageType())
                        .summary(draft.getSummary())
                        .build();

                targetPage = wikiPageRepository.save(targetPage);
                draft.setWikiPageId(targetPage.getId());
            }
        }

        // Vectorize the newly saved WikiPage asynchronously (tách biệt transaction)
        final WikiPage finalTargetPage = targetPage;
        final boolean finalIsUpdate = isUpdate;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            log.info("[WikiDraftService] Starting async VectorStore sync for WikiPage ID: {}, isUpdate: {}", finalTargetPage.getId(), finalIsUpdate);
            try {
                if (finalIsUpdate) {
                    try {
                        vectorStore.delete(String.format("wikiPageId == '%s'", finalTargetPage.getId()));
                        log.info("[WikiDraftService] Async deleted old embedding for page ID {}", finalTargetPage.getId());
                    } catch (Exception ex) {
                        log.warn("[WikiDraftService] Could not delete old embedding for page ID {} in async task: {}", finalTargetPage.getId(), ex.getMessage());
                    }
                }

                Document vectorDoc = new Document(
                        "Tiêu đề: " + finalTargetPage.getTitle() + "\n\n" + finalTargetPage.getContent(),
                        Map.of(
                                "wikiPageId", finalTargetPage.getId().toString(),
                                "workspaceId", finalTargetPage.getWorkspaceId() != null ? finalTargetPage.getWorkspaceId() : "",
                                "departmentId", finalTargetPage.getDepartmentId() != null ? finalTargetPage.getDepartmentId() : "",
                                "allowedRoles", finalTargetPage.getAllowedRoles() != null ? finalTargetPage.getAllowedRoles() : "ALL",
                                "classification", finalTargetPage.getSecurityClassification() != null ? finalTargetPage.getSecurityClassification() : "INTERNAL",
                                "securityClassification", finalTargetPage.getSecurityClassification() != null ? finalTargetPage.getSecurityClassification() : "INTERNAL",
                                "type", "wiki"
                        )
                );
                // Use targetPage.getId().toString() as vector ID to ensure we can delete it easily
                String docId = java.util.UUID.nameUUIDFromBytes(("wiki-" + finalTargetPage.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                Document vectorDocWithId = new Document(docId, vectorDoc.getText(), vectorDoc.getMetadata());
                vectorStore.add(List.of(vectorDocWithId));
                log.info("[WikiDraftService] Vectorized WikiPage ID: {} successfully in VectorStore asynchronously", finalTargetPage.getId());
            } catch (Exception e) {
                log.error("[WikiDraftService] Error syncing WikiPage ID {} to VectorStore asynchronously: {}", finalTargetPage.getId(), e.getMessage(), e);
            }
        });

        // Refresh knowledge graph wiki links
        refreshLinks(targetPage.getId(), targetPage.getSlug(), targetPage.getContent(), targetPage.getWorkspaceId());

        // Update draft status
        draft.setStatus("APPROVED");
        draft.setReviewerNote("Approved and committed successfully.");
        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(), saved.getWorkspaceId(), "APPROVED", reviewerId);
        return saved;
    }

    private void refreshLinks(Long fromPageId, String fromSlug, String contentMd, String workspaceId) {
        try {
            wikiLinkRepository.deleteByFromPageId(fromPageId);
            List<String> targets = com.security.security.dto.WikiPageMetadataDto.extractLinks(contentMd);
            if (targets == null || targets.isEmpty()) {
                return;
            }

            List<WikiPage> allPages = wikiPageRepository.findByWorkspaceId(workspaceId);
            
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

        draft.setStatus("REJECTED");
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

        draft.setStatus("NEEDS_REVISION");
        draft.setReviewerNote(note);
        WikiPageDraft saved = wikiPageDraftRepository.save(draft);
        natsEventPublisher.publishWikiDraftUpdated(saved.getId(), saved.getTitle(), saved.getSlug(), saved.getWorkspaceId(), "NEEDS_REVISION", reviewerId);
        return saved;
    }

    /**
     * Retrieve all pending drafts
     */
    public List<WikiPageDraft> getPendingDrafts() {
        return wikiPageDraftRepository.findByStatus("PENDING");
    }

    /**
     * Retrieve all pending drafts with pagination
     */
    public Page<WikiPageDraft> getPendingDrafts(Pageable pageable) {
        return wikiPageDraftRepository.findByStatus("PENDING", pageable);
    }

    /**
     * Retrieve drafts by workspace
     */
    public List<WikiPageDraft> getDraftsByWorkspace(String workspaceId) {
        return wikiPageDraftRepository.findByWorkspaceId(workspaceId);
    }

    /**
     * Auto link draft content by inserting double bracket links around keywords matching existing slugs
     */
    public String autoLinkDraftContent(String content, String workspaceId, UserPermissionContext perm) {
        List<WikiPage> pages = wikiPageRepository.findAccessiblePages(
                workspaceId, null, perm.isAdmin(), perm.getDeptIdsWhereHead(), perm.getDeptIdsWhereMember());
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
