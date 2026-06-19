package com.security.security.entity;

import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.enumeration.WikiPageType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "wiki_pages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WikiPage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 255)
    private String slug;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "workspace_id", length = 50)
    private String workspaceId;

    @Column(name = "department_id", length = 100)
    private String departmentId;

    @Column(name = "allowed_roles", length = 100)
    @Builder.Default
    private String allowedRoles = "ALL"; // ALL, HEAD, MEMBER

    @Column(name = "security_classification", length = 50)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SecurityClassification securityClassification = SecurityClassification.INTERNAL; // PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED

    @Column(name = "tags")
    private String tags;

    @Column(name = "page_type", length = 50)
    private WikiPageType pageType; // entity, concept, topic, source

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "source_document_id")
    private Long sourceDocumentId;

    @Version
    @Column(name = "version")
    @Builder.Default
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
