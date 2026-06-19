package com.security.security.entity;

import com.security.security.entity.enumeration.SourceChunkStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "source_chunk_extracts", indexes = {
    @Index(name = "idx_chunk_source_doc", columnList = "source_document_id"),
    @Index(name = "idx_chunk_source_doc_idx", columnList = "source_document_id, chunk_index")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceChunkExtract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_document_id", nullable = false)
    private Long sourceDocumentId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "start_char")
    private Integer startChar;

    @Column(name = "end_char")
    private Integer endChar;

    @Column(name = "section_path", length = 512)
    private String sectionPath;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SourceChunkStatus status = SourceChunkStatus.PENDING; // PENDING, DONE, ERROR

    @Column(name = "extract_json", columnDefinition = "TEXT")
    private String extractJson;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
