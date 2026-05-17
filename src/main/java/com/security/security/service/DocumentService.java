package com.security.security.service;

import com.security.security.dto.DocumentUploadResponse;
import com.security.security.entity.Document;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import com.security.security.event.DocumentUploadedEvent;
import com.security.security.exception.ApiException;
import com.security.security.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final VectorStore vectorStore;
    private final ApplicationEventPublisher eventPublisher;

    // Khuyến nghị set ABSOLUTE:
    // app.upload.dir=C:/data/myapp/uploads
    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    private static final long MAX_FILE_SIZE = 52_428_800L; // 50MB

    // Cho phép PDF, DOCX, TXT (đúng như validate hiện tại của bạn)
    private static final List<String> ALLOWED_DOC_TYPES = Arrays.asList(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain"
    );

    @Transactional
    public DocumentUploadResponse uploadDocument(MultipartFile file, String userId) {
        log.info("Uploading document for user: {}", userId);

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

            // Lưu path (bạn có thể lưu absolute nếu muốn: uploadPath.toAbsolutePath().resolve(...))
            String storedPath = targetPath.toString();

            Document document = Document.builder()
                    .userId(userId)
                    .fileName(safeName)                 // tên gốc để hiển thị
                    .fileSize((int) file.getSize())
                    .filePath(storedPath)               // path file đã lưu
                    .documentType(docType)
                    .status(DocStatus.PENDING)
                    .chunkCount(0)
                    .build();

            Document saved = documentRepository.save(document);

            // Trigger async processing
            eventPublisher.publishEvent(new DocumentUploadedEvent(this, saved));

            return DocumentUploadResponse.builder()
                    .documentId(saved.getId())
                    .fileName(saved.getFileName())
                    .status(String.valueOf(saved.getStatus()))
                    .message("Document uploaded successfully. Processing started.")
                    .build();

        } catch (IOException e) {
            log.error("Error uploading document for user {}: {}", userId, e.getMessage());
            throw new ApiException("Failed to upload document: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error uploading document for user {}: {}", userId, e.getMessage());
            throw new ApiException("Failed to upload document: " + e.getMessage());
        }
    }

    public List<Document> getUserDocuments(String userId) {
        // Return ALL documents for company knowledge base instead of just the user's
        return documentRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<Document> getCompletedDocuments(String userId) {
        // Return ALL completed docs
        return documentRepository.findCompletedByOrderByCreatedAtDesc();
    }

    public Document getDocument(Long documentId, String userId) {
        // Allow reading any document in the system
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ApiException("Document not found"));
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

        // check content-type
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_DOC_TYPES.contains(contentType)) {
            // Một số client upload có contentType hơi “lạ” -> vẫn còn check extension phía dưới
            // return false; // nếu muốn strict thì bật dòng này
        }

        // check extension
        String name = file.getOriginalFilename();
        if (name == null) return false;
        String ext = getFileExtension(name).toLowerCase();

        return ext.equals(".pdf") || ext.equals(".docx") || ext.equals(".txt");
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
        if (e.equals(".docx")) return DocType.docx;
        if (e.equals(".txt")) return DocType.txt;
        return null;
    }
}
