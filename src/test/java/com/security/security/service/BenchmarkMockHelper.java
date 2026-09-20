package com.security.security.service;

import com.security.security.client.WorkspaceServiceClient;
import com.security.security.dtorequest.RAGQueryPayload;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;

public class BenchmarkMockHelper {

    public static void setupMockWorkspaceClient(WorkspaceServiceClient client) {
        // Mock getWorkspace for ws-default, ws-it, ws-hr
        when(client.getWorkspace("ws-default", "user-admin")).thenReturn(Map.of("id", "ws-default", "departmentId", "ALL"));
        when(client.getWorkspace("ws-default", "user-guest")).thenReturn(Map.of("id", "ws-default", "departmentId", "ALL"));
        when(client.getWorkspace("ws-default", "user-member-it")).thenReturn(Map.of("id", "ws-default", "departmentId", "ALL"));
        when(client.getWorkspace("ws-default", "user-head-it")).thenReturn(Map.of("id", "ws-default", "departmentId", "ALL"));
        when(client.getWorkspace("ws-default", "user-member-hr")).thenReturn(Map.of("id", "ws-default", "departmentId", "ALL"));
        when(client.getWorkspace("ws-default", "user-head-hr")).thenReturn(Map.of("id", "ws-default", "departmentId", "ALL"));

        when(client.getWorkspace("ws-it", "user-admin")).thenReturn(Map.of("id", "ws-it", "departmentId", "dept-it"));
        when(client.getWorkspace("ws-it", "user-member-it")).thenReturn(Map.of("id", "ws-it", "departmentId", "dept-it"));
        when(client.getWorkspace("ws-it", "user-head-it")).thenReturn(Map.of("id", "ws-it", "departmentId", "dept-it"));

        when(client.getWorkspace("ws-hr", "user-admin")).thenReturn(Map.of("id", "ws-hr", "departmentId", "dept-hr"));
        when(client.getWorkspace("ws-hr", "user-member-hr")).thenReturn(Map.of("id", "ws-hr", "departmentId", "dept-hr"));
        when(client.getWorkspace("ws-hr", "user-head-hr")).thenReturn(Map.of("id", "ws-hr", "departmentId", "dept-hr"));

        // Mock getUserDepartments for each test user
        when(client.getUserDepartments("user-admin")).thenReturn(List.of());
        when(client.getUserDepartments("user-guest")).thenReturn(List.of());
        
        when(client.getUserDepartments("user-member-it")).thenReturn(Arrays.asList(
                new RAGQueryPayload.DepartmentRole("dept-it", "MEMBER")
        ));
        when(client.getUserDepartments("user-head-it")).thenReturn(Arrays.asList(
                new RAGQueryPayload.DepartmentRole("dept-it", "HEAD")
        ));

        when(client.getUserDepartments("user-member-hr")).thenReturn(Arrays.asList(
                new RAGQueryPayload.DepartmentRole("dept-hr", "MEMBER")
        ));
        when(client.getUserDepartments("user-head-hr")).thenReturn(Arrays.asList(
                new RAGQueryPayload.DepartmentRole("dept-hr", "HEAD")
        ));
    }
}
