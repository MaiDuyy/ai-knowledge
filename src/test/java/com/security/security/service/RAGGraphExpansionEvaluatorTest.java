package com.security.security.service;

import com.security.security.client.WorkspaceServiceClient;
import com.security.security.dtorequest.RAGQueryPayload.UserPermissionContext;
import com.security.security.dtorequest.RAGQueryPayload.DepartmentRole;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.repository.WikiPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SecWiki-Bench: Wiki Graph Context Expansion Evaluator")
class RAGGraphExpansionEvaluatorTest {

    private static final Logger log = LoggerFactory.getLogger(RAGGraphExpansionEvaluatorTest.class);

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

    private WikiPage pagePublic1;
    private WikiPage pageInternal1;
    private WikiPage pageConfidential1;
    private WikiPage pageRestricted1;
    private WikiPage pageHrPublic;
    private WikiPage pageHrInternal;
    private WikiPage pageHrConfidential;

    @BeforeEach
    void setUp() {
        // Seed the 10 wiki pages and 5 wikilinks
        List<WikiPage> seededPages = seeder.seed();
        BenchmarkMockHelper.setupMockWorkspaceClient(workspaceServiceClient);

        // Find relevant pages by slug for mock behavior and assertions
        pagePublic1 = getPage(seededPages, "page-public-1");
        pageInternal1 = getPage(seededPages, "page-internal-1");
        pageConfidential1 = getPage(seededPages, "page-confidential-1");
        pageRestricted1 = getPage(seededPages, "page-restricted-1");
        pageHrPublic = getPage(seededPages, "page-hr-public");
        pageHrInternal = getPage(seededPages, "page-hr-internal");
        pageHrConfidential = getPage(seededPages, "page-hr-confidential");
    }

