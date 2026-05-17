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
public class DocumentChunksResponse {
    private Long documentId;
    private String fileName;
    private Integer totalChunks;
    private List<ChunkDTO> chunks;
}
