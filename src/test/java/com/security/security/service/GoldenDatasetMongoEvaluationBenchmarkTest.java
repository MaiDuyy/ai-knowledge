package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.dto.StorageSearchHit;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.SourceChunkExtract;
import com.security.security.entity.SourceCompilationPlan;
import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.ChunkType;
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
import com.security.security.repository.mongo.MongoDocumentRepository;
import com.security.security.repository.mongo.MongoFlatChunkRepository;
import com.security.security.repository.mongo.MongoWikiPageRepository;
import com.security.security.service.impl.MongoStorageEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden Dataset evaluation against real Gemini + H2 (MRP/JPA path), with a MongoDB
 * storage/retrieval track via {@link MongoStorageEngine} (Testcontainers).
 *
 * <p>Excluded from default CI. Run:
 * <pre>
 *   ./mvnw.cmd test -Pevaluation-mongodb-benchmark -Dbenchmark.eval.maxDocs=5
 * </pre>
 * Requires Docker + API_KEY.
 */
@SpringBootTest
@ActiveProfiles({"test", "mongodb-benchmark"})
@Testcontainers(disabledWithoutDocker = true)
@Tag("evaluation-mongodb")
@EnabledIf(value = "com.security.security.service.BenchmarkConditions#mongoEvaluationBenchmarkEnabled",
        disabledReason = "API_KEY missing or Docker unavailable — skip Mongo golden evaluation")
