package com.security.security.entity;

import com.security.security.entity.enumeration.ChunkType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "embeddings", indexes = {
    @Index(name = "idx_embedding_chunk_type", columnList = "chunk_type"),
    @Index(name = "idx_embedding_document_type", columnList = "document_id, chunk_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Embedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "TEXT")
    private String chunkText;

    @Column(name = "context_header", columnDefinition = "TEXT")
    private String contextHeader;

    @Column(name = "section_path", length = 512)
    private String sectionPath;

    @Column(name = "chunk_type", length = 30)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ChunkType chunkType = ChunkType.TEXT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "embedding", columnDefinition = "JSONB")
    private String embedding;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "chunk_title")
    private String chunkTitle;

    @Column(name = "workspace_id", length = 50)
    private String workspaceId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
