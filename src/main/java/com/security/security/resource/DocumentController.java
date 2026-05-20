package com.security.security.resource;

import com.security.security.dto.*;
import com.security.security.dtorequest.ChunkSearchRequest;
import com.security.security.dtorequest.AiRefactorRequest;
import com.security.security.entity.Document;
import com.security.security.service.ChunkService;
import com.security.security.service.DocumentService;
import com.security.security.service.AiRefactorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final ChunkService chunkService;
    private final AiRefactorService aiRefactorService;

    /**
     * Upload a document
     */
    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "preview", required = false) Boolean preview,
            @RequestParam(value = "parser", required = false, defaultValue = "gemini") String parser,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) throws IOException {
        DocumentUploadResponse response = documentService.uploadDocument(file, userId, preview, parser);

        return ResponseEntity.accepted().body(response);
    }

    /**
     * Ingest a document after user pre-view/edit approval
     */
    @PostMapping("/{id}/ingest")
    public ResponseEntity<Map<String, String>> ingestDocument(
            @PathVariable Long id,
            @RequestBody Map<String, String> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        
        String markdownContent = payload.get("markdownContent");
        if (markdownContent == null || markdownContent.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "markdownContent is required"));
        }

        documentService.ingestDocument(id, markdownContent, userId);
        return ResponseEntity.ok(Map.of("message", "Document ingested successfully"));
    }

    /**
     * Use AI to refactor document Markdown (Full or Partial)
     */
    @PostMapping("/{id}/ai-refactor")
    public ResponseEntity<AiRefactorResponse> aiRefactor(
            @PathVariable Long id,
            @RequestBody AiRefactorRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        
        AiRefactorResponse response = aiRefactorService.refactorDocument(id, request, userId);
        return ResponseEntity.ok(response);
    }

    /**
     * List user's documents
     */
    @GetMapping
    public ResponseEntity<List<Document>> getDocuments(
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId)
    {

        List<Document> documents = documentService.getUserDocuments(userId);

        return ResponseEntity.ok(documents);
    }

    /**
     * Get document by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        Document document = documentService.getDocument(id, userId);

        return ResponseEntity.ok(document);
    }

    /**
     * Get raw document file
     */
    @GetMapping("/{id}/raw")
    public ResponseEntity<org.springframework.core.io.Resource> getRawDocument(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        
        org.springframework.core.io.Resource fileResource = documentService.getDocumentFileResource(id, userId);
        String contentType = "application/pdf"; 
        
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, contentType)
                .body(fileResource);
    }

    /**
     * Delete document
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDocument(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        documentService.deleteDocument(id, userId);

        return ResponseEntity.ok(Map.of("message", "Document deleted successfully"));
    }

    // ==================== CHUNK ENDPOINTS ====================

    /**
     * Get all chunks for a document
     */
    @GetMapping("/{id}/chunks")
    public ResponseEntity<DocumentChunksResponse> getDocumentChunks(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        DocumentChunksResponse response = chunkService.getDocumentChunks(id, userId);

        return ResponseEntity.ok(response);
    }

    /**
     * Get a specific chunk by index
     */
    @GetMapping("/{id}/chunks/{chunkIndex}")
    public ResponseEntity<ChunkDTO> getChunk(
            @PathVariable Long id,
            @PathVariable Integer chunkIndex,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        ChunkDTO chunk = chunkService.getChunk(id, chunkIndex, userId);

        return ResponseEntity.ok(chunk);
    }

    /**
     * Get document statistics
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<DocumentStatsDTO> getDocumentStats(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        DocumentStatsDTO stats = chunkService.getDocumentStats(id, userId);

        return ResponseEntity.ok(stats);
    }

    /**
     * Search chunks by semantic similarity
     */
    @PostMapping("/search")
    public ResponseEntity<ChunkSearchResponse> searchChunks(
            @RequestBody ChunkSearchRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {

        ChunkSearchResponse response = chunkService.searchChunks(
                request.getQuery(),
                request.getTopK() != null ? request.getTopK() : 5,
                request.getMinSimilarity() != null ? request.getMinSimilarity() : 0.5,
                userId);

        return ResponseEntity.ok(response);
    }


}
