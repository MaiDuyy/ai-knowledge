package com.security.security.resource;

import com.security.security.dto.RAGResponseDTO;
import com.security.security.dtorequest.DocumentSyncPayload;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.service.EmbeddingService;
import com.security.security.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal API controller - called exclusively by knowledge-service (Node.js).
 * NOT exposed to the public internet.  
 * Provides: document indexing, RAG queries, and document deletion.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class InternalAIController {

    private final RAGService ragService;
    private final EmbeddingService embeddingService;

    /**
     * Permission-aware RAG query.
     * Called by: knowledge-service → POST /api/rag/query
     */
    @PostMapping("/rag/query")
    public ResponseEntity<RAGResponseDTO> query(@RequestBody RAGQueryPayload payload) {
        log.info("Received RAG query from knowledge-service for user: {}", payload.getUserId());

        RAGResponseDTO response = ragService.performRAGQuery(payload);
        return ResponseEntity.ok(response);
    }

    /**
     * Index a document for RAG (sync from knowledge-service).
     * Called by: knowledge-service → POST /api/documents/index
     */
    @PostMapping("/documents/index")
    public ResponseEntity<Map<String, Object>> indexDocument(@RequestBody DocumentSyncPayload payload) {
        log.info("Indexing document {} with {} chunks",
                payload.getDocumentId(),
                payload.getChunks() != null ? payload.getChunks().size() : 0);

        try {
            embeddingService.storeDocumentsFromSync(payload);
            return ResponseEntity.ok(Map.of("indexed", true));
        } catch (Exception e) {
            log.error("Failed to index document {}", payload.getDocumentId(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("indexed", false, "error", e.getMessage()));
        }
    }

    /**
     * Delete document from vector index.
     * Called by: knowledge-service → DELETE /api/documents/{id}
     */
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Map<String, Boolean>> deleteDocument(@PathVariable String id) {
        log.info("Deleting document {} from vector index", id);
        try {
            embeddingService.deleteByDocumentId(Long.parseLong(id));
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (Exception e) {
            log.error("Failed to delete document {} from index", id, e);
            return ResponseEntity.internalServerError().body(Map.of("deleted", false));
        }
    }
}
