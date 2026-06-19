package com.security.security.service;

import com.security.security.dto.WikiHealthDto;
import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class WikiHealthService {

    private final WikiPageRepository wikiPageRepository;
    private final WikiLinkRepository wikiLinkRepository;

    @Value("${wiki.health.stale-days:30}")
    private int staleDays;

    public WikiHealthDto getHealth(String workspaceId) {
        List<WikiPage> pages = wikiPageRepository.findByWorkspaceId(workspaceId);
        if (pages.isEmpty()) {
            return WikiHealthDto.builder()
                    .orphanPages(Collections.emptyList())
                    .brokenLinks(Collections.emptyList())
                    .stalePages(Collections.emptyList())
                    .summary(WikiHealthDto.HealthSummary.builder()
                            .totalPages(0).orphanCount(0).brokenLinkCount(0).staleCount(0).healthScore(1.0)
                            .build())
                    .build();
        }

        Map<String, WikiPage> slugToPage = new HashMap<>();
        Set<Long> pageIds = new HashSet<>();
        for (WikiPage p : pages) {
            slugToPage.put(p.getSlug(), p);
            pageIds.add(p.getId());
        }

        List<WikiLink> allLinks = wikiLinkRepository.findByFromPageIdIn(new ArrayList<>(pageIds));

        // Orphan pages: no incoming AND no outgoing links
        Set<Long> hasOutgoing = allLinks.stream().map(WikiLink::getFromPageId).collect(Collectors.toSet());
        Set<Long> hasIncoming = new HashSet<>();
        List<WikiHealthDto.BrokenLink> brokenLinks = new ArrayList<>();

        for (WikiLink link : allLinks) {
            WikiPage targetPage = slugToPage.get(link.getToSlug());
            if (targetPage != null) {
                hasIncoming.add(targetPage.getId());
            } else {
                WikiPage fromPage = pages.stream()
                        .filter(p -> p.getId().equals(link.getFromPageId()))
                        .findFirst().orElse(null);
                brokenLinks.add(WikiHealthDto.BrokenLink.builder()
                        .fromSlug(fromPage != null ? fromPage.getSlug() : "unknown")
                        .toSlug(link.getToSlug())
                        .build());
            }
        }

        List<WikiHealthDto.WikiPageRef> orphanPages = pages.stream()
                .filter(p -> !hasOutgoing.contains(p.getId()) && !hasIncoming.contains(p.getId()))
                .map(p -> WikiHealthDto.WikiPageRef.builder()
                        .slug(p.getSlug()).title(p.getTitle())
                        .pageType(p.getPageType()).updatedAt(p.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());

        // Stale pages
        LocalDateTime threshold = LocalDateTime.now().minusDays(staleDays);
        List<WikiHealthDto.WikiPageRef> stalePages = pages.stream()
                .filter(p -> p.getUpdatedAt() != null && p.getUpdatedAt().isBefore(threshold))
                .map(p -> WikiHealthDto.WikiPageRef.builder()
                        .slug(p.getSlug()).title(p.getTitle())
                        .pageType(p.getPageType()).updatedAt(p.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());

        int total = pages.size();
        int issues = orphanPages.size() + brokenLinks.size() + stalePages.size();
        double healthScore = total > 0 ? Math.max(0.0, Math.min(1.0, 1.0 - (double) issues / (3.0 * total))) : 1.0;

        return WikiHealthDto.builder()
                .orphanPages(orphanPages)
                .brokenLinks(brokenLinks)
                .stalePages(stalePages)
                .summary(WikiHealthDto.HealthSummary.builder()
                        .totalPages(total)
                        .orphanCount(orphanPages.size())
                        .brokenLinkCount(brokenLinks.size())
                        .staleCount(stalePages.size())
                        .healthScore(Math.round(healthScore * 100.0) / 100.0)
                        .build())
                .build();
    }
}
