package com.security.security.resource;

import com.security.security.entity.WikiPage;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.SourceCompilationPlanRepository;
import com.security.security.repository.WikiPageDraftRepository;
import com.security.security.repository.WikiPageRepository;
import com.security.security.service.MrpPipelineService;
import com.security.security.service.WikiDraftService;
import com.security.security.client.WorkspaceServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import com.security.security.entity.SourceCompilationPlan;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@DisplayName("MrpController Tests")
class MrpControllerTest {

    @Mock
    private MrpPipelineService mrpPipelineService;
    @Mock
    private WikiDraftService wikiDraftService;
    @Mock
    private WikiPageRepository wikiPageRepository;
    @Mock
    private WikiPageDraftRepository wikiPageDraftRepository;
    @Mock
    private SourceCompilationPlanRepository sourceCompilationPlanRepository;
    @Mock
    private com.security.security.repository.WikiLinkRepository wikiLinkRepository;
    @Mock
    private WorkspaceServiceClient workspaceServiceClient;
    @Mock
    private DocumentRepository documentRepository;
    
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MrpController mrpController;

    private Method checkAccessMethod;

    @BeforeEach
    void setUp() throws Exception {
        checkAccessMethod = MrpController.class.getDeclaredMethod("checkPageAccess", WikiPage.class, 
                com.security.security.dto.UserPermissionContext.class);
        checkAccessMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Should parse headers correctly for Admin")
    void parseUserPermissions_Admin_SetsIsAdminTrue() throws Exception {
        com.security.security.dto.UserPermissionContext permissions = 
                com.security.security.service.PermissionUtils.parse("ADMIN", null, objectMapper);
        
        assertThat(permissions.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("Should parse headers correctly for Departments")
    void parseUserPermissions_Departments_PopulatesDeptLists() throws Exception {
        String departmentsJson = "[{\"departmentId\":\"dept-123\",\"role\":\"HEAD\"},{\"departmentId\":\"dept-456\",\"role\":\"MEMBER\"}]";
        com.security.security.dto.UserPermissionContext permissions = 
                com.security.security.service.PermissionUtils.parse("MEMBER", departmentsJson, objectMapper);

        assertThat(permissions.getDeptIdsWhereHead()).contains("dept-123");
        assertThat(permissions.getDeptIdsWhereMember()).contains("dept-456");
    }

    @Test
    @DisplayName("Should grant access to PUBLIC page for anyone")
    void checkPageAccess_PublicPage_GrantsAccess() throws Exception {
        WikiPage page = WikiPage.builder()
                .securityClassification("PUBLIC")
                .departmentId("some-dept")
                .allowedRoles("HEAD")
                .build();

        com.security.security.dto.UserPermissionContext perm = 
                com.security.security.service.PermissionUtils.parse("MEMBER", null, objectMapper);

        // Should not throw exception
        checkAccessMethod.invoke(mrpController, page, perm);
    }

    @Test
    @DisplayName("Should deny access to department page if user has no department membership")
    void checkPageAccess_DeptHeadPage_DeniesAccess() throws Exception {
        WikiPage page = WikiPage.builder()
                .securityClassification("INTERNAL")
                .departmentId("dept-vip")
                .allowedRoles("HEAD")
                .build();

        com.security.security.dto.UserPermissionContext perm = 
                com.security.security.service.PermissionUtils.parse("MEMBER", null, objectMapper);

        assertThatThrownBy(() -> {
            try {
                checkAccessMethod.invoke(mrpController, page, perm);
            } catch (Exception e) {
                throw e.getCause();
            }
        }).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Should grant access to department page if user is HEAD of that department")
    void checkPageAccess_DeptHeadPage_GrantsAccessToHead() throws Exception {
        WikiPage page = WikiPage.builder()
                .securityClassification("INTERNAL")
                .departmentId("dept-vip")
                .allowedRoles("HEAD")
                .build();

        String departmentsJson = "[{\"departmentId\":\"dept-vip\",\"role\":\"HEAD\"}]";
        com.security.security.dto.UserPermissionContext perm = 
                com.security.security.service.PermissionUtils.parse("MEMBER", departmentsJson, objectMapper);

        // Should not throw exception
        checkAccessMethod.invoke(mrpController, page, perm);
    }

    @Test
    @DisplayName("Should deny access to department page if user is MEMBER but allowedRoles is HEAD")
    void checkPageAccess_DeptHeadPage_DeniesMember() throws Exception {
        WikiPage page = WikiPage.builder()
                .securityClassification("INTERNAL")
                .departmentId("dept-vip")
                .allowedRoles("HEAD")
                .build();

        String departmentsJson = "[{\"departmentId\":\"dept-vip\",\"role\":\"MEMBER\"}]";
        com.security.security.dto.UserPermissionContext perm = 
                com.security.security.service.PermissionUtils.parse("MEMBER", departmentsJson, objectMapper);

        assertThatThrownBy(() -> {
            try {
                checkAccessMethod.invoke(mrpController, page, perm);
            } catch (Exception e) {
                throw e.getCause();
            }
        }).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Should return 202 Accepted and the initial plan when compilation is triggered")
    void compileDocument_ReturnsAccepted() {
        Long documentId = 1L;
        String workspaceId = "test-workspace";
        String userId = "test-user";
        boolean autoApprove = false;

        SourceCompilationPlan mockPlan = SourceCompilationPlan.builder()
                .id(100L)
                .sourceDocumentId(documentId)
                .status("PROCESSING")
                .build();

        Mockito.when(workspaceServiceClient.getWorkspace(workspaceId, userId))
                .thenReturn(java.util.Map.of("id", (Object) workspaceId));

        Mockito.when(mrpPipelineService.initiateCompile(documentId, workspaceId, userId, autoApprove))
                .thenReturn(mockPlan);

        ResponseEntity<SourceCompilationPlan> response = mrpController.compileDocument(
                documentId, workspaceId, autoApprove, userId, "WORKSPACE_MEMBER", null);

        assertThat(response.getStatusCodeValue()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("Should return Wiki graph nodes and edges for accessible pages")
    void getWikiGraph_ReturnsGraphData() {
        String workspaceId = "test-workspace";
        String userId = "test-user";
        String userRoles = "MEMBER";

        // Mock workspace access
        Mockito.when(workspaceServiceClient.getWorkspace(workspaceId, userId))
                .thenReturn(java.util.Map.of("id", (Object) workspaceId));

        // Mock two accessible pages
        WikiPageRepository.WikiPageMetadata page1 = Mockito.mock(WikiPageRepository.WikiPageMetadata.class);
        Mockito.when(page1.getId()).thenReturn(1L);
        Mockito.when(page1.getSlug()).thenReturn("slug-1");
        Mockito.when(page1.getTitle()).thenReturn("Title 1");
        Mockito.when(page1.getPageType()).thenReturn("concept");

        WikiPageRepository.WikiPageMetadata page2 = Mockito.mock(WikiPageRepository.WikiPageMetadata.class);
        Mockito.when(page2.getId()).thenReturn(2L);
        Mockito.when(page2.getSlug()).thenReturn("slug-2");
        Mockito.when(page2.getTitle()).thenReturn("Title 2");
        Mockito.when(page2.getPageType()).thenReturn("entity");

        Mockito.when(wikiPageRepository.findAccessibleMetadata(
                Mockito.eq(workspaceId), Mockito.any(), Mockito.eq(false), Mockito.anyList(), Mockito.anyList()))
                .thenReturn(java.util.List.of(page1, page2));

        // Mock links: link from 1 to 2 (valid), link from 1 to 3 (invalid/not accessible)
        com.security.security.entity.WikiLink link1 = com.security.security.entity.WikiLink.builder()
                .id(10L)
                .fromPageId(1L)
                .toSlug("slug-2")
                .build();
        com.security.security.entity.WikiLink link2 = com.security.security.entity.WikiLink.builder()
                .id(11L)
                .fromPageId(1L)
                .toSlug("slug-3") // slug-3 is not accessible/doesn't exist
                .build();

        Mockito.when(wikiLinkRepository.findByFromPageIdIn(java.util.List.of(1L, 2L)))
                .thenReturn(java.util.List.of(link1, link2));

        // Execute
        ResponseEntity<?> response = mrpController.getWikiGraph(workspaceId, userId, userRoles, null);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        com.security.security.dto.WikiGraphDto body = (com.security.security.dto.WikiGraphDto) response.getBody();
        assertThat(body).isNotNull();
        
        // Verify nodes (both accessible pages are included)
        assertThat(body.getNodes()).hasSize(2);
        assertThat(body.getNodes().get(0).getSlug()).isEqualTo("slug-1");
        assertThat(body.getNodes().get(1).getSlug()).isEqualTo("slug-2");

        // Verify edges (only link to slug-2 is included, link to slug-3 is filtered out)
        assertThat(body.getEdges()).hasSize(1);
        assertThat(body.getEdges().get(0).getFrom()).isEqualTo("slug-1");
        assertThat(body.getEdges().get(0).getTo()).isEqualTo("slug-2");
    }
}


