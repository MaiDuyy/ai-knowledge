package com.security.security.service;

import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WikiGraphServiceTest {

    @Mock
    private WikiPageRepository wikiPageRepository;

    @Mock
    private WikiLinkRepository wikiLinkRepository;

    @Mock
    private ObjectProvider<MongoTemplate> mongoTemplateProvider;

    private WikiGraphService wikiGraphService;

    @BeforeEach
    void setUp() {
        wikiGraphService = new WikiGraphService(wikiPageRepository, wikiLinkRepository, mongoTemplateProvider);
    }

    @Test
    @DisplayName("PostgreSQL JPA path: jgraphtReachable traverses 1-hop and 2-hop links correctly")
    void testJgraphtReachable() {
        WikiPage page1 = WikiPage.builder().id(1L).slug("page-1").title("Page 1").build();
        WikiPage page2 = WikiPage.builder().id(2L).slug("page-2").title("Page 2").build();
        WikiPage page3 = WikiPage.builder().id(3L).slug("page-3").title("Page 3").build();

        WikiLink link1to2 = WikiLink.builder().fromPageId(1L).toSlug("page-2").build();
        WikiLink link2to3 = WikiLink.builder().fromPageId(2L).toSlug("page-3").build();

        when(wikiPageRepository.findAll()).thenReturn(List.of(page1, page2, page3));
        when(wikiLinkRepository.findAll()).thenReturn(List.of(link1to2, link2to3));

        // 1-hop test from page-1
        List<String> hop1 = wikiGraphService.jgraphtReachable("page-1", 1);
        assertEquals(1, hop1.size());
        assertTrue(hop1.contains("page-2"));

        // 2-hop test from page-1
        List<String> hop2 = wikiGraphService.jgraphtReachable("page-1", 2);
        assertEquals(2, hop2.size());
        assertTrue(hop2.contains("page-2"));
        assertTrue(hop2.contains("page-3"));
    }

    @Test
    @DisplayName("MongoDB $graphLookup path returns empty list gracefully when MongoTemplate is absent")
    void testGraphLookupReachableWithoutMongo() {
        when(mongoTemplateProvider.getIfAvailable()).thenReturn(null);

        List<String> reachable = wikiGraphService.graphLookupReachable("page-1", 2);
        assertNotNull(reachable);
        assertTrue(reachable.isEmpty());
    }
}
