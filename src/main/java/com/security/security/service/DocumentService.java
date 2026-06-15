package com.security.security.service;

import com.security.security.dto.DocumentUploadResponse;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import com.security.security.event.NatsEventPublisher;
import com.security.security.exception.ApiException;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.EmbeddingRepository;
import com.security.security.service.tika.TikaHtmlExtractor;
import com.security.security.service.tika.HtmlToMarkdownConverter;
import com.security.security.service.tika.TikaHtmlResult;
import com.security.security.service.tika.SemanticMarkdownChunker;
import com.security.security.service.tika.DocumentProfiler;
import com.security.security.service.docling.DoclingClient;
import com.security.security.entity.SourceImage;
import com.security.security.repository.SourceImageRepository;
import com.security.security.service.ImageExtractionService;
import org.springframework.ai.chat.model.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
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
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.security.security.client.WorkspaceServiceClient;
import org.springframework.security.access.AccessDeniedException;


@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final TikaHtmlExtractor tikaHtmlExtractor;
    private final HtmlToMarkdownConverter htmlToMarkdownConverter;
    private final EmbeddingRepository embeddingRepository;
    private final SemanticMarkdownChunker semanticMarkdownChunker;
    private final DocumentProfiler documentProfiler;
    private final DoclingClient doclingClient;
    private final MrpPipelineService mrpPipelineService;
    private final NatsEventPublisher natsEventPublisher;
    private final WorkspaceServiceClient workspaceServiceClient;
    private final ImageExtractionService imageExtractionService;
    private final SourceImageRepository sourceImageRepository;
    private final ChatModel chatModel;
    // Khuyến nghị set ABSOLUTE:
    // app.upload.dir=C:/data/myapp/uploads
    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${gemini.modle.image}")
    private String geminiModel;
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

    private static class ParsedUserPermissions {
        boolean isAdmin = false;
        List<String> deptIdsWhereHead = new ArrayList<>();
        List<String> deptIdsWhereMember = new ArrayList<>();
    }

    private ParsedUserPermissions parseUserPermissions(String userRole, String userDepartments) {
        ParsedUserPermissions permissions = new ParsedUserPermissions();
        
        // 1. Check admin status from userRole
        if (userRole != null) {
            String upper = userRole.toUpperCase();
            if (upper.contains("SUPER_ADMIN") || upper.contains("ADMIN") || upper.contains("ORG_ADMIN")) {
                permissions.isAdmin = true;
            }
        }
        
        // Double-check from SecurityContextHolder
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            boolean hasAdminAuthority = auth.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN") || a.equals("ROLE_ORG_ADMIN") || a.contains("ADMIN"));
            if (hasAdminAuthority) {
                permissions.isAdmin = true;
            }
        }
        
        // 2. Parse departments and roles
        if (userDepartments != null && !userDepartments.trim().isEmpty()) {
            try {
                // Parse [{"departmentId": "...", "role": "..."}]
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                List<Map<String, String>> depts = mapper.readValue(
                    userDepartments, 
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {}
                );
                for (Map<String, String> dept : depts) {
                    String deptId = dept.get("departmentId");
                    String role = dept.get("role");
                    if (deptId != null && !deptId.trim().isEmpty()) {
                        if ("HEAD".equalsIgnoreCase(role) || "MANAGER".equalsIgnoreCase(role)) {
                            permissions.deptIdsWhereHead.add(deptId);
                        } else {
                            permissions.deptIdsWhereMember.add(deptId);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse x-user-departments header in DocumentService: {}", e.getMessage());
            }
        }
        
        return permissions;
    }

    private void validateWorkspaceAccess(String workspaceId, String userId) {
        if (isSystemOrAdmin(userId)) {
            return;
        }
        if (workspaceId == null || workspaceId.trim().isEmpty() || "default-workspace".equals(workspaceId)) {
            return; // Allow public or default
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
            ".html", ".htm", ".xhtml", ".wav", ".mp3", ".vtt",
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
        log.info("Uploading document for user: {}, preview: {}, parser: {}, workspaceId: {}, departmentId: {}, allowedRoles: {}, classification: {}, role: {}, depts: {}", 
                userId, preview, parser, workspaceId, departmentId, allowedRoles, securityClassification, userRole, userDepartments);

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
        String resolvedWorkspaceId = workspaceId;
        if (resolvedWorkspaceId != null && resolvedWorkspaceId.trim().isEmpty()) {
            resolvedWorkspaceId = null;
        }
        if ("all".equalsIgnoreCase(resolvedWorkspaceId)) {
            resolvedWorkspaceId = null;
        }

        boolean isDeptLevel = (resolvedWorkspaceId == null);

        // 2. Resolve departmentId & validate workspace access
        String targetDeptId = (departmentId != null && !departmentId.isBlank()) ? departmentId : null;
        if (!isDeptLevel && !"default-workspace".equals(resolvedWorkspaceId)) {
            // Workspace-level upload: Validate access and fetch workspace info
            Map<String, Object> workspaceMap = workspaceServiceClient.getWorkspace(resolvedWorkspaceId, userId);
            if (workspaceMap.isEmpty()) {
                log.warn("[Security] Access denied or workspace not found: User {} in Workspace {}", userId, resolvedWorkspaceId);
                throw new AccessDeniedException("You do not have access to Workspace: " + resolvedWorkspaceId);
            }
            if (targetDeptId == null) {
                targetDeptId = (String) workspaceMap.get("departmentId");
            }
        }

        // 3. Permission checks
        ParsedUserPermissions perms = parseUserPermissions(userRole, userDepartments);
        boolean hasLeaderPrivilege = perms.isAdmin;
        if (!hasLeaderPrivilege && targetDeptId != null && perms.deptIdsWhereHead.contains(targetDeptId)) {
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
                .securityClassification(securityClassification != null && !securityClassification.isBlank() ? securityClassification : "INTERNAL")
                .build();

        Document saved = documentRepository.save(document);

        if (isPreview) {
            // Parse immediately to extract raw Markdown for preview
            try {
                log.info("Immediately parsing document {} for preview", safeName);
                String markdown = null;
                try {
                    if (doclingClient.isHealthy()) {
                        DoclingClient.DoclingResult doclingResult = doclingClient.convertToMarkdown(
                                new FileSystemResource(storedPath), safeName, saved.getParserMethod(), saved.getId());
                        if (doclingResult.success()) {
                            markdown = doclingResult.markdown();
                        }
                    }
                } catch (Exception doclingEx) {
                    log.warn("[Preview] Error calling Docling: {}. Falling back to Apache Tika.", doclingEx.getMessage());
                }

                if (markdown == null) {
                    TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(new FileSystemResource(storedPath));
                    markdown = htmlToMarkdownConverter.convert(htmlResult.html());
                }
                saved.setMarkdownContent(markdown);
            } catch (Exception parseEx) {
                log.error("Failed to pre-parse document for preview: {}", parseEx.getMessage());
                saved.setStatus(DocStatus.FAILED);
                saved.setErrorMessage("Failed to pre-parse document: " + parseEx.getMessage());
            }
            saved = documentRepository.save(saved);
        }

        if (isPreview && saved.getStatus() == DocStatus.PREVIEW) {
            String markdown = saved.getMarkdownContent();
            if (markdown != null) {
                String updatedMarkdown = extractAndSaveImages(saved, markdown);
                if (!updatedMarkdown.equals(markdown)) {
                    saved.setMarkdownContent(updatedMarkdown);
                    saved = documentRepository.save(saved);
                }
            }
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
    private List<Document> filterDocumentsByRole(List<Document> docs, String userRole, String userDepartments) {
        ParsedUserPermissions perms = parseUserPermissions(userRole, userDepartments);
        if (perms.isAdmin) {
            return docs; // Admin sees everything
        }

        List<Document> filtered = new java.util.ArrayList<>();
        for (Document doc : docs) {
            String allowed = doc.getAllowedRoles();
            if (allowed == null || allowed.isBlank() || "ALL".equalsIgnoreCase(allowed)) {
                filtered.add(doc);
                continue;
            }

            String docDeptId = doc.getDepartmentId();
            if (docDeptId != null && !docDeptId.isBlank()) {
                if ("HEAD".equalsIgnoreCase(allowed)) {
                    if (perms.deptIdsWhereHead.contains(docDeptId)) {
                        filtered.add(doc);
                    }
                } else if ("MEMBER".equalsIgnoreCase(allowed)) {
                    if (perms.deptIdsWhereMember.contains(docDeptId) || perms.deptIdsWhereHead.contains(docDeptId)) {
                        filtered.add(doc);
                    }
                }
            } else {
                filtered.add(doc);
            }
        }
        return filtered;
    }

    /**
     * Get documents scoped by workspaceId.
     * Falls back to company-wide listing if workspaceId is null/blank (backward compat).
     */
    public List<Document> getDocuments(String userId, String workspaceId, String userRole, String userDepartments) {
        validateWorkspaceAccess(workspaceId, userId);
        List<Document> docs;
        if (workspaceId != null && !workspaceId.isBlank()) {
            String departmentId = null;
            if (!"default-workspace".equals(workspaceId)) {
                try {
                    Map<String, Object> ws = workspaceServiceClient.getWorkspace(workspaceId, userId);
                    if (ws != null && !ws.isEmpty()) {
                        departmentId = (String) ws.get("departmentId");
                    }
                } catch (Exception e) {
                    log.warn("Failed to get workspace department for workspaceId={}: {}", workspaceId, e.getMessage());
                }
            }
            if (departmentId != null && !departmentId.isBlank()) {
                docs = documentRepository.findByWorkspaceIdOrDepartmentIdAndWorkspaceIdEmpty(workspaceId, departmentId);
            } else {
                docs = documentRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
            }
        } else {
            docs = documentRepository.findAllByOrderByCreatedAtDesc();
        }
        return filterDocumentsByRole(docs, userRole, userDepartments);
    }

    public Page<Document> getDocuments(String userId, String workspaceId, Pageable pageable, String userRole, String userDepartments) {
        validateWorkspaceAccess(workspaceId, userId);
        Page<Document> page;
        if (workspaceId != null && !workspaceId.isBlank()) {
            String departmentId = null;
            if (!"default-workspace".equals(workspaceId)) {
                try {
                    Map<String, Object> ws = workspaceServiceClient.getWorkspace(workspaceId, userId);
                    if (ws != null && !ws.isEmpty()) {
                        departmentId = (String) ws.get("departmentId");
                    }
                } catch (Exception e) {
                    log.warn("Failed to get workspace department for workspaceId={}: {}", workspaceId, e.getMessage());
                }
            }
            if (departmentId != null && !departmentId.isBlank()) {
                page = documentRepository.findByWorkspaceIdOrDepartmentIdAndWorkspaceIdEmpty(workspaceId, departmentId, pageable);
            } else {
                page = documentRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId, pageable);
            }
        } else {
            page = documentRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        List<Document> filteredList = filterDocumentsByRole(page.getContent(), userRole, userDepartments);
        return new org.springframework.data.domain.PageImpl<>(filteredList, pageable, page.getTotalElements());
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
        validateWorkspaceAccess(doc.getWorkspaceId(), userId);
        return doc;
    }

    public Document getDocument(Long documentId, String userId, String userRole, String userDepartments) {
        Document doc = getDocument(documentId, userId);
        String allowed = doc.getAllowedRoles();
        if (allowed != null && !allowed.isBlank() && !"ALL".equalsIgnoreCase(allowed)) {
            ParsedUserPermissions perms = parseUserPermissions(userRole, userDepartments);
            if (!perms.isAdmin) {
                String docDeptId = doc.getDepartmentId();
                if (docDeptId != null && !docDeptId.isBlank()) {
                    if ("HEAD".equalsIgnoreCase(allowed)) {
                        if (!perms.deptIdsWhereHead.contains(docDeptId)) {
                            throw new AccessDeniedException("Chỉ Trưởng phòng hoặc Quản trị viên mới được phép truy cập tài liệu này.");
                        }
                    } else if ("MEMBER".equalsIgnoreCase(allowed)) {
                        if (!perms.deptIdsWhereMember.contains(docDeptId) && !perms.deptIdsWhereHead.contains(docDeptId)) {
                            throw new AccessDeniedException("Chỉ thành viên thuộc phòng ban này mới được phép truy cập tài liệu.");
                        }
                    }
                }
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
        Document doc = getDocument(documentId, userId);
        
        // Security logic: check owner before deleting
        if (!doc.getUserId().equals(userId)) {
            throw new ApiException("Permission denied. You can only delete documents you uploaded.");
        }

        // Delete from VectorStore asynchronously (tách biệt transaction)
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                com.security.security.dtorequest.DeleteRequest deleteReq = new com.security.security.dtorequest.DeleteRequest(documentId);
                vectorStore.delete(String.format("documentId == '%s'", deleteReq.getDocumentId()));
            } catch (Exception e) {
                log.warn("Could not delete from VectorStore: {}", e.getMessage());
            }
        });

        // Delete record
        documentRepository.delete(doc);

        // Delete file from disk
        deleteFileOnDisk(doc.getFilePath());

        // Publish event to NATS for real-time frontend update
        natsEventPublisher.publishDocumentStatus(doc.getId(), doc.getUserId(), doc.getWorkspaceId(), "DELETED");

        log.info("Document deleted: {}", documentId);
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
        if (e.equals(".mp3")) return DocType.mp3;
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
            if (sourceImageRepository.findBySourceId(documentId).isEmpty()) {
                markdownContent = extractAndSaveImages(document, markdownContent);
                document.setMarkdownContent(markdownContent);
                documentRepository.save(document);
            }

            List<SemanticMarkdownChunker.ChunkResult> chunkResults = semanticMarkdownChunker.chunk(markdownContent);
            if (chunkResults.isEmpty()) {
                throw new IllegalStateException("No chunks produced from markdown");
            }

            // Purge old chunks from VectorStore before loading new ones
            try {
                com.security.security.dtorequest.DeleteRequest deleteReq = new com.security.security.dtorequest.DeleteRequest(documentId);
                vectorStore.delete(String.format("documentId == '%s'", deleteReq.getDocumentId()));
            } catch (Exception e) {
                log.warn("Could not delete old chunks from VectorStore during update: {}", e.getMessage());
            }

            // G6: Load to DB & VectorStore
            embeddingRepository.deleteByDocumentId(documentId);

            int batchSize = 30;
            List<org.springframework.ai.document.Document> vBatch = new ArrayList<>(batchSize);
            List<Embedding> eBatch = new ArrayList<>(batchSize);

            for (int i = 0; i < chunkResults.size(); i++) {
                SemanticMarkdownChunker.ChunkResult cr = chunkResults.get(i);

                Map<String, Object> meta = new HashMap<>();
                meta.put("documentId", document.getId().toString());
                meta.put("userId", document.getUserId());
                meta.put("fileName", document.getFileName());
                meta.put("chunkIndex", String.valueOf(i));
                meta.put("chunkTitle", cr.title());
                meta.put("tokenCount", String.valueOf(semanticMarkdownChunker.estimateTokens(cr.text())));
                meta.put("charCount", String.valueOf(cr.text().length()));
                meta.put("classification", document.getSecurityClassification());
                meta.put("securityClassification", document.getSecurityClassification());
                meta.put("uploadedBy", document.getUserId());
                meta.put("workspaceId", document.getWorkspaceId() != null ? document.getWorkspaceId() : "");
                meta.put("departmentId", document.getDepartmentId() != null ? document.getDepartmentId() : "");
                meta.put("allowedRoles", document.getAllowedRoles() != null ? document.getAllowedRoles() : "ALL");

                vBatch.add(new org.springframework.ai.document.Document(cr.text(), meta));
                eBatch.add(Embedding.builder()
                        .documentId(document.getId())
                        .chunkIndex(i)
                        .chunkText(cr.text())
                        .chunkTitle(cr.title())
                        .tokenCount(semanticMarkdownChunker.estimateTokens(cr.text()))
                        .charCount(cr.text().length())
                        .build());

                if (vBatch.size() >= batchSize) {
                    vectorStore.add(new ArrayList<>(vBatch));
                    embeddingRepository.saveAll(new ArrayList<>(eBatch));
                    vBatch.clear();
                    eBatch.clear();
                }
            }
            if (!vBatch.isEmpty()) {
                vectorStore.add(vBatch);
                embeddingRepository.saveAll(eBatch);
            }

            log.info("Successfully ingested {} chunks for doc={}", chunkResults.size(), documentId);

            // Profiling stats (docling-style)
            DocumentProfiler.ProfileResult profile = documentProfiler.profile(markdownContent, chunkResults);

            document.setStatus(DocStatus.COMPLETED);
            document.setChunkCount(chunkResults.size());
            document.setNumHeadings(profile.numHeadings());
            document.setNumTables(profile.numTables());
            document.setNumParagraphs(profile.numParagraphs());
            document.setTotalTokens(profile.estimatedTokens());
            document.setAvgTokensPerChunk(profile.avgTokensPerChunk());
            document.setErrorMessage(null);
            documentRepository.save(document);
            natsEventPublisher.publishDocumentStatus(document.getId(), document.getUserId(), document.getWorkspaceId(), "COMPLETED");

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
        ParsedUserPermissions perms = parseUserPermissions(userRole, userDepartments);
        boolean hasLeaderPrivilege = perms.isAdmin;
        String docDeptId = document.getDepartmentId();
        if ((docDeptId == null || docDeptId.trim().isEmpty()) && document.getWorkspaceId() != null) {
            Map<String, Object> ws = workspaceServiceClient.getWorkspace(document.getWorkspaceId(), userId);
            if (!ws.isEmpty()) {
                docDeptId = (String) ws.get("departmentId");
            }
        }
        if (!hasLeaderPrivilege && docDeptId != null && perms.deptIdsWhereHead.contains(docDeptId)) {
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
        log.info("Updating metadata for document {} by user {}", id, userId);
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Document not found"));

        validateWorkspaceAccess(document.getWorkspaceId(), userId);

        if (!document.getUserId().equals(userId) && !userId.equals("system-user")) {
            throw new ApiException("Permission denied");
        }

        if (securityClassification != null) {
            document.setSecurityClassification(securityClassification);
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

        Document saved = documentRepository.save(document);
        // Publish event to NATS for real-time frontend update
        natsEventPublisher.publishDocumentStatus(saved.getId(), saved.getUserId(), saved.getWorkspaceId(), saved.getStatus().name());
        return saved;
    }

    private String extractAndSaveImages(Document document, String markdown) {
        try {
            Path filePath = Paths.get(document.getFilePath());
            if (!Files.exists(filePath)) {
                return markdown;
            }
            byte[] fileBytes = Files.readAllBytes(filePath);
            List<ImageExtractionService.ExtractedImage> extractedImages = 
                    imageExtractionService.extractImages(fileBytes, document.getFileName());
            
            if (extractedImages != null && !extractedImages.isEmpty()) {
                log.info("[DocumentService] Extracted {} inline images from document ID: {}", extractedImages.size(), document.getId());
                
                Path imagesDir = Paths.get(uploadDir).resolve("images");
                if (!Files.exists(imagesDir)) {
                    Files.createDirectories(imagesDir);
                }
                
                ProcessedImageResult[] resultsArray = new ProcessedImageResult[extractedImages.size()];
                for (int idx = 0; idx < extractedImages.size(); idx++) {
                    ProcessedImageResult def = new ProcessedImageResult();
                    def.index = idx;
                    def.skipped = true;
                    resultsArray[idx] = def;
                }

                List<CompletableFuture<Void>> futures = new ArrayList<>();
                Semaphore captionSemaphore = new Semaphore(3); // Cap concurrency for caption calls
                ExecutorService imageExecutor = Executors.newVirtualThreadPerTaskExecutor();

                for (int idx = 0; idx < extractedImages.size(); idx++) {
                    final int index = idx;
                    final ImageExtractionService.ExtractedImage extImg = extractedImages.get(idx);
                    
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            boolean isLogoOrHeader = false;
                            if (extImg.getWidth() > 0 && extImg.getHeight() > 0) {
                                int w = extImg.getWidth();
                                int h = extImg.getHeight();
                                // Heuristic 1: Very small icon or logo (e.g. <= 80x80)
                                if (w <= 80 && h <= 80) {
                                    isLogoOrHeader = true;
                                }
                                // Heuristic 2: Long horizontal/vertical lines or banners (aspect ratio > 8.0 or < 0.125)
                                double aspect = (double) w / h;
                                if (aspect > 8.0 || aspect < 0.125) {
                                    isLogoOrHeader = true;
                                }
                            }

                            if (isLogoOrHeader) {
                                log.info("[DocumentService] Skipping logo/header image index={} due to heuristics (w={}, h={})", index, extImg.getWidth(), extImg.getHeight());
                                return;
                            }

                            UUID imgId = UUID.randomUUID();
                            String imgFilename = imgId.toString() + "." + extImg.getExtension();
                            Path targetPath = imagesDir.resolve(imgFilename);
                            
                            // Generate caption using Gemini (under concurrency control)
                            captionSemaphore.acquire();
                            String caption;
                            try {
                                caption = generateCaption(extImg.getBytes(), extImg.getContentType());
                            } finally {
                                captionSemaphore.release();
                            }
                            
                            boolean isIgnoredText = false;
                            if (caption != null) {
                                String cleanCaption = caption.toLowerCase().trim();
                                if (cleanCaption.replaceAll("[^a-zA-Z]", "").equalsIgnoreCase("IGNORE")
                                        || cleanCaption.contains("logo")
                                        || cleanCaption.contains("biểu tượng")
                                        || cleanCaption.contains("bieu tuong")
                                        || cleanCaption.contains("header")
                                        || cleanCaption.contains("footer")
                                        || cleanCaption.contains("icon")
                                        || cleanCaption.contains("banner")
                                        || cleanCaption.contains("ảnh bìa")
                                        || cleanCaption.contains("anh bia")
                                        || cleanCaption.contains("trang trí")
                                        || cleanCaption.contains("trang tri")) {
                                    isIgnoredText = true;
                                }
                            }

                            if (isIgnoredText) {
                                log.info("[DocumentService] Skipping logo/header image index={} based on Gemini keyword detection in caption: '{}'", index, caption);
                                return;
                            }

                            // Write to disk only if NOT skipped
                            Files.write(targetPath, extImg.getBytes());

                            SourceImage sourceImg = SourceImage.builder()
                                    .id(imgId)
                                    .source(document)
                                    .minioKey("images/" + imgFilename)
                                    .pageNumber(extImg.getPageNumber())
                                    .imageIndex(extImg.getImageIndex())
                                    .caption(caption)
                                    .contentType(extImg.getContentType())
                                    .sizeBytes(extImg.getBytes().length)
                                    .build();
                                    
                            ProcessedImageResult result = new ProcessedImageResult();
                            result.index = index;
                            result.skipped = false;
                            result.sourceImage = sourceImg;
                            resultsArray[index] = result;
                        } catch (Exception ex) {
                            log.warn("[DocumentService] Failed to process extracted image idx={} for docId={}: {}", index, document.getId(), ex.getMessage());
                        }
                    }, imageExecutor);
                    futures.add(future);
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                imageExecutor.shutdown();

                // Delete old source images first to prevent duplicates
                try {
                    sourceImageRepository.deleteBySourceId(document.getId());
                } catch (Exception e) {
                    log.warn("[DocumentService] Failed to delete old source images for docId={}: {}", document.getId(), e.getMessage());
                }

                List<ProcessedImageResult> sortedResults = Arrays.asList(resultsArray);

                // Save non-skipped SourceImage records to the database
                List<SourceImage> imagesToSave = sortedResults.stream()
                        .filter(r -> !r.skipped && r.sourceImage != null)
                        .map(r -> r.sourceImage)
                        .toList();
                if (!imagesToSave.isEmpty()) {
                    sourceImageRepository.saveAll(imagesToSave);
                }

                // Replace image placeholders in the markdown text in-place using sortedResults (1-to-1 matching)
                String updatedMarkdown = markdown;
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("!\\[(.*?)\\]\\((.*?)\\)");
                java.util.regex.Matcher matcher = pattern.matcher(updatedMarkdown);
                
                StringBuffer sb = new StringBuffer();
                int imgIndex = 0;
                while (matcher.find()) {
                    String replacement = "";
                    if (imgIndex < sortedResults.size()) {
                        ProcessedImageResult r = sortedResults.get(imgIndex);
                        if (!r.skipped && r.sourceImage != null) {
                            SourceImage img = r.sourceImage;
                            String alt = sanitizeCaptionForAlt(img.getCaption());
                            replacement = String.format("![%s](image://%s)", alt, img.getId().toString());
                        }
                        imgIndex++;
                    }
                    matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
                }
                matcher.appendTail(sb);
                return sb.toString();
            }
        } catch (Exception e) {
            log.warn("[DocumentService] Image extraction/processing failed for docId={}: {}", document.getId(), e.getMessage());
        }
        return markdown;
    }

    private String generateCaption(byte[] imgBytes, String contentType) {
        try {
            if (chatModel == null) return "Extracted Image";
            
            String mime = contentType.toLowerCase();
            if (!mime.contains("png") && !mime.contains("jpeg") && !mime.contains("jpg") && !mime.contains("webp") && !mime.contains("heic") && !mime.contains("heif")) {
                log.info("Skipping Gemini caption generation for unsupported image type: {}", contentType);
                return "Extracted Image";
            }
            
            org.springframework.core.io.ByteArrayResource byteResource = 
                    new org.springframework.core.io.ByteArrayResource(imgBytes);
            org.springframework.ai.content.Media media = 
                    new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.parseMimeType(contentType), byteResource);
            org.springframework.ai.chat.client.ChatClient chatClient = 
                    org.springframework.ai.chat.client.ChatClient.builder(chatModel).build();
            
            String systemPrompt = "Analyze the image. If the image is a corporate logo, brand icon, page header, page footer, or decorative banner/line, you MUST reply with exactly the word 'IGNORE'. Otherwise, write a brief, 1-sentence description (maximum 10 words) of this image in Vietnamese. Do NOT write any intro, notes, or explanations. Keep it as short as possible.";
            
            org.springframework.ai.chat.model.ChatResponse response = chatClient.prompt()
                    .options(org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder()
                            .model(geminiModel)
                            .temperature(0.2)
                            .maxOutputTokens(40)
                            .build())
                    .system(systemPrompt)
                    .user(u -> u.text("Describe in max 10 words:").media(media))
                    .call()
                    .chatResponse();
                    
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null) {
                    return text.trim();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to generate caption using Gemini: {}", e.getMessage());
        }
        return "Extracted Image";
    }

    private String sanitizeCaptionForAlt(String caption) {
        if (caption == null) return "Extracted Image";
        return caption.replace("\"", "'").replace("[", "").replace("]", "").replace("\n", " ").trim();
    }

    private static class ProcessedImageResult {
        int index;
        boolean skipped;
        SourceImage sourceImage;
    }
}