    private WikiPage getPage(List<WikiPage> pages, String slug) {
        return pages.stream()
                .filter(p -> p.getSlug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Missing page slug: " + slug));
    }

    @Test
    @DisplayName("Evaluate GHR Hop 2: IT Onboarding & CI/CD Query")
    void evaluateGraphHopRecall_Hop2() {
        // Query: "onboard"
        // Base docs returned by Vector Search: page-internal-1
        // Target Hop 2 to retrieve: page-confidential-1 (linked from page-internal-1)
        
        // IT Head context (has permissions for all pages)
        UserPermissionContext context = UserPermissionContext.builder()
                .workspaceId("ws-it")
                .roles(Arrays.asList("EMPLOYEE"))
                .userDepartments(Arrays.asList(new DepartmentRole("dept-it", "HEAD")))
                .build();

        // 1. Mock Vector Store to return only the Flat Vector result: page-internal-1
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(Collections.singletonList(toDocument(pageInternal1)));

        // 2. Measure Flat Search
        List<org.springframework.ai.document.Document> flatResults = Collections.singletonList(toDocument(pageInternal1));
        boolean flatContainsHop2 = containsPage(flatResults, "page-confidential-1");
        double flatGhrHop2 = flatContainsHop2 ? 100.0 : 0.0;

        // 3. Measure Graph Expanded Search via RAGService
        List<org.springframework.ai.document.Document> graphResults = ragService.executeHybridSearchAndExpansion(
                "onboard", context, "user-head-it", 5, 0.1
        );
        boolean graphContainsHop2 = containsPage(graphResults, "page-confidential-1");
        double graphGhrHop2 = graphContainsHop2 ? 100.0 : 0.0;

        // Log results
        log.info("=== EVALUATION RESULTS: GHR HOP 2 (IT Onboarding Query) ===");
        log.info("Flat Vector Search Hop 2 GHR: {}%", flatGhrHop2);
        log.info("Graph Expanded Search Hop 2 GHR: {}%", graphGhrHop2);

        // Assert
        assertThat(flatGhrHop2).isLessThan(20.0);
        assertThat(graphGhrHop2).isGreaterThanOrEqualTo(85.0);
    }

    @Test
    @DisplayName("Evaluate GHR Hop 3: Multi-hop Query with IT Head Context")
    void evaluateGraphHopRecall_Hop3() {
        // Query: "onboard và CI/CD IT"
        // Base documents: page-internal-1 (Hop 1), page-confidential-1 (Hop 2)
        // Target Hop 3 to retrieve: page-restricted-1 (linked from page-confidential-1)

        UserPermissionContext context = UserPermissionContext.builder()
                .workspaceId("ws-it")
                .roles(Arrays.asList("EMPLOYEE"))
                .userDepartments(Arrays.asList(new DepartmentRole("dept-it", "HEAD")))
                .build();

        // 1. Mock Vector Store to return: page-internal-1 and page-confidential-1
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(Arrays.asList(toDocument(pageInternal1), toDocument(pageConfidential1)));

        // 2. Measure Flat Search
        List<org.springframework.ai.document.Document> flatResults = Arrays.asList(toDocument(pageInternal1), toDocument(pageConfidential1));
        boolean flatContainsHop3 = containsPage(flatResults, "page-restricted-1");
        double flatGhrHop3 = flatContainsHop3 ? 100.0 : 0.0;

        // 3. Measure Graph Expanded Search
        List<org.springframework.ai.document.Document> graphResults = ragService.executeHybridSearchAndExpansion(
                "onboard và CI/CD IT", context, "user-head-it", 5, 0.1
        );
        boolean graphContainsHop3 = containsPage(graphResults, "page-restricted-1");
        double graphGhrHop3 = graphContainsHop3 ? 100.0 : 0.0;

        // Log results
        log.info("=== EVALUATION RESULTS: GHR HOP 3 (IT Multihop Query) ===");
        log.info("Flat Vector Search Hop 3 GHR: {}%", flatGhrHop3);
        log.info("Graph Expanded Search Hop 3 GHR: {}%", graphGhrHop3);

        // Assert
        assertThat(flatGhrHop3).isLessThan(20.0);
        assertThat(graphGhrHop3).isGreaterThanOrEqualTo(85.0);
    }

    @Test
    @DisplayName("Evaluate GHR Hop 2: HR Department Hiring Query")
    void evaluateGraphHopRecall_HR_Hop2() {
        // Query: "tuyển dụng"
        // Base documents: page-hr-public
        // Target Hop 2 to retrieve: page-hr-internal (linked from page-hr-public)

        UserPermissionContext context = UserPermissionContext.builder()
                .workspaceId("ws-hr")
                .roles(Arrays.asList("EMPLOYEE"))
                .userDepartments(Arrays.asList(new DepartmentRole("dept-hr", "MEMBER")))
                .build();

        // 1. Mock Vector Store to return: page-hr-public
        when(vectorStore.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenReturn(Collections.singletonList(toDocument(pageHrPublic)));

        // 2. Measure Flat Search
        List<org.springframework.ai.document.Document> flatResults = Collections.singletonList(toDocument(pageHrPublic));
        boolean flatContainsHop2 = containsPage(flatResults, "page-hr-internal");
        double flatGhrHop2 = flatContainsHop2 ? 100.0 : 0.0;

        // 3. Measure Graph Expanded Search
        List<org.springframework.ai.document.Document> graphResults = ragService.executeHybridSearchAndExpansion(
                "tuyển dụng", context, "user-member-hr", 5, 0.1
        );
        boolean graphContainsHop2 = containsPage(graphResults, "page-hr-internal");
        double graphGhrHop2 = graphContainsHop2 ? 100.0 : 0.0;

        // Log results
        log.info("=== EVALUATION RESULTS: GHR HOP 2 (HR Hiring Query) ===");
        log.info("Flat Vector Search Hop 2 GHR: {}%", flatGhrHop2);
        log.info("Graph Expanded Search Hop 2 GHR: {}%", graphGhrHop2);

        // Assert
        assertThat(flatGhrHop2).isLessThan(20.0);
        assertThat(graphGhrHop2).isGreaterThanOrEqualTo(85.0);
    }

    private org.springframework.ai.document.Document toDocument(WikiPage page) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("wikiPageId", page.getId().toString());
        metadata.put("workspaceId", page.getWorkspaceId());
        metadata.put("departmentId", page.getDepartmentId());
        metadata.put("allowedRoles", page.getAllowedRoles() != null ? page.getAllowedRoles() : "ALL");
        metadata.put("classification", page.getSecurityClassification() != null ? page.getSecurityClassification().name() : "INTERNAL");
        metadata.put("securityClassification", page.getSecurityClassification() != null ? page.getSecurityClassification().name() : "INTERNAL");
        metadata.put("type", "wiki");
        metadata.put("slug", page.getSlug() != null ? page.getSlug() : "");
        metadata.put("fileName", page.getTitle());

        return new org.springframework.ai.document.Document(
                "Tiêu đề: " + page.getTitle() + "\n\n" + page.getContent(),
                metadata
        );
    }

    private boolean containsPage(List<org.springframework.ai.document.Document> docs, String slug) {
        return docs.stream()
                .anyMatch(doc -> slug.equals(doc.getMetadata().get("slug")));
    }
}
