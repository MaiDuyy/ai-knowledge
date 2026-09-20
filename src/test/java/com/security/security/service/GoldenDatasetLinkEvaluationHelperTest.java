package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoldenDatasetLinkEvaluationHelper")
class GoldenDatasetLinkEvaluationHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private WikiPageRepository wikiPageRepository;

    @Mock
    private WikiLinkRepository wikiLinkRepository;

    @Mock
    private WikiDraftService wikiDraftService;

    @Test
    @DisplayName("resolveEvalPage prefers fromSlug in workspace over MRP SOURCE page")
    void resolveEvalPage_prefersFromSlugInWorkspace() throws Exception {
        JsonNode linkEval = MAPPER.readTree("""
                {"fromSlug":"concept/from-page","expectedLinks":[]}
                """);

        WikiPage fromPage = WikiPage.builder()
                .id(5L).slug("concept/from-page").title("From")
                .content("anchor")
                .workspaceId("ws-bench")
                .build();
        WikiPage source = WikiPage.builder()
                .id(1L).slug("source/doc").title("Doc")
                .content("# Long MRP compiled wiki")
                .pageType(WikiPageType.SOURCE)
                .sourceDocumentId(99L)
                .workspaceId("ws-bench")
                .build();

        when(wikiPageRepository.fetchBySlugAndWorkspaceId("concept/from-page", "ws-bench"))
                .thenReturn(Optional.of(fromPage));

        Optional<WikiPage> resolved = GoldenDatasetLinkEvaluationHelper.resolveEvalPage(
                linkEval, 99L, "ws-bench", wikiPageRepository);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getSlug()).isEqualTo("concept/from-page");
    }

    @Test
    @DisplayName("resolveEvalPage falls back to SOURCE page when fromSlug is absent in workspace")
    void resolveEvalPage_fallsBackToSourcePage() throws Exception {
        JsonNode linkEval = MAPPER.readTree("""
                {"fromSlug":"concept/missing","expectedLinks":[]}
                """);

        WikiPage source = WikiPage.builder()
                .id(1L).slug("source/doc").title("Doc")
                .content("# Compiled wiki from MRP")
                .pageType(WikiPageType.SOURCE)
                .sourceDocumentId(99L)
                .workspaceId("ws-bench")
                .build();

        when(wikiPageRepository.fetchBySlugAndWorkspaceId("concept/missing", "ws-bench"))
                .thenReturn(Optional.empty());
        when(wikiPageRepository.findBySourceDocumentId(99L)).thenReturn(List.of(source));

        Optional<WikiPage> resolved = GoldenDatasetLinkEvaluationHelper.resolveEvalPage(
                linkEval, 99L, "ws-bench", wikiPageRepository);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getPageType()).isEqualTo(WikiPageType.SOURCE);
    }

    @Test
    @DisplayName("resolveEvalPage creates fromSlug anchor when MRP did not produce it")
    void resolveEvalPage_createsFromSlugAnchor() throws Exception {
        JsonNode linkEval = MAPPER.readTree("""
                {"fromSlug":"concept/oauth2","expectedLinks":[]}
                """);

        WikiPage created = WikiPage.builder()
                .id(3L).slug("concept/oauth2").title("concept/oauth2")
                .content("Golden manifest link eval anchor")
                .workspaceId("ws-bench")
                .sourceDocumentId(10L)
                .build();

        when(wikiPageRepository.fetchBySlugAndWorkspaceId("concept/oauth2", "ws-bench"))
                .thenReturn(Optional.empty());
        when(wikiPageRepository.findBySourceDocumentId(10L)).thenReturn(List.of());
        when(wikiPageRepository.save(any(WikiPage.class))).thenReturn(created);

        Optional<WikiPage> resolved = GoldenDatasetLinkEvaluationHelper.resolveEvalPage(
                linkEval, 10L, "ws-bench", wikiPageRepository);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getSlug()).isEqualTo("concept/oauth2");
    }

    @Test
    @DisplayName("evaluateEndToEndLinks refreshes links from manifest content, not MRP body")
    void evaluateEndToEndLinks_usesManifestContent() throws Exception {
        JsonNode linkEval = MAPPER.readTree("""
                {
                  "fromSlug":"concept/pgvector",
                  "content":"Embedding lưu [[concept/pgvector]] qua [[concept/prisma]].",
                  "expectedLinks":["concept/pgvector","concept/prisma"],
                  "forbiddenLinks":[]
                }
                """);

        WikiPage anchor = WikiPage.builder()
                .id(7L).slug("concept/pgvector").title("pgvector")
                .content("MRP-generated body should not be used for link eval")
                .workspaceId("ws-bench")
                .sourceDocumentId(42L)
                .build();

        when(wikiPageRepository.findBySourceDocumentId(42L)).thenReturn(List.of(anchor));
        when(wikiPageRepository.fetchBySlugAndWorkspaceId("concept/pgvector", "ws-bench"))
                .thenReturn(Optional.of(anchor));
        when(wikiLinkRepository.findByFromPageId(7L)).thenReturn(List.of(
                WikiLink.builder().fromPageId(7L).toSlug("concept/pgvector").build(),
                WikiLink.builder().fromPageId(7L).toSlug("concept/prisma").build()
        ));

        GoldenDatasetLinkEvaluationHelper.EndToEndLinkResult result =
                GoldenDatasetLinkEvaluationHelper.evaluateEndToEndLinks(
                        42L, linkEval, "ws-bench",
                        wikiDraftService, wikiPageRepository, wikiLinkRepository);

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(wikiDraftService).refreshLinks(eq(7L), eq("concept/pgvector"), contentCaptor.capture(), eq("ws-bench"), eq(false));
        assertThat(contentCaptor.getValue()).contains("[[concept/pgvector]]");
        assertThat(result.pageResolved()).isTrue();
        assertThat(result.linkF1()).isEqualTo(1.0);
        assertThat(result.actualLinks()).containsExactlyInAnyOrder("concept/pgvector", "concept/prisma");
        assertThat(result.wikiContent()).isEqualTo(linkEval.get("content").asText());
    }
}