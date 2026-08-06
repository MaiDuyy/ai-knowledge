package com.security.security.service;

import com.security.security.dtorequest.RAGQueryPayload.DepartmentRole;
import com.security.security.dtorequest.RAGQueryPayload.UserPermissionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MongoRbacFilterBuilderTest {

    private MongoRbacFilterBuilder filterBuilder;

    @BeforeEach
    void setUp() {
        filterBuilder = new MongoRbacFilterBuilder();
    }

    @Test
    @DisplayName("Null permission context returns fallback non-matching criteria")
    void testNullContext() {
        Criteria criteria = filterBuilder.buildDocumentCriteria(null);
        assertNotNull(criteria);
        assertEquals("__none__", criteria.getCriteriaObject().get("workspaceId"));
    }

    @Test
    @DisplayName("Admin role generates workspace OR ALL OR GLOBAL criteria")
    void testAdminContext() {
        UserPermissionContext context = new UserPermissionContext();
        context.setWorkspaceId("ws-1");
        context.setRoles(List.of("ADMIN"));
        context.setRoleLevel(1);

        Criteria criteria = filterBuilder.buildDocumentCriteria(context);
        assertNotNull(criteria);
        assertTrue(criteria.getCriteriaObject().containsKey("$or"));
    }

    @Test
    @DisplayName("Guest role generates PUBLIC security classification criteria")
    void testGuestContext() {
        UserPermissionContext context = new UserPermissionContext();
        context.setWorkspaceId("ws-1");
        context.setRoles(List.of("EXTERNAL_GUEST"));
        context.setRoleLevel(6);

        Criteria criteria = filterBuilder.buildDocumentCriteria(context);
        assertNotNull(criteria);
        assertEquals("PUBLIC", criteria.getCriteriaObject().get("securityClassification"));
    }

    @Test
    @DisplayName("Department member generates workspace and department criteria")
    void testDepartmentMemberContext() {
        UserPermissionContext context = new UserPermissionContext();
        context.setWorkspaceId("ws-1");
        context.setRoles(List.of("MEMBER"));
        context.setRoleLevel(3);

        DepartmentRole deptRole = new DepartmentRole();
        deptRole.setDepartmentId("dept-engineering");
        deptRole.setRole("MEMBER");
        context.setUserDepartments(List.of(deptRole));

        Criteria criteria = filterBuilder.buildDocumentCriteria(context);
        assertNotNull(criteria);
        assertTrue(criteria.getCriteriaObject().containsKey("$or"));
    }
}
