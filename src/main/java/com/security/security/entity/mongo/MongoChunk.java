package com.security.security.entity.mongo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Nested chunk stored inside {@link MongoDocument#chunks} when
 * {@code mongo.use-nested-chunks=true}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MongoChunk {

    private Integer chunkIndex;
    private String chunkText;
    private String contextHeader;
    private String sectionPath;
    private String chunkType;

    /** 768-dim embedding vector (gemini-embedding-001 / pgvector parity). */
    @Builder.Default
    private List<Double> embedding = new ArrayList<>();

    private Integer tokenCount;
    private Integer charCount;
    private String chunkTitle;
}
