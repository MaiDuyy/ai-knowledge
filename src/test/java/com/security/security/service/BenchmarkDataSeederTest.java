package com.security.security.service;

import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiLink;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.WikiLinkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("BenchmarkDataSeeder Tests")
class BenchmarkDataSeederTest {

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private io.nats.client.Connection natsConnection;

    @MockBean
    private com.security.security.client.WorkspaceServiceClient workspaceServiceClient;

    @Autowired
    private WikiPageRepository wikiPageRepository;

    @Autowired
    private WikiLinkRepository wikiLinkRepository;

    @Autowired
    private BenchmarkDataSeeder seeder;

    @Test
    @DisplayName("Should seed 10 wiki pages and 5 wikilinks successfully")
    void seed_Seeds10PagesAnd5LinksSuccessfully() {
        // Act
        List<WikiPage> pages = seeder.seed();

        // Assert
        assertThat(pages).hasSize(10);
        
        List<WikiPage> dbPages = wikiPageRepository.findAll();
        assertThat(dbPages).hasSize(10);

        List<WikiLink> dbLinks = wikiLinkRepository.findAll();
        assertThat(dbLinks).hasSize(5);
    }

    @Test
    @DisplayName("Should setup WorkspaceServiceClient mock mapping correctly for IT users")
    void mockHelper_MapsItUsersCorrectly() {
        // Arrange
        BenchmarkMockHelper.setupMockWorkspaceClient(workspaceServiceClient);

        // Act & Assert
        assertThat(workspaceServiceClient.getUserDepartments("user-member-it")).hasSize(1);
        assertThat(workspaceServiceClient.getUserDepartments("user-member-it").get(0).getDepartmentId()).isEqualTo("dept-it");
        assertThat(workspaceServiceClient.getUserDepartments("user-member-it").get(0).getRole()).isEqualTo("MEMBER");

        assertThat(workspaceServiceClient.getUserDepartments("user-head-it")).hasSize(1);
        assertThat(workspaceServiceClient.getUserDepartments("user-head-it").get(0).getDepartmentId()).isEqualTo("dept-it");
        assertThat(workspaceServiceClient.getUserDepartments("user-head-it").get(0).getRole()).isEqualTo("HEAD");
    }
}
