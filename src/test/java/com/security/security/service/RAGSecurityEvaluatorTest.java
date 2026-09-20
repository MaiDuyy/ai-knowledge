package com.security.security.service;

import com.security.security.client.WorkspaceServiceClient;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.dtorequest.RAGQueryPayload.UserPermissionContext;
import com.security.security.dtorequest.RAGQueryPayload.DepartmentRole;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.repository.WikiPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SecWiki-Bench: RAG Security & Access Control Evaluator")
class RAGSecurityEvaluatorTest {

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private io.nats.client.Connection natsConnection;

    @MockBean
    private WorkspaceServiceClient workspaceServiceClient;

    @Autowired
    private RAGService ragService;

    @Autowired
    private WikiPageRepository wikiPageRepository;

    @Autowired
    private BenchmarkDataSeeder seeder;

    private List<WikiPage> seededPages;

    @BeforeEach
    void setUp() {
        // Seed the 10 wiki pages and 4 wikilinks
        seededPages = seeder.seed();
        // Setup mock mappings for users
        BenchmarkMockHelper.setupMockWorkspaceClient(workspaceServiceClient);
    }

    @Test
    @DisplayName("SLR & AR Evaluation: IT Member User Context")
    void evaluateSecurity_ITMemberContext() {
        // Arrange: IT Member user context (userId = "user-member-it")
        UserPermissionContext context = UserPermissionContext.builder()
                .workspaceId("ws-it")
                .roles(Arrays.asList("EMPLOYEE"))
                .userDepartments(Arrays.asList(new DepartmentRole("dept-it", "MEMBER")))
                .build();

        int leakedCount = 0;
        int authorizedVisible = 0;
        int authorizedTotal = 0;

        // Act: Evaluate accessibility of all 10 seeded pages
        for (WikiPage page : seededPages) {
            boolean accessible = ragService.isPageAccessible(page, context, "user-member-it");
            boolean shouldBeAccessible = isItMemberAuthorized(page);

            if (shouldBeAccessible) {
                authorizedTotal++;
                if (accessible) {
                    authorizedVisible++;
                }
            } else {
                if (accessible) {
                    leakedCount++; // LEAKED!
                }
            }
        }

        // Calculate SLR & AR
        double slr = (double) leakedCount / (seededPages.size() - authorizedTotal);
        double ar = (double) authorizedVisible / authorizedTotal;

        // Assert
        assertThat(slr).isEqualTo(0.0); // SLR must be exactly 0% (no leaks)
        assertThat(ar).isEqualTo(1.0);  // AR must be 100% (all authorized files accessible)
    }

