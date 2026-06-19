package com.security.security.dto;

import com.security.security.entity.enumeration.WikiPageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WikiHealthDto {
    private List<WikiPageRef> orphanPages;
    private List<BrokenLink> brokenLinks;
    private List<WikiPageRef> stalePages;
    private HealthSummary summary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WikiPageRef {
        private String slug;
        private String title;
        private WikiPageType pageType;
        private LocalDateTime updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrokenLink {
        private String fromSlug;
        private String toSlug;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HealthSummary {
        private int totalPages;
        private int orphanCount;
        private int brokenLinkCount;
        private int staleCount;
        private double healthScore;
    }
}
