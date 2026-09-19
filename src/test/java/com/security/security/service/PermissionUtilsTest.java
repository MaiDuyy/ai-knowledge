package com.security.security.service;

import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.enumeration.SecurityClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionUtilsTest {

    @Test
    @DisplayName("Admin in workspace ws-hr can access ws-hr and ALL/GLOBAL documents, but NOT ws-it documents")
    void adminInSpecificWorkspace_RespectsWorkspaceIsolation() {
        RAGQueryPayload.UserPermissionContext hrAdminContext = new RAGQueryPayload.UserPermissionContext();
        hrAdminContext.setWorkspaceId("ws-hr");
        hrAdminContext.setRoles(List.of("ADMIN"));
        hrAdminContext.setRoleLevel(1);

        // Same workspace
        boolean canAccessHr = PermissionUtils.isResourceAccessible(
                "ws-hr", "dept-hr", "MEMBER", SecurityClassification.INTERNAL, hrAdminContext);
        assertThat(canAccessHr).isTrue();

        // ALL / GLOBAL workspace
        boolean canAccessAll = PermissionUtils.isResourceAccessible(
                "ALL", "dept-hr", "MEMBER", SecurityClassification.INTERNAL, hrAdminContext);
        assertThat(canAccessAll).isTrue();

        boolean canAccessGlobal = PermissionUtils.isResourceAccessible(
                "GLOBAL", "dept-hr", "MEMBER", SecurityClassification.INTERNAL, hrAdminContext);
        assertThat(canAccessGlobal).isTrue();

        // Different workspace (ws-it) -> must be blocked even though user is ADMIN!
        boolean canAccessIt = PermissionUtils.isResourceAccessible(
                "ws-it", "dept-it", "MEMBER", SecurityClassification.INTERNAL, hrAdminContext);
        assertThat(canAccessIt).isFalse();
    }

    @Test
    @DisplayName("Admin in cross-workspace mode (workspaceId=ALL) can access any workspace document")
    void adminInCrossWorkspaceMode_CanAccessAllWorkspaces() {
        RAGQueryPayload.UserPermissionContext globalAdminContext = new RAGQueryPayload.UserPermissionContext();
        globalAdminContext.setWorkspaceId("ALL");
        globalAdminContext.setRoles(List.of("SUPER_ADMIN"));
        globalAdminContext.setRoleLevel(0);

        boolean canAccessIt = PermissionUtils.isResourceAccessible(
                "ws-it", "dept-it", "MEMBER", SecurityClassification.INTERNAL, globalAdminContext);
        assertThat(canAccessIt).isTrue();

        boolean canAccessHr = PermissionUtils.isResourceAccessible(
                "ws-hr", "dept-hr", "MEMBER", SecurityClassification.INTERNAL, globalAdminContext);
        assertThat(canAccessHr).isTrue();
    }
}
