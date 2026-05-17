package com.security.security.entity;

import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size", nullable = false)
    private Integer fileSize;

    @Column(name = "file_path")
    private String filePath;

    // URL from S3/Cloudinary when uploaded via file-service NATS event
    @Column(name = "file_url", length = 2048)
    private String fileUrl;

    @Column(name = "document_type")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DocType documentType =DocType.pdf;  // pdf, docx, txt

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DocStatus status = DocStatus.PENDING;  // PENDING, PROCESSING, COMPLETED, FAILED

    @Column(name = "chunk_count")
    @Builder.Default
    private Integer chunkCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
