package com.security.security.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "wiki_page_drafts", indexes = {
    @Index(name = "idx_draft_wiki_page", columnList = "wiki_page_id"),
    @Index(name = "idx_draft_slug", columnList = "slug"),
    @Index(name = "idx_draft_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WikiPageDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wiki_page_id")
    private Long wikiPageId; // Nullable (for new page creations)

    @Column(nullable = false, length = 255)
    private String slug;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "page_type", length = 50)
    private String pageType; // entity, concept, topic, source

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "tags")
    private String tags;

    @Column(name = "workspace_id", length = 50)
    private String workspaceId;

    @Column(name = "department_id", length = 100)
    private String departmentId;

    @Column(name = "allowed_roles", length = 100)
    @Builder.Default
    private String allowedRoles = "ALL"; // ALL, HEAD, MEMBER

    @Column(name = "security_classification", length = 50)
    @Builder.Default
    private String securityClassification = "INTERNAL"; // PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED

    @Column(name = "author_id", nullable = false, length = 100)
    private String authorId;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED, NEEDS_REVISION, WITHDRAWN

    @Column(name = "note", length = 500)
    private String note; // Author's change/submission note

    @Column(name = "reviewer_note", length = 1000)
    private String reviewerNote; // Reviewer's feedback note

    @Column(name = "base_version")
    private Integer baseVersion; // Version this draft is based on (for optimistic conflict check)

    @Column(name = "revision_round")
    @Builder.Default
    private Integer revisionRound = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
