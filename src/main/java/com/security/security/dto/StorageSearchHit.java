package com.security.security.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Engine-agnostic similarity search hit used by {@link com.security.security.service.KnowledgeStorageEngine}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageSearchHit {
    private Long documentId;
    private String documentMongoId;
    private Integer chunkIndex;
    private String chunkTitle;
    private String text;
    private double score;
    private Integer tokenCount;

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
}
