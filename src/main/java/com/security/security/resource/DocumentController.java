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

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
            @RequestParam(value = "workspaceId", required = false) String workspaceIdParam,
            @RequestParam(value = "departmentId", required = false) String departmentId,
            @RequestParam(value = "allowedRoles", required = false) String allowedRoles,
            @RequestParam(value = "securityClassification", required = false) String securityClassification,
            @RequestParam(value = "folderPath", required = false) String folderPath,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartments,
            @RequestHeader(value = "x-workspace-id", required = false) String workspaceIdHeader) throws IOException {
        // Resolve workspaceId: explicit param wins; if departmentId is set with no param,
        // it's a department-level upload (null workspace) — don't fall back to header.
        String workspaceId;
        if (workspaceIdParam != null && !workspaceIdParam.isBlank()) {
            workspaceId = workspaceIdParam;
        } else if (departmentId != null && !departmentId.isBlank()) {
            workspaceId = null;
        } else {
            workspaceId = workspaceIdHeader;
        }
        DocumentUploadResponse response = documentService.uploadDocument(file, userId, preview, parser, workspaceId, departmentId, allowedRoles, securityClassification, userRole, userDepartments, folderPath);

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
     * List user's documents with optional pagination
     */
    @GetMapping
    public ResponseEntity<?> getDocuments(
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartments,
            @RequestHeader(value = "x-workspace-id", required = false) String workspaceIdHeader,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size)
    {
        // Resolve workspaceId: query param takes priority over header
        String resolvedWsId = (workspaceId != null && !workspaceId.isBlank()) ? workspaceId : workspaceIdHeader;
        if ("all".equalsIgnoreCase(resolvedWsId)) {
            resolvedWsId = null;
        } else if (resolvedWsId == null || resolvedWsId.isBlank()) {
            resolvedWsId = "default-workspace";
        }
        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
            Page<Document> pagedDocs = documentService.getDocuments(userId, resolvedWsId, pageable, userRole, userDepartments);
            return ResponseEntity.ok(pagedDocs);
        }

        List<Document> documents = documentService.getDocuments(userId, resolvedWsId, userRole, userDepartments);
        return ResponseEntity.ok(documents);
    }

    /**
     * Get document by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartments) {

        Document document = documentService.getDocument(id, userId, userRole, userDepartments);

        return ResponseEntity.ok(document);
    }

    /**
     * Get raw document file
     */
    @GetMapping("/{id}/raw")
    public ResponseEntity<org.springframework.core.io.Resource> getRawDocument(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartments) {
        
        org.springframework.core.io.Resource fileResource = documentService.getDocumentFileResource(id, userId, userRole, userDepartments);
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
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole) {

        documentService.deleteDocument(id, userId, userRole);

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
                request.getWorkspaceId(),
                userId);

        return ResponseEntity.ok(response);
    }

    /**
     * Approve document (trigger ETL pipeline)
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Document> approveDocument(
            @PathVariable Long id,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId,
            @RequestHeader(value = "x-user-role", required = false) String userRole,
            @RequestHeader(value = "x-user-departments", required = false) String userDepartments) {
        Document doc = documentService.approveDocument(id, userId, userRole, userDepartments);
        return ResponseEntity.ok(doc);
    }

    /**
     * Update document metadata (security classification, departmentId, allowedRoles, tags, folderPath, workspaceId)
     */
    @PatchMapping("/{id}/metadata")
    public ResponseEntity<Document> updateMetadata(
            @PathVariable Long id,
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "x-user-id", defaultValue = "system-user") String userId) {
        String securityClassification = (String) payload.get("securityClassification");
        String departmentId = (String) payload.get("departmentId");
        String allowedRoles = (String) payload.get("allowedRoles");
        String folderPath = (String) payload.get("folderPath");
        String workspaceId = (String) payload.get("workspaceId");
        List<String> tags = (List<String>) payload.get("tags");
        Document doc = documentService.updateDocumentMetadata(id, securityClassification, departmentId, allowedRoles, tags, folderPath, workspaceId, userId);
        return ResponseEntity.ok(doc);
    }

}
