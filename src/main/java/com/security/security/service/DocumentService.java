package com.security.security.service;

import com.security.security.dto.DocumentUploadResponse;
import com.security.security.entity.Document;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.event.NatsEventPublisher;
import com.security.security.exception.ApiException;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.SourceImageRepository;
import com.security.security.repository.SourceCompilationPlanRepository;
import com.security.security.repository.SourceChunkExtractRepository;
import com.security.security.repository.OcrResultRepository;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.WikiPageDraftRepository;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.entity.SourceImage;
import com.security.security.entity.WikiPage;
import com.security.security.service.docling.DoclingClient;
import com.security.security.service.ImageProcessingService;
import com.security.security.service.EmbeddingService;
import com.security.security.service.tika.DocumentProfiler;
import com.security.security.service.tika.SemanticMarkdownChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.client.WorkspaceServiceClient;
import com.security.security.dto.UserPermissionContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.AccessDeniedException;


@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final EmbeddingService embeddingService;
    private final DocumentProfiler documentProfiler;
    private final DoclingClient doclingClient;
    private final MrpPipelineService mrpPipelineService;
    private final NatsEventPublisher natsEventPublisher;
    private final WorkspaceServiceClient workspaceServiceClient;
    private final ObjectMapper objectMapper;
    private final ImageProcessingService imageProcessingService;
    private final SourceImageRepository sourceImageRepository;
    private final SourceCompilationPlanRepository sourceCompilationPlanRepository;
    private final SourceChunkExtractRepository sourceChunkExtractRepository;
    private final OcrResultRepository ocrResultRepository;
    private final WikiPageRepository wikiPageRepository;
    private final WikiPageDraftRepository wikiPageDraftRepository;
    private final WikiLinkRepository wikiLinkRepository;
    /** Optional: absent when Redis auto-config is excluded (e.g. mongodb-benchmark). */
    private final Optional<StringRedisTemplate> redisTemplate;
    private final PostProcessingCoordinator postProcessingCoordinator;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;
    private boolean isSystemOrAdmin(String userId) {
        if ("system-user".equals(userId)) {
            return true;
        }
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            return auth.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN") || a.equals("ROLE_ORG_ADMIN") || a.contains("ADMIN"));
        }
        return false;
    }

    // Permission parsing delegated to PermissionUtils.parse()

    private void validateWorkspaceAccess(String workspaceId, String userId) {
        if (isSystemOrAdmin(userId)) {
            return;
        }
        if (workspaceId == null || workspaceId.trim().isEmpty() 
            || "default-workspace".equals(workspaceId) 
            || "workspace-default".equals(workspaceId)
            || "GLOBAL".equals(workspaceId)
            || "ALL".equals(workspaceId)) {
            return; // Allow public, default, GLOBAL or ALL
        }
        var workspace = workspaceServiceClient.getWorkspace(workspaceId, userId);
        if (workspace.isEmpty()) {
            log.warn("[Security] Access denied or workspace not found: User {} in Workspace {}", userId, workspaceId);
            throw new AccessDeniedException("You do not have access to Workspace: " + workspaceId);
        }
    }



    private static final long MAX_FILE_SIZE = 52_428_800L; // 50MB

    // Cho phép các định dạng Docling hỗ trợ bao gồm PDF, DOCX, PPTX, XLSX, HTML, âm thanh, hình ảnh, LaTeX, Markdown, Text
    private static final List<String> ALLOWED_EXTENSIONS = Arrays.asList(
            ".pdf", ".docx", ".doc", ".pptx", ".ppt", ".xlsx", ".xls",
            ".html", ".htm", ".xhtml", ".wav", ".mp3", ".m4a", ".vtt",
            ".png", ".jpg", ".jpeg", ".tiff", ".tif", ".gif", ".bmp", ".webp",
            ".tex", ".latex", ".txt", ".md"
    );

    @Transactional
    public DocumentUploadResponse uploadDocument(MultipartFile file, String userId) {
        return uploadDocument(file, userId, false, "gemini", null);
    }

    @Transactional
    public DocumentUploadResponse uploadDocument(MultipartFile file, String userId, Boolean preview) {
        return uploadDocument(file, userId, preview, "gemini", null);
    }

    @Transactional
    public DocumentUploadResponse uploadDocument(MultipartFile file, String userId, Boolean preview, String parser) {
        return uploadDocument(file, userId, preview, parser, null, null, null, null);
    }

    @Transactional
    public DocumentUploadResponse uploadDocument(MultipartFile file, String userId, Boolean preview, String parser, String workspaceId) {
        return uploadDocument(file, userId, preview, parser, workspaceId, null, null, null);
    }

    @Transactional
    public DocumentUploadResponse uploadDocument(MultipartFile file, String userId, Boolean preview, String parser, String workspaceId, String departmentId, String allowedRoles, String securityClassification) {
        return uploadDocument(file, userId, preview, parser, workspaceId, departmentId, allowedRoles, securityClassification, null, null);
    }

    @Transactional
    public DocumentUploadResponse uploadDocument(
            MultipartFile file,
            String userId,
            Boolean preview,
            String parser,
            String workspaceId,
            String departmentId,
            String allowedRoles,
            String securityClassification,
            String userRole,
            String userDepartments) {
        return uploadDocument(file, userId, preview, parser, workspaceId, departmentId, allowedRoles, securityClassification, userRole, userDepartments, null);
    }

    @Transactional
    public DocumentUploadResponse uploadDocument(
            MultipartFile file,
            String userId,
            Boolean preview,
            String parser,
            String workspaceId,
            String departmentId,
            String allowedRoles,
            String securityClassification,
            String userRole,
            String userDepartments,
            String folderPath) {
        log.info("Uploading document for user: {}, preview: {}, parser: {}, workspaceId: {}, departmentId: {}, allowedRoles: {}, classification: {}, role: {}, depts: {}, folderPath: {}", 
                userId, preview, parser, workspaceId, departmentId, allowedRoles, securityClassification, userRole, userDepartments, folderPath);

        if (file == null || file.isEmpty()) {
            throw new ApiException("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ApiException("File size exceeds 50MB limit");
        }

        if (!isValidDocumentFile(file)) {
            throw new ApiException("Unsupported file type. Only PDF, DOCX, TXT are allowed.");
        }

        // 1. Resolve workspaceId
        String resolvedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        boolean isDeptLevel = "ALL".equals(resolvedWorkspaceId) || "GLOBAL".equals(resolvedWorkspaceId);

        // 2. Resolve departmentId & validate workspace access
        String targetDeptId = ScopeNormalizer.normalizeDepartment(departmentId);
        if (!isDeptLevel) {
            // Workspace-level upload: Validate access and fetch workspace info
            Map<String, Object> workspaceMap = workspaceServiceClient.getWorkspace(resolvedWorkspaceId, userId);
            if (workspaceMap.isEmpty()) {
                log.warn("[Security] Access denied or workspace not found: User {} in Workspace {}", userId, resolvedWorkspaceId);
                throw new AccessDeniedException("You do not have access to Workspace: " + resolvedWorkspaceId);
            }
            if (targetDeptId == null || "GLOBAL".equals(targetDeptId) || "ALL".equals(targetDeptId)) {
                targetDeptId = (String) workspaceMap.get("departmentId");
            }
            targetDeptId = ScopeNormalizer.normalizeDepartment(targetDeptId);
        }

        // 3. Permission checks
        UserPermissionContext perms = PermissionUtils.parse(userRole, userDepartments, objectMapper);
        boolean hasLeaderPrivilege = perms.isAdmin();
        if (!hasLeaderPrivilege && targetDeptId != null && perms.getDeptIdsWhereHead().contains(targetDeptId)) {
            hasLeaderPrivilege = true;
        }

        if (isDeptLevel && !hasLeaderPrivilege) {
            throw new AccessDeniedException("Chỉ Trưởng phòng, Phó phòng và Quản trị viên mới có quyền upload tài liệu dùng chung cấp phòng ban.");
        }

        // 4. Save file to disk
        String storedPath;
        String safeName;
        DocType docType;
        try {
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String originalName = file.getOriginalFilename();
            safeName = (originalName == null || originalName.isBlank())
                    ? "file"
                    : Paths.get(originalName).getFileName().toString();

            String ext = getFileExtension(safeName);
            docType = resolveDocTypeByExtension(ext);
            if (docType == null) {
                throw new ApiException("Unsupported file type. Only PDF, DOCX, TXT are allowed.");
            }

            String uniqueFileName = "doc_" + userId + "_" + UUID.randomUUID() + ext;
            Path targetPath = uploadPath.resolve(uniqueFileName).normalize();
            if (!targetPath.startsWith(uploadPath.normalize())) {
                throw new ApiException("Invalid file path");
            }

            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            storedPath = targetPath.toString();
            log.info("Document uploaded successfully to disk: {}", uniqueFileName);
        } catch (IOException e) {
            log.error("Error saving document to disk for user {}: {}", userId, e.getMessage());
            throw new ApiException("Failed to upload document. Please try again with a valid file.");
        }

        boolean isPreview = preview != null && preview;

        // Determine initial status based on role privileges and preview flag
        DocStatus docStatus;
        if (isPreview) {
            docStatus = DocStatus.PREVIEW;
        } else if (hasLeaderPrivilege) {
            docStatus = DocStatus.PROCESSING;
        } else {
            docStatus = DocStatus.PENDING;
        }

        // Calculate SHA-256 hash of the uploaded file
        String fileHash = "";
        try {
            fileHash = calculateSHA256(file.getBytes());
        } catch (IOException e) {
            log.error("Failed to read file bytes for hashing: {}", e.getMessage());
        }

        // Check Redis/DB cache for duplicate completed document
        String cachedDocId = null;
        if (redisTemplate.isPresent()) {
            try {
                cachedDocId = redisTemplate.get().opsForValue().get("doc:hash:" + fileHash);
                if (cachedDocId != null) {
                    log.info("Duplicate document detected in Redis cache: hash={}, cachedDocId={}", fileHash, cachedDocId);
                }
            } catch (Exception e) {
                log.error("Failed to query Redis for file hash: {}", e.getMessage());
            }
        }

        if (cachedDocId == null) {
            // Fallback to database query
            List<Document> existing = documentRepository.findByFileHashAndStatus(fileHash, DocStatus.COMPLETED);
            if (!existing.isEmpty()) {
                cachedDocId = String.valueOf(existing.get(0).getId());
                log.info("Duplicate document detected in Database: hash={}, cachedDocId={}", fileHash, cachedDocId);
            }
        }

        Document document = Document.builder()
                .userId(userId)
                .workspaceId(resolvedWorkspaceId)
                .fileName(safeName)
                .fileSize((int) file.getSize())
                .filePath(storedPath)
                .documentType(docType)
                .status(docStatus)
                .chunkCount(0)
                .parserMethod(parser != null ? parser : "gemini")
                .departmentId(targetDeptId)
                .allowedRoles(allowedRoles != null && !allowedRoles.isBlank() ? allowedRoles : "ALL")
                .securityClassification(securityClassification != null && !securityClassification.isBlank() ? SecurityClassification.valueOf(securityClassification.toUpperCase().trim()) : SecurityClassification.INTERNAL)
                .fileHash(fileHash)
                .folderPath(folderPath)
                .build();

        Document saved = documentRepository.save(document);

        if (isPreview) {
            // Parse immediately to extract raw Markdown for preview
            try {
                log.info("Immediately parsing document {} for preview", safeName);
                DoclingClient.DoclingResult doclingResult = doclingClient.convertToMarkdown(
                        new FileSystemResource(storedPath), safeName, saved.getParserMethod(), saved.getId());
                if (!doclingResult.success()) {
                    throw new IllegalStateException("Docling parsing failed: " + doclingResult.errorMessage());
                }
                String markdown = doclingResult.markdown();
                
                // Extract and save preview images (no Gemini, no heuristics filter)
                String updatedMarkdown = imageProcessingService.processPreviewImages(saved, markdown);
                saved.setMarkdownContent(updatedMarkdown);
            } catch (Exception parseEx) {
                log.error("Failed to pre-parse document for preview: {}", parseEx.getMessage());
                saved.setStatus(DocStatus.FAILED);
                saved.setErrorMessage("Failed to pre-parse document: " + parseEx.getMessage());
            }
            saved = documentRepository.save(saved);
        }

        natsEventPublisher.publishDocumentStatus(saved.getId(), saved.getUserId(), saved.getWorkspaceId(), saved.getStatus().name());

        // Trigger ETL pipeline immediately if status is PROCESSING
        if (saved.getStatus() == DocStatus.PROCESSING) {
            natsEventPublisher.publishDocumentIngestRequested(saved.getId(), saved.getUserId());
        }

        return DocumentUploadResponse.builder()
                .documentId(saved.getId())
                .fileName(saved.getFileName())
                .status(saved.getStatus().name())
                .markdownContent(saved.getMarkdownContent())
                .message(isPreview 
                        ? "Document uploaded and parsed for preview successfully."
                        : (saved.getStatus() == DocStatus.PENDING 
                            ? "Document uploaded successfully. Awaiting leader approval."
                            : "Document uploaded successfully. Processing started."))
                .build();
    }

    /**
     * Get documents scoped by workspaceId.
     * Falls back to company-wide listing if workspaceId is null/blank (backward compat).
     */
    /**
     * Get documents scoped by workspaceId.
     * Falls back to company-wide listing if workspaceId is null/blank (backward compat).
     */
    public List<Document> getDocuments(String userId, String workspaceId, String userRole, String userDepartments) {
        UserPermissionContext perms = PermissionUtils.parse(userRole, userDepartments, objectMapper);
        if ("all".equalsIgnoreCase(workspaceId)) {
            if (perms.isAdmin()) {
                return documentRepository.findAllByOrderByCreatedAtDesc();
            }
            return documentRepository.findAccessibleAllOrderByCreatedAtDesc(
                perms.isAdmin(),
                perms.hasHeadRole(),
                perms.getDeptIdsWhereHead(),
                perms.getDeptIdsWhereMember()
            );
        }
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        validateWorkspaceAccess(normalizedWorkspaceId, userId);

        if (!"ALL".equals(normalizedWorkspaceId) && !"GLOBAL".equals(normalizedWorkspaceId)) {
            String departmentId = null;
            try {
                Map<String, Object> ws = workspaceServiceClient.getWorkspace(normalizedWorkspaceId, userId);
                if (ws != null && !ws.isEmpty()) {
                    departmentId = (String) ws.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("Failed to get workspace department for workspaceId={}: {}", normalizedWorkspaceId, e.getMessage());
            }
            String normalizedDeptId = ScopeNormalizer.normalizeDepartment(departmentId);
            if (!"ALL".equals(normalizedDeptId) && !"GLOBAL".equals(normalizedDeptId)) {
                return documentRepository.findAccessibleByWorkspaceIdOrDepartmentIdAndWorkspaceIdEmpty(
                    normalizedWorkspaceId,
                    normalizedDeptId,
                    perms.isAdmin(),
                    perms.hasHeadRole(),
                    perms.getDeptIdsWhereHead(),
                    perms.getDeptIdsWhereMember()
                );
            } else {
                return documentRepository.findAccessibleByWorkspaceIdOrderByCreatedAtDesc(
                    normalizedWorkspaceId,
                    perms.isAdmin(),
                    perms.hasHeadRole(),
                    perms.getDeptIdsWhereHead(),
                    perms.getDeptIdsWhereMember()
                );
            }
        } else {
            return documentRepository.findAccessibleByWorkspaceIdOrderByCreatedAtDesc(
                "ALL",
                perms.isAdmin(),
                perms.hasHeadRole(),
                perms.getDeptIdsWhereHead(),
                perms.getDeptIdsWhereMember()
            );
        }
    }

    public Page<Document> getDocuments(String userId, String workspaceId, Pageable pageable, String userRole, String userDepartments) {
        UserPermissionContext perms = PermissionUtils.parse(userRole, userDepartments, objectMapper);
        if ("all".equalsIgnoreCase(workspaceId)) {
            if (perms.isAdmin()) {
                return documentRepository.findAllByOrderByCreatedAtDesc(pageable);
            }
            return documentRepository.findAccessibleAllOrderByCreatedAtDesc(
                perms.isAdmin(),
                perms.hasHeadRole(),
                perms.getDeptIdsWhereHead(),
                perms.getDeptIdsWhereMember(),
                pageable
            );
        }
        String normalizedWorkspaceId = ScopeNormalizer.normalizeWorkspace(workspaceId);
        validateWorkspaceAccess(normalizedWorkspaceId, userId);

        if (!"ALL".equals(normalizedWorkspaceId) && !"GLOBAL".equals(normalizedWorkspaceId)) {
            String departmentId = null;
            try {
                Map<String, Object> ws = workspaceServiceClient.getWorkspace(normalizedWorkspaceId, userId);
                if (ws != null && !ws.isEmpty()) {
                    departmentId = (String) ws.get("departmentId");
                }
            } catch (Exception e) {
                log.warn("Failed to get workspace department for workspaceId={}: {}", normalizedWorkspaceId, e.getMessage());
            }
            String normalizedDeptId = ScopeNormalizer.normalizeDepartment(departmentId);
            if (!"ALL".equals(normalizedDeptId) && !"GLOBAL".equals(normalizedDeptId)) {
                return documentRepository.findAccessibleByWorkspaceIdOrDepartmentIdAndWorkspaceIdEmpty(
                    normalizedWorkspaceId,
                    normalizedDeptId,
                    perms.isAdmin(),
                    perms.hasHeadRole(),
                    perms.getDeptIdsWhereHead(),
                    perms.getDeptIdsWhereMember(),
                    pageable
                );
            } else {
                return documentRepository.findAccessibleByWorkspaceIdOrderByCreatedAtDesc(
                    normalizedWorkspaceId,
                    perms.isAdmin(),
                    perms.hasHeadRole(),
                    perms.getDeptIdsWhereHead(),
                    perms.getDeptIdsWhereMember(),
                    pageable
                );
            }
        } else {
            return documentRepository.findAccessibleByWorkspaceIdOrderByCreatedAtDesc(
                "ALL",
                perms.isAdmin(),
                perms.hasHeadRole(),
                perms.getDeptIdsWhereHead(),
                perms.getDeptIdsWhereMember(),
                pageable
            );
        }
    }

    public List<Document> getUserDocuments(String userId) {
        // Return ALL documents for company knowledge base instead of just the user's
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }

    public Page<Document> getUserDocuments(String userId, Pageable pageable) {
        return documentRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public List<Document> getCompletedDocuments(String userId) {
        // Return ALL completed docs
        return documentRepository.findCompletedByOrderByCreatedAtDesc();
    }

    public Page<Document> getCompletedDocuments(String userId, Pageable pageable) {
        return documentRepository.findCompletedByOrderByCreatedAtDesc(pageable);
    }

    public Document getDocument(Long documentId, String userId) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException("Document not found"));
        validateWorkspaceAccess(ScopeNormalizer.normalizeWorkspace(doc.getWorkspaceId()), userId);
        return doc;
    }

    public Document getDocument(Long documentId, String userId, String userRole, String userDepartments) {
        Document doc = getDocument(documentId, userId);
        UserPermissionContext perms = PermissionUtils.parse(userRole, userDepartments, objectMapper);
        if (perms.isAdmin()) return doc;

        if (com.security.security.entity.enumeration.SecurityClassification.PUBLIC == doc.getSecurityClassification()) {
            return doc;
        }

        String docDeptId = ScopeNormalizer.normalizeDepartment(doc.getDepartmentId());
        String docWsId = ScopeNormalizer.normalizeWorkspace(doc.getWorkspaceId());
        String allowed = doc.getAllowedRoles();

        boolean isGlobalScope = ("ALL".equals(docDeptId) || "GLOBAL".equals(docDeptId)) 
            && ("ALL".equals(docWsId) || "GLOBAL".equals(docWsId));

        if (isGlobalScope) {
            if ("HEAD".equalsIgnoreCase(allowed) && !perms.hasHeadRole()) {
                throw new AccessDeniedException("Chỉ Trưởng phòng hoặc Quản trị viên mới được phép truy cập tài liệu này.");
            }
            return doc;
        }

        if (!"ALL".equals(docDeptId) && !"GLOBAL".equals(docDeptId)) {
            boolean isHead = perms.getDeptIdsWhereHead().contains(docDeptId);
            boolean isMember = perms.getDeptIdsWhereMember().contains(docDeptId);

            if (!isHead && !isMember) {
                throw new AccessDeniedException("Bạn không thuộc phòng ban được phép truy cập tài liệu này.");
            }
            if ("HEAD".equalsIgnoreCase(allowed) && !isHead) {
                throw new AccessDeniedException("Chỉ Trưởng phòng hoặc Quản trị viên mới được phép truy cập tài liệu này.");
            }
            if ("MEMBER".equalsIgnoreCase(allowed) && !isMember && !isHead) {
                throw new AccessDeniedException("Chỉ thành viên thuộc phòng ban này mới được phép truy cập tài liệu.");
            }
        } else {
            // Workspace-scoped but no department restriction
            if ("HEAD".equalsIgnoreCase(allowed) && !perms.hasHeadRole()) {
                throw new AccessDeniedException("Chỉ Trưởng phòng hoặc Quản trị viên mới được phép truy cập tài liệu này.");
            }
        }

        return doc;
    }


    public org.springframework.core.io.Resource getDocumentFileResource(Long documentId, String userId, String userRole, String userDepartments) {
        Document doc = getDocument(documentId, userId, userRole, userDepartments);
        if (doc.getFilePath() == null) {
            throw new ApiException("Original file not found for this document");
        }
        Path path = Paths.get(doc.getFilePath());
        if (!Files.exists(path)) {
            throw new ApiException("Original file not found on disk");
        }
        return new FileSystemResource(path);
    }

    public org.springframework.core.io.Resource getDocumentFileResource(Long documentId, String userId) {
        return getDocumentFileResource(documentId, userId, null, null);
    }

    @Transactional
    public void deleteDocument(Long documentId, String userId) {
        deleteDocument(documentId, userId, null);
    }

    @Transactional
    public void deleteDocument(Long documentId, String userId, String userRole) {
        Document doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException("Document not found"));

        // 1. Permission check: only system-user or SUPER_ADMIN
        boolean isSuper = "system-user".equals(userId);
        if (!isSuper && userRole != null) {
            String upper = userRole.toUpperCase();
            if (upper.contains("SUPER_ADMIN") || upper.contains("ROLE_SUPER_ADMIN")) {
                isSuper = true;
            }
        }
        if (!isSuper) {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null) {
                isSuper = auth.getAuthorities().stream()
                        .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                        .anyMatch(a -> a.equals("ROLE_SUPER_ADMIN") || a.equals("SUPER_ADMIN"));
            }
        }
        if (!isSuper) {
            throw new AccessDeniedException("Chỉ Quản trị viên cấp cao (Super Admin) mới được phép xóa tài liệu.");
        }

        // 2. Validate state: do not delete if document is processing
        if (doc.getStatus() == DocStatus.PROCESSING) {
            throw new ApiException("Không thể xóa tài liệu đang trong quá trình xử lý (PROCESSING).");
        }

        // 3. Delete embeddings synchronously
        try {
            embeddingService.deleteByDocumentId(documentId);
        } catch (Exception e) {
            log.warn("Could not delete embeddings: {}", e.getMessage());
        }

        // 4. Delete related WikiPages and their associated drafts & links
        List<WikiPage> wikiPages = wikiPageRepository.findBySourceDocumentId(documentId);
        if (wikiPages.isEmpty()) {
            String targetSummary = "Compiled from document ID: " + documentId;
            wikiPages = wikiPageRepository.findBySummaryContaining(targetSummary);
        }
        for (WikiPage page : wikiPages) {
            try {
                wikiPageDraftRepository.deleteByWikiPageId(page.getId());
            } catch (Exception e) {
                log.warn("Could not delete drafts for wiki page {}: {}", page.getId(), e.getMessage());
            }
            try {
                wikiLinkRepository.deleteByFromPageId(page.getId());
            } catch (Exception e) {
                log.warn("Could not delete wiki links from page {}: {}", page.getId(), e.getMessage());
            }
            try {
                embeddingService.deleteWikiPageEmbedding(page.getId());
            } catch (Exception e) {
                log.warn("Could not delete wiki page embeddings for ID {}: {}", page.getId(), e.getMessage());
            }
            try {
                wikiPageRepository.delete(page);
            } catch (Exception e) {
                log.warn("Could not delete wiki page {}: {}", page.getId(), e.getMessage());
            }
        }

        // 5. Delete other logical associations
        sourceCompilationPlanRepository.deleteBySourceDocumentId(documentId);
        sourceChunkExtractRepository.deleteBySourceDocumentId(documentId);
        ocrResultRepository.deleteByDocumentId(documentId);

        // 6. Delete extracted source images and files from disk
        List<SourceImage> sourceImages = sourceImageRepository.findBySourceId(documentId);
        for (SourceImage img : sourceImages) {
            if (img.getMinioKey() != null) {
                deleteFileOnDisk(Paths.get(uploadDir).resolve(img.getMinioKey()).toString());
            }
        }
        sourceImageRepository.deleteBySourceId(documentId);

        // 7. Delete original document file from disk
        deleteFileOnDisk(doc.getFilePath());

        // 8. Delete document entity from database
        documentRepository.delete(doc);

        // 9. Publish event to NATS
        natsEventPublisher.publishDocumentStatus(doc.getId(), doc.getUserId(), doc.getWorkspaceId(), "DELETED");

        log.info("Document and all related entities deleted: {}", documentId);
    }

    /* ===================== Helpers (giống style CVFileServiceImpl) ===================== */

    public boolean isValidDocumentFile(MultipartFile file) {
        if (file == null || file.isEmpty()) return false;

        // size already checked outside, but keep consistent
        if (file.getSize() > MAX_FILE_SIZE) return false;

        // check extension
        String name = file.getOriginalFilename();
        if (name == null) return false;
        String ext = getFileExtension(name).toLowerCase();

        return ALLOWED_EXTENSIONS.contains(ext);
    }

    public String getDocumentFilePath(String storedFileName) {
        if (storedFileName == null || storedFileName.trim().isEmpty()) return null;
        return Paths.get(uploadDir).resolve(storedFileName).toString();
    }

    private void deleteFileOnDisk(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) return;

        try {
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                Files.delete(path);
                log.info("Deleted file from disk: {}", filePath);
            }
        } catch (IOException e) {
            log.warn("Could not delete file: {} - {}", filePath, e.getMessage());
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.lastIndexOf('.') == -1) return "";
        return filename.substring(filename.lastIndexOf('.'));
    }

    private DocType resolveDocTypeByExtension(String ext) {
        if (ext == null) return null;
        String e = ext.toLowerCase();
        if (e.equals(".pdf")) return DocType.pdf;
        if (e.equals(".docx") || e.equals(".doc")) return DocType.docx;
        if (e.equals(".pptx") || e.equals(".ppt")) return DocType.pptx;
        if (e.equals(".xlsx") || e.equals(".xls")) return DocType.xlsx;
        if (e.equals(".html") || e.equals(".htm") || e.equals(".xhtml")) return DocType.html;
        if (e.equals(".wav")) return DocType.wav;
        if (e.equals(".mp3") || e.equals(".m4a")) return DocType.mp3;
        if (e.equals(".vtt")) return DocType.vtt;
        if (e.equals(".png")) return DocType.png;
        if (e.equals(".tiff") || e.equals(".tif")) return DocType.tiff;
        if (e.equals(".jpeg") || e.equals(".jpg")) return DocType.jpeg;
        if (e.equals(".tex") || e.equals(".latex")) return DocType.latex;
        if (e.equals(".txt")) return DocType.txt;
        if (e.equals(".md")) return DocType.md;
        return null;
    }

