package com.security.security.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RAGResponseDTO {
    private String answer;
    private List<SourceDTO> sources;
    private Map<String, Object> metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceDTO {
        private String documentId;
        private String documentTitle;
        private String chunkId;
        private String content;
        private Double score;
    }
}
