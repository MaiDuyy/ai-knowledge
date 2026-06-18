package com.security.security.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class UserPermissionContext {
    private boolean admin = false;
    private List<String> deptIdsWhereHead = new ArrayList<>();
    private List<String> deptIdsWhereMember = new ArrayList<>();
    private List<String> workspaceRoles = new ArrayList<>();

    public boolean hasWorkspaceRole(String role) {
        return workspaceRoles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }
}
