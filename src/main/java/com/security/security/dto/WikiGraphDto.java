package com.security.security.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WikiGraphDto {
    private List<NodeDto> nodes;
    private List<EdgeDto> edges;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class NodeDto {
        private String slug;
        private String title;
        private String pageType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EdgeDto {
        private String from;
        private String to;
    }
}
