package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hybrid link evaluation: MRP pipeline runs for real extraction metrics; GERBIL link F1 uses
 * manifest {@code content} injected on the {@code fromSlug} anchor (or SOURCE fallback) →
 * {@link WikiDraftService#refreshLinks}.
 */
public final class GoldenDatasetLinkEvaluationHelper {

    public static final String LINK_EVAL_MODE = "mrp-hybrid";

    private GoldenDatasetLinkEvaluationHelper() {}

    public record EndToEndLinkResult(
            double linkF1,
            double linkPrecision,
            double linkRecall,
            Set<String> actualLinks,
            Set<String> expectedLinks,
            Set<String> forbiddenLinks,
            String evalPageSlug,
            String wikiContent,
            int mrpPagesCreated,
            boolean pageResolved) {}

    public static int seedLinkTargetPages(JsonNode linkEval, String workspaceId, WikiPageRepository wikiPageRepository) {
        if (linkEval == null || !linkEval.has("seedPages")) return 0;

        int seeded = 0;
        for (JsonNode page : linkEval.get("seedPages")) {
            String slug = page.get("slug").asText();
            if (wikiPageRepository.fetchBySlugAndWorkspaceId(slug, workspaceId).isPresent()) continue;

            wikiPageRepository.save(WikiPage.builder()
                    .title(page.get("title").asText())
                    .slug(slug)
                    .content("Seed target for E2E link eval: " + page.get("title").asText())
                    .workspaceId(workspaceId)
                    .departmentId("ALL")
                    .allowedRoles("ALL")
                    .securityClassification(SecurityClassification.INTERNAL)
                    .pageType(WikiPageType.CONCEPT)
                    .summary("Golden manifest link seed")
                    .build());
            seeded++;
        }
        return seeded;
    }

    /**
     * Resolves the wiki page that anchors link evaluation. Prefers {@code fromSlug}, then MRP
     * SOURCE output, then creates a minimal page at {@code fromSlug}.
     */
    public static Optional<WikiPage> resolveEvalPage(
            JsonNode linkEval,
            Long sourceDocumentId,
            String workspaceId,
            WikiPageRepository wikiPageRepository) {

        String fromSlug = linkEval.has("fromSlug") ? linkEval.get("fromSlug").asText() : null;

        if (fromSlug != null && !fromSlug.isBlank()) {
            Optional<WikiPage> inWorkspace = wikiPageRepository.fetchBySlugAndWorkspaceId(fromSlug, workspaceId);
            if (inWorkspace.isPresent()) {
                return inWorkspace;
            }

            Optional<WikiPage> onDocument = wikiPageRepository.findBySourceDocumentId(sourceDocumentId).stream()
                    .filter(p -> fromSlug.equals(p.getSlug()))
                    .findFirst();
            if (onDocument.isPresent()) {
                return onDocument;
            }
        }

        List<WikiPage> mrpPages = wikiPageRepository.findBySourceDocumentId(sourceDocumentId).stream()
                .filter(p -> sourceDocumentId.equals(p.getSourceDocumentId()))
                .filter(GoldenDatasetLinkEvaluationHelper::hasContent)
                .filter(p -> !isSeedStub(p))
                .toList();

        Optional<WikiPage> sourcePage = mrpPages.stream()
                .filter(p -> p.getPageType() == WikiPageType.SOURCE)
                .max(Comparator.comparingInt(p -> p.getContent().length()));
        if (sourcePage.isPresent()) {
            return sourcePage;
        }

        Optional<WikiPage> sourceSlugPage = mrpPages.stream()
                .filter(p -> p.getSlug() != null && p.getSlug().startsWith("source/"))
                .max(Comparator.comparingInt(p -> p.getContent().length()));
        if (sourceSlugPage.isPresent()) {
            return sourceSlugPage;
        }

        if (fromSlug != null && !fromSlug.isBlank()) {
            return Optional.of(wikiPageRepository.save(WikiPage.builder()
                    .title(fromSlug)
                    .slug(fromSlug)
                    .content("Golden manifest link eval anchor")
                    .workspaceId(workspaceId)
                    .departmentId("ALL")
                    .allowedRoles("ALL")
                    .securityClassification(SecurityClassification.INTERNAL)
                    .pageType(WikiPageType.CONCEPT)
                    .summary("Golden manifest link eval anchor")
                    .sourceDocumentId(sourceDocumentId)
                    .build()));
        }

        return mrpPages.stream()
                .max(Comparator.comparingInt(p -> p.getContent().length()));
    }

    private static boolean isSeedStub(WikiPage page) {
        return page.getSummary() != null && page.getSummary().contains("Golden manifest link seed");
    }

    public static EndToEndLinkResult evaluateEndToEndLinks(
            Long sourceDocumentId,
            JsonNode linkEval,
            String workspaceId,
            WikiDraftService wikiDraftService,
            WikiPageRepository wikiPageRepository,
            WikiLinkRepository wikiLinkRepository) {

        Set<String> expected = readSlugSet(linkEval.get("expectedLinks"));
        Set<String> forbidden = linkEval.has("forbiddenLinks")
                ? readSlugSet(linkEval.get("forbiddenLinks"))
                : Set.of();

        List<WikiPage> mrpPages = wikiPageRepository.findBySourceDocumentId(sourceDocumentId);
        int mrpPagesCreated = mrpPages.size();

        String linkContent = linkEval.has("content") ? linkEval.get("content").asText("") : "";
        if (linkContent.isBlank()) {
            return new EndToEndLinkResult(
                    0.0, 0.0, 0.0, Set.of(), expected, forbidden,
                    "", "", mrpPagesCreated, false);
        }

        Optional<WikiPage> evalPageOpt = resolveEvalPage(linkEval, sourceDocumentId, workspaceId, wikiPageRepository);
        if (evalPageOpt.isEmpty()) {
            return new EndToEndLinkResult(
                    0.0, 0.0, 0.0, Set.of(), expected, forbidden,
                    "", linkContent, mrpPagesCreated, false);
        }

        WikiPage evalPage = evalPageOpt.get();

        wikiDraftService.refreshLinks(evalPage.getId(), evalPage.getSlug(), linkContent, workspaceId, false);

        Set<String> actual = wikiLinkRepository.findByFromPageId(evalPage.getId()).stream()
                .map(WikiLink::getToSlug)
                .collect(Collectors.toSet());

        Map<String, Double> metrics = BenchmarkEvaluationAssertions.computeEntityLinkingMetrics(actual, expected);

        return new EndToEndLinkResult(
                metrics.get("linkF1"),
                metrics.get("linkPrecision"),
                metrics.get("linkRecall"),
                actual,
                expected,
                forbidden,
                evalPage.getSlug(),
                linkContent,
                mrpPagesCreated,
                true);
    }

    private static Set<String> readSlugSet(JsonNode array) {
        Set<String> slugs = new HashSet<>();
        if (array == null || !array.isArray()) return slugs;
        array.forEach(n -> slugs.add(n.asText()));
        return slugs;
    }

    private static boolean hasContent(WikiPage page) {
        return page.getContent() != null && !page.getContent().isBlank();
    }
}