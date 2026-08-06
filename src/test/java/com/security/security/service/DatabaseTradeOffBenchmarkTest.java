package com.security.security.service;

import com.security.security.dto.StorageSearchHit;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.ChunkType;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.enumeration.WikiPageType;
import com.security.security.service.impl.MongoStorageEngine;
import com.security.security.service.impl.PostgresStorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Side-by-side PostgreSQL vs MongoDB trade-off benchmark using Testcontainers.
 * Excluded from default CI via {@code @Tag("mongodb-benchmark")}.
 * <p>
 * Run: {@code ./mvnw.cmd test -Pmongodb-benchmark} (Docker required).
 */
@SpringBootTest
@ActiveProfiles({"test", "mongodb-benchmark"})
@Testcontainers(disabledWithoutDocker = true)
@Tag("mongodb-benchmark")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Database Trade-Off: PostgreSQL vs MongoDB")
@EnabledIf("dockerAvailable")
class DatabaseTradeOffBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTradeOffBenchmarkTest.class);
    private static final String WORKSPACE = "ws-tradeoff";
    private static final int DIM = 768;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("ai_knowledge_bench")
            .withUsername("bench")
            .withPassword("bench");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongo:7.0"))
            .withReuse(true);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.ai.chat.memory.repository.jdbc.initialize-schema", () -> "never");

        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.database", () -> "ai_knowledge_bench");
        registry.add("mongo.use-nested-chunks", () -> "true");
        registry.add("mongo.vector.mode", () -> "brute-force");

        // Re-enable Mongo auto-config for this suite (overrides application-test excludes)
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                        + "org.springframework.ai.autoconfigure.vectorstore.pgvector.PgVectorStoreAutoConfiguration,"
                        + "org.springframework.ai.vectorstore.mongodb.autoconfigure.MongoDBAtlasVectorStoreAutoConfiguration");
    }

    static boolean dockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private io.nats.client.Connection natsConnection;

    @Autowired
    private PostgresStorageEngine postgresStorageEngine;

    @Autowired
    private MongoStorageEngine mongoStorageEngine;

    @Autowired
    private WikiGraphService wikiGraphService;

    @Autowired
    private BenchmarkReportService benchmarkReportService;

    @BeforeEach
    void resetReport() {
        benchmarkReportService.resetDatabaseTradeOff();
    }

    @Test
    @DisplayName("Write + similarity search + graph traversal (small scale)")
    void smallScaleTradeOff() throws Exception {
        Scale scale = Scale.SMALL;
        // Cap for CI-friendly default; full scale via -Dbenchmark.full=true
        boolean full = Boolean.parseBoolean(System.getProperty("benchmark.full", "false"));
        int docs = full ? scale.documents : Math.min(40, scale.documents);
        int chunksPerDoc = scale.chunksPerDoc();
        int wikiPages = full ? scale.wikiPages : Math.min(80, scale.wikiPages);

        RAGQueryPayload.UserPermissionContext member = memberContext();
        RAGQueryPayload.UserPermissionContext guest = guestContext();
        List<Double> queryVec = VectorMath.randomUnitVector(DIM, 7L);

        // --- PostgreSQL write ---
        long pgWriteStart = System.nanoTime();
        concurrentSeed(postgresStorageEngine, docs, chunksPerDoc, wikiPages);
        long pgWriteMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pgWriteStart);

        // --- MongoDB write ---
        long mongoWriteStart = System.nanoTime();
        concurrentSeed(mongoStorageEngine, docs, chunksPerDoc, wikiPages);
        long mongoWriteMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - mongoWriteStart);

        int searchIters = 15;
        LatencyStats pgSearch = measureSearch(postgresStorageEngine, queryVec, member, searchIters);
        LatencyStats mongoSearch = measureSearch(mongoStorageEngine, queryVec, member, searchIters);

        // RBAC: guest must not see INTERNAL-only corpus (or only PUBLIC — we seeded INTERNAL)
        List<StorageSearchHit> guestHitsPg = postgresStorageEngine.similaritySearch("q", queryVec, 20, guest);
        List<StorageSearchHit> guestHitsMongo = mongoStorageEngine.similaritySearch("q", queryVec, 20, guest);
        assertThat(guestHitsPg).isEmpty();
        assertThat(guestHitsMongo).isEmpty();

        // Graph traversal
        long pgGraphStart = System.nanoTime();
        List<String> pgReachable = wikiGraphService.jgraphtReachable("page-0", 3);
        long pgGraphMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - pgGraphStart);

        long mongoGraphStart = System.nanoTime();
        List<String> mongoReachable = wikiGraphService.graphLookupReachable("page-0", 3);
        long mongoGraphMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - mongoGraphStart);

        assertThat(pgReachable).isNotEmpty();
        assertThat(mongoReachable).isNotEmpty();

        // Record + export report
        recordEngine("postgres", docs, pgWriteMs, pgSearch, pgGraphMs, pgReachable.size(), "JGraphT-in-memory-BFS");
        recordEngine("mongodb", docs, mongoWriteMs, mongoSearch, mongoGraphMs, mongoReachable.size(), "MongoDB-$graphLookup");
        benchmarkReportService.recordDatabaseTradeOff("modeling", "postgres",
                "relational tables documents+embeddings+wiki_pages+wiki_links");
        benchmarkReportService.recordDatabaseTradeOff("modeling", "mongodb",
                "unified nested MongoDocument.chunks + MongoWikiPage.outboundSlugs");
        benchmarkReportService.recordDatabaseTradeOff("meta", "docs", docs);
        benchmarkReportService.recordDatabaseTradeOff("meta", "chunksPerDoc", chunksPerDoc);
        benchmarkReportService.recordDatabaseTradeOff("meta", "wikiPages", wikiPages);
        benchmarkReportService.recordDatabaseTradeOff("meta", "vectorMode", "brute-force-cosine-768");

        benchmarkReportService.writeDatabaseTradeOffReport();
        assertThat(benchmarkReportService.getLastDatabaseTradeOffRunDir()).isNotBlank();

        log.info("=== Trade-off results ===");
        log.info("Postgres writeMs={} searchAvg={}p95={} graphMs={} reachable={}",
                pgWriteMs, pgSearch.avg, pgSearch.p95, pgGraphMs, pgReachable.size());
        log.info("MongoDB  writeMs={} searchAvg={}p95={} graphMs={} reachable={}",
                mongoWriteMs, mongoSearch.avg, mongoSearch.p95, mongoGraphMs, mongoReachable.size());
    }

    @Test
    @DisplayName("HEAD-only documents filtered for MEMBER on both engines")
    void rbacHeadRoleParity() {
        Document headDoc = Document.builder()
                .id(9001L)
                .userId("bench-user")
                .workspaceId(WORKSPACE)
                .departmentId("dept-eng")
                .fileName("head-only.pdf")
                .fileSize(100)
                .documentType(DocType.pdf)
                .status(DocStatus.COMPLETED)
                .allowedRoles("HEAD")
                .securityClassification(SecurityClassification.INTERNAL)
                .chunkCount(1)
                .build();

        List<Double> vec = VectorMath.randomUnitVector(DIM, 9001L);
        Embedding chunk = Embedding.builder()
                .documentId(9001L)
                .chunkIndex(0)
                .chunkText("Confidential head-only policy text for RBAC test.")
                .chunkTitle("Head only")
                .chunkType(ChunkType.TEXT)
                .embedding(VectorMath.toEmbeddingJson(vec))
                .tokenCount(20)
                .charCount(50)
                .workspaceId(WORKSPACE)
                .build();

        postgresStorageEngine.storeDocument(headDoc, List.of(chunk));
        mongoStorageEngine.storeDocument(headDoc, List.of(chunk));

        RAGQueryPayload.UserPermissionContext member = memberContext();
        List<StorageSearchHit> pgHits = postgresStorageEngine.similaritySearch("policy", vec, 20, member);
        List<StorageSearchHit> mongoHits = mongoStorageEngine.similaritySearch("policy", vec, 20, member);

        // MEMBER without head role must not retrieve HEAD-only hits among top results from this doc
        assertThat(pgHits.stream().noneMatch(h -> "Confidential head-only policy text for RBAC test.".equals(h.getText())))
                .as("Postgres must filter HEAD-only from MEMBER")
                .isTrue();
        assertThat(mongoHits.stream().noneMatch(h -> "Confidential head-only policy text for RBAC test.".equals(h.getText())))
                .as("Mongo must filter HEAD-only from MEMBER")
                .isTrue();
    }

    private void recordEngine(String suite, int docs, long writeMs, LatencyStats search,
                              long graphMs, int reachable, String graphStrategy) {
        benchmarkReportService.recordDatabaseTradeOff(suite, "docsWritten", docs);
        benchmarkReportService.recordDatabaseTradeOff(suite, "writeTotalMs", writeMs);
        benchmarkReportService.recordDatabaseTradeOff(suite, "writeDocsPerSec",
                writeMs > 0 ? docs * 1000.0 / writeMs : docs);
        benchmarkReportService.recordDatabaseTradeOff(suite, "searchAvgMs", search.avg);
        benchmarkReportService.recordDatabaseTradeOff(suite, "searchP95Ms", search.p95);
        benchmarkReportService.recordDatabaseTradeOff(suite, "searchP99Ms", search.p99);
        benchmarkReportService.recordDatabaseTradeOff(suite, "graphTraversalMs", graphMs);
        benchmarkReportService.recordDatabaseTradeOff(suite, "graphReachableCount", reachable);
        benchmarkReportService.recordDatabaseTradeOff(suite, "graphStrategy", graphStrategy);
    }

    private LatencyStats measureSearch(
            KnowledgeStorageEngine engine,
            List<Double> queryVec,
            RAGQueryPayload.UserPermissionContext perms,
            int iterations) {
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            List<StorageSearchHit> hits = engine.similaritySearch("security policy " + i, queryVec, 10, perms);
            latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
            assertThat(hits).isNotNull();
        }
        latencies.sort(Long::compareTo);
        return new LatencyStats(average(latencies), percentile(latencies, 0.95), percentile(latencies, 0.99));
    }

    private void concurrentSeed(KnowledgeStorageEngine engine, int docs, int chunksPerDoc, int wikiPages)
            throws Exception {
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < docs; i++) {
                final int idx = i;
                futures.add(vt.submit(() -> storeDoc(engine, idx, chunksPerDoc)));
            }
            for (int i = 0; i < wikiPages; i++) {
                final int idx = i;
                futures.add(vt.submit(() -> storeWiki(engine, idx, wikiPages)));
            }
            for (Future<?> f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        }
    }

    private void storeDoc(KnowledgeStorageEngine engine, int idx, int chunksPerDoc) {
        Document doc = Document.builder()
                .id((long) (idx + 1))
                .userId("bench-user")
                .workspaceId(WORKSPACE)
                .departmentId("dept-eng")
                .fileName("doc-" + idx + ".md")
                .fileSize(2048)
                .documentType(DocType.pdf)
                .status(DocStatus.COMPLETED)
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.INTERNAL)
                .chunkCount(chunksPerDoc)
                .build();

        List<Embedding> chunks = new ArrayList<>();
        for (int c = 0; c < chunksPerDoc; c++) {
            List<Double> vec = VectorMath.randomUnitVector(DIM, idx * 1000L + c);
            chunks.add(Embedding.builder()
                    .documentId(doc.getId())
                    .chunkIndex(c)
                    .chunkText("Document " + idx + " chunk " + c + " security compliance baseline text.")
                    .chunkTitle("Chunk " + c)
                    .chunkType(ChunkType.TEXT)
                    .embedding(VectorMath.toEmbeddingJson(vec))
                    .tokenCount(32)
                    .charCount(64)
                    .workspaceId(WORKSPACE)
                    .build());
        }
        engine.storeDocument(doc, chunks);
    }

    private void storeWiki(KnowledgeStorageEngine engine, int idx, int total) {
        List<String> outbound = new ArrayList<>();
        if (idx + 1 < total) outbound.add("page-" + (idx + 1));
        if (idx + 2 < total) outbound.add("page-" + (idx + 2));
        WikiPage page = WikiPage.builder()
                .id((long) (idx + 1))
                .title("Page " + idx)
                .slug("page-" + idx)
                .content("Content " + idx)
                .workspaceId(WORKSPACE)
                .departmentId("dept-eng")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.INTERNAL)
                .pageType(WikiPageType.TOPIC)
                .version(0)
                .build();
        engine.storeWikiPage(page, outbound);
    }

    private static RAGQueryPayload.UserPermissionContext memberContext() {
        return RAGQueryPayload.UserPermissionContext.builder()
                .workspaceId(WORKSPACE)
                .roles(List.of("MEMBER"))
                .roleLevel(4)
                .userDepartments(List.of(
                        RAGQueryPayload.DepartmentRole.builder()
                                .departmentId("dept-eng")
                                .role("MEMBER")
                                .build()
                ))
                .build();
    }

    private static RAGQueryPayload.UserPermissionContext guestContext() {
        return RAGQueryPayload.UserPermissionContext.builder()
                .workspaceId(WORKSPACE)
                .roles(List.of("EXTERNAL_GUEST"))
                .roleLevel(6)
                .userDepartments(List.of())
                .build();
    }

    private static double average(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0L;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private record LatencyStats(double avg, long p95, long p99) {}

    private enum Scale {
        SMALL(100, 1000, 2000);

        final int documents;
        final int chunks;
        final int wikiPages;

        Scale(int documents, int chunks, int wikiPages) {
            this.documents = documents;
            this.chunks = chunks;
            this.wikiPages = wikiPages;
        }

        int chunksPerDoc() {
            return Math.max(1, chunks / documents);
        }
    }
}
