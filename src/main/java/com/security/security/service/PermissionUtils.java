package com.security.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.dto.UserPermissionContext;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.enumeration.SecurityClassification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PermissionUtils {
    private static final Logger log = LoggerFactory.getLogger(PermissionUtils.class);

    private PermissionUtils() {}

    public static boolean isHeadOrDeputy(String role) {
        if (role == null) return false;
        String r = role.toUpperCase();
        return "HEAD".equals(r) || "MANAGER".equals(r) 
            || "DEPUTY_HEAD".equals(r) || "VICE_HEAD".equals(r)
            || "DEPUTY_MANAGER".equals(r) || "VICE_MANAGER".equals(r);
    }

    /**
     * Defense-in-depth post-query access check for wiki/document metadata
     * (shared by PostgreSQL RAG path and experimental Mongo engines).
     * Logic aligned with {@link RAGService#isPageAccessible}.
     */
    public static boolean isResourceAccessible(
            String resourceWorkspaceId,
            String resourceDepartmentId,
            String allowedRoles,
            SecurityClassification securityClassification,
            RAGQueryPayload.UserPermissionContext context) {
        if (context == null) {
            return false;
        }

        String resolvedWorkspaceId = ScopeNormalizer.normalizeWorkspace(context.getWorkspaceId());
        String pageWsId = ScopeNormalizer.normalizeWorkspace(resourceWorkspaceId);

        if (!"ALL".equals(pageWsId) && !"GLOBAL".equals(pageWsId) && !pageWsId.equals(resolvedWorkspaceId)) {
            return false;
        }

        List<String> roles = context.getRoles();
        Integer roleLevel = context.getRoleLevel();
        boolean isAdmin = false;
        if (roles != null) {
            if (roles.contains("SUPER_ADMIN") || roles.contains("ADMIN") || roles.contains("ORG_ADMIN")) {
                isAdmin = true;
            }
        }
        if (roleLevel != null && roleLevel <= 1) {
            isAdmin = true;
        }
        if (isAdmin) {
            return true;
        }

        boolean isGuest = false;
        if (roles != null && roles.contains("EXTERNAL_GUEST")) {
            isGuest = true;
        }
        if (roleLevel != null && roleLevel >= 6) {
            isGuest = true;
        }
        if (isGuest) {
            return SecurityClassification.PUBLIC == securityClassification;
        }

        if (SecurityClassification.PUBLIC == securityClassification) {
            return true;
        }

        List<String> deptIdsWhereHead = new ArrayList<>();
        List<String> deptIdsWhereMember = new ArrayList<>();
        List<RAGQueryPayload.DepartmentRole> userDepts = context.getUserDepartments();
        if (userDepts != null) {
            for (RAGQueryPayload.DepartmentRole dept : userDepts) {
                String deptId = dept.getDepartmentId();
                String role = dept.getRole();
                if (deptId != null && !deptId.trim().isEmpty()) {
                    if (isHeadOrDeputy(role)) {
                        deptIdsWhereHead.add(deptId);
                        deptIdsWhereMember.add(deptId);
                    } else {
                        deptIdsWhereMember.add(deptId);
                    }
                }
            }
        }

        boolean hasHeadRole = !deptIdsWhereHead.isEmpty();
        if ("HEAD".equalsIgnoreCase(allowedRoles) && !hasHeadRole) {
            return false;
        }

        String pageDeptId = ScopeNormalizer.normalizeDepartment(resourceDepartmentId);
        if (!"ALL".equals(pageDeptId) && !"GLOBAL".equals(pageDeptId)) {
            if (deptIdsWhereHead.contains(pageDeptId)) {
                return true;
            }
            if (deptIdsWhereMember.contains(pageDeptId) && !"HEAD".equalsIgnoreCase(allowedRoles)) {
                return true;
            }
            return false;
        }

        if (SecurityClassification.INTERNAL == securityClassification) {
            return true;
        }

        if (!"ALL".equals(pageWsId) && !"GLOBAL".equals(pageWsId)) {
            return true;
        }

        return false;
    }

    /** Convenience overload for {@link com.security.security.entity.WikiPage}. */
    public static boolean isPageAccessible(
            com.security.security.entity.WikiPage page,
            RAGQueryPayload.UserPermissionContext context) {
        if (page == null) {
            return false;
        }
        return isResourceAccessible(
                page.getWorkspaceId(),
                page.getDepartmentId(),
                page.getAllowedRoles(),
                page.getSecurityClassification(),
                context);
    }

    public static UserPermissionContext parse(
            String userRolesHeader,
            String userDepartmentsHeader,
            ObjectMapper objectMapper
    ) {
        UserPermissionContext ctx = new UserPermissionContext();

        if (userRolesHeader != null) {
            String upper = userRolesHeader.toUpperCase();
            if (upper.contains("SUPER_ADMIN") || upper.contains("ADMIN") || upper.contains("ORG_ADMIN")) {
                ctx.setAdmin(true);
            }
            if (upper.contains("WORKSPACE_MANAGER")) ctx.getWorkspaceRoles().add("WORKSPACE_MANAGER");
            if (upper.contains("WORKSPACE_ADMIN")) ctx.getWorkspaceRoles().add("WORKSPACE_ADMIN");
            if (upper.contains("WORKSPACE_OWNER")) ctx.getWorkspaceRoles().add("WORKSPACE_OWNER");
        }

        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            boolean hasAdminAuthority = auth.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN")
                            || a.equals("ROLE_ORG_ADMIN") || a.contains("ADMIN"));
            if (hasAdminAuthority) {
                ctx.setAdmin(true);
            }
        }

        if (userDepartmentsHeader != null && !userDepartmentsHeader.trim().isEmpty()) {
            try {
                List<Map<String, String>> depts = objectMapper.readValue(
                        userDepartmentsHeader,
                        new TypeReference<List<Map<String, String>>>() {}
                );
                for (Map<String, String> dept : depts) {
                    String deptId = dept.get("departmentId");
                    if (deptId == null) {
                        deptId = dept.get("id");
                    }
                    String role = dept.get("role");
                    if (role == null) {
                        role = dept.get("userRole");
                    }
                    if (deptId != null && !deptId.trim().isEmpty()) {
                        if (isHeadOrDeputy(role)) {
                            ctx.getDeptIdsWhereHead().add(deptId);
                            ctx.getDeptIdsWhereMember().add(deptId);
                        } else {
                            ctx.getDeptIdsWhereMember().add(deptId);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse x-user-departments header: {}", e.getMessage());
            }
        }

        if (ctx.getDeptIdsWhereHead().isEmpty()) ctx.getDeptIdsWhereHead().add("DUMMY_DEPT_ID");
        if (ctx.getDeptIdsWhereMember().isEmpty()) ctx.getDeptIdsWhereMember().add("DUMMY_DEPT_ID");

        return ctx;
    }
}
