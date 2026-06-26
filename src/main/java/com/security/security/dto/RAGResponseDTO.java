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
    /** Confidence level: HIGH | MEDIUM | LOW | NONE */
    private String confidence;
    private Double confidenceScore;
    /** 2-3 follow-up questions suggested by the LLM based on context */
    private List<String> suggestedFollowUps;

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
