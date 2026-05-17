package com.security.security.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStatsDTO {
    private Long documentId;
    private String fileName;
    private String status;
    private Integer totalChunks;
    private Integer totalTokens;
    private Integer totalCharacters;
    private Integer avgTokensPerChunk;
    private Integer avgCharsPerChunk;
}

