package com.security.security.entity;

import com.security.security.entity.enumeration.WikiIssueStatus;
import com.security.security.entity.enumeration.WikiIssueType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "wiki_issue")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WikiIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String wikiPageSlug;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private WikiIssueType issueType;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WikiIssueStatus status = WikiIssueStatus.OPEN;

    @Column(length = 100)
    private String workspaceId;

    @Column(columnDefinition = "TEXT")
    private String description; // human-readable description of the issue

    @Column(columnDefinition = "TEXT")
    private String evidence; // the specific text excerpt showing the issue

    @Column(columnDefinition = "TEXT")
    private String suggestedFix; // LLM-generated suggestion

    @Column(length = 100)
    private String detectedBy; // "auto" or userId

    @Column(length = 100)
    private String resolvedBy; // userId who fixed/ignored

    @Column(columnDefinition = "TEXT")
    private String resolvedNote;

    private LocalDateTime resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
