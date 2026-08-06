package com.security.security.resource;

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
import com.security.security.service.BenchmarkReportService;
import com.security.security.service.KnowledgeStorageEngine;
import com.security.security.service.StorageEngineType;
import com.security.security.service.VectorMath;
import com.security.security.service.WikiGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Experimental endpoints for dynamic PostgreSQL vs MongoDB trade-off runs.
 * Only active under {@code experimental} or {@code mongodb-benchmark} profiles.
 */
@RestController
@RequestMapping("/api/benchmark/database-tradeoff")
@Profile({"experimental", "mongodb-benchmark"})
@RequiredArgsConstructor
@Slf4j
public class BenchmarkController {

    private final List<KnowledgeStorageEngine> storageEngines;
    private final BenchmarkReportService benchmarkReportService;
    private final WikiGraphService wikiGraphService;

    private final Map<String, Object> lastRunMetrics = new ConcurrentHashMap<>();

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed(
            @RequestParam(defaultValue = "small") String scale,
            @RequestParam(defaultValue = "ws-bench") String workspaceId) throws Exception {

        Scale s = Scale.from(scale);
        // Unique prefix so re-seed never collides with unique slug index on Mongo wiki_pages
        String runKey = newRunKey("seed");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scale", s.name());
        result.put("runKey", runKey);
        result.put("documents", s.documents);
        result.put("chunksPerDoc", s.chunksPerDoc());
        result.put("wikiPages", s.wikiPages);

        RAGQueryPayload.UserPermissionContext perms = memberContext(workspaceId);
        List<Double> queryVec = VectorMath.randomUnitVector(768, 42L);

        for (KnowledgeStorageEngine engine : storageEngines) {
            long writeStart = System.nanoTime();
            seedEngine(engine, s, workspaceId, runKey);
            long writeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writeStart);

            long searchStart = System.nanoTime();
            List<StorageSearchHit> hits = engine.similaritySearch("benchmark query", queryVec, 10, perms);
            long searchMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - searchStart);

