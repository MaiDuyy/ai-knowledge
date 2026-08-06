package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Tag("benchmark")
@DisplayName("SecWiki-Bench v2: WikiLink Entity Linking (GERBIL)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WikiLinkResolutionBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(WikiLinkResolutionBenchmarkTest.class);

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private io.nats.client.Connection natsConnection;

    @Autowired
    private WikiDraftService wikiDraftService;

    @Autowired
    private WikiGraphService wikiGraphService;

    @Autowired
    private WikiPageRepository wikiPageRepository;

    @Autowired
    private WikiLinkRepository wikiLinkRepository;

    private JsonNode scenariosRoot;
    private String workspaceId;
    private Map<String, Long> slugToId = new HashMap<>();
    private final List<Map<String, Double>> perScenarioF1 = new ArrayList<>();
    private int danglingDetected = 0;
    private int explicitCorrect = 0;
    private int explicitTotal = 0;
    private int implicitCorrect = 0;
    private int implicitTotal = 0;
    private int totalNilCount = 0;

    @BeforeEach
    void setUp() throws Exception {
        wikiLinkRepository.deleteAll();
        wikiPageRepository.deleteAll();
        scenariosRoot = BenchmarkEvaluationAssertions.loadResourceJson("benchmark/wikilink/link-scenarios.json");
        workspaceId = GoldenDatasetManifestLoader.workspaceId();
        seedPages(scenariosRoot.get("pages"));
    }

    @Test
    @Order(1)
    @DisplayName("GERBIL: Benchmark all link scenarios (explicit, implicit, dangling, negative)")
    void benchmarkAllLinkScenarios() {
        Set<String> allActual = new HashSet<>();
        Set<String> allExpected = new HashSet<>();

        for (JsonNode scenario : scenariosRoot.get("scenarios")) {
            String scenarioId = scenario.get("id").asText();
            String fromSlug = scenario.get("fromSlug").asText();
            Long fromPageId = slugToId.get(fromSlug);
            assertThat(fromPageId).as("Page id for slug %s", fromSlug).isNotNull();
            String content = scenario.get("content").asText();
            String workspaceId = scenariosRoot.get("workspaceId").asText();
            String linkType = scenario.get("linkType").asText();

            wikiLinkRepository.deleteAll();

            wikiDraftService.refreshLinks(fromPageId, fromSlug, content, workspaceId);

            List<WikiLink> saved = wikiLinkRepository.findAll();
            Set<String> actualSlugs = saved.stream().map(WikiLink::getToSlug).collect(Collectors.toSet());

            Set<String> existingSlugs = wikiPageRepository.findAll().stream()
                    .map(WikiPage::getSlug).collect(Collectors.toSet());
            List<String> dangling = actualSlugs.stream()
                    .filter(s -> !existingSlugs.contains(s))
                    .collect(Collectors.toList());

            Set<String> expected = new HashSet<>();
            if (scenario.has("expectedLinks")) {
                scenario.get("expectedLinks").forEach(n -> expected.add(n.asText()));
            }
            Set<String> forbidden = new HashSet<>();
            if (scenario.has("forbiddenLinks")) {
                scenario.get("forbiddenLinks").forEach(n -> forbidden.add(n.asText()));
            }

            EvaluationMetricsService.GerbilLinkingMetrics gerbil =
                    BenchmarkEvaluationAssertions.computeGerbilLinkingMetrics(actualSlugs, expected, existingSlugs);
            Map<String, Double> f1 = Map.of(
                    "linkPrecision", gerbil.microPrecision(),
                    "linkRecall", gerbil.microRecall(),
                    "linkF1", gerbil.microF1());
            perScenarioF1.add(f1);
            totalNilCount += gerbil.nilCount();
            allActual.addAll(actualSlugs);
            allExpected.addAll(expected);

            if ("dangling".equals(linkType) && scenario.has("expectedDangling")) {
                Set<String> expectedDangling = new HashSet<>();
                scenario.get("expectedDangling").forEach(n -> expectedDangling.add(n.asText()));
                danglingDetected += (int) dangling.stream().filter(expectedDangling::contains).count();
            }

            if (linkType.startsWith("explicit")) {
                explicitTotal++;
                if (actualSlugs.equals(expected) && forbidden.stream().noneMatch(actualSlugs::contains)) {
                    explicitCorrect++;
                }
            }
            if ("implicit".equals(linkType)) {
                implicitTotal++;
                if (expected.stream().allMatch(actualSlugs::contains)) {
                    implicitCorrect++;
                }
            }

            for (String f : forbidden) {
                assertThat(actualSlugs).as("Scenario %s must not link %s", scenarioId, f).doesNotContain(f);
            }

            log.info("[GERBIL] scenario={} type={} actual={} expected={} dangling={} F1={}",
                    scenarioId, linkType, actualSlugs, expected, dangling, f1.get("linkF1"));
        }

        Set<String> allExistingSlugs = wikiPageRepository.findAll().stream()
                .map(WikiPage::getSlug).collect(Collectors.toSet());
        EvaluationMetricsService.GerbilLinkingMetrics corpusGerbil =
                BenchmarkEvaluationAssertions.computeGerbilLinkingMetrics(allActual, allExpected, allExistingSlugs);
        Map<String, Double> micro = Map.of(
                "linkPrecision", corpusGerbil.microPrecision(),
                "linkRecall", corpusGerbil.microRecall(),
                "linkF1", corpusGerbil.microF1());
        double macroF1 = BenchmarkEvaluationAssertions.computeMacroF1(perScenarioF1);
        double explicitAcc = explicitTotal == 0 ? 1.0 : (double) explicitCorrect / explicitTotal;
        double implicitRec = implicitTotal == 0 ? 1.0 : (double) implicitCorrect / implicitTotal;

        BenchmarkEvaluationAssertions.record("wikilink", "linkPrecision", micro.get("linkPrecision"));
        BenchmarkEvaluationAssertions.record("wikilink", "linkRecall", micro.get("linkRecall"));
        BenchmarkEvaluationAssertions.record("wikilink", "linkF1", micro.get("linkF1"));
        BenchmarkEvaluationAssertions.record("wikilink", "macroF1", macroF1);
        BenchmarkEvaluationAssertions.record("wikilink", "explicitAccuracy", explicitAcc);
        BenchmarkEvaluationAssertions.record("wikilink", "implicitRecall", implicitRec);
        BenchmarkEvaluationAssertions.record("wikilink", "danglingDetected", danglingDetected);
        BenchmarkEvaluationAssertions.record("wikilink", "nilCount", totalNilCount);
        BenchmarkEvaluationAssertions.record("wikilink", "nilRate", corpusGerbil.nilRate());
        BenchmarkEvaluationAssertions.recordDetail("wikilink", "perScenarioF1", perScenarioF1);
        BenchmarkEvaluationAssertions.recordDetail("wikilink", "nilLinks", corpusGerbil.nilLinks());

        log.info("=== [GERBIL] Entity Linking Results ===");
        log.info("Micro P={} R={} F1={}", micro.get("linkPrecision"), micro.get("linkRecall"), micro.get("linkF1"));
        log.info("Macro F1={} | Explicit Acc={} | Implicit Recall={} | Dangling={}",
                macroF1, explicitAcc, implicitRec, danglingDetected);

        assertThat(micro.get("linkF1")).isGreaterThanOrEqualTo(0.80);
    }

    @Test
    @Order(2)
    @DisplayName("GERBIL: Graph connectivity after refreshLinks")
    void benchmarkGraphConnectivity() {
        String workspaceId = scenariosRoot.get("workspaceId").asText();
        WikiPage from = wikiPageRepository.findAll().stream().findFirst().orElseThrow();

        String content = "Liên kết [[concept/spring-security]] và nhắc Hướng dẫn Onboarding IT.";
        wikiDraftService.refreshLinks(from.getId(), from.getSlug(), content, workspaceId);

        var graph = wikiGraphService.detectCommunities(workspaceId);
        assertThat(graph.getCommunities()).isNotNull();

        int edgeCount = wikiLinkRepository.findAll().size();
        BenchmarkEvaluationAssertions.recordDetail("wikilink", "graphEdgeCount", edgeCount);
        BenchmarkEvaluationAssertions.recordDetail("wikilink", "graphCommunityCount", graph.getCommunities().size());

        log.info("[GERBIL] Graph edges={} communities={}", edgeCount, graph.getCommunities().size());
        assertThat(edgeCount).isGreaterThan(0);
    }

    @Test
    @Order(3)
    @DisplayName("GERBIL: Golden manifest linkEvaluation scenarios (40 docs)")
    void benchmarkGoldenManifestLinkScenarios() throws Exception {
        List<JsonNode> linkDocs = GoldenDatasetManifestLoader.linkEvaluationDocuments();
        assertThat(linkDocs).hasSize(GoldenDatasetManifestLoader.documentCount());

        List<Map<String, Double>> manifestF1 = new ArrayList<>();
        int passed = 0;

        for (JsonNode docEntry : linkDocs) {
            String docId = docEntry.get("id").asText();
            JsonNode linkEval = GoldenDatasetManifestLoader.linkEvaluation(docEntry);

            wikiLinkRepository.deleteAll();
            wikiPageRepository.deleteAll();
            slugToId.clear();
            seedLinkPages(linkEval.get("seedPages"), workspaceId);

            String fromSlug = linkEval.get("fromSlug").asText();
            Long fromPageId = slugToId.get(fromSlug);
            if (fromPageId == null) {
                WikiPage fromPage = wikiPageRepository.save(WikiPage.builder()
                        .title(fromSlug)
                        .slug(fromSlug)
                        .content("Golden manifest from page")
                        .workspaceId(workspaceId)
                        .departmentId("ALL")
                        .allowedRoles("ALL")
                        .securityClassification(SecurityClassification.INTERNAL)
                        .pageType(WikiPageType.CONCEPT)
                        .summary("Manifest from")
                        .build());
                fromPageId = fromPage.getId();
                slugToId.put(fromSlug, fromPageId);
            }

            wikiDraftService.refreshLinks(fromPageId, fromSlug, linkEval.get("content").asText(), workspaceId);

            Set<String> actual = wikiLinkRepository.findAll().stream()
                    .map(WikiLink::getToSlug).collect(Collectors.toSet());
            Set<String> expected = new HashSet<>();
            linkEval.get("expectedLinks").forEach(n -> expected.add(n.asText()));
            Set<String> forbidden = new HashSet<>();
            if (linkEval.has("forbiddenLinks")) {
                linkEval.get("forbiddenLinks").forEach(n -> forbidden.add(n.asText()));
            }

            for (String f : forbidden) {
                assertThat(actual).as("Manifest doc %s must not link %s", docId, f).doesNotContain(f);
            }

            Set<String> existingSlugs = wikiPageRepository.findAll().stream()
                    .map(WikiPage::getSlug).collect(Collectors.toSet());
            EvaluationMetricsService.GerbilLinkingMetrics gerbil =
                    BenchmarkEvaluationAssertions.computeGerbilLinkingMetrics(actual, expected, existingSlugs);
            Map<String, Double> f1 = Map.of(
                    "linkPrecision", gerbil.microPrecision(),
                    "linkRecall", gerbil.microRecall(),
                    "linkF1", gerbil.microF1());
            manifestF1.add(f1);
            if (gerbil.microF1() >= 0.80) passed++;

            log.info("[GERBIL/manifest] doc={} actual={} expected={} F1={}",
                    docId, actual, expected, gerbil.microF1());
        }

        double macroF1 = BenchmarkEvaluationAssertions.computeMacroF1(manifestF1);
        double passRate = (double) passed / linkDocs.size();

        BenchmarkEvaluationAssertions.record("wikilink", "manifestLinkScenarioCount", linkDocs.size());
        BenchmarkEvaluationAssertions.record("wikilink", "manifestLinkMacroF1", macroF1);
        BenchmarkEvaluationAssertions.record("wikilink", "manifestLinkPassRate", passRate);
        BenchmarkEvaluationAssertions.recordDetail("wikilink", "manifestPerDocF1", manifestF1);

        log.info("[GERBIL/manifest] scenarios={} macroF1={} passRate={}", linkDocs.size(), macroF1, passRate);
        assertThat(macroF1).isGreaterThanOrEqualTo(0.80);
        assertThat(passRate).isGreaterThanOrEqualTo(0.80);
    }

    private void seedPages(JsonNode pages) {
        slugToId.clear();
        String workspaceId = scenariosRoot.get("workspaceId").asText();
        for (JsonNode p : pages) {
            WikiPage saved = wikiPageRepository.save(WikiPage.builder()
                    .title(p.get("title").asText())
                    .slug(p.get("slug").asText())
                    .content("Seed content for " + p.get("title").asText())
                    .workspaceId(workspaceId)
                    .departmentId("ALL")
                    .allowedRoles("ALL")
                    .securityClassification(SecurityClassification.INTERNAL)
                    .pageType(WikiPageType.CONCEPT)
                    .summary("Benchmark seed")
                    .build());
            slugToId.put(saved.getSlug(), saved.getId());
        }
    }

    private void seedLinkPages(JsonNode pages, String wsId) {
        for (JsonNode p : pages) {
            WikiPage saved = wikiPageRepository.save(WikiPage.builder()
                    .title(p.get("title").asText())
                    .slug(p.get("slug").asText())
                    .content("Seed content for " + p.get("title").asText())
                    .workspaceId(wsId)
                    .departmentId("ALL")
                    .allowedRoles("ALL")
                    .securityClassification(SecurityClassification.INTERNAL)
                    .pageType(WikiPageType.CONCEPT)
                    .summary("Manifest seed")
                    .build());
            slugToId.put(saved.getSlug(), saved.getId());
        }
    }
}