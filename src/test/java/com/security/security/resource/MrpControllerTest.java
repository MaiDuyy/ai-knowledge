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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

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

    private Method parseMethod;
    private Method checkAccessMethod;

    @BeforeEach
    void setUp() throws Exception {
        parseMethod = MrpController.class.getDeclaredMethod("parseUserPermissions", String.class, String.class);
        parseMethod.setAccessible(true);

        checkAccessMethod = MrpController.class.getDeclaredMethod("checkPageAccess", WikiPage.class, 
                Class.forName("com.security.security.resource.MrpController$ParsedUserPermissions"));
        checkAccessMethod.setAccessible(true);
    }

    @Test
    @DisplayName("Should parse headers correctly for Admin")
    void parseUserPermissions_Admin_SetsIsAdminTrue() throws Exception {
        Object permissions = parseMethod.invoke(mrpController, "ADMIN", null);
        
        boolean isAdmin = (boolean) permissions.getClass().getDeclaredField("isAdmin").get(permissions);
        assertThat(isAdmin).isTrue();
    }

    @Test
    @DisplayName("Should parse headers correctly for Departments")
    void parseUserPermissions_Departments_PopulatesDeptLists() throws Exception {
        String departmentsJson = "[{\"departmentId\":\"dept-123\",\"role\":\"HEAD\"},{\"departmentId\":\"dept-456\",\"role\":\"MEMBER\"}]";
        Object permissions = parseMethod.invoke(mrpController, "MEMBER", departmentsJson);

        java.util.List<String> headList = (java.util.List<String>) permissions.getClass().getDeclaredField("deptIdsWhereHead").get(permissions);
        java.util.List<String> memberList = (java.util.List<String>) permissions.getClass().getDeclaredField("deptIdsWhereMember").get(permissions);

        assertThat(headList).contains("dept-123");
        assertThat(memberList).contains("dept-456");
    }

    @Test
    @DisplayName("Should grant access to PUBLIC page for anyone")
    void checkPageAccess_PublicPage_GrantsAccess() throws Exception {
        WikiPage page = WikiPage.builder()
                .securityClassification("PUBLIC")
                .departmentId("some-dept")
                .allowedRoles("HEAD")
                .build();

        Object perm = parseMethod.invoke(mrpController, "MEMBER", null);

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

        Object perm = parseMethod.invoke(mrpController, "MEMBER", null);

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
        Object perm = parseMethod.invoke(mrpController, "MEMBER", departmentsJson);

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
        Object perm = parseMethod.invoke(mrpController, "MEMBER", departmentsJson);

        assertThatThrownBy(() -> {
            try {
                checkAccessMethod.invoke(mrpController, page, perm);
            } catch (Exception e) {
                throw e.getCause();
            }
        }).isInstanceOf(AccessDeniedException.class);
    }
}
