package com.security.security.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChunkDTO {
    private Long id;
    private Integer chunkIndex;
    private String chunkTitle;
    private String text;
    private Integer tokenCount;
    private Integer charCount;
    private Double similarity;  // Used in search results
    private LocalDateTime createdAt;
}
