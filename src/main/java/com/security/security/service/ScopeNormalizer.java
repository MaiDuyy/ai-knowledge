package com.security.security.service;

public final class ScopeNormalizer {

    private ScopeNormalizer() {
        // Utility class
    }

    public static String normalizeWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.trim().isEmpty() 
            || "default-workspace".equalsIgnoreCase(workspaceId.trim()) 
            || "workspace-default".equalsIgnoreCase(workspaceId.trim())
            || "all".equalsIgnoreCase(workspaceId.trim())
            || "GLOBAL".equalsIgnoreCase(workspaceId.trim())) {
            return "GLOBAL";
        }
        return workspaceId.trim();
    }

    public static String normalizeDepartment(String departmentId) {
        if (departmentId == null || departmentId.trim().isEmpty() 
            || "all".equalsIgnoreCase(departmentId.trim())
            || "default".equalsIgnoreCase(departmentId.trim())
            || "GLOBAL".equalsIgnoreCase(departmentId.trim())) {
            return "GLOBAL";
        }
        return departmentId.trim();
    }
}
