package com.security.security.service;

import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.enumeration.SecurityClassification;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds MongoDB {@link Criteria} filters that mirror the intent of
 * {@link RAGService#buildFilterExpressionAST} for workspace / department / role isolation.
 * <p>
 * Results must still be post-checked with {@link PermissionUtils#isResourceAccessible}.
 */
@Component
public class MongoRbacFilterBuilder {

    public Criteria buildDocumentCriteria(RAGQueryPayload.UserPermissionContext context) {
        return buildCriteria(context, false);
    }

    public Criteria buildChunkCriteria(RAGQueryPayload.UserPermissionContext context) {
        // Flat chunks denormalize the same RBAC fields at the root.
        return buildCriteria(context, false);
    }

    private Criteria buildCriteria(RAGQueryPayload.UserPermissionContext context, boolean nestedPrefix) {
        if (context == null) {
            return Criteria.where("workspaceId").is("__none__");
        }

        String workspaceId = ScopeNormalizer.normalizeWorkspace(context.getWorkspaceId());
        List<String> roles = context.getRoles();
        Integer roleLevel = context.getRoleLevel();

        boolean isAdmin = isAdmin(roles, roleLevel);
        if (isAdmin) {
            if ("ALL".equals(workspaceId) || "GLOBAL".equals(workspaceId)) {
                return new Criteria().orOperator(
                        Criteria.where("workspaceId").is("ALL"),
                        Criteria.where("workspaceId").is("GLOBAL"),
                        Criteria.where("workspaceId").is(workspaceId)
                );
            }
            return new Criteria().orOperator(
                    Criteria.where("workspaceId").is(workspaceId),
                    Criteria.where("workspaceId").is("ALL"),
                    Criteria.where("workspaceId").is("GLOBAL")
            );
        }

        boolean isGuest = isGuest(roles, roleLevel);
        if (isGuest) {
            return Criteria.where("securityClassification").is(SecurityClassification.PUBLIC.name());
        }

        List<String> headDepts = new ArrayList<>();
        List<String> memberDepts = new ArrayList<>();
        collectDepartments(context, headDepts, memberDepts);

        List<Criteria> orBranches = new ArrayList<>();

        // Workspace-scoped docs in current workspace
        Criteria workspaceBranch = Criteria.where("workspaceId").is(workspaceId);
        if (headDepts.isEmpty()) {
            workspaceBranch = workspaceBranch.and("allowedRoles").ne("HEAD");
        }
        orBranches.add(workspaceBranch);

        // Shared GLOBAL/ALL workspace docs limited by department membership
        for (String deptId : memberDepts) {
            if ("DUMMY_DEPT_ID".equals(deptId)) {
                continue;
            }
            Criteria shared = new Criteria().andOperator(
                    new Criteria().orOperator(
                            Criteria.where("workspaceId").is("ALL"),
                            Criteria.where("workspaceId").is("GLOBAL")
                    ),
                    new Criteria().orOperator(
                            Criteria.where("departmentId").is(deptId),
                            Criteria.where("departmentId").is("ALL"),
                            Criteria.where("departmentId").is("GLOBAL")
                    )
            );
            if (!headDepts.contains(deptId)) {
                shared = new Criteria().andOperator(shared, Criteria.where("allowedRoles").ne("HEAD"));
            }
            orBranches.add(shared);
        }

        // PUBLIC always visible to internal users
        orBranches.add(Criteria.where("securityClassification").is(SecurityClassification.PUBLIC.name()));

        if (orBranches.isEmpty()) {
            return Criteria.where("workspaceId").is(workspaceId);
        }
        return new Criteria().orOperator(orBranches.toArray(Criteria[]::new));
    }

    private static boolean isAdmin(List<String> roles, Integer roleLevel) {
        if (roleLevel != null && roleLevel <= 1) {
            return true;
        }
        if (roles == null) {
            return false;
        }
        return roles.contains("SUPER_ADMIN") || roles.contains("ADMIN") || roles.contains("ORG_ADMIN");
    }

    private static boolean isGuest(List<String> roles, Integer roleLevel) {
        if (roleLevel != null && roleLevel >= 6) {
            return true;
        }
        return roles != null && roles.contains("EXTERNAL_GUEST");
    }

    private static void collectDepartments(
            RAGQueryPayload.UserPermissionContext context,
            List<String> headDepts,
            List<String> memberDepts) {
        List<RAGQueryPayload.DepartmentRole> userDepts = context.getUserDepartments();
        if (userDepts == null) {
            return;
        }
        for (RAGQueryPayload.DepartmentRole dept : userDepts) {
            String deptId = dept.getDepartmentId();
            if (deptId == null || deptId.isBlank()) {
                continue;
            }
            if (PermissionUtils.isHeadOrDeputy(dept.getRole())) {
                headDepts.add(deptId);
                memberDepts.add(deptId);
            } else {
                memberDepts.add(deptId);
            }
        }
    }
}
