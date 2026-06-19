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
public class WikiGraphCommunityDto {
    private List<Community> communities;
    private List<WikiHealthDto.WikiPageRef> bridgeNodes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Community {
        private int id;
        private List<String> pageSlugs;
        private double cohesion;
        private String topHub;
        private boolean lowCohesion;
    }
}
