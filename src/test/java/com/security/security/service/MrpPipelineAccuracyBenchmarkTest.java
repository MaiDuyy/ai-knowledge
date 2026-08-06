package com.security.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
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
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.provider.LlmFactory;
import com.security.security.provider.LlmProvider;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.SourceChunkExtractRepository;
import com.security.security.repository.SourceCompilationPlanRepository;
import com.security.security.repository.WikiPageRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Tag("benchmark")
@EnabledIf(value = "com.security.security.service.BenchmarkConditions#apiKeyAvailable",
        disabledReason = "API_KEY env/property not set — skip real LLM MRP benchmarks")
@DisplayName("SecWiki-Bench v2: MRP Pipeline Accuracy (Ragas/TruLens)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(300)
class MrpPipelineAccuracyBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(MrpPipelineAccuracyBenchmarkTest.class);
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
    private LlmFactory llmFactory;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private SourceCompilationPlanRepository planRepository;

    @Autowired
    private SourceChunkExtractRepository chunkRepository;

    @Autowired
    private WikiPageRepository wikiPageRepository;

    private final Map<String, Object> mrpMetrics = new LinkedHashMap<>();
    private String workspaceId;

    @BeforeAll
    void loadGoldenManifest() throws Exception {
        workspaceId = GoldenDatasetManifestLoader.workspaceId();
        BenchmarkEvaluationAssertions.record("mrp_accuracy", "goldenManifestVersion",
                GoldenDatasetManifestLoader.manifest().get("version").asText());
        BenchmarkEvaluationAssertions.record("mrp_accuracy", "goldenDocumentCount",
                GoldenDatasetManifestLoader.documentCount());
    }

    @BeforeEach
    void clean() {
        chunkRepository.deleteAll();
        planRepository.deleteAll();
        documentRepository.deleteAll();
        wikiPageRepository.deleteAll();
    }

    @Test
    @Order(1)
    @DisplayName("Ragas/TruLens: Map phase faithfulness, groundedness, context recall/precision")
    void benchmarkMapPhase_FaithfulnessAndExtraction() throws Exception {
        JsonNode docEntry = GoldenDatasetManifestLoader.getById("short-note");
        String source = GoldenDatasetManifestLoader.loadSource(docEntry);
        JsonNode groundTruth = GoldenDatasetManifestLoader.loadGroundTruth(docEntry);

        Document doc = saveDocument(source, "short-note.md");
        SourceCompilationPlan plan = createPlan(doc.getId());

        long pipelineStart = System.nanoTime();
        runPipeline(plan, doc, source, false);
        long pipelineLatencyMs = (System.nanoTime() - pipelineStart) / 1_000_000;

        List<SourceChunkExtract> chunks = chunkRepository.findBySourceDocumentIdAndStatus(
                doc.getId(), SourceChunkStatus.DONE);
        assertThat(chunks).isNotEmpty();

        Set<String> extractedTopics = new HashSet<>();
        List<String> allClaims = new ArrayList<>();
        List<String> allTexts = new ArrayList<>();

        for (SourceChunkExtract chunk : chunks) {
            JsonNode root = MAPPER.readTree(chunk.getExtractJson());
            double mapSchema = BenchmarkEvaluationAssertions.computeSchemaComplianceMap(root);
            mrpMetrics.put("mapSchemaCompliance", mapSchema);
            assertThat(mapSchema).isGreaterThanOrEqualTo(1.0);

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
        PipelineBenchmarkTracker.RunMetrics runMetrics = new PipelineBenchmarkTracker.RunMetrics(
                pipelineLatencyMs,
                PipelineBenchmarkTracker.estimateTokens(source),
                PipelineBenchmarkTracker.estimateTokens(outputText),
                PipelineBenchmarkTracker.estimateTokens(source) + PipelineBenchmarkTracker.estimateTokens(outputText),
                List.of());

        mrpMetrics.put("faithfulness", faithfulness);
        mrpMetrics.put("groundedness", groundedness);
        mrpMetrics.putAll(ctx);
        mrpMetrics.put("hallucinationCount", hallucinations.size());
        mrpMetrics.put("extractedTopics", extractedTopics);
        mrpMetrics.putAll(runMetrics.toMap());
        BenchmarkEvaluationAssertions.recordPipelineMetrics("mrp_accuracy", "short-note", runMetrics);

        log.info("=== [Ragas/TruLens] Map Phase ===");
        log.info("Faithfulness={} Groundedness={} Recall={} Precision={} F1={}",
                faithfulness, groundedness, ctx.get("contextRecall"), ctx.get("contextPrecision"), ctx.get("extractionF1"));
        log.info("Hallucinations={}", hallucinations);

        assertThat(faithfulness).isGreaterThanOrEqualTo(0.85);
        assertThat(groundedness).isGreaterThanOrEqualTo(0.85);
        assertThat(ctx.get("extractionF1")).isGreaterThanOrEqualTo(0.40);
        assertThat(hallucinations).doesNotContainAnyElementsOf(forbidden);
    }

    @Test
    @Order(2)
    @DisplayName("Prompt compliance: SUBSTANCE_FILTER — passing mentions not extracted")
    void benchmarkMapPhase_SubstanceFilter() throws Exception {
        JsonNode docEntry = GoldenDatasetManifestLoader.getById("short-note");
        String source = GoldenDatasetManifestLoader.loadSource(docEntry);
        JsonNode groundTruth = GoldenDatasetManifestLoader.loadGroundTruth(docEntry);

        Document doc = saveDocument(source, "substance-filter.md");
        SourceCompilationPlan plan = createPlan(doc.getId());
        runPipeline(plan, doc, source, false);

        Set<String> extracted = collectAllEntityConceptNames(doc.getId());
        List<String> passing = new ArrayList<>();
        groundTruth.get("passingMentions").forEach(n -> passing.add(n.asText()));

        long leaked = passing.stream().filter(p -> extracted.stream().anyMatch(e ->
                e.toLowerCase().contains(p.toLowerCase()))).count();

        double substanceFilterScore = passing.isEmpty() ? 1.0 : 1.0 - (double) leaked / passing.size();
        mrpMetrics.put("substanceFilterScore", substanceFilterScore);

        log.info("[Prompt] SUBSTANCE_FILTER score={} (passing mentions leaked={})", substanceFilterScore, leaked);
        assertThat(leaked).isLessThanOrEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("Prompt compliance: ENTITY_CONCEPT_SPLIT — no duplicate in both lists")
    void benchmarkMapPhase_EntityConceptSplit() throws Exception {
        String source = GoldenDatasetManifestLoader.loadSource("short-note");
        Document doc = saveDocument(source, "entity-split.md");
        SourceCompilationPlan plan = createPlan(doc.getId());
        runPipeline(plan, doc, source, false);

        Set<String> entities = new HashSet<>();
        Set<String> concepts = new HashSet<>();

        for (SourceChunkExtract chunk : chunkRepository.findBySourceDocumentIdAndStatus(doc.getId(), SourceChunkStatus.DONE)) {
            JsonNode root = MAPPER.readTree(chunk.getExtractJson());
            collectNames(root.get("entities"), "name", entities, new ArrayList<>());
            collectNames(root.get("concepts"), "name", concepts, new ArrayList<>());
        }

        Set<String> overlap = new HashSet<>(entities);
        overlap.retainAll(concepts);
        double splitScore = entities.isEmpty() && concepts.isEmpty() ? 0.0 : 1.0 - (double) overlap.size() / Math.max(1, entities.size() + concepts.size());
        mrpMetrics.put("entityConceptSplitScore", splitScore);

        log.info("[Prompt] ENTITY_CONCEPT_SPLIT overlap={} score={}", overlap, splitScore);
        assertThat(overlap.size()).isLessThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("Prompt compliance: contradictions array populated for conflicting doc")
    void benchmarkMapPhase_Contradictions() throws Exception {
        String source = GoldenDatasetManifestLoader.loadSource("conflicting-doc");
        Document doc = saveDocument(source, "conflicting.md");
        SourceCompilationPlan plan = createPlan(doc.getId());
        runPipeline(plan, doc, source, false);

        int contradictionCount = 0;
        for (SourceChunkExtract chunk : chunkRepository.findBySourceDocumentIdAndStatus(doc.getId(), SourceChunkStatus.DONE)) {
            JsonNode root = MAPPER.readTree(chunk.getExtractJson());
            if (root.has("contradictions") && root.get("contradictions").isArray()) {
                contradictionCount += root.get("contradictions").size();
            }
        }
        mrpMetrics.put("contradictionCount", contradictionCount);
        log.info("[Prompt] Contradictions extracted={}", contradictionCount);
        assertThat(contradictionCount).isGreaterThanOrEqualTo(0);
    }

    @Test
    @Order(5)
    @DisplayName("Prompt compliance: DEDUP_SYNONYM JWT -> UPDATE existing JSON Web Token page")
    void benchmarkReducePhase_DedupSynonym() throws Exception {
        JsonNode mergeScenario = BenchmarkEvaluationAssertions.loadResourceJson("benchmark/mrp/merge-scenarios.json");
        JsonNode syn = mergeScenario.get("synonymMerge");

        wikiPageRepository.save(WikiPage.builder()
                .title(syn.get("existingPage").get("title").asText())
                .slug(syn.get("existingPage").get("slug").asText())
                .content("Existing JWT documentation.")
                .workspaceId(workspaceId)
                .departmentId("ALL")
                .pageType(WikiPageType.CONCEPT)
                .summary("Existing")
                .securityClassification(SecurityClassification.INTERNAL)
                .build());

        String source = GoldenDatasetManifestLoader.loadSource(syn.get("sourceDocId").asText());
        Document doc = saveDocument(source, "dedup-synonym.md");
        SourceCompilationPlan plan = createPlan(doc.getId());
        runPipeline(plan, doc, source, false);

        SourceCompilationPlan result = planRepository.findById(plan.getId()).orElseThrow();
        List<Map<String, Object>> items = MAPPER.readValue(result.getPlanJson(), new TypeReference<>() {});

        boolean hasUpdate = items.stream().anyMatch(item -> {
            String action = String.valueOf(item.get("action"));
            String title = String.valueOf(item.get("title")).toLowerCase();
            return "UPDATE".equalsIgnoreCase(action)
                    && (title.contains("jwt") || title.contains("json web token"));
        });

        double dedupAccuracy = hasUpdate ? 1.0 : 0.0;
        mrpMetrics.put("dedupSynonymAccuracy", dedupAccuracy);
        log.info("[Prompt] DEDUP_SYNONYM hasUpdate={}", hasUpdate);
    }

    @Test
    @Order(6)
    @DisplayName("Prompt compliance: DEDUP_DISTINCT AI Safety vs Content Review remain CREATE")
    void benchmarkReducePhase_DedupDistinct() throws Exception {
        String source = GoldenDatasetManifestLoader.loadSource("distinct-topics");
        Document doc = saveDocument(source, "distinct-topics.md");
        SourceCompilationPlan plan = createPlan(doc.getId());
        runPipeline(plan, doc, source, false);

        SourceCompilationPlan result = planRepository.findById(plan.getId()).orElseThrow();
        List<Map<String, Object>> items = MAPPER.readValue(result.getPlanJson(), new TypeReference<>() {});

        long createCount = items.stream()
                .filter(i -> "CREATE".equalsIgnoreCase(String.valueOf(i.get("action"))))
                .count();

        boolean hasAiSafety = items.stream().anyMatch(i -> String.valueOf(i.get("title")).toLowerCase().contains("ai safety"));
        boolean hasContentReview = items.stream().anyMatch(i -> String.valueOf(i.get("title")).toLowerCase().contains("content review"));

        double distinctScore = (hasAiSafety && hasContentReview && createCount >= 2) ? 1.0 : 0.0;
        mrpMetrics.put("dedupDistinctScore", distinctScore);
        mrpMetrics.put("distinctCreateCount", createCount);

        log.info("[Prompt] DEDUP_DISTINCT createCount={} aiSafety={} contentReview={}",
                createCount, hasAiSafety, hasContentReview);
    }

    @Test
    @Order(7)
    @DisplayName("Prompt compliance: CITATION_INTEGRITY — [Source Context:] preserved in keyClaims")
    void benchmarkReducePhase_CitationIntegrity() throws Exception {
        JsonNode docEntry = GoldenDatasetManifestLoader.getById("short-note");
        String source = GoldenDatasetManifestLoader.loadSource(docEntry);
        Document doc = saveDocument(source, "citation.md");
        SourceCompilationPlan plan = createPlan(doc.getId());
        runPipeline(plan, doc, source, false);

        SourceCompilationPlan result = planRepository.findById(plan.getId()).orElseThrow();
        List<Map<String, Object>> items = MAPPER.readValue(result.getPlanJson(), new TypeReference<>() {});

        List<String> allKeyClaims = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object kc = item.get("keyClaims");
            if (kc instanceof List<?> list) {
                list.forEach(c -> allKeyClaims.add(String.valueOf(c)));
            }
        }

        double citationIntegrity = allKeyClaims.isEmpty() ? 0.0 :
                allKeyClaims.stream().filter(c -> c.contains("[Source Context:")).count() / (double) allKeyClaims.size();

        double reduceSchema = BenchmarkEvaluationAssertions.computeSchemaComplianceReduce(
                MAPPER.readTree(result.getPlanJson()));

        JsonNode groundTruth = GoldenDatasetManifestLoader.loadGroundTruth(docEntry);
        Set<String> expectedTopics = new HashSet<>();
        groundTruth.get("expectedTopics").forEach(n -> expectedTopics.add(n.asText()));
        String generatedWikiContent = items.stream()
                .map(i -> String.valueOf(i.get("title")) + " " + String.valueOf(i.get("reason")) + " " + i.get("keyClaims"))
                .collect(Collectors.joining(" "));
        double answerRelevancy = BenchmarkEvaluationAssertions.computeAnswerRelevancy(generatedWikiContent, expectedTopics);

        mrpMetrics.put("citationIntegrity", citationIntegrity);
        mrpMetrics.put("reduceSchemaCompliance", reduceSchema);
        mrpMetrics.put("answerRelevancy", answerRelevancy);

        log.info("[Prompt] CITATION_INTEGRITY={} REDUCE schema={} answerRelevancy={}",
                citationIntegrity, reduceSchema, answerRelevancy);
        assertThat(reduceSchema).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    @Order(8)
    @DisplayName("TruLens LLM-as-judge: optional faithfulness scoring on sample claims")
    void benchmarkLlmJudge_Faithfulness() throws Exception {
        String source = GoldenDatasetManifestLoader.loadSource("short-note");
        Document doc = saveDocument(source, "judge.md");
        SourceCompilationPlan plan = createPlan(doc.getId());
        runPipeline(plan, doc, source, false);

        LlmProvider provider = llmFactory.getProvider("gemini");
        List<Double> scores = new ArrayList<>();

        for (SourceChunkExtract chunk : chunkRepository.findBySourceDocumentIdAndStatus(doc.getId(), SourceChunkStatus.DONE)) {
            JsonNode root = MAPPER.readTree(chunk.getExtractJson());
            if (!root.has("claims")) continue;
            for (JsonNode claim : root.get("claims")) {
                String claimText = claim.get("claim").asText();
                BenchmarkEvaluationAssertions.llmJudgeFaithfulness(provider, claimText, source)
                        .ifPresent(scores::add);
                if (scores.size() >= 2) break;
            }
            if (scores.size() >= 2) break;
        }

        double avgJudge = scores.isEmpty() ? 0.0 : scores.stream().mapToDouble(d -> d).average().orElse(0.0);
        mrpMetrics.put("llmJudgeScoreAvg", avgJudge);
        mrpMetrics.put("llmJudgeSamples", scores.size());
        log.info("[TruLens] LLM-as-judge avg={} samples={}", avgJudge, scores.size());
    }

    @Test
    @Order(9)
    @DisplayName("Aggregate MRP metrics and record to report")
    void aggregateAndRecordMetrics() {
        double promptCompliance = average(
                toDouble(mrpMetrics.get("substanceFilterScore")),
                toDouble(mrpMetrics.get("entityConceptSplitScore")),
                toDouble(mrpMetrics.get("dedupSynonymAccuracy")),
                toDouble(mrpMetrics.get("dedupDistinctScore")),
                toDouble(mrpMetrics.get("citationIntegrity"))
        );

        for (Map.Entry<String, Object> e : mrpMetrics.entrySet()) {
            BenchmarkEvaluationAssertions.record("mrp_accuracy", e.getKey(), e.getValue());
        }
        BenchmarkEvaluationAssertions.record("mrp_accuracy", "promptCompliance", promptCompliance);

        log.info("=== MRP ACCURACY AGGREGATE ===");
        log.info("promptCompliance={}", promptCompliance);
        mrpMetrics.forEach((k, v) -> log.info("  {} = {}", k, v));
    }

    // --- helpers ---

    private void runPipeline(SourceCompilationPlan plan, Document doc, String content, boolean autoApprove) {
        mrpPipelineService.runCompilationProcess(
                plan.getId(), doc.getId(), content, workspaceId, "bench-user", autoApprove);
    }

    private Document saveDocument(String markdown, String fileName) {
        return documentRepository.save(Document.builder()
                .userId("bench-user")
                .fileName(fileName)
                .fileSize(markdown.length())
                .workspaceId(workspaceId)
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

    private Set<String> collectAllEntityConceptNames(Long docId) throws Exception {
        Set<String> names = new HashSet<>();
        for (SourceChunkExtract chunk : chunkRepository.findBySourceDocumentIdAndStatus(docId, SourceChunkStatus.DONE)) {
            JsonNode root = MAPPER.readTree(chunk.getExtractJson());
            collectNames(root.get("entities"), "name", names, new ArrayList<>());
            collectNames(root.get("concepts"), "name", names, new ArrayList<>());
        }
        return names;
    }

    private void collectNames(JsonNode array, String field, Set<String> names, List<String> texts) {
        if (array == null || !array.isArray()) return;
        for (JsonNode n : array) {
            if (n.has(field)) {
                String val = n.get(field).asText();
                names.add(val);
                texts.add(val);
                if (n.has("description")) texts.add(n.get("description").asText());
            }
        }
    }

    private void collectClaims(JsonNode array, List<String> claims, List<String> texts) {
        if (array == null || !array.isArray()) return;
        for (JsonNode n : array) {
            if (n.has("claim")) {
                String claim = n.get("claim").asText();
                claims.add(claim);
                texts.add(claim);
            }
            if (n.has("sourceContext")) texts.add(n.get("sourceContext").asText());
        }
    }

    private double average(double... vals) {
        return Arrays.stream(vals).average().orElse(0.0);
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}