package com.security.security.service;

import com.security.security.entity.WikiPage;
import com.security.security.entity.WikiPageDraft;
import com.security.security.event.NatsEventPublisher;
import com.security.security.repository.WikiPageDraftRepository;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.WikiLinkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WikiDraftService Tests")
class WikiDraftServiceTest {

    @Mock
    private WikiPageDraftRepository wikiPageDraftRepository;
    @Mock
    private WikiPageRepository wikiPageRepository;
    @Mock
    private VectorStore vectorStore;
    @Mock
    private WikiLinkRepository wikiLinkRepository;
    @Mock
    private NatsEventPublisher natsEventPublisher;
    @Mock
    private ChatClient chatClient;

    @InjectMocks
    private WikiDraftService wikiDraftService;

    @Test
    @DisplayName("Should throw exception when proposing a draft and a PENDING draft already exists for the same slug")
    void proposeDraft_WhenPendingDraftExists_ThrowsException() {
        // Arrange
        WikiPageDraft newDraft = WikiPageDraft.builder()
                .slug("conflict-slug")
                .workspaceId("workspace-123")
                .title("New Title")
                .build();

        WikiPageDraft existingPendingDraft = WikiPageDraft.builder()
                .id(99L)
                .slug("conflict-slug")
                .workspaceId("workspace-123")
                .status(com.security.security.entity.enumeration.WikiPageDraftStatus.PENDING)
                .build();

        when(wikiPageDraftRepository.findBySlugAndWorkspaceId("conflict-slug", "workspace-123"))
                .thenReturn(Arrays.asList(existingPendingDraft));

        // Act & Assert
        assertThatThrownBy(() -> wikiDraftService.proposeDraft(newDraft))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("A pending draft already exists");

        verify(wikiPageDraftRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should propose draft successfully when no PENDING drafts exist")
    void proposeDraft_WhenNoPendingDraftExists_SavesSuccessfully() {
        // Arrange
        WikiPageDraft newDraft = WikiPageDraft.builder()
                .slug("clean-slug")
                .workspaceId("workspace-123")
                .title("New Title")
                .authorId("user-123")
                .build();

        when(wikiPageDraftRepository.findBySlugAndWorkspaceId("clean-slug", "workspace-123"))
                .thenReturn(Collections.emptyList());

        when(wikiPageDraftRepository.save(newDraft)).thenReturn(newDraft);

        // Act
        WikiPageDraft result = wikiDraftService.proposeDraft(newDraft);

        // Assert
        assertThat(result).isNotNull();
        verify(wikiPageDraftRepository).save(newDraft);
        verify(natsEventPublisher).publishWikiDraftUpdated(
                any(), any(), eq("clean-slug"), eq("workspace-123"), eq("PENDING"), eq("user-123")
        );
    }

    @Test
    @DisplayName("Should call LLM with allowed slugs to auto-link draft content")
    void autoLinkDraftContent_CallsLLMWithSlugs() {
        // Arrange
        String content = "Hello world context about standard serial number.";
        String workspaceId = "workspace-123";

        WikiPage page1 = new WikiPage();
        page1.setSlug("standard");
        
        WikiPage page2 = new WikiPage();
        page2.setSlug("serial-number");

        com.security.security.dto.UserPermissionContext perm = new com.security.security.dto.UserPermissionContext();
        perm.setAdmin(true);

        when(wikiPageRepository.findAccessiblePages(eq(workspaceId), eq(null), eq(true), anyBoolean(), anyList(), anyList()))
                .thenReturn(Arrays.asList(page1, page2));

        // Mock fluent ChatClient chain
        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec responseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("Hello world context about [[standard]] [[serial-number]].");

        // Act
        String result = wikiDraftService.autoLinkDraftContent(content, workspaceId, perm);

        // Assert
        assertThat(result).isEqualTo("Hello world context about [[standard]] [[serial-number]].");
        verify(wikiPageRepository).findAccessiblePages(eq(workspaceId), eq(null), eq(true), anyBoolean(), anyList(), anyList());
    }

    @Test
    @DisplayName("Should approve draft and copy sourceDocumentId to new WikiPage")
    void approveDraft_CopiesSourceDocumentIdToNewWikiPage() {
        // Arrange
        Long draftId = 1L;
        String reviewerId = "reviewer-123";
        WikiPageDraft draft = WikiPageDraft.builder()
                .id(draftId)
                .slug("test-slug")
                .title("Test Title")
                .content("Test Content")
                .sourceDocumentId(42L)
                .workspaceId("workspace-123")
                .status(com.security.security.entity.enumeration.WikiPageDraftStatus.PENDING)
                .build();

        when(wikiPageDraftRepository.findById(draftId)).thenReturn(java.util.Optional.of(draft));
        when(wikiPageRepository.fetchBySlugAndWorkspaceId("test-slug", "workspace-123"))
                .thenReturn(java.util.Optional.empty());

        when(wikiPageRepository.save(any(WikiPage.class))).thenAnswer(invocation -> {
            WikiPage arg = invocation.getArgument(0);
            arg.setId(10L);
            return arg;
        });

        when(wikiPageDraftRepository.save(any(WikiPageDraft.class))).thenReturn(draft);

        // Act
        WikiPageDraft result = wikiDraftService.approveDraft(draftId, reviewerId);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(com.security.security.entity.enumeration.WikiPageDraftStatus.APPROVED);
        verify(wikiPageRepository).save(argThat(page -> 
            page.getSourceDocumentId() != null && page.getSourceDocumentId().equals(42L)
        ));
     }

    @Test
    @DisplayName("Should propose draft with empty/default workspace and department mapped to ALL sentinel value")
    void proposeDraft_WithGlobalWorkspaceAndDepartment_SavesWithGlobalSentinel() {
        // Arrange
        WikiPageDraft newDraft = WikiPageDraft.builder()
                .slug("global-slug")
                .workspaceId("default-workspace")
                .departmentId("")
                .title("Global Title")
                .authorId("user-123")
                .build();

        org.mockito.ArgumentCaptor<WikiPageDraft> draftCaptor = org.mockito.ArgumentCaptor.forClass(WikiPageDraft.class);
        when(wikiPageDraftRepository.findBySlugAndWorkspaceId("global-slug", "ALL"))
                .thenReturn(Collections.emptyList());
        when(wikiPageDraftRepository.save(draftCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        WikiPageDraft result = wikiDraftService.proposeDraft(newDraft);

        // Assert
        assertThat(result).isNotNull();
        WikiPageDraft saved = draftCaptor.getValue();
        assertThat(saved.getWorkspaceId()).isEqualTo("ALL");
        assertThat(saved.getDepartmentId()).isEqualTo("ALL");
    }
}
