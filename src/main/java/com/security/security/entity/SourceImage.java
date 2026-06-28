package com.security.security.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "source_images", schema = "ai_knowledge")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceImage {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = true)
    private Document source;

    @Column(name = "minio_key", nullable = false, columnDefinition = "TEXT")
    private String minioKey;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "image_index", nullable = false)
    private Integer imageIndex;

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Integer sizeBytes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
