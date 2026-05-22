package com.security.security.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "agent_skills")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentSkill {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String systemPrompt;

    @Column(name = "workspace_id", length = 50)
    private String workspaceId;

    @Column(name = "default_provider", length = 50)
    private String defaultProvider;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
