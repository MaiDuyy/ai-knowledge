package com.security.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.dto.UserPermissionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
