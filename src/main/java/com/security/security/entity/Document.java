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
    private DocStatus status = DocStatus.PENDING;  // PENDING, PREVIEW, PROCESSING, COMPLETED, FAILED

    @Column(name = "parser_method")
    @Builder.Default
    private String parserMethod = "gemini";

    @Column(name = "markdown_content", columnDefinition = "TEXT")
    private String markdownContent;

    @Column(name = "chunk_count")
    @Builder.Default
    private Integer chunkCount = 0;

    // ── Document Profiling Stats (docling-style) ────────────────────────────

    @Column(name = "num_headings")
    @Builder.Default
    private Integer numHeadings = 0;

    @Column(name = "num_tables")
    @Builder.Default
    private Integer numTables = 0;

    @Column(name = "num_paragraphs")
    @Builder.Default
    private Integer numParagraphs = 0;

    @Column(name = "total_tokens")
    @Builder.Default
    private Integer totalTokens = 0;

    @Column(name = "avg_tokens_per_chunk")
    @Builder.Default
    private Integer avgTokensPerChunk = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
