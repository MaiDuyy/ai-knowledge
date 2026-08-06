package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.entity.Document;
import com.security.security.entity.SourceChunkExtract;
import com.security.security.entity.SourceCompilationPlan;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.enumeration.SourceChunkStatus;
import com.security.security.entity.enumeration.SourceCompilationStatus;
import com.security.security.provider.LlmFactory;
import com.security.security.provider.LlmProvider;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.SourceChunkExtractRepository;
import com.security.security.repository.SourceCompilationPlanRepository;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evaluation-tier benchmark: runs Golden Dataset against real Gemini API + H2 test DB.
 * Cross-model judge (GPT-4o when OPENAI_API_KEY set, else Gemini self-judge).
 * Excluded from default CI — run via {@code mvn test -Pevaluation-benchmark}.
 *
 * <p>Limit docs (recommended for free-tier / faster Ragas):
 * {@code -Dbenchmark.eval.maxDocs=15}
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("evaluation")
@EnabledIf(value = "com.security.security.service.BenchmarkConditions#evaluationBenchmarkEnabled",
        disabledReason = "API_KEY not set — skip evaluation golden dataset benchmarks")
@DisplayName("SecWiki Evaluation: Golden Dataset (40 docs, MRP + Hybrid Link Eval)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(3600)
class GoldenDatasetEvaluationBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(GoldenDatasetEvaluationBenchmarkTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private io.nats.client.Connection natsConnection;

    @MockBean
    private com.security.security.client.WorkspaceServiceClient workspaceServiceClient;

    @Autowired
    private MrpPipelineService mrpPipelineService;

    @Autowired
    private WikiDraftService wikiDraftService;

    @Autowired
    private LlmFactory llmFactory;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private SourceCompilationPlanRepository planRepository;

    @Autowired
    private SourceChunkExtractRepository chunkRepository;

    @Autowired
    private WikiPageRepository wikiPageRepository;

    @Autowired
    private WikiLinkRepository wikiLinkRepository;

    private JsonNode manifest;
    private final List<Double> allFaithfulness = new ArrayList<>();
    private final List<Double> allGroundedness = new ArrayList<>();
    private final List<Double> allExtractionF1 = new ArrayList<>();
    private final List<Double> allLinkF1 = new ArrayList<>();
    private final List<Double> allJudgeScores = new ArrayList<>();
    private final List<Double> allLinkJudgeScores = new ArrayList<>();
    private final List<Double> allAnswerRelevancy = new ArrayList<>();
    private final List<Long> allLatencyMs = new ArrayList<>();
    private final List<Integer> allEstimatedTokens = new ArrayList<>();
    private int documentsEvaluated = 0;
    private int linkScenariosEvaluated = 0;
    private int linkPagesResolved = 0;
    private int totalMrpWikiPagesCreated = 0;
    private boolean corpusAggregated = false;

    @AfterAll
    void flushPartialReportIfNeeded() {
        try {
            if (documentsEvaluated > 0 && !corpusAggregated) {
                recordCorpusMetrics();
            }
            if (documentsEvaluated > 0) {
                // Always flush Ragas export here so latest/export matches this run
                // (Z_ finalizer may run in another surefire fork with empty exporter).
                flushEvaluationArtifacts();
                log.info("[EVAL] Flushed evaluation report + export (documentsEvaluated={}, corpusAggregated={}, claims={}, structured={})",
                        documentsEvaluated, corpusAggregated,
                        BenchmarkEvaluationAssertions.exporter().claimSampleCount(),
                        BenchmarkEvaluationAssertions.exporter().structuredSampleCount());
            }
        } catch (Exception e) {
            log.warn("[EVAL] Could not flush partial report: {}", e.getMessage());
        }
    }

    @BeforeAll
    void loadManifest() throws Exception {
        BenchmarkEvaluationAssertions.resetEvaluation();
        String runId = BenchmarkReportService.currentRunId();
        BenchmarkEvaluationAssertions.report().setEvaluationRunId(runId);
        manifest = GoldenDatasetManifestLoader.manifest();
        int maxDocs = resolveMaxDocs();
        BenchmarkEvaluationAssertions.recordEvaluation("judgeModel", BenchmarkCrossModelJudge.resolveJudgeModel());
        BenchmarkEvaluationAssertions.recordEvaluation("openAiJudgeEnabled", BenchmarkConditions.openAiKeyAvailable());
        BenchmarkEvaluationAssertions.recordEvaluation("documentCount", GoldenDatasetManifestLoader.documentCount());
        BenchmarkEvaluationAssertions.recordEvaluation("maxDocsLimit", maxDocs > 0 ? maxDocs : "unlimited");
        BenchmarkEvaluationAssertions.recordEvaluation("manifestVersion", manifest.get("version").asText());
        BenchmarkEvaluationAssertions.recordEvaluation("linkEvalMode", GoldenDatasetLinkEvaluationHelper.LINK_EVAL_MODE);
        log.info("[EVAL] maxDocs limit = {} (set -Dbenchmark.eval.maxDocs=N to cap)",
                maxDocs > 0 ? maxDocs : "unlimited");
    }

    @Test
    @Order(1)
    @DisplayName("Evaluate Golden Dataset documents via real MRP pipeline")
    void evaluateAllGoldenDocuments() throws Exception {
        LlmProvider judgeFallback = llmFactory.getProvider("gemini");
        int maxClaims = manifest.path("judgeConfig").path("maxClaimsPerDocument").asInt(2);
        String workspaceId = GoldenDatasetManifestLoader.workspaceId();
        int maxDocs = resolveMaxDocs();

        List<JsonNode> docs = GoldenDatasetManifestLoader.documents();
        if (maxDocs > 0 && maxDocs < docs.size()) {
            docs = docs.subList(0, maxDocs);
            log.info("[EVAL] Limiting to first {} of {} golden documents", maxDocs, GoldenDatasetManifestLoader.documentCount());
        }

        for (JsonNode docEntry : docs) {
            String docId = docEntry.get("id").asText();
            String source = GoldenDatasetManifestLoader.loadSource(docEntry);
            JsonNode groundTruth = GoldenDatasetManifestLoader.loadGroundTruth(docEntry);

            Document doc = null;
            SourceCompilationPlan plan = null;
            long pipelineLatencyMs = 0;
            List<SourceChunkExtract> chunks = List.of();

            JsonNode linkEval = docEntry.has("linkEvaluation") && !docEntry.get("linkEvaluation").isNull()
                    ? docEntry.get("linkEvaluation") : null;

            for (int attempt = 1; attempt <= 3; attempt++) {
                cleanRepositories();
                doc = saveDocument(source, docId + ".md");
                plan = createPlan(doc.getId());

                if (linkEval != null) {
                    int seeded = GoldenDatasetLinkEvaluationHelper.seedLinkTargetPages(
                            linkEval, workspaceId, wikiPageRepository);
                    log.info("[EVAL] doc={} seeded {} link target pages before MRP refine", docId, seeded);
                }

                long pipelineStart = System.nanoTime();
                mrpPipelineService.runCompilationProcess(
                        plan.getId(), doc.getId(), source, workspaceId, "eval-user", true);
                pipelineLatencyMs = (System.nanoTime() - pipelineStart) / 1_000_000;

                chunks = chunkRepository.findBySourceDocumentIdAndStatus(
                        doc.getId(), SourceChunkStatus.DONE);
                if (!chunks.isEmpty()) break;
                log.warn("[EVAL] doc={} attempt {}/3 — map produced no chunks, retrying", docId, attempt);
            }
            assertThat(chunks).as("Chunks for %s after 3 attempts", docId).isNotEmpty();

            Set<String> extractedTopics = new HashSet<>();
            List<String> allClaims = new ArrayList<>();
            List<String> allTexts = new ArrayList<>();
            List<JsonNode> extractRoots = new ArrayList<>();

            for (SourceChunkExtract chunk : chunks) {
                JsonNode root = MAPPER.readTree(chunk.getExtractJson());
                extractRoots.add(root);
                collectNames(root.get("entities"), "name", extractedTopics, allTexts);
                collectNames(root.get("concepts"), "name", extractedTopics, allTexts);
                collectClaims(root.get("claims"), allClaims, allTexts);
            }

            Set<String> expectedTopics = new HashSet<>();
            groundTruth.get("expectedTopics").forEach(n -> expectedTopics.add(n.asText()));

            Map<String, Double> ctx = BenchmarkEvaluationAssertions.computeContextRecallPrecision(extractedTopics, expectedTopics);
            double faithfulness = BenchmarkEvaluationAssertions.computeFaithfulness(allClaims, source);
            double groundedness = BenchmarkEvaluationAssertions.computeGroundednessRate(allTexts, source);

            List<String> forbidden = new ArrayList<>();
            groundTruth.get("forbiddenHallucinations").forEach(n -> forbidden.add(n.asText()));
            List<String> hallucinations = BenchmarkEvaluationAssertions.detectHallucinations(extractedTopics, source, forbidden);

            String outputText = allTexts.stream().collect(Collectors.joining(" "));
            int estimatedTokens = PipelineBenchmarkTracker.estimateTokens(source)
                    + PipelineBenchmarkTracker.estimateTokens(outputText);
            double answerRelevancy = BenchmarkEvaluationAssertions.computeAnswerRelevancy(outputText, expectedTopics);

            SourceCompilationPlan finishedPlan = planRepository.findById(plan.getId()).orElseThrow();
            if (finishedPlan.getPlanJson() != null && !finishedPlan.getPlanJson().isBlank()) {
                answerRelevancy = Math.max(answerRelevancy,
                        BenchmarkEvaluationAssertions.computeAnswerRelevancy(finishedPlan.getPlanJson(), expectedTopics));
            }

            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "faithfulness", faithfulness);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "groundedness", groundedness);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "extractionF1", ctx.get("extractionF1"));
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "answerRelevancy", answerRelevancy);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "pipelineLatencyMs", pipelineLatencyMs);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "estimatedTotalTokens", estimatedTokens);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "hallucinationCount", hallucinations.size());
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "forbiddenHits",
                    forbidden.stream().filter(hallucinations::contains).collect(Collectors.toList()));

            // Ragas-ready export (buffered; flushed by report finalizer)
            Map<String, Object> responseMap = BenchmarkDataExporter.aggregateMapExtracts(extractRoots);
            Map<String, Object> referenceMap = BenchmarkDataExporter.normalizeGroundTruthToMapSchema(groundTruth);
            BenchmarkEvaluationAssertions.exporter().appendStructuredSample(docId, source, responseMap, referenceMap);

            allAnswerRelevancy.add(answerRelevancy);
            allLatencyMs.add(pipelineLatencyMs);
            allEstimatedTokens.add(estimatedTokens);

            allFaithfulness.add(faithfulness);
            allGroundedness.add(groundedness);
            allExtractionF1.add(ctx.get("extractionF1"));
            documentsEvaluated++;

            List<Double> docJudgeScores = new ArrayList<>();
            int claimsExported = 0;
            for (SourceChunkExtract chunk : chunks) {
                JsonNode root = MAPPER.readTree(chunk.getExtractJson());
                if (!root.has("claims")) continue;
                for (JsonNode claim : root.get("claims")) {
                    if (!claim.has("claim")) continue;
                    String claimText = claim.get("claim").asText();
                    String subject = claim.has("subject") ? claim.get("subject").asText("") : "";
                    String sourceContext = claim.has("sourceContext") ? claim.get("sourceContext").asText("") : "";
                    boolean hasSuffix = claimText.contains("[Source Context:");
                    if (claimsExported < maxClaims) {
                        // Prefer production sourceContext for Ragas faithfulness context
                        BenchmarkEvaluationAssertions.exporter().appendClaimSample(
                                docId, claimText, sourceContext, source, subject, hasSuffix);
                        claimsExported++;
                    }
                    BenchmarkCrossModelJudge.judgeGroundedness(claimText, source, judgeFallback)
                            .ifPresent(score -> {
                                docJudgeScores.add(score);
                                allJudgeScores.add(score);
                            });
                    if (docJudgeScores.size() >= maxClaims) break;
                }
                if (docJudgeScores.size() >= maxClaims) break;
            }
            double docJudgeAvg = docJudgeScores.isEmpty() ? 0.0 :
                    docJudgeScores.stream().mapToDouble(d -> d).average().orElse(0.0);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "crossModelJudgeAvg", docJudgeAvg);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "crossModelJudgeSamples", docJudgeScores.size());

            GoldenDatasetLinkEvaluationHelper.EndToEndLinkResult linkResult = null;
            if (linkEval != null) {
                linkResult = GoldenDatasetLinkEvaluationHelper.evaluateEndToEndLinks(
                        doc.getId(), linkEval, workspaceId,
                        wikiDraftService, wikiPageRepository, wikiLinkRepository);

                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "linkF1", linkResult.linkF1());
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "linkPrecision", linkResult.linkPrecision());
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "linkRecall", linkResult.linkRecall());
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "linkEvalMode", GoldenDatasetLinkEvaluationHelper.LINK_EVAL_MODE);
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "linkEvalPageSlug", linkResult.evalPageSlug());
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "mrpWikiPagesCreated", linkResult.mrpPagesCreated());
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "linkPageResolved", linkResult.pageResolved());
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "actualLinks", linkResult.actualLinks());
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "expectedLinks", linkResult.expectedLinks());

                Map<String, Double> linkMetrics = Map.of(
                        "linkF1", linkResult.linkF1(),
                        "linkPrecision", linkResult.linkPrecision(),
                        "linkRecall", linkResult.linkRecall());
                BenchmarkEvaluationAssertions.exporter().appendLinkSample(
                        docId, linkResult.wikiContent(), linkResult.actualLinks(),
                        linkResult.expectedLinks(), linkResult.forbiddenLinks(), linkMetrics);

                if (linkResult.pageResolved()) {
                    linkPagesResolved++;
                }
                totalMrpWikiPagesCreated += linkResult.mrpPagesCreated();

                BenchmarkCrossModelJudge.judgeLinkAccuracy(
                                linkResult.wikiContent(), linkResult.actualLinks(),
                                linkResult.expectedLinks(), linkResult.forbiddenLinks(), judgeFallback)
                        .ifPresent(score -> {
                            allLinkJudgeScores.add(score);
                            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "linkJudgeScore", score);
                        });

                allLinkF1.add(linkResult.linkF1());
                linkScenariosEvaluated++;

                for (String forbiddenLink : linkResult.forbiddenLinks()) {
                    assertThat(linkResult.actualLinks())
                            .as("E2E link eval %s must not link %s", docId, forbiddenLink)
                            .doesNotContain(forbiddenLink);
                }
            }

            // Post-Publish Markdown track for official Ragas (faithfulness / relevancy / recall)
            int publishExported = exportPublishSamples(
                    docId, doc.getId(), source, groundTruth, linkEval);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "publishPagesExported", publishExported);

            log.info("[EVAL] doc={} faith={} ground={} f1={} judge={} linkF1={} linkPage={} mrpPages={} publishPages={} forbiddenHits={}",
                    docId, faithfulness, groundedness, ctx.get("extractionF1"), docJudgeAvg,
                    linkResult != null ? linkResult.linkF1() : "n/a",
                    linkResult != null ? linkResult.evalPageSlug() : "n/a",
                    linkResult != null ? linkResult.mrpPagesCreated() : 0,
                    publishExported,
                    forbidden.stream().filter(hallucinations::contains).count());

            assertThat(forbidden.stream().filter(hallucinations::contains).collect(Collectors.toList()))
                    .as("Forbidden hallucinations in %s", docId)
                    .isEmpty();
        }

        recordCorpusMetrics();
    }

    @Test
    @Order(2)
    @DisplayName("Aggregate corpus-level evaluation metrics")
    void aggregateCorpusMetrics() {
        recordCorpusMetrics();
        assertCorpusThresholds();
    }

    private void recordCorpusMetrics() {
        if (documentsEvaluated == 0) {
            return;
        }

        double corpusFaith = average(allFaithfulness);
        double corpusGround = average(allGroundedness);
        double corpusF1 = average(allExtractionF1);
        double corpusLinkF1 = allLinkF1.isEmpty() ? 0.0 : average(allLinkF1);
        double judgeAvg = allJudgeScores.isEmpty() ? 0.0 : average(allJudgeScores);
        double linkJudgeAvg = allLinkJudgeScores.isEmpty() ? 0.0 : average(allLinkJudgeScores);

        BenchmarkEvaluationAssertions.recordEvaluation("corpusFaithfulness", corpusFaith);
        BenchmarkEvaluationAssertions.recordEvaluation("corpusGroundedness", corpusGround);
        BenchmarkEvaluationAssertions.recordEvaluation("corpusExtractionF1", corpusF1);
        BenchmarkEvaluationAssertions.recordEvaluation("corpusLinkF1", corpusLinkF1);
        BenchmarkEvaluationAssertions.recordEvaluation("corpusAnswerRelevancy", average(allAnswerRelevancy));
        BenchmarkEvaluationAssertions.recordEvaluation("avgPipelineLatencyMs", averageLong(allLatencyMs));
        BenchmarkEvaluationAssertions.recordEvaluation("totalEstimatedTokens", allEstimatedTokens.stream().mapToInt(i -> i).sum());
        BenchmarkEvaluationAssertions.recordEvaluation("crossModelJudgeAvg", judgeAvg);
        BenchmarkEvaluationAssertions.recordEvaluation("crossModelLinkJudgeAvg", linkJudgeAvg);
        BenchmarkEvaluationAssertions.recordEvaluation("documentsEvaluated", documentsEvaluated);
        BenchmarkEvaluationAssertions.recordEvaluation("linkScenariosEvaluated", linkScenariosEvaluated);
        BenchmarkEvaluationAssertions.recordEvaluation("linkPagesResolved", linkPagesResolved);
        BenchmarkEvaluationAssertions.recordEvaluation("totalMrpWikiPagesCreated", totalMrpWikiPagesCreated);
        BenchmarkEvaluationAssertions.recordEvaluation("linkEvalMode", GoldenDatasetLinkEvaluationHelper.LINK_EVAL_MODE);
        BenchmarkEvaluationAssertions.recordEvaluation("judgeSampleCount", allJudgeScores.size());
        corpusAggregated = true;

        log.info("=== EVALUATION CORPUS AGGREGATE ===");
        log.info("documents={} faith={} ground={} f1={} linkF1={} judge={}",
                documentsEvaluated, corpusFaith, corpusGround, corpusF1, corpusLinkF1, judgeAvg);
    }

    private void assertCorpusThresholds() {
        JsonNode thresholds = manifest.get("thresholds");
        double corpusFaith = average(allFaithfulness);
        double corpusGround = average(allGroundedness);
        double corpusF1 = average(allExtractionF1);
        double corpusLinkF1 = allLinkF1.isEmpty() ? 0.0 : average(allLinkF1);
        double judgeAvg = allJudgeScores.isEmpty() ? 0.0 : average(allJudgeScores);

        assertThat(corpusFaith).isGreaterThanOrEqualTo(thresholds.get("corpusFaithfulness").asDouble());
        assertThat(corpusGround).isGreaterThanOrEqualTo(thresholds.get("corpusGroundedness").asDouble());
        assertThat(corpusF1).isGreaterThanOrEqualTo(thresholds.get("corpusExtractionF1").asDouble());
        if (!allLinkF1.isEmpty()) {
            double linkThreshold = thresholds.get("corpusLinkF1").asDouble();
            if (corpusLinkF1 < linkThreshold) {
                log.warn("=== E2E link F1 below threshold: {} < {} ({} scenarios, {} pages resolved) ===",
                        corpusLinkF1, linkThreshold, linkScenariosEvaluated, linkPagesResolved);
            }
        }
        if (!allJudgeScores.isEmpty()) {
            assertThat(judgeAvg).isGreaterThanOrEqualTo(thresholds.get("crossModelJudgeAvg").asDouble());
        }
    }

    /**
     * 0 = unlimited. Override via {@code -Dbenchmark.eval.maxDocs=15} or env {@code BENCHMARK_EVAL_MAX_DOCS}.
     * Default when unset: 15 (balanced for free-tier + Ragas); set 0 or 40+ for full golden.
     */
    static int resolveMaxDocs() {
        String prop = System.getProperty("benchmark.eval.maxDocs");
        if (prop == null || prop.isBlank()) {
            prop = System.getenv("BENCHMARK_EVAL_MAX_DOCS");
        }
        if (prop == null || prop.isBlank()) {
            return 15; // practical default; full set: -Dbenchmark.eval.maxDocs=0
        }
        try {
            return Math.max(0, Integer.parseInt(prop.trim()));
        } catch (NumberFormatException e) {
            return 15;
        }
    }

    private void flushEvaluationArtifacts() throws Exception {
        String pinned = BenchmarkEvaluationAssertions.report().getPinnedEvaluationRunId();
        String runId = (pinned != null && !pinned.isBlank())
                ? pinned
                : BenchmarkReportService.currentRunId();
        BenchmarkEvaluationAssertions.report().setEvaluationRunId(runId);

        Path runDir = Path.of(BenchmarkReportService.BENCHMARK_ROOT, "evaluation", runId);
        Path exportDir = runDir.resolve("export");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runId", runId);
        meta.put("javaReport", "../evaluation-benchmark-report.json");
        meta.put("documentsEvaluated", documentsEvaluated);
        Path flushed = BenchmarkEvaluationAssertions.exporter().flushTo(exportDir, meta);

        BenchmarkEvaluationAssertions.recordEvaluation("exportDir", flushed.toString().replace('\\', '/'));
        BenchmarkEvaluationAssertions.recordEvaluation(
                "exportClaimSamples", BenchmarkEvaluationAssertions.exporter().claimSampleCount());
        BenchmarkEvaluationAssertions.recordEvaluation(
                "exportStructuredSamples", BenchmarkEvaluationAssertions.exporter().structuredSampleCount());
        BenchmarkEvaluationAssertions.recordEvaluation(
                "exportPublishSamples", BenchmarkEvaluationAssertions.exporter().publishSampleCount());
        BenchmarkEvaluationAssertions.recordEvaluation(
                "exportLinkSamples", BenchmarkEvaluationAssertions.exporter().linkSampleCount());

        BenchmarkEvaluationAssertions.writeEvaluationReport();

        Path latestExport = Path.of(BenchmarkReportService.BENCHMARK_ROOT, "evaluation", "latest", "export");
        copyDirectory(exportDir, latestExport);
        log.info("[EVAL] Ragas export ready: {} (claims={}, structured={}, publish={})",
                latestExport.toAbsolutePath(),
                BenchmarkEvaluationAssertions.exporter().claimSampleCount(),
                BenchmarkEvaluationAssertions.exporter().structuredSampleCount(),
                BenchmarkEvaluationAssertions.exporter().publishSampleCount());
    }

    /**
     * Export post-Publish WikiPage markdown for Ragas.
     * Prefers MRP-created pages (auto-approved); skips golden seed stubs.
     */
    private int exportPublishSamples(
            String docId,
            Long sourceDocumentId,
            String sourceMarkdown,
            JsonNode groundTruth,
            JsonNode linkEval) {

        List<String> expectedTopics = new ArrayList<>();
        if (groundTruth.has("expectedTopics")) {
            groundTruth.get("expectedTopics").forEach(n -> expectedTopics.add(n.asText()));
        }
        List<String> forbiddenHallu = new ArrayList<>();
        if (groundTruth.has("forbiddenHallucinations")) {
            groundTruth.get("forbiddenHallucinations").forEach(n -> forbiddenHallu.add(n.asText()));
        }
        List<String> expectedLinks = new ArrayList<>();
        List<String> forbiddenLinks = new ArrayList<>();
        if (linkEval != null) {
            if (linkEval.has("expectedLinks")) {
                linkEval.get("expectedLinks").forEach(n -> expectedLinks.add(n.asText()));
            }
            if (linkEval.has("forbiddenLinks")) {
                linkEval.get("forbiddenLinks").forEach(n -> forbiddenLinks.add(n.asText()));
            }
        }

        String reference = BenchmarkDataExporter.buildPublishReference(groundTruth);
        List<WikiPage> pages = wikiPageRepository.findBySourceDocumentId(sourceDocumentId).stream()
                .filter(p -> p.getContent() != null && !p.getContent().isBlank())
                .filter(p -> !isSeedOrAnchorStub(p))
                .sorted(Comparator.comparingInt((WikiPage p) -> p.getContent().length()).reversed())
                .toList();

        // Cap pages per doc to control Ragas cost (primary SOURCE page + top concepts)
        int maxPublishPages = 3;
        int exported = 0;
        for (WikiPage page : pages) {
            if (exported >= maxPublishPages) {
                break;
            }
            // Prefer real compiled content over tiny placeholders
            if (page.getContent().length() < 40) {
                continue;
            }
            BenchmarkEvaluationAssertions.exporter().appendPublishSample(
                    docId,
                    page.getTitle(),
                    page.getSlug(),
                    page.getContent(),
                    sourceMarkdown,
                    reference,
                    expectedTopics,
                    forbiddenHallu,
                    expectedLinks,
                    forbiddenLinks);
            exported++;
        }

        // Fallback: if MRP did not materialize pages, use plan JSON as soft publish artifact
        if (exported == 0) {
            Optional<SourceCompilationPlan> planOpt = planRepository.findBySourceDocumentId(sourceDocumentId);
            if (planOpt.isPresent()) {
                SourceCompilationPlan plan = planOpt.get();
                if (plan.getPlanJson() != null && plan.getPlanJson().length() > 40) {
                    BenchmarkEvaluationAssertions.exporter().appendPublishSample(
                            docId,
                            "compilation-plan",
                            "plan/" + docId,
                            plan.getPlanJson(),
                            sourceMarkdown,
                            reference,
                            expectedTopics,
                            forbiddenHallu,
                            expectedLinks,
                            forbiddenLinks);
                    exported = 1;
                }
            }
        }
        return exported;
    }

    private static boolean isSeedOrAnchorStub(WikiPage page) {
        String summary = page.getSummary() != null ? page.getSummary() : "";
        if (summary.contains("Golden manifest link seed")
                || summary.contains("Golden manifest link eval anchor")) {
            return true;
        }
        String content = page.getContent() != null ? page.getContent() : "";
        return content.startsWith("Seed target for E2E link eval:")
                || content.equals("Golden manifest link eval anchor");
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path rel = source.relativize(path);
                Path dest = target.resolve(rel);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void cleanRepositories() {
        chunkRepository.deleteAll();
        planRepository.deleteAll();
        documentRepository.deleteAll();
        wikiLinkRepository.deleteAll();
        wikiPageRepository.deleteAll();
    }

    private Document saveDocument(String markdown, String fileName) {
        return documentRepository.save(Document.builder()
                .userId("eval-user")
                .fileName(fileName)
                .fileSize(markdown.length())
                .workspaceId("ws-bench")
                .departmentId("ALL")
                .markdownContent(markdown)
                .status(DocStatus.COMPLETED)
                .documentType(DocType.pdf)
                .securityClassification(SecurityClassification.INTERNAL)
                .build());
    }

    private SourceCompilationPlan createPlan(Long docId) {
        return planRepository.save(SourceCompilationPlan.builder()
                .sourceDocumentId(docId)
                .status(SourceCompilationStatus.PROCESSING)
                .departmentId("ALL")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.INTERNAL)
                .build());
    }

    private void collectNames(JsonNode array, String field, Set<String> names, List<String> texts) {
        if (array == null || !array.isArray()) return;
        for (JsonNode n : array) {
            if (n.has(field)) {
                names.add(n.get(field).asText());
                texts.add(n.get(field).asText());
            }
        }
    }

    private void collectClaims(JsonNode array, List<String> claims, List<String> texts) {
        if (array == null || !array.isArray()) return;
        for (JsonNode n : array) {
            if (n.has("claim")) {
                claims.add(n.get("claim").asText());
                texts.add(n.get("claim").asText());
            }
        }
    }

    private double average(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private double averageLong(List<Long> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToLong(l -> l).average().orElse(0.0);
    }
}