            Map<String, Object> engineMetrics = new LinkedHashMap<>();
            engineMetrics.put("writeMs", writeMs);
            engineMetrics.put("searchMs", searchMs);
            engineMetrics.put("searchHits", hits.size());
            result.put(engine.getEngineType().name(), engineMetrics);
        }

        lastRunMetrics.clear();
        lastRunMetrics.putAll(result);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(
            @RequestParam(defaultValue = "small") String scale,
            @RequestParam(defaultValue = "ws-bench") String workspaceId,
            @RequestParam(defaultValue = "20") int searchIterations) throws Exception {

        Scale s = Scale.from(scale);
        String runKey = newRunKey("run");
        benchmarkReportService.resetDatabaseTradeOff();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("scale", s.name());
        response.put("runKey", runKey);

        RAGQueryPayload.UserPermissionContext perms = memberContext(workspaceId);
        List<Double> queryVec = VectorMath.randomUnitVector(768, 99L);
        String startSlug = pageSlug(runKey, 0);

        for (KnowledgeStorageEngine engine : storageEngines) {
            String suite = engine.getEngineType().name().toLowerCase();

            // Concurrent writes with virtual threads
            long writeStart = System.nanoTime();
            int written = concurrentWrite(engine, s, workspaceId, runKey);
            long writeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writeStart);
            double writeThroughput = writeMs > 0 ? (written * 1000.0 / writeMs) : written;

            List<Long> searchLatencies = new ArrayList<>();
            for (int i = 0; i < searchIterations; i++) {
                long t0 = System.nanoTime();
                engine.similaritySearch("tradeoff query " + i, queryVec, 10, perms);
                searchLatencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
            }
            searchLatencies.sort(Long::compareTo);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("docsWritten", written);
            metrics.put("writeTotalMs", writeMs);
            metrics.put("writeDocsPerSec", writeThroughput);
            metrics.put("searchAvgMs", average(searchLatencies));
            metrics.put("searchP95Ms", percentile(searchLatencies, 0.95));
            metrics.put("searchP99Ms", percentile(searchLatencies, 0.99));

            if (engine.getEngineType() == StorageEngineType.POSTGRES) {
                long g0 = System.nanoTime();
                List<String> reachable = wikiGraphService.jgraphtReachable(startSlug, 3);
                metrics.put("graphTraversalMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - g0));
                metrics.put("graphReachableCount", reachable.size());
                metrics.put("graphStrategy", "JGraphT-in-memory-BFS");
            } else {
                long g0 = System.nanoTime();
                List<String> reachable = wikiGraphService.graphLookupReachable(startSlug, 3);
                metrics.put("graphTraversalMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - g0));
                metrics.put("graphReachableCount", reachable.size());
                metrics.put("graphStrategy", "MongoDB-$graphLookup");
            }

            for (Map.Entry<String, Object> e : metrics.entrySet()) {
                benchmarkReportService.recordDatabaseTradeOff(suite, e.getKey(), e.getValue());
            }
            response.put(suite, metrics);
        }

        benchmarkReportService.writeDatabaseTradeOffReport();
        response.put("reportDir", benchmarkReportService.getLastDatabaseTradeOffRunDir());
        lastRunMetrics.clear();
        lastRunMetrics.putAll(response);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> report() {
        Map<String, Object> body = new LinkedHashMap<>(lastRunMetrics);
        body.putIfAbsent("reportDir", benchmarkReportService.getLastDatabaseTradeOffRunDir());
        return ResponseEntity.ok(body);
    }

    private static String newRunKey(String kind) {
        return kind + "-" + System.currentTimeMillis() + "-" + Integer.toHexString((int) (Math.random() * 0xffff));
    }

    private static String pageSlug(String runKey, int idx) {
        return runKey + "-page-" + idx;
    }

    private int concurrentWrite(KnowledgeStorageEngine engine, Scale s, String workspaceId, String runKey) throws Exception {
        int docs = Math.min(s.documents, 50); // controller path: bounded concurrent sample
        int pages = Math.min(s.wikiPages, 100);
        try (ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < docs; i++) {
                final int idx = i;
                futures.add(vt.submit(() -> storeOneDocument(engine, idx, s.chunksPerDoc(), workspaceId, runKey)));
            }
            for (int i = 0; i < pages; i++) {
                final int idx = i;
                futures.add(vt.submit(() -> storeOneWiki(engine, idx, workspaceId, pages, runKey)));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            return docs;
        }
    }

    private void seedEngine(KnowledgeStorageEngine engine, Scale s, String workspaceId, String runKey) {
        int docs = Math.min(s.documents, 20);
        for (int i = 0; i < docs; i++) {
            storeOneDocument(engine, i, s.chunksPerDoc(), workspaceId, runKey);
        }
        int pages = Math.min(s.wikiPages, 50);
        for (int i = 0; i < pages; i++) {
            storeOneWiki(engine, i, workspaceId, pages, runKey);
        }
    }

    private void storeOneDocument(KnowledgeStorageEngine engine, int idx, int chunksPerDoc, String workspaceId, String runKey) {
        Document doc = Document.builder()
                .id((long) (idx + 1))
                .userId("bench-user")
                .workspaceId(workspaceId)
                .departmentId("ALL")
                .fileName(runKey + "-doc-" + idx + ".md")
                .fileSize(1024)
                .documentType(DocType.pdf)
                .status(DocStatus.COMPLETED)
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.INTERNAL)
                .chunkCount(chunksPerDoc)
                .build();

        List<Embedding> chunks = new ArrayList<>();
        for (int c = 0; c < chunksPerDoc; c++) {
            List<Double> vec = VectorMath.randomUnitVector(768, idx * 1000L + c);
            chunks.add(Embedding.builder()
                    .documentId(doc.getId())
                    .chunkIndex(c)
                    .chunkText("Chunk " + c + " of document " + idx + " about security policy compliance.")
                    .chunkTitle("Section " + c)
                    .chunkType(ChunkType.TEXT)
                    .embedding(VectorMath.toEmbeddingJson(vec))
                    .tokenCount(40)
                    .charCount(80)
                    .workspaceId(workspaceId)
                    .build());
        }
        engine.storeDocument(doc, chunks);
    }

    private void storeOneWiki(KnowledgeStorageEngine engine, int idx, String workspaceId, int totalPages, String runKey) {
        List<String> outbound = new ArrayList<>();
        if (idx + 1 < totalPages) {
            outbound.add(pageSlug(runKey, idx + 1));
        }
        if (idx + 2 < totalPages) {
            outbound.add(pageSlug(runKey, idx + 2));
        }
        WikiPage page = WikiPage.builder()
                .id((long) (idx + 1))
                .title("Page " + idx)
                .slug(pageSlug(runKey, idx))
                .content("Wiki content for page " + idx)
                .workspaceId(workspaceId)
                .departmentId("ALL")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.INTERNAL)
                .pageType(WikiPageType.TOPIC)
                .version(0)
                .build();
        engine.storeWikiPage(page, outbound);
    }

    private static RAGQueryPayload.UserPermissionContext memberContext(String workspaceId) {
        return RAGQueryPayload.UserPermissionContext.builder()
                .workspaceId(workspaceId)
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

    private static double average(List<Long> values) {
        if (values.isEmpty()) return 0.0;
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0L;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private enum Scale {
        SMALL(100, 1000, 2000),
        MEDIUM(500, 5000, 5000),
        LARGE(1000, 10000, 10000);

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

        static Scale from(String raw) {
            if (raw == null) return SMALL;
            return switch (raw.trim().toLowerCase()) {
                case "medium" -> MEDIUM;
                case "large" -> LARGE;
                default -> SMALL;
            };
        }
    }
}
