package com.security.security.dtorequest;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RAGQueryPayload {
    private String query;
    private String userId;
    private UserPermissionContext userPermissions;
    private RAGOptions options;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserPermissionContext {
        private List<String> roles;
        private Integer roleLevel;
        private List<String> departments;
        private List<String> groups;
        private List<String> accessibleCollections;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RAGOptions {
        private Integer maxResults;
        private Double minScore;
        private List<String> collections;
    }
}
