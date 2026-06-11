package com.security.security.service;

import com.security.security.dto.DocumentUploadResponse;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import com.security.security.event.DocumentUploadedEvent;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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

import com.security.security.client.WorkspaceServiceClient;
import org.springframework.security.access.AccessDeniedException;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final ApplicationEventPublisher eventPublisher;
    private final TikaHtmlExtractor tikaHtmlExtractor;
    private final HtmlToMarkdownConverter htmlToMarkdownConverter;
    private final EmbeddingRepository embeddingRepository;
    private final SemanticMarkdownChunker semanticMarkdownChunker;
    private final DocumentProfiler documentProfiler;
    private final DoclingClient doclingClient;
    private final MrpPipelineService mrpPipelineService;
    private final NatsEventPublisher natsEventPublisher;
    private final WorkspaceServiceClient workspaceServiceClient;

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

    // Khuyến nghị set ABSOLUTE:
    // app.upload.dir=C:/data/myapp/uploads
    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

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
        log.info("Uploading document for user: {}, preview: {}, parser: {}, workspaceId: {}, departmentId: {}, allowedRoles: {}, classification: {}", 
                userId, preview, parser, workspaceId, departmentId, allowedRoles, securityClassification);

        String resolvedWorkspaceId = workspaceId;
        if (resolvedWorkspaceId == null || resolvedWorkspaceId.trim().isEmpty() || "all".equalsIgnoreCase(resolvedWorkspaceId.trim())) {
            resolvedWorkspaceId = "default-workspace";
        }

        if (file == null || file.isEmpty()) {
            throw new ApiException("File is empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ApiException("File size exceeds 50MB limit");
        }

        if (!isValidDocumentFile(file)) {
            throw new ApiException("Unsupported file type. Only PDF, DOCX, TXT are allowed.");
        }

        try {
            // 1) Create upload directory if it doesn't exist
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // 2) Sanitize original filename (remove any path parts)
            String originalName = file.getOriginalFilename();
            String safeName = (originalName == null || originalName.isBlank())
                    ? "file"
                    : Paths.get(originalName).getFileName().toString();

            // 3) Unique filename
            String ext = getFileExtension(safeName);
            DocType docType = resolveDocTypeByExtension(ext);

            if (docType == null) {
                throw new ApiException("Unsupported file type. Only PDF, DOCX, TXT are allowed.");
            }

            String uniqueFileName = "doc_" + userId + "_" + UUID.randomUUID() + ext;

            // 4) Save file (copy stream)
            Path targetPath = uploadPath.resolve(uniqueFileName).normalize();

            // Optional safety: prevent path traversal
            if (!targetPath.startsWith(uploadPath.normalize())) {
                throw new ApiException("Invalid file path");
            }

            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("Document uploaded successfully: {}", uniqueFileName);

            String storedPath = targetPath.toString();

            boolean isPreview = preview != null && preview;

            Document document = Document.builder()
                    .userId(userId)
                    .workspaceId(resolvedWorkspaceId)          // Associate document with workspace
                    .fileName(safeName)                 // tên gốc để hiển thị
                    .fileSize((int) file.getSize())
                    .filePath(storedPath)               // path file đã lưu
                    .documentType(docType)
                    .status(isPreview ? DocStatus.PREVIEW : DocStatus.PENDING)
                    .chunkCount(0)
                    .parserMethod(parser != null ? parser : "gemini")
                    .departmentId(departmentId)
                    .allowedRoles(allowedRoles != null && !allowedRoles.isBlank() ? allowedRoles : "ALL")
                    .securityClassification(securityClassification != null && !securityClassification.isBlank() ? securityClassification : "INTERNAL")
                    .build();

            if (isPreview) {
                // Parse immediately to extract raw Markdown
                try {
                    log.info("Immediately parsing document {} for preview", safeName);
                    String markdown = null;

                    try {
                        if (doclingClient.isHealthy()) {
                            log.info("[Preview] Docling is healthy. Using Docling API for '{}' with preferred parser: '{}'", safeName, document.getParserMethod());
                            DoclingClient.DoclingResult doclingResult = doclingClient.convertToMarkdown(new FileSystemResource(storedPath), safeName, document.getParserMethod());
                            if (doclingResult.success()) {
                                markdown = doclingResult.markdown();
                                log.info("[Preview] Successfully parsed with Docling: {} chars", markdown.length());
                            } else {
                                log.warn("[Preview] Docling parsing failed: {}. Falling back to Apache Tika.", doclingResult.errorMessage());
                            }
                        } else {
                            log.info("[Preview] Docling is offline. Falling back to Apache Tika.");
                        }
                    } catch (Exception doclingEx) {
                        log.warn("[Preview] Error calling Docling: {}. Falling back to Apache Tika.", doclingEx.getMessage());
                    }

                    if (markdown == null) {
                        log.info("[Preview] Running Apache Tika fallback extraction.");
                        TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(new FileSystemResource(storedPath));
                        String rawHtml = htmlResult.html();
                        markdown = htmlToMarkdownConverter.convert(rawHtml);
                    }
                    document.setMarkdownContent(markdown);
                } catch (Exception parseEx) {
                    log.error("Failed to pre-parse document for preview: {}", parseEx.getMessage());
                    document.setStatus(DocStatus.FAILED);
                    document.setErrorMessage("Failed to pre-parse document: " + parseEx.getMessage());
                }
            }

            Document saved = documentRepository.save(document);
            natsEventPublisher.publishDocumentStatus(saved.getId(), saved.getUserId(), saved.getWorkspaceId(), saved.getStatus().name());

            if (!isPreview && saved.getStatus() != DocStatus.FAILED) {
                // Trigger async processing
                eventPublisher.publishEvent(new DocumentUploadedEvent(this, saved));
            }

            return DocumentUploadResponse.builder()
                    .documentId(saved.getId())
                    .fileName(saved.getFileName())
                    .status(String.valueOf(saved.getStatus()))
                    .markdownContent(saved.getMarkdownContent())
                    .message(isPreview 
                            ? "Document uploaded and parsed for preview successfully."
                            : "Document uploaded successfully. Processing started.")
                    .build();

        } catch (IOException e) {
            log.error("Error uploading document for user {}: {}", userId, e.getMessage());
            throw new ApiException("Failed to upload document. Please try again with a valid file.");
        } catch (Exception e) {
            log.error("Unexpected error uploading document for user {}: {}", userId, e.getMessage());
            throw new ApiException("Failed to upload document due to an internal error.");
        }
    }

    /**
     * Get documents scoped by workspaceId.
     * Falls back to company-wide listing if workspaceId is null/blank (backward compat).
     */
    public List<Document> getDocuments(String userId, String workspaceId) {
        validateWorkspaceAccess(workspaceId, userId);
        if (workspaceId != null && !workspaceId.isBlank()) {
            return documentRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        }
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }

    public Page<Document> getDocuments(String userId, String workspaceId, Pageable pageable) {
        validateWorkspaceAccess(workspaceId, userId);
        if (workspaceId != null && !workspaceId.isBlank()) {
            return documentRepository.findByWorkspaceIdOrderByCreatedAtDesc(workspaceId, pageable);
        }
        return documentRepository.findAllByOrderByCreatedAtDesc(pageable);
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

    public org.springframework.core.io.Resource getDocumentFileResource(Long documentId, String userId) {
        Document doc = getDocument(documentId, userId);
        if (doc.getFilePath() == null) {
            throw new ApiException("Original file not found for this document");
        }
        Path path = Paths.get(doc.getFilePath());
        if (!Files.exists(path)) {
            throw new ApiException("Original file not found on disk");
        }
        return new FileSystemResource(path);
    }

    @Transactional
    public void deleteDocument(Long documentId, String userId) {
        Document doc = getDocument(documentId, userId);
        
        // Security logic: check owner before deleting
        if (!doc.getUserId().equals(userId)) {
            throw new ApiException("Permission denied. You can only delete documents you uploaded.");
        }

        // Delete from VectorStore
        try {
            vectorStore.delete(String.format("documentId == '%s'", documentId));
        } catch (Exception e) {
            log.warn("Could not delete from VectorStore: {}", e.getMessage());
        }

        // Delete record
        documentRepository.delete(doc);

        // Delete file from disk
        deleteFileOnDisk(doc.getFilePath());

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
            List<SemanticMarkdownChunker.ChunkResult> chunkResults = semanticMarkdownChunker.chunk(markdownContent);
            if (chunkResults.isEmpty()) {
                throw new IllegalStateException("No chunks produced from markdown");
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
            eventPublisher.publishEvent(new DocumentUploadedEvent(this, saved));
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

        return documentRepository.save(document);
    }
}
