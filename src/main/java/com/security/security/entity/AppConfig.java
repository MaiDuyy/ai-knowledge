package com.security.security.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Stores dynamic application configuration key-value pairs.
 * Sensitive values (API keys, tokens) are encrypted at rest.
 * Priority: DB > application.properties > default.
 */
@Entity
@Table(name = "app_config", schema = "ai_knowledge",
        uniqueConstraints = @UniqueConstraint(columnNames = "config_key"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 100, unique = true)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "description", length = 500)
    private String description;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
