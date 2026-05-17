package com.security.security.dtorequest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkSearchRequest {
    private String query;
    private Integer topK = 5;
    private Double minSimilarity = 0.5;
}
