package com.security.security.resource;

import com.security.security.dto.*;
import com.security.security.dtorequest.ChunkSearchRequest;
import com.security.security.entity.Document;
import com.security.security.service.ChunkService;
import com.security.security.service.DocumentService;
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

    /**
     * Upload a document
     */
    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
//            @AuthenticationPrincipal UserDTO principal) {
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) throws IOException {
//String   userId = pr
        DocumentUploadResponse response = documentService.uploadDocument(file, userId);

        return ResponseEntity.accepted().body(response);
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
