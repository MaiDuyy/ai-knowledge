package com.security.security.entity.mongo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified MongoDB document model for experimental trade-off analysis.
 * Optionally embeds chunks when {@code mongo.use-nested-chunks=true}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "documents")
public class MongoDocument {

    @Id
    private String id;

    /** Cross-reference to PostgreSQL documents.id when dual-written. */
    @Indexed
    private Long postgresDocumentId;

    private String userId;

    @Indexed
    private String workspaceId;

    private String fileName;
    private Long fileSize;
    private String filePath;
    private String fileUrl;
    private String documentType;
    private String status;
    private String parserMethod;
    private String markdownContent;
    private Integer chunkCount;
    private String securityClassification;

    @Indexed
    private String departmentId;

    @Indexed
    private String allowedRoles;

    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    /** Nested chunks (nested document model). Empty/null in flat-chunk mode. */
    @Builder.Default
    private List<MongoChunk> chunks = new ArrayList<>();
}