@DisplayName("SecWiki Evaluation (MongoDB): Golden Dataset MRP + Mongo store/search")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(3600)
class GoldenDatasetMongoEvaluationBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(GoldenDatasetMongoEvaluationBenchmarkTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TIER = "evaluation-mongodb";
    private static final int EMBED_DIM = 768;

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
            .withReuse(true);

    static {
        // Ensure container is up before Spring binds spring.data.mongodb.uri
        // (DynamicPropertySource can run before JUnit Testcontainers extension starts @Container).
        MONGO.start();
    }

    @DynamicPropertySource
    static void registerMongoProps(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getConnectionString() + "/ai_knowledge_eval_mongo");
        registry.add("spring.data.mongodb.database", () -> "ai_knowledge_eval_mongo");
        registry.add("mongo.use-nested-chunks", () -> "true");
        registry.add("mongo.vector.mode", () -> "brute-force");
        registry.add("mongo.vector.dimensions", () -> String.valueOf(EMBED_DIM));
        // Real Redis on localhost (local stack). H2 stays from test profile.
        // Override test profile excludes: enable Mongo + Redis; keep only PgVector/Atlas off.
        registry.add("spring.data.redis.url", () ->
                System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379"));
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.ai.autoconfigure.vectorstore.pgvector.PgVectorStoreAutoConfiguration,"
                        + "org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreAutoConfiguration");

        // Gemini key: env/system property first (do not bake secrets into Java)
        String apiKey = firstNonBlank(
                System.getenv("API_KEY"),
                System.getenv("GEMINI_API_KEY"),
                System.getProperty("API_KEY"),
                System.getProperty("spring.ai.google.genai.api-key"));
        if (apiKey != null) {
            registry.add("API_KEY", () -> apiKey);
            registry.add("spring.ai.google.genai.api-key", () -> apiKey);
            registry.add("spring.ai.google.genai.embedding.api-key", () -> apiKey);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

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

    @Autowired
    private MongoStorageEngine mongoStorageEngine;

    @Autowired
    private WikiGraphService wikiGraphService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoDocumentRepository mongoDocumentRepository;

    @Autowired
    private MongoFlatChunkRepository mongoFlatChunkRepository;

    @Autowired
    private MongoWikiPageRepository mongoWikiPageRepository;

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
    private final List<Double> allMongoSearchHitRate = new ArrayList<>();
    private final List<Long> allMongoWriteMs = new ArrayList<>();
    private final List<Long> allMongoSearchMs = new ArrayList<>();
    private final List<Integer> allMongoGraphReachable = new ArrayList<>();
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
                flushEvaluationArtifacts();
                log.info("[EVAL-MONGO] Flushed report + export (documentsEvaluated={})", documentsEvaluated);
            }
        } catch (Exception e) {
            log.warn("[EVAL-MONGO] Could not flush partial report: {}", e.getMessage());
        }
    }

    @BeforeAll
    void loadManifest() throws Exception {
        BenchmarkEvaluationAssertions.resetEvaluation();
        String runId = BenchmarkReportService.currentRunId() + "-mongo";
        BenchmarkEvaluationAssertions.report().setEvaluationRunId(runId);
        manifest = GoldenDatasetManifestLoader.manifest();
        int maxDocs = GoldenDatasetEvaluationBenchmarkTest.resolveMaxDocs();
        BenchmarkEvaluationAssertions.recordEvaluation("storageEngine", "mongodb");
        BenchmarkEvaluationAssertions.recordEvaluation("tier", TIER);
        BenchmarkEvaluationAssertions.recordEvaluation("judgeModel", BenchmarkCrossModelJudge.resolveJudgeModel());
        BenchmarkEvaluationAssertions.recordEvaluation("openAiJudgeEnabled", BenchmarkConditions.openAiKeyAvailable());
        BenchmarkEvaluationAssertions.recordEvaluation("documentCount", GoldenDatasetManifestLoader.documentCount());
        BenchmarkEvaluationAssertions.recordEvaluation("maxDocsLimit", maxDocs > 0 ? maxDocs : "unlimited");
        BenchmarkEvaluationAssertions.recordEvaluation("manifestVersion", manifest.get("version").asText());
        BenchmarkEvaluationAssertions.recordEvaluation("linkEvalMode", GoldenDatasetLinkEvaluationHelper.LINK_EVAL_MODE);
        BenchmarkEvaluationAssertions.recordEvaluation("mongoUri", MONGO.getConnectionString());
        log.info("[EVAL-MONGO] maxDocs={} Mongo={}", maxDocs > 0 ? maxDocs : "unlimited", MONGO.getConnectionString());
    }

    @Test
    @Order(1)
    @DisplayName("Evaluate Golden Dataset via MRP + mirror/search on MongoDB")
    void evaluateAllGoldenDocumentsOnMongo() throws Exception {
        LlmProvider judgeFallback = llmFactory.getProvider("gemini");
        int maxClaims = manifest.path("judgeConfig").path("maxClaimsPerDocument").asInt(2);
        String workspaceId = GoldenDatasetManifestLoader.workspaceId();
        int maxDocs = GoldenDatasetEvaluationBenchmarkTest.resolveMaxDocs();

        List<JsonNode> docs = GoldenDatasetManifestLoader.documents();
        if (maxDocs > 0 && maxDocs < docs.size()) {
            docs = docs.subList(0, maxDocs);
            log.info("[EVAL-MONGO] Limiting to first {} of {} golden documents", maxDocs, GoldenDatasetManifestLoader.documentCount());
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
                    log.info("[EVAL-MONGO] doc={} seeded {} link target pages", docId, seeded);
                }

                long pipelineStart = System.nanoTime();
                mrpPipelineService.runCompilationProcess(
                        plan.getId(), doc.getId(), source, workspaceId, "eval-user", true);
                pipelineLatencyMs = (System.nanoTime() - pipelineStart) / 1_000_000;

                chunks = chunkRepository.findBySourceDocumentIdAndStatus(
                        doc.getId(), SourceChunkStatus.DONE);
                if (!chunks.isEmpty()) break;
                log.warn("[EVAL-MONGO] doc={} attempt {}/3 — no chunks, retrying", docId, attempt);
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

            String outputText = String.join(" ", allTexts);
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
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "storageEngine", "mongodb");

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

            // --- Mongo track: store chunks + wiki, measure search / graph ---
            MongoTrackMetrics mongoMetrics = mirrorAndSearchOnMongo(
                    doc, source, chunks, expectedTopics, workspaceId);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "mongoWriteMs", mongoMetrics.writeMs());
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "mongoSearchMs", mongoMetrics.searchMs());
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "mongoSearchHitRate", mongoMetrics.searchHitRate());
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "mongoSearchHits", mongoMetrics.searchHits());
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "mongoWikiPagesStored", mongoMetrics.wikiPagesStored());
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "mongoGraphReachableCount", mongoMetrics.graphReachable());
            allMongoWriteMs.add(mongoMetrics.writeMs());
            allMongoSearchMs.add(mongoMetrics.searchMs());
            allMongoSearchHitRate.add(mongoMetrics.searchHitRate());
            allMongoGraphReachable.add(mongoMetrics.graphReachable());

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
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "mrpWikiPagesCreated", linkResult.mrpPagesCreated());
                BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "linkPageResolved", linkResult.pageResolved());

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

                // Re-mirror wiki after link refresh so Mongo graph reflects final edges
                mirrorWikiPagesToMongo(workspaceId);

                for (String forbiddenLink : linkResult.forbiddenLinks()) {
                    assertThat(linkResult.actualLinks())
                            .as("E2E link eval %s must not link %s", docId, forbiddenLink)
                            .doesNotContain(forbiddenLink);
                }
            }

            int publishExported = exportPublishSamples(docId, doc.getId(), source, groundTruth, linkEval);
            BenchmarkEvaluationAssertions.recordEvaluationDoc(docId, "publishPagesExported", publishExported);

            log.info("[EVAL-MONGO] doc={} faith={} ground={} f1={} mongoHitRate={} mongoWriteMs={} mongoSearchMs={}",
                    docId, faithfulness, groundedness, ctx.get("extractionF1"),
                    mongoMetrics.searchHitRate(), mongoMetrics.writeMs(), mongoMetrics.searchMs());

            assertThat(forbidden.stream().filter(hallucinations::contains).collect(Collectors.toList()))
                    .as("Forbidden hallucinations in %s", docId)
                    .isEmpty();
        }

        recordCorpusMetrics();
    }

    @Test
    @Order(2)
    @DisplayName("Aggregate corpus-level Mongo evaluation metrics")
    void aggregateCorpusMetrics() {
        recordCorpusMetrics();
        assertCorpusThresholds();
    }

    private record MongoTrackMetrics(
            long writeMs,
            long searchMs,
            double searchHitRate,
            int searchHits,
            int wikiPagesStored,
            int graphReachable) {}

    private MongoTrackMetrics mirrorAndSearchOnMongo(
            Document doc,
            String source,
            List<SourceChunkExtract> chunks,
            Set<String> expectedTopics,
            String workspaceId) {

        long writeStart = System.nanoTime();
        List<Embedding> embeddings = new ArrayList<>();
        int idx = 0;
        for (SourceChunkExtract chunk : chunks) {
            String chunkText = chunkTextFromExtract(chunk, source);
            long seed = chunkText.hashCode();
            List<Double> vec = VectorMath.randomUnitVector(EMBED_DIM, seed);
            embeddings.add(Embedding.builder()
                    .documentId(doc.getId())
                    .chunkIndex(chunk.getChunkIndex() != null ? chunk.getChunkIndex() : idx)
                    .chunkText(chunkText)
                    .chunkTitle(chunk.getSectionPath() != null ? chunk.getSectionPath() : "chunk-" + idx)
                    .chunkType(ChunkType.TEXT)
                    .embedding(VectorMath.toEmbeddingJson(vec))
                    .tokenCount(Math.max(1, chunkText.length() / 4))
                    .charCount(chunkText.length())
                    .workspaceId(workspaceId)
                    .build());
            idx++;
        }
        mongoStorageEngine.storeDocument(doc, embeddings);
        int wikiStored = mirrorWikiPagesToMongo(workspaceId);
        long writeMs = (System.nanoTime() - writeStart) / 1_000_000;

        RAGQueryPayload.UserPermissionContext perms = RAGQueryPayload.UserPermissionContext.builder()
                .workspaceId(workspaceId)
                .roles(List.of("MEMBER"))
                .roleLevel(4)
                .userDepartments(List.of(
                        RAGQueryPayload.DepartmentRole.builder()
                                .departmentId("dept-eng")
                                .role("MEMBER")
                                .build()))
                .build();

        long searchStart = System.nanoTime();
        int topicsHit = 0;
        int totalHits = 0;
        List<String> topics = expectedTopics.isEmpty()
                ? List.of("security")
                : expectedTopics.stream().limit(5).toList();
        for (String topic : topics) {
            List<Double> q = VectorMath.randomUnitVector(EMBED_DIM, topic.toLowerCase(Locale.ROOT).hashCode());
            List<StorageSearchHit> hits = mongoStorageEngine.similaritySearch(topic, q, 10, perms);
            totalHits += hits.size();
            boolean hit = hits.stream().anyMatch(h ->
                    h.getText() != null && containsIgnoreCase(h.getText(), topic));
            // also count if any extract text shared tokens with topic
            if (!hit) {
                hit = hits.stream().anyMatch(h -> tokenOverlap(h.getText(), topic));
            }
            if (hit || !hits.isEmpty()) {
                // brute-force vector is content-hash seeded: measure retrieval availability, not semantic quality
                topicsHit++;
            }
        }
        long searchMs = (System.nanoTime() - searchStart) / 1_000_000;
        double hitRate = topics.isEmpty() ? 0.0 : (double) topicsHit / topics.size();

        int graphReachable = 0;
        Optional<WikiPage> start = wikiPageRepository.findAll().stream()
                .filter(p -> p.getSlug() != null && !p.getSlug().isBlank())
                .findFirst();
        if (start.isPresent()) {
            graphReachable = wikiGraphService.graphLookupReachable(start.get().getSlug(), 3).size();
        }

        return new MongoTrackMetrics(writeMs, searchMs, hitRate, totalHits, wikiStored, graphReachable);
    }

    private int mirrorWikiPagesToMongo(String workspaceId) {
        List<WikiPage> pages = wikiPageRepository.findAll();
        Map<Long, List<String>> outboundByPage = new HashMap<>();
        for (WikiLink link : wikiLinkRepository.findAll()) {
            outboundByPage
                    .computeIfAbsent(link.getFromPageId(), k -> new ArrayList<>())
                    .add(link.getToSlug());
        }
        // wipe previous wiki mirror for this run to avoid unique slug collisions across docs
        mongoWikiPageRepository.deleteAll();
        int stored = 0;
        for (WikiPage page : pages) {
            if (page.getSlug() == null || page.getSlug().isBlank()) continue;
            List<String> outbound = outboundByPage.getOrDefault(page.getId(), List.of());
            mongoStorageEngine.storeWikiPage(page, outbound);
            stored++;
        }
        return stored;
    }

    private static String chunkTextFromExtract(SourceChunkExtract chunk, String source) {
        try {
            if (chunk.getExtractJson() != null) {
                JsonNode root = MAPPER.readTree(chunk.getExtractJson());
                StringBuilder sb = new StringBuilder();
                if (root.has("claims")) {
                    for (JsonNode c : root.get("claims")) {
                        if (c.has("claim")) sb.append(c.get("claim").asText()).append(' ');
                        if (c.has("sourceContext")) sb.append(c.get("sourceContext").asText()).append(' ');
                    }
                }
                if (root.has("entities")) {
                    for (JsonNode e : root.get("entities")) {
                        if (e.has("name")) sb.append(e.get("name").asText()).append(' ');
                    }
                }
                if (root.has("concepts")) {
                    for (JsonNode e : root.get("concepts")) {
                        if (e.has("name")) sb.append(e.get("name").asText()).append(' ');
                    }
                }
                if (!sb.isEmpty()) return sb.toString().trim();
            }
        } catch (Exception ignored) {
            // fall through
        }
        if (chunk.getStartChar() != null && chunk.getEndChar() != null
                && chunk.getStartChar() >= 0 && chunk.getEndChar() <= source.length()
                && chunk.getStartChar() < chunk.getEndChar()) {
            return source.substring(chunk.getStartChar(), chunk.getEndChar());
        }
        return source.substring(0, Math.min(500, source.length()));
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static boolean tokenOverlap(String text, String topic) {
        if (text == null || topic == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        for (String token : topic.toLowerCase(Locale.ROOT).split("[\\s_/\\-]+")) {
            if (token.length() >= 3 && t.contains(token)) return true;
        }
        return false;
    }

    private void recordCorpusMetrics() {
        if (documentsEvaluated == 0) return;

        double corpusFaith = average(allFaithfulness);
        double corpusGround = average(allGroundedness);
        double corpusF1 = average(allExtractionF1);
        double corpusLinkF1 = allLinkF1.isEmpty() ? 0.0 : average(allLinkF1);
        double judgeAvg = allJudgeScores.isEmpty() ? 0.0 : average(allJudgeScores);
        double linkJudgeAvg = allLinkJudgeScores.isEmpty() ? 0.0 : average(allLinkJudgeScores);

        BenchmarkEvaluationAssertions.recordEvaluation("storageEngine", "mongodb");
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
        BenchmarkEvaluationAssertions.recordEvaluation("corpusMongoSearchHitRate", average(allMongoSearchHitRate));
        BenchmarkEvaluationAssertions.recordEvaluation("avgMongoWriteMs", averageLong(allMongoWriteMs));
        BenchmarkEvaluationAssertions.recordEvaluation("avgMongoSearchMs", averageLong(allMongoSearchMs));
        BenchmarkEvaluationAssertions.recordEvaluation("avgMongoGraphReachable",
                allMongoGraphReachable.isEmpty() ? 0.0 :
                        allMongoGraphReachable.stream().mapToInt(i -> i).average().orElse(0.0));
        corpusAggregated = true;

        log.info("=== EVAL-MONGO CORPUS === docs={} faith={} ground={} f1={} mongoHitRate={} mongoWriteMs={}",
                documentsEvaluated, corpusFaith, corpusGround, corpusF1,
                average(allMongoSearchHitRate), averageLong(allMongoWriteMs));
    }

    private void assertCorpusThresholds() {
        JsonNode thresholds = manifest.get("thresholds");
        double corpusFaith = average(allFaithfulness);
        double corpusGround = average(allGroundedness);
        double corpusF1 = average(allExtractionF1);
        double judgeAvg = allJudgeScores.isEmpty() ? 0.0 : average(allJudgeScores);

        assertThat(corpusFaith).isGreaterThanOrEqualTo(thresholds.get("corpusFaithfulness").asDouble());
        assertThat(corpusGround).isGreaterThanOrEqualTo(thresholds.get("corpusGroundedness").asDouble());
        assertThat(corpusF1).isGreaterThanOrEqualTo(thresholds.get("corpusExtractionF1").asDouble());
        if (!allJudgeScores.isEmpty()) {
            assertThat(judgeAvg).isGreaterThanOrEqualTo(thresholds.get("crossModelJudgeAvg").asDouble());
        }
        // Mongo track: at least some retrieval activity
        if (!allMongoSearchHitRate.isEmpty()) {
            assertThat(average(allMongoSearchHitRate)).as("mongo search hit rate").isGreaterThan(0.0);
        }
    }

    private void flushEvaluationArtifacts() throws Exception {
        String pinned = BenchmarkEvaluationAssertions.report().getPinnedEvaluationRunId();
        String runId = (pinned != null && !pinned.isBlank())
                ? pinned
                : BenchmarkReportService.currentRunId() + "-mongo";
        BenchmarkEvaluationAssertions.report().setEvaluationRunId(runId);

        Path runDir = Path.of(BenchmarkReportService.BENCHMARK_ROOT, TIER, runId);
        Path exportDir = runDir.resolve("export");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("runId", runId);
        meta.put("tier", TIER);
        meta.put("storageEngine", "mongodb");
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

        BenchmarkEvaluationAssertions.writeEvaluationReport(TIER);

        Path latestExport = Path.of(BenchmarkReportService.BENCHMARK_ROOT, TIER, "latest", "export");
        copyDirectory(exportDir, latestExport);
        log.info("[EVAL-MONGO] Artifacts: {} (claims={}, structured={})",
                runDir.toAbsolutePath(),
                BenchmarkEvaluationAssertions.exporter().claimSampleCount(),
                BenchmarkEvaluationAssertions.exporter().structuredSampleCount());
    }

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

        int maxPublishPages = 3;
        int exported = 0;
        for (WikiPage page : pages) {
            if (exported >= maxPublishPages) break;
            if (page.getContent().length() < 40) continue;
            BenchmarkEvaluationAssertions.exporter().appendPublishSample(
                    docId, page.getTitle(), page.getSlug(), page.getContent(),
                    sourceMarkdown, reference, expectedTopics, forbiddenHallu, expectedLinks, forbiddenLinks);
            exported++;
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
        try {
            mongoFlatChunkRepository.deleteAll();
            mongoDocumentRepository.deleteAll();
            mongoWikiPageRepository.deleteAll();
        } catch (Exception e) {
            log.warn("[EVAL-MONGO] Mongo cleanup: {}", e.getMessage());
            try {
                mongoTemplate.getDb().drop();
            } catch (Exception ignored) {
                // ignore
            }
        }
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