    @Test
    @DisplayName("SLR & AR Evaluation: IT Head User Context")
    void evaluateSecurity_ITHeadContext() {
        // Arrange: IT Head user context (userId = "user-head-it")
        UserPermissionContext context = UserPermissionContext.builder()
                .workspaceId("ws-it")
                .roles(Arrays.asList("EMPLOYEE"))
                .userDepartments(Arrays.asList(new DepartmentRole("dept-it", "HEAD")))
                .build();

        int leakedCount = 0;
        int authorizedVisible = 0;
        int authorizedTotal = 0;

        for (WikiPage page : seededPages) {
            boolean accessible = ragService.isPageAccessible(page, context, "user-head-it");
            boolean shouldBeAccessible = isItHeadAuthorized(page);

            if (shouldBeAccessible) {
                authorizedTotal++;
                if (accessible) {
                    authorizedVisible++;
                }
            } else {
                if (accessible) {
                    leakedCount++;
                }
            }
        }

        double slr = (double) leakedCount / (seededPages.size() - authorizedTotal);
        double ar = (double) authorizedVisible / authorizedTotal;

        assertThat(slr).isEqualTo(0.0);
        assertThat(ar).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SLR & AR Evaluation: External Guest User Context")
    void evaluateSecurity_GuestContext() {
        // Arrange: Guest user context (userId = "user-guest")
        UserPermissionContext context = UserPermissionContext.builder()
                .workspaceId("ws-default")
                .roles(Arrays.asList("EXTERNAL_GUEST"))
                .build();

        int leakedCount = 0;
        int authorizedVisible = 0;
        int authorizedTotal = 0;

        for (WikiPage page : seededPages) {
            boolean accessible = ragService.isPageAccessible(page, context, "user-guest");
            boolean shouldBeAccessible = isGuestAuthorized(page);

            if (shouldBeAccessible) {
                authorizedTotal++;
                if (accessible) {
                    authorizedVisible++;
                }
            } else {
                if (accessible) {
                    leakedCount++;
                }
            }
        }

        double slr = (double) leakedCount / (seededPages.size() - authorizedTotal);
        double ar = (double) authorizedVisible / authorizedTotal;

        assertThat(slr).isEqualTo(0.0);
        assertThat(ar).isEqualTo(1.0);
    }

    @Test
    @DisplayName("DB Layer Keyword Search Security Filtering Test")
    void evaluateDatabaseKeywordSearch_FiltersUnauthorizedPages() {
        // Arrange: IT Member user context (userId = "user-member-it")
        List<String> deptIdsWhereHead = Collections.singletonList("DUMMY_DEPT");
        List<String> deptIdsWhereMember = Arrays.asList("dept-it"); // IT member

        // Act 1: Execute search for "onboard" (MEMBER role allowed)
        List<WikiPage> resultsAccessible = wikiPageRepository.searchAccessiblePagesByKeyword(
                "ws-it", "dept-it", false, false, deptIdsWhereHead, deptIdsWhereMember, "onboard"
        );

        // Act 2: Execute search for "Cấu hình" (HEAD role only)
        List<WikiPage> resultsRestricted = wikiPageRepository.searchAccessiblePagesByKeyword(
                "ws-it", "dept-it", false, false, deptIdsWhereHead, deptIdsWhereMember, "Cấu hình"
        );

        // Assert
        assertThat(resultsAccessible).isNotEmpty();
        assertThat(resultsAccessible.get(0).getSlug()).isEqualTo("page-internal-1");

        assertThat(resultsRestricted).isEmpty(); // Restricted to HEAD, should be empty for MEMBER
    }

    @Test
    @DisplayName("Vector SQL AST Filter Expression validation")
    void evaluateVectorFilterExpression_ITMember_ContainsExpectedConstraints() {
        // Arrange: IT Member user context (userId = "user-member-it")
        UserPermissionContext context = UserPermissionContext.builder()
                .workspaceId("ws-it")
                .roles(Arrays.asList("EMPLOYEE"))
                .userDepartments(Arrays.asList(new DepartmentRole("dept-it", "MEMBER")))
                .build();

        // Act: Generate SQL AST Filter expression string
        String filterExpressionStr = ragService.getFilterExpressionStr(context, "user-member-it");

        // Assert: Filter must not allow HEAD roles, and must restrict to ws-it and company-wide ALL
        assertThat(filterExpressionStr).contains("allowedRoles != 'HEAD'");
        assertThat(filterExpressionStr).contains("workspaceId == 'ws-it'");
        assertThat(filterExpressionStr).contains("workspaceId == 'ALL'");
    }

    // Helper methods to define expected authorization rules for tests
    private boolean isItMemberAuthorized(WikiPage page) {
        // IT member in ws-it can access only:
        // - IT workspace public/internal pages (page-internal-1)
        // - Global public pages (page-global-public)
        String slug = page.getSlug();
        return "page-internal-1".equals(slug) || 
               "page-global-public".equals(slug);
    }

    private boolean isItHeadAuthorized(WikiPage page) {
        // IT Head in ws-it can access:
        // - IT workspace public/internal pages (page-internal-1)
        // - IT workspace confidential pages (page-confidential-1)
        // - IT workspace restricted pages (page-restricted-1)
        // - Global public pages (page-global-public)
        String slug = page.getSlug();
        return "page-internal-1".equals(slug) || 
               "page-confidential-1".equals(slug) || 
               "page-restricted-1".equals(slug) || 
               "page-global-public".equals(slug);
    }

    private boolean isGuestAuthorized(WikiPage page) {
        // Guest in ws-default can only access PUBLIC pages in ws-default or global
        String slug = page.getSlug();
        return "page-public-1".equals(slug) || 
               "page-guest-public".equals(slug) || 
               "page-global-public".equals(slug);
    }
}
