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
 * Flat chunk collection — relational-style modeling inside MongoDB
 * when {@code mongo.use-nested-chunks=false}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "mongo_flat_chunks")
public class MongoFlatChunk {

    @Id
    private String id;

    @Indexed
    private String documentId;

    @Indexed
    private Long postgresDocumentId;

    private Integer chunkIndex;
    private String chunkText;
    private String contextHeader;
    private String sectionPath;
    private String chunkType;

    @Builder.Default
    private List<Double> embedding = new ArrayList<>();

    private Integer tokenCount;
    private Integer charCount;
    private String chunkTitle;

    /** Denormalized for metadata pre-filter without joining parent document. */
    @Indexed
    private String workspaceId;

    @Indexed
    private String departmentId;

    private String allowedRoles;
    private String securityClassification;

    private Instant createdAt;
}
