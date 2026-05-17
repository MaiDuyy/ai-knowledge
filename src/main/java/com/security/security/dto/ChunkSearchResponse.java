package com.security.security.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkSearchResponse {
    private String query;
    private Integer totalResults;
    private List<ChunkSearchResult> chunks;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkSearchResult {
        private Long documentId;
        private String fileName;
        private Integer chunkIndex;
        private String chunkTitle;
        private String text;
        private Double similarity;
        private Integer tokenCount;
    }
}
