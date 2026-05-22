package com.security.security.service;

import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiPageDraft;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Service
@Slf4j
@RequiredArgsConstructor
public class WikiDraftService {

    private final WikiPageDraftRepository wikiPageDraftRepository;
    private final WikiPageRepository wikiPageRepository;
    private final VectorStore vectorStore;

    /**
     * Propose a new draft for a Wiki page
     */
    @Transactional
    public WikiPageDraft proposeDraft(WikiPageDraft draft) {
        log.info("[WikiDraftService] Proposing draft for slug: {}, workspace: {}", draft.getSlug(), draft.getWorkspaceId());
        draft.setStatus("PENDING");
        return wikiPageDraftRepository.save(draft);
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
        if (draft.getWikiPageId() != null) {
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
                throw new IllegalStateException(errorMsg);
            }

            log.info("[WikiDraftService] Updating existing page '{}' (ID: {})", targetPage.getTitle(), targetPage.getId());
            targetPage.setTitle(draft.getTitle());
            targetPage.setContent(draft.getContent());
            targetPage.setTags(draft.getTags());
            targetPage.setPageType(draft.getPageType());
            targetPage.setSummary(draft.getSummary());
            
            // JPA handles @Version increments automatically
            targetPage = wikiPageRepository.save(targetPage);

            // Sync Vector Store: Delete old vector and insert new one
            try {
                String docId = java.util.UUID.nameUUIDFromBytes(("wiki-" + targetPage.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                vectorStore.delete(List.of(docId));
            } catch (Exception e) {
                log.warn("[WikiDraftService] Could not delete old embedding for page ID {}: {}", targetPage.getId(), e.getMessage());
            }

        } else {
            // This is a CREATE action
            // Double check if a page with the same slug already exists in this workspace to prevent duplicates
            Optional<WikiPage> existingPage = wikiPageRepository.findBySlugAndWorkspaceId(draft.getSlug(), draft.getWorkspaceId());
            if (existingPage.isPresent()) {
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
                targetPage = wikiPageRepository.save(targetPage);
                
                try {
                    String docId = java.util.UUID.nameUUIDFromBytes(("wiki-" + targetPage.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    vectorStore.delete(List.of(docId));
                } catch (Exception e) {
                    log.warn("[WikiDraftService] Could not delete old embedding for page ID {}: {}", targetPage.getId(), e.getMessage());
                }
            } else {
                log.info("[WikiDraftService] Creating new WikiPage '{}' from draft", draft.getTitle());
                targetPage = WikiPage.builder()
                        .title(draft.getTitle())
                        .slug(draft.getSlug())
                        .content(draft.getContent())
                        .tags(draft.getTags())
                        .workspaceId(draft.getWorkspaceId())
                        .pageType(draft.getPageType())
                        .summary(draft.getSummary())
                        .build();

                targetPage = wikiPageRepository.save(targetPage);
                draft.setWikiPageId(targetPage.getId());
            }
        }

        // Vectorize the newly saved WikiPage
        try {
            Document vectorDoc = new Document(
                    "Tiêu đề: " + targetPage.getTitle() + "\n\n" + targetPage.getContent(),
                    Map.of(
                            "wikiPageId", targetPage.getId().toString(),
                            "workspaceId", targetPage.getWorkspaceId() != null ? targetPage.getWorkspaceId() : "",
                            "type", "wiki"
                    )
            );
            // Use targetPage.getId().toString() as vector ID to ensure we can delete it easily
            // But standard Spring AI vectorStore.add doesn't take separate ID unless it's set in the document ID
            String docId = java.util.UUID.nameUUIDFromBytes(("wiki-" + targetPage.getId()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            Document vectorDocWithId = new Document(docId, vectorDoc.getText(), vectorDoc.getMetadata());
            vectorStore.add(List.of(vectorDocWithId));
            log.info("[WikiDraftService] Vectorized WikiPage ID: {} successfully in VectorStore", targetPage.getId());
        } catch (Exception e) {
            log.error("[WikiDraftService] Error indexing WikiPage ID {} to VectorStore: {}", targetPage.getId(), e.getMessage(), e);
        }

        // Update draft status
        draft.setStatus("APPROVED");
        draft.setReviewerNote("Approved and committed successfully.");
        return wikiPageDraftRepository.save(draft);
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
        return wikiPageDraftRepository.save(draft);
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
        return wikiPageDraftRepository.save(draft);
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
}
