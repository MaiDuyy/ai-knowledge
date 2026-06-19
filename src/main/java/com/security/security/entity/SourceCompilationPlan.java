package com.security.security.entity;

import com.security.security.entity.enumeration.SourceCompilationStatus;
import com.security.security.entity.enumeration.SecurityClassification;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "source_compilation_plans", indexes = {
    @Index(name = "idx_plan_source_doc", columnList = "source_document_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceCompilationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_document_id", nullable = false, unique = true)
    private Long sourceDocumentId;

    @Transient
    private String sourceDocumentName;

    @Column(name = "plan_json", columnDefinition = "TEXT")
    private String planJson;

    @Column(nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SourceCompilationStatus status = SourceCompilationStatus.PENDING_REVIEW; // PENDING_REVIEW, APPROVED, DONE

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "department_id", length = 100)
    private String departmentId;

    @Column(name = "allowed_roles", length = 100)
    @Builder.Default
    private String allowedRoles = "ALL"; // ALL, HEAD, MEMBER

    @Column(name = "security_classification", length = 50)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private SecurityClassification securityClassification = SecurityClassification.INTERNAL; // PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
