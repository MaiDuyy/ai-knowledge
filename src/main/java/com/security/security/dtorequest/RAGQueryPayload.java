package com.security.security.dtorequest;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RAGQueryPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private String query;
    private String userId;

    @JsonAlias({"userContext", "userPermissions"})
    private UserPermissionContext userPermissions;
    private RAGOptions options;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserPermissionContext implements Serializable {
        private static final long serialVersionUID = 1L;

        private List<String> roles;
        private Integer roleLevel;
        private List<String> departments;
        private List<String> groups;
        private List<String> accessibleCollections;
        private String workspaceId;
        private List<DepartmentRole> userDepartments;
        private String ragScope;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepartmentRole implements Serializable {
        private static final long serialVersionUID = 1L;

        @com.fasterxml.jackson.annotation.JsonAlias({"departmentId", "id"})
        private String departmentId;

        @com.fasterxml.jackson.annotation.JsonAlias({"role", "userRole"})
        private String role; // HEAD, MEMBER, etc.
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RAGOptions implements Serializable {
        private static final long serialVersionUID = 1L;

        private Integer maxResults;
        private Double minScore;
        private List<String> collections;
        private String pageType;
    }
}