//    @Transactional
//    public void ingestDocument(Long documentId, String markdownContent, String userId) {
//        log.info("Ingesting document {} with edited markdown content", documentId);
//
//        Document document = documentRepository.findById(documentId)
//                .orElseThrow(() -> new ApiException("Document not found"));
//
//        if (!document.getUserId().equals(userId) && !userId.equals("system-user")) {
//            throw new ApiException("Permission denied");
//        }
//
//        // Set status to PROCESSING
//        document.setStatus(DocStatus.PROCESSING);
//        document.setMarkdownContent(markdownContent);
//        documentRepository.save(document);
//
//        // Run MRP Pipeline synchronously
//        try {
//            int numPages = mrpPipelineService.compileToWiki(markdownContent, documentId, "default-workspace", userId);
//
//            // Xóa embedding cũ nếu có
//            try {
//                embeddingRepository.deleteByDocumentId(documentId);
//            } catch (Exception ex) {
//                log.warn("Could not delete old embeddings: {}", ex.getMessage());
//            }
//
//            document.setStatus(DocStatus.COMPLETED);
//            document.setChunkCount(numPages); // Re-purpose chunkCount as wikiPageCount
//            document.setErrorMessage(null);
//            documentRepository.save(document);
//
//        } catch (Exception e) {
//            log.error("Failed to ingest document {}: {}", documentId, e.getMessage(), e);
//            document.setStatus(DocStatus.FAILED);
//            document.setErrorMessage(e.getMessage());
//            documentRepository.save(document);
//            throw new ApiException("Failed to ingest document: " + e.getMessage());
//        }
//    }
//@Transactional
//public void ingestDocument(Long documentId, String markdownContent, String userId) {
//    log.info("Ingesting document {} with edited markdown content", documentId);
//
//    Document document = documentRepository.findById(documentId)
//            .orElseThrow(() -> new ApiException("Document not found"));
//
//    if (!document.getUserId().equals(userId) && !userId.equals("system-user")) {
//        throw new ApiException("Permission denied");
//    }
//
//    // Set status to PROCESSING
//    document.setStatus(DocStatus.PROCESSING);
//    document.setMarkdownContent(markdownContent);
//    documentRepository.save(document);
//
//    try {
//        // 1. Xóa embedding cũ trước khi thực hiện quy trình mới để đảm bảo dữ liệu sạch
//        try {
//            embeddingRepository.deleteByDocumentId(documentId);
//        } catch (Exception ex) {
//            log.warn("Could not delete old embeddings: {}", ex.getMessage());
//        }
//
//        // 2. CHUNKING & VECTOR DB PIPELINE (Từ hàm thứ 2)
//        List<SemanticMarkdownChunker.ChunkResult> chunkResults = semanticMarkdownChunker.chunk(markdownContent);
//        if (chunkResults.isEmpty()) {
//            throw new IllegalStateException("No chunks produced from markdown");
//        }
//
//        int batchSize = 30;
//        List<org.springframework.ai.document.Document> vBatch = new ArrayList<>(batchSize);
//        List<Embedding> eBatch = new ArrayList<>(batchSize);
//
//        for (int i = 0; i < chunkResults.size(); i++) {
//            SemanticMarkdownChunker.ChunkResult cr = chunkResults.get(i);
//
//            Map<String, Object> meta = new HashMap<>();
//            meta.put("documentId", document.getId().toString());
//            meta.put("userId", document.getUserId());
//            meta.put("fileName", document.getFileName());
//            meta.put("chunkIndex", String.valueOf(i));
//            meta.put("chunkTitle", cr.title());
//            meta.put("tokenCount", String.valueOf(semanticMarkdownChunker.estimateTokens(cr.text())));
//            meta.put("charCount", String.valueOf(cr.text().length()));
//
//            vBatch.add(new org.springframework.ai.document.Document(cr.text(), meta));
//            eBatch.add(Embedding.builder()
//                    .documentId(document.getId())
//                    .chunkIndex(i)
//                    .chunkText(cr.text())
//                    .chunkTitle(cr.title())
//                    .tokenCount(semanticMarkdownChunker.estimateTokens(cr.text()))
//                    .charCount(cr.text().length())
//                    .build());
//
//            if (vBatch.size() >= batchSize) {
//                vectorStore.add(new ArrayList<>(vBatch));
//                embeddingRepository.saveAll(new ArrayList<>(eBatch));
//                vBatch.clear();
//                eBatch.clear();
//            }
//        }
//
//        // Lưu những chunk còn sót lại trong batch cuối cùng
//        if (!vBatch.isEmpty()) {
//            vectorStore.add(vBatch);
//            embeddingRepository.saveAll(eBatch);
//        }
//
//        log.info("Successfully ingested {} chunks for doc={}", chunkResults.size(), documentId);
//
//        // 3. MRP PIPELINE (Từ hàm thứ 1)
//        // Chạy quy trình biên soạn Wiki đồng bộ
//        int numWikiPages = mrpPipelineService.compileToWiki(markdownContent, documentId, "default-workspace", userId);
//        log.info("Successfully compiled doc={} into {} wiki pages", documentId, numWikiPages);
//
//        // 4. CẬP NHẬT THỐNG KÊ & TRẠNG THÁI DOCUMENT
//        DocumentProfiler.ProfileResult profile = documentProfiler.profile(markdownContent, chunkResults);
//
//        document.setStatus(DocStatus.COMPLETED);
//
//        // Lưu ý: Ở code cũ (hàm 1) bạn dùng chunkCount để lưu numPages. Ở code 2 bạn dùng để lưu số chunks.
//        // Tôi gán cho nó là số chunks (chuẩn logic AI), nếu Entity Document của bạn có thêm trường `wikiPageCount`,
//        // bạn có thể mở comment dòng bên dưới.
//        document.setChunkCount(chunkResults.size());
//        // document.setWikiPageCount(numWikiPages);
//
//        document.setNumHeadings(profile.numHeadings());
//        document.setNumTables(profile.numTables());
//        document.setNumParagraphs(profile.numParagraphs());
//        document.setTotalTokens(profile.estimatedTokens());
//        document.setAvgTokensPerChunk(profile.avgTokensPerChunk());
//        document.setErrorMessage(null);
//
//        documentRepository.save(document);
//
//    } catch (Exception e) {
//        log.error("Failed to ingest document {}: {}", documentId, e.getMessage(), e);
//        document.setStatus(DocStatus.FAILED);
//        document.setErrorMessage(e.getMessage());
//        documentRepository.save(document);
//        throw new ApiException("Failed to ingest document: " + e.getMessage());
//    }
//}

    @Transactional
    public void ingestDocument(Long documentId, String markdownContent, String userId) {
        log.info("Ingesting document {} with edited markdown content", documentId);

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException("Document not found"));

        validateWorkspaceAccess(document.getWorkspaceId(), userId);

        if (!document.getUserId().equals(userId) && !userId.equals("system-user")) {
            throw new ApiException("Permission denied");
        }

        // Set status to PROCESSING
        document.setStatus(DocStatus.PROCESSING);
        document.setMarkdownContent(markdownContent);
        documentRepository.save(document);
        natsEventPublisher.publishDocumentStatus(document.getId(), document.getUserId(), document.getWorkspaceId(), "PROCESSING");

        // Run chunking and loading synchronously
        try {
            markdownContent = imageProcessingService.processIngestImages(document, markdownContent);
            document.setMarkdownContent(markdownContent);
            documentRepository.save(document);

            // Chunking & Vector Store Loading
            List<SemanticMarkdownChunker.ChunkResult> chunkResults = embeddingService.ingestMarkdown(document, markdownContent);

            // Profiling stats (docling-style)
            DocumentProfiler.ProfileResult profile = documentProfiler.profile(markdownContent, chunkResults);

            document.setChunkCount(chunkResults.size());
            document.setNumHeadings(profile.numHeadings());
            document.setNumTables(profile.numTables());
            document.setNumParagraphs(profile.numParagraphs());
            document.setTotalTokens(profile.estimatedTokens());
            document.setAvgTokensPerChunk(profile.avgTokensPerChunk());
            document.setErrorMessage(null);
            documentRepository.save(document);

            // Async post-processing: summary, Q&A, wiki compilation
            // Document transitions to COMPLETED only when all subtasks finish
            postProcessingCoordinator.startPostProcessing(document, markdownContent, chunkResults);

        } catch (Exception e) {
            log.error("Failed to ingest document {}: {}", documentId, e.getMessage(), e);
            document.setStatus(DocStatus.FAILED);
            document.setErrorMessage("Internal ingestion error");
            documentRepository.save(document);
            natsEventPublisher.publishDocumentStatus(document.getId(), document.getUserId(), document.getWorkspaceId(), "FAILED");
            throw new ApiException("Failed to ingest document due to an internal processing error.");
        }
    }

    @Transactional
    public Document approveDocument(Long id, String userId) {
        log.info("Approving document {} for user {}", id, userId);
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Document not found"));

        validateWorkspaceAccess(document.getWorkspaceId(), userId);

        if (!document.getUserId().equals(userId) && !userId.equals("system-user")) {
            throw new ApiException("Permission denied");
        }

        if (document.getStatus() == DocStatus.PENDING || document.getStatus() == DocStatus.FAILED || document.getStatus() == DocStatus.PREVIEW) {
            document.setStatus(DocStatus.PROCESSING);
            Document saved = documentRepository.save(document);
            natsEventPublisher.publishDocumentStatus(saved.getId(), saved.getUserId(), saved.getWorkspaceId(), "PROCESSING");
            natsEventPublisher.publishDocumentIngestRequested(saved.getId(), saved.getUserId());
            return saved;
        }

        return document;
    }

    @Transactional
    public Document approveDocument(Long id, String userId, String userRole, String userDepartments) {
        log.info("Approving document {} for user {}", id, userId);
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Document not found"));

        // 1. Check leader/admin privilege first
        UserPermissionContext perms = PermissionUtils.parse(userRole, userDepartments, objectMapper);
        boolean hasLeaderPrivilege = perms.isAdmin();
        String docDeptId = ScopeNormalizer.normalizeDepartment(document.getDepartmentId());
        String docWsId = ScopeNormalizer.normalizeWorkspace(document.getWorkspaceId());
        if ("GLOBAL".equals(docDeptId) && !"GLOBAL".equals(docWsId)) {
            Map<String, Object> ws = workspaceServiceClient.getWorkspace(docWsId, userId);
            if (!ws.isEmpty()) {
                docDeptId = ScopeNormalizer.normalizeDepartment((String) ws.get("departmentId"));
            }
        }
        if (!hasLeaderPrivilege && !"GLOBAL".equals(docDeptId) && perms.getDeptIdsWhereHead().contains(docDeptId)) {
            hasLeaderPrivilege = true;
        }

        if (!hasLeaderPrivilege) {
            throw new AccessDeniedException("Chỉ Trưởng phòng, Phó phòng và Quản trị viên mới có quyền duyệt tài liệu.");
        }

        if (document.getStatus() == DocStatus.PENDING || document.getStatus() == DocStatus.FAILED || document.getStatus() == DocStatus.PREVIEW) {
            document.setStatus(DocStatus.PROCESSING);
            Document saved = documentRepository.save(document);
            natsEventPublisher.publishDocumentStatus(saved.getId(), saved.getUserId(), saved.getWorkspaceId(), "PROCESSING");
            natsEventPublisher.publishDocumentIngestRequested(saved.getId(), saved.getUserId());
            return saved;
        }

        return document;
    }

    @Transactional
    public Document updateDocumentMetadata(Long id, String securityClassification, String departmentId, String allowedRoles, List<String> tags, String userId) {
        return updateDocumentMetadata(id, securityClassification, departmentId, allowedRoles, tags, null, userId);
    }

    @Transactional
    public Document updateDocumentMetadata(Long id, String securityClassification, String departmentId, String allowedRoles, List<String> tags, String folderPath, String userId) {
        return updateDocumentMetadata(id, securityClassification, departmentId, allowedRoles, tags, folderPath, null, userId);
    }

    @Transactional
    public Document updateDocumentMetadata(Long id, String securityClassification, String departmentId, String allowedRoles, List<String> tags, String folderPath, String workspaceId, String userId) {
        log.info("Updating metadata for document {} by user {}, folderPath: {}, workspaceId: {}", id, userId, folderPath, workspaceId);
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Document not found"));

        validateWorkspaceAccess(document.getWorkspaceId(), userId);

        if (!document.getUserId().equals(userId) && !userId.equals("system-user")) {
            throw new ApiException("Permission denied");
        }

        if (securityClassification != null) {
            try {
                document.setSecurityClassification(SecurityClassification.valueOf(securityClassification.toUpperCase().trim()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid security classification provided: {}, ignoring update", securityClassification);
            }
        }
        if (departmentId != null) {
            document.setDepartmentId(departmentId);
        }
        if (allowedRoles != null) {
            document.setAllowedRoles(allowedRoles);
        }
        if (tags != null) {
            document.setTags(tags);
        }
        if (folderPath != null) {
            document.setFolderPath(folderPath);
        }
        if (workspaceId != null) {
            document.setWorkspaceId(workspaceId.trim().isEmpty() ? null : workspaceId);
        }

        Document saved = documentRepository.save(document);

        // 1. Cascade changes to compiled WikiPages and their associated drafts
        List<WikiPage> pages = wikiPageRepository.findBySourceDocumentId(saved.getId());
        if (pages.isEmpty()) {
            String targetSummary = "Compiled from document ID: " + saved.getId();
            pages = wikiPageRepository.findBySummaryContaining(targetSummary);
            for (WikiPage page : pages) {
                page.setSourceDocumentId(saved.getId());
            }
        }
        for (WikiPage page : pages) {
            page.setAllowedRoles(saved.getAllowedRoles());
            page.setDepartmentId(saved.getDepartmentId());
            page.setSecurityClassification(saved.getSecurityClassification());
            page.setWorkspaceId(saved.getWorkspaceId());
            wikiPageRepository.save(page);

            // Cascade changes to WikiPage embeddings in the VectorStore
            try {
                embeddingService.updateWikiPageEmbeddingsMetadata(
                        page.getId(),
                        page.getWorkspaceId(),
                        page.getDepartmentId(),
                        page.getAllowedRoles(),
                        page.getSecurityClassification() != null ? page.getSecurityClassification().name() : null
                );
            } catch (Exception e) {
                log.error("[DocumentService] Failed to cascade metadata updates to wiki page {} embeddings: {}", page.getId(), e.getMessage());
            }

            // Cascade to associated drafts
            List<com.security.security.entity.WikiPageDraft> drafts = wikiPageDraftRepository.findByWikiPageId(page.getId());
            for (com.security.security.entity.WikiPageDraft draft : drafts) {
                draft.setAllowedRoles(saved.getAllowedRoles());
                draft.setDepartmentId(saved.getDepartmentId());
                draft.setSecurityClassification(saved.getSecurityClassification());
                draft.setWorkspaceId(saved.getWorkspaceId());
                if (draft.getSourceDocumentId() == null) {
                    draft.setSourceDocumentId(saved.getId());
                }
                wikiPageDraftRepository.save(draft);
            }
        }

        // 2. Cascade changes to embeddings and pgvector store metadata
        try {
            embeddingService.updateEmbeddingsMetadata(
                    saved.getId(),
                    saved.getWorkspaceId(),
                    saved.getDepartmentId(),
                    saved.getAllowedRoles(),
                    saved.getSecurityClassification() != null ? saved.getSecurityClassification().name() : null
            );
        } catch (Exception e) {
            log.error("[DocumentService] Failed to cascade metadata updates to document embeddings: {}", e.getMessage());
        }

        // Publish event to NATS for real-time frontend update
        natsEventPublisher.publishDocumentStatus(saved.getId(), saved.getUserId(), saved.getWorkspaceId(), saved.getStatus().name());
        return saved;
    }

    private String calculateSHA256(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Failed to calculate SHA-256 hash: {}", e.getMessage());
            return "";
        }
    }
}
