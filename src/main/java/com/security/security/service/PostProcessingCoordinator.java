package com.security.security.service;

import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.repository.DocumentRepository;
import com.security.security.event.NatsEventPublisher;
import com.security.security.service.tika.SemanticMarkdownChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Orchestrates async post-processing subtasks after document chunking completes.
 * Inspired by WeKnora's PendingSubtasksCount pattern:
 * document transitions to COMPLETED only when ALL subtasks finish.
 *
 * Subtasks:
 *   1. Summary generation (LLM)
 *   2. Synthetic Q&A generation (LLM per chunk)
 *   3. Wiki compilation trigger (MRP pipeline)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PostProcessingCoordinator {

    private final DocumentRepository documentRepository;
    private final EmbeddingService embeddingService;
    private final NatsEventPublisher natsEventPublisher;
    private final DebouncedWikiTrigger debouncedWikiTrigger;

    private static final int SUBTASK_SUMMARY = 1;
    private static final int SUBTASK_QA = 1;
    private static final int SUBTASK_WIKI = 1;
    private static final int TOTAL_SUBTASKS = SUBTASK_SUMMARY + SUBTASK_QA + SUBTASK_WIKI;

    /**
     * Kick off all post-processing subtasks after chunking.
     * Sets document to FINALIZING stage with pendingSubtasks counter.
     */
    @Transactional
    public void startPostProcessing(Document document, String markdownContent, List<SemanticMarkdownChunker.ChunkResult> chunkResults) {
        log.info("[PostProcess] Starting post-processing for document id={}", document.getId());

        document.setPendingSubtasks(TOTAL_SUBTASKS);
        document.setProcessingStage("FINALIZING");
        documentRepository.save(document);

        // 1. Summary generation
        Thread.startVirtualThread(() -> {
            try {
                log.info("[PostProcess] Subtask SUMMARY started for doc={}", document.getId());
                String summary = embeddingService.generateDocumentSummary(markdownContent);
                if (summary != null && !summary.isBlank()) {
                    updateSummary(document.getId(), summary);
                }
            } catch (Exception e) {
                log.error("[PostProcess] SUMMARY failed for doc={}: {}", document.getId(), e.getMessage());
            } finally {
                decrementAndCheckCompletion(document.getId());
            }
        });

        // 2. Synthetic Q&A generation (sync variant — blocks until all Q&A is indexed)
        Thread.startVirtualThread(() -> {
            try {
                log.info("[PostProcess] Subtask QA started for doc={}", document.getId());
                List<Embedding> childEmbeddings = embeddingService.getChildEmbeddings(document.getId());
                embeddingService.generateAndIndexQuestionsSync(document, childEmbeddings);
            } catch (Exception e) {
                log.error("[PostProcess] QA failed for doc={}: {}", document.getId(), e.getMessage());
            } finally {
                decrementAndCheckCompletion(document.getId());
            }
        });

        // 3. Wiki compilation (debounced batch)
        Thread.startVirtualThread(() -> {
            try {
                log.info("[PostProcess] Subtask WIKI queued for doc={}", document.getId());
                debouncedWikiTrigger.enqueue(document.getId(), document.getWorkspaceId(), document.getUserId());
            } catch (Exception e) {
                log.error("[PostProcess] WIKI trigger failed for doc={}: {}", document.getId(), e.getMessage());
            } finally {
                decrementAndCheckCompletion(document.getId());
            }
        });
    }

    @Transactional
    void updateSummary(Long documentId, String summary) {
        documentRepository.findById(documentId).ifPresent(doc -> {
            doc.setSummary(summary);
            documentRepository.save(doc);
        });
    }

    @Transactional
    void decrementAndCheckCompletion(Long documentId) {
        documentRepository.decrementPendingSubtasks(documentId);

        documentRepository.findById(documentId).ifPresent(doc -> {
            int remaining = doc.getPendingSubtasks() != null ? doc.getPendingSubtasks() : 0;

            if (remaining <= 0) {
                doc.setProcessingStage("COMPLETED");
                doc.setStatus(DocStatus.COMPLETED);
                documentRepository.save(doc);
                natsEventPublisher.publishDocumentStatus(doc.getId(), doc.getUserId(), doc.getWorkspaceId(), "COMPLETED");
                log.info("[PostProcess] All subtasks completed for doc={}. Status → COMPLETED", documentId);
            } else {
                log.debug("[PostProcess] doc={} has {} subtasks remaining", documentId, remaining);
            }
        });
    }
}
