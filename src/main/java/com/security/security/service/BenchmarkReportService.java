package com.security.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates benchmark metrics and writes JSON + Markdown artifacts.
 */
@Service
public class BenchmarkReportService {

    public static final String BENCHMARK_ROOT = "benchmark";
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm").withZone(ZoneId.systemDefault());

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private String lastMrpWikiRunDir;
    private String lastEvaluationRunDir;
    private String pendingEvaluationRunId;

    private final Map<String, Object> report = new ConcurrentHashMap<>();
    private final Map<String, Object> evaluationReport = new ConcurrentHashMap<>();

    public void record(String suite, String metric, Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> suiteMap = (Map<String, Object>) report.computeIfAbsent(suite, k -> new ConcurrentHashMap<>());
        suiteMap.put(metric, value);
    }

    public void recordEvaluation(String metric, Object value) {
        evaluationReport.put(metric, value);
    }

    @SuppressWarnings("unchecked")
    public void recordEvaluationDoc(String documentId, String metric, Object value) {
        Map<String, Object> docs = (Map<String, Object>) evaluationReport.computeIfAbsent("perDocument", k -> new ConcurrentHashMap<>());
        Map<String, Object> docMap = (Map<String, Object>) docs.computeIfAbsent(documentId, k -> new ConcurrentHashMap<>());
        docMap.put(metric, value);
    }

    public void recordDetail(String suite, String key, Object value) {
        @SuppressWarnings("unchecked")
        Map<String, Object> suiteMap = (Map<String, Object>) report.computeIfAbsent(suite, k -> new ConcurrentHashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) suiteMap.computeIfAbsent("details", k -> new ConcurrentHashMap<>());
        details.put(key, value);
    }

    public void recordPipelineMetrics(String suite, String documentId, PipelineBenchmarkTracker.RunMetrics metrics) {
        recordDetail(suite, "latencyCost_" + documentId, metrics.toMap());
    }

    public void reset() {
        report.clear();
        evaluationReport.clear();
    }

    public void resetEvaluation() {
        evaluationReport.clear();
    }

    public void writeFullReport() throws Exception {
        writeFullReport(BENCHMARK_ROOT);
    }

    public void writeFullReport(String benchmarkRoot) throws Exception {
        String runId = currentRunId();
        String runDir = benchmarkRoot + "/mrp-wiki/" + runId;
        lastMrpWikiRunDir = runDir;

        Map<String, Object> fullReport = new LinkedHashMap<>();
        fullReport.put("benchmarkVersion", "2.1");
        fullReport.put("runId", runId);
        fullReport.put("tier", "mrp-wiki");
        fullReport.put("timestamp", Instant.now().toString());
        fullReport.put("methodology", List.of("Ragas", "TruLens", "GERBIL", "LLMStructBench", "AgentBench"));
        fullReport.put("suites", new LinkedHashMap<>(report));

        String artifactBase = "mrp-wiki-benchmark-report";
        String markdown = renderMarkdownReport(fullReport, runDir + "/" + artifactBase);
        publishTierReport(benchmarkRoot, "mrp-wiki", runId, runDir, artifactBase, fullReport, markdown);
    }

    public void writeEvaluationReport(String judgeModel) throws Exception {
        writeEvaluationReport(BENCHMARK_ROOT, judgeModel);
    }

    public void writeEvaluationReport(String benchmarkRoot, String judgeModel) throws Exception {
        String runId = pendingEvaluationRunId != null && !pendingEvaluationRunId.isBlank()
                ? pendingEvaluationRunId
                : currentRunId();
        String runDir = benchmarkRoot + "/evaluation/" + runId;
        lastEvaluationRunDir = runDir;

        Map<String, Object> fullReport = new LinkedHashMap<>();
        fullReport.put("benchmarkVersion", "2.1-evaluation");
        fullReport.put("runId", runId);
        fullReport.put("tier", "evaluation");
        fullReport.put("timestamp", Instant.now().toString());
        fullReport.put("judgeModel", judgeModel);
        fullReport.put("methodology", List.of(
                "Java Ragas-aligned heuristics",
                "Official Ragas export (Python optional)",
                "GERBIL-style linking",
                "Cross-Model Judge"));

        Map<String, Object> metrics = new LinkedHashMap<>(evaluationReport);
        fillCorpusMetricsFromPerDocument(metrics);
        fullReport.put("metrics", metrics);

        String artifactBase = "evaluation-benchmark-report";
        String markdown = renderEvaluationMarkdownReport(fullReport, runDir + "/" + artifactBase);
        publishTierReport(benchmarkRoot, "evaluation", runId, runDir, artifactBase, fullReport, markdown);
    }

    /** Pin evaluation run id so export and report share the same directory. */
    public void setEvaluationRunId(String runId) {
        this.pendingEvaluationRunId = runId;
    }

    public String getPinnedEvaluationRunId() {
        return pendingEvaluationRunId;
    }

    /**
     * Derives corpus-level aggregates from {@code perDocument} when Order(2) did not run
     * (e.g. partial flush after Order(1) only).
     */
    @SuppressWarnings("unchecked")
    public void fillCorpusMetricsFromPerDocument(Map<String, Object> metrics) {
        if (metrics == null || !metrics.containsKey("perDocument")) {
            return;
        }
        if (metrics.containsKey("corpusFaithfulness")) {
            return;
        }

        Map<String, Object> perDoc = (Map<String, Object>) metrics.get("perDocument");
        if (perDoc == null || perDoc.isEmpty()) {
            return;
        }

        List<Double> faith = new ArrayList<>();
        List<Double> ground = new ArrayList<>();
        List<Double> extractionF1 = new ArrayList<>();
        List<Double> linkF1 = new ArrayList<>();
        List<Double> answerRel = new ArrayList<>();
        List<Double> judgeAvg = new ArrayList<>();
        List<Double> linkJudge = new ArrayList<>();
        List<Long> latency = new ArrayList<>();
        List<Integer> tokens = new ArrayList<>();
        int linkResolved = 0;
        int mrpPages = 0;
        int judgeSamples = 0;

        for (Object docValue : perDoc.values()) {
            if (!(docValue instanceof Map<?, ?> rawDoc)) continue;
            Map<String, Object> doc = (Map<String, Object>) rawDoc;
            addDouble(faith, doc.get("faithfulness"));
            addDouble(ground, doc.get("groundedness"));
            addDouble(extractionF1, doc.get("extractionF1"));
            addDouble(answerRel, doc.get("answerRelevancy"));
            addDouble(judgeAvg, doc.get("crossModelJudgeAvg"));
            addLong(latency, doc.get("pipelineLatencyMs"));
            addInt(tokens, doc.get("estimatedTotalTokens"));
            if (doc.containsKey("linkF1")) {
                addDouble(linkF1, doc.get("linkF1"));
            }
            if (doc.containsKey("linkJudgeScore")) {
                addDouble(linkJudge, doc.get("linkJudgeScore"));
            }
            if (Boolean.TRUE.equals(doc.get("linkPageResolved"))) {
                linkResolved++;
            }
            if (doc.containsKey("mrpWikiPagesCreated")) {
                mrpPages += toInt(doc.get("mrpWikiPagesCreated"));
            }
            if (doc.containsKey("crossModelJudgeSamples")) {
                judgeSamples += toInt(doc.get("crossModelJudgeSamples"));
            }
        }

        metrics.put("corpusFaithfulness", average(faith));
        metrics.put("corpusGroundedness", average(ground));
        metrics.put("corpusExtractionF1", average(extractionF1));
        metrics.put("corpusAnswerRelevancy", average(answerRel));
        metrics.put("corpusLinkF1", linkF1.isEmpty() ? 0.0 : average(linkF1));
        metrics.put("crossModelJudgeAvg", judgeAvg.isEmpty() ? 0.0 : average(judgeAvg));
        metrics.put("crossModelLinkJudgeAvg", linkJudge.isEmpty() ? 0.0 : average(linkJudge));
        metrics.put("avgPipelineLatencyMs", latency.isEmpty() ? 0.0 : averageLong(latency));
        metrics.put("totalEstimatedTokens", tokens.stream().mapToInt(i -> i).sum());
        metrics.put("documentsEvaluated", perDoc.size());
        metrics.put("linkScenariosEvaluated", linkF1.size());
        metrics.put("linkPagesResolved", linkResolved);
        metrics.put("totalMrpWikiPagesCreated", mrpPages);
        metrics.put("judgeSampleCount", judgeSamples);
    }

    private static void addDouble(List<Double> list, Object value) {
        if (value instanceof Number n) {
            list.add(n.doubleValue());
        }
    }

    private static void addLong(List<Long> list, Object value) {
        if (value instanceof Number n) {
            list.add(n.longValue());
        }
    }

    private static void addInt(List<Integer> list, Object value) {
        if (value instanceof Number n) {
            list.add(n.intValue());
        }
    }

    private static int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static double average(List<Double> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    private static double averageLong(List<Long> values) {
        return values.isEmpty() ? 0.0 : values.stream().mapToLong(l -> l).average().orElse(0.0);
    }

    public String getLastMrpWikiRunDir() {
        return lastMrpWikiRunDir;
    }

    public String getLastEvaluationRunDir() {
        return lastEvaluationRunDir;
    }

    private void publishTierReport(
            String benchmarkRoot,
            String tier,
            String runId,
            String runDir,
            String artifactBase,
            Map<String, Object> fullReport,
            String markdown) throws Exception {

        writeArtifacts(runDir, artifactBase, fullReport, markdown);
        updateLatestDashboard(benchmarkRoot, tier, runId, runDir, artifactBase, fullReport);
        writeWorkspaceSummary(tier, markdown);
        // backward-compatible mirror for CI tools expecting target/benchmark
        writeArtifacts("target/benchmark", artifactBase, fullReport, markdown);
    }

    private void writeArtifacts(String targetDir, String baseName, Map<String, Object> fullReport, String markdown) throws Exception {
        File dir = new File(targetDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create report directory: " + targetDir);
        }

        File jsonFile = new File(dir, baseName + ".json");
        MAPPER.writeValue(jsonFile, fullReport);

        File mdFile = new File(dir, baseName + ".md");
        try (FileWriter w = new FileWriter(mdFile, StandardCharsets.UTF_8)) {
            w.write(markdown);
        }
    }

    private void updateLatestDashboard(
            String benchmarkRoot,
            String tier,
            String runId,
            String runDir,
            String artifactBase,
            Map<String, Object> fullReport) throws Exception {

        Path latestDir = Path.of(benchmarkRoot, tier, "latest");
        Files.createDirectories(latestDir);

        Path runPath = Path.of(runDir);
        copyReportFile(runPath.resolve(artifactBase + ".json"), latestDir.resolve(artifactBase + ".json"));
        copyReportFile(runPath.resolve(artifactBase + ".md"), latestDir.resolve(artifactBase + ".md"));

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("tier", tier);
        manifest.put("runId", runId);
        manifest.put("runDir", runDir.replace('\\', '/'));
        manifest.put("latestDir", latestDir.toString().replace('\\', '/'));
        manifest.put("timestamp", fullReport.get("timestamp"));
        manifest.put("reports", List.of(artifactBase + ".json", artifactBase + ".md"));
        manifest.put("dashboard", Map.of(
                "json", latestDir.resolve(artifactBase + ".json").toString().replace('\\', '/'),
                "markdown", latestDir.resolve(artifactBase + ".md").toString().replace('\\', '/')
        ));

        MAPPER.writeValue(latestDir.resolve("manifest.json").toFile(), manifest);
        trySymlinkOrCopyRunRef(latestDir, runPath, runId);
    }

    private void trySymlinkOrCopyRunRef(Path latestDir, Path runPath, String runId) throws Exception {
        Path symlinkTarget = latestDir.resolve("run-ref");
        if (Files.exists(symlinkTarget)) {
            Files.delete(symlinkTarget);
        }
        try {
            Files.createSymbolicLink(symlinkTarget, runPath.toAbsolutePath());
        } catch (Exception ignored) {
            Files.writeString(
                    latestDir.resolve("run-id.txt"),
                    runId + System.lineSeparator() + runPath.toAbsolutePath(),
                    StandardCharsets.UTF_8);
        }
    }

    private static void copyReportFile(Path source, Path target) throws Exception {
        if (Files.exists(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeWorkspaceSummary(String tier, String markdown) throws Exception {
        String workspaceFile = "evaluation".equals(tier)
                ? "benchmark_results_evaluation.md"
                : "benchmark_results_mrp_wiki.md";
        try (FileWriter w = new FileWriter(new File(workspaceFile), StandardCharsets.UTF_8)) {
            w.write(markdown);
        }
    }

    public static String currentRunId() {
        return RUN_ID_FORMAT.format(Instant.now());
    }

    @SuppressWarnings("unchecked")
    private String renderEvaluationMarkdownReport(Map<String, Object> fullReport, String artifactBasePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SecWiki-Bench v2 — Evaluation Benchmark Report\n\n");
        sb.append("**Tier:** Periodic Evaluation (Real API + Golden Dataset)\n\n");
        sb.append("**Generated:** ").append(fullReport.get("timestamp")).append("\n\n");
        sb.append("**Judge Model:** ").append(fullReport.get("judgeModel")).append("\n\n");
        sb.append("**Methodology:** ").append(fullReport.get("methodology")).append("\n\n");
        sb.append("---\n\n");

        Map<String, Object> metrics = (Map<String, Object>) fullReport.get("metrics");
        if (metrics == null || metrics.isEmpty()) {
            sb.append("_No evaluation data recorded._\n");
            return sb.toString();
        }

        sb.append("## Corpus-Level Metrics\n\n");
        sb.append("| Metric | Value | Threshold | Status |\n");
        sb.append("|--------|-------|-----------|--------|\n");
        appendEvalScorecard(sb, "corpusFaithfulness", metrics, 0.80);
        appendEvalScorecard(sb, "corpusGroundedness", metrics, 0.80);
        appendEvalScorecard(sb, "corpusExtractionF1", metrics, 0.55);
        appendEvalScorecard(sb, "corpusLinkF1", metrics, 0.70);
        appendEvalScorecard(sb, "corpusAnswerRelevancy", metrics, 0.60);
        appendEvalScorecard(sb, "crossModelJudgeAvg", metrics, 3.5);
        appendEvalScorecard(sb, "crossModelLinkJudgeAvg", metrics, 3.5);

        sb.append("\n## Run Metadata\n\n");
        sb.append("| Field | Value |\n|-------|-------|\n");
        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            if ("perDocument".equals(e.getKey())) continue;
            if (e.getKey().startsWith("corpus") || e.getKey().contains("Judge")) continue;
            sb.append("| `").append(e.getKey()).append("` | ").append(formatValue(e.getValue())).append(" |\n");
        }

        if (metrics.containsKey("perDocument")) {
            sb.append("\n## Per-Document Scores\n\n");
            sb.append("| Document | Faithfulness | Groundedness | Extraction F1 | Answer Rel. | Latency ms | Tokens | Link F1 | Judge Avg |\n");
            sb.append("|----------|-------------|-------------|--------------|------------|-----------|--------|---------|----------|\n");
            Map<String, Object> perDoc = (Map<String, Object>) metrics.get("perDocument");
            for (Map.Entry<String, Object> docEntry : perDoc.entrySet()) {
                Map<String, Object> doc = (Map<String, Object>) docEntry.getValue();
                sb.append("| ").append(docEntry.getKey())
                        .append(" | ").append(formatValue(doc.get("faithfulness")))
                        .append(" | ").append(formatValue(doc.get("groundedness")))
                        .append(" | ").append(formatValue(doc.get("extractionF1")))
                        .append(" | ").append(formatValue(doc.getOrDefault("answerRelevancy", "N/A")))
                        .append(" | ").append(formatValue(doc.getOrDefault("pipelineLatencyMs", "N/A")))
                        .append(" | ").append(formatValue(doc.getOrDefault("estimatedTotalTokens", "N/A")))
                        .append(" | ").append(formatValue(doc.getOrDefault("linkF1", "N/A")))
                        .append(" | ").append(formatValue(doc.getOrDefault("crossModelJudgeAvg", "N/A")))
                        .append(" |\n");
            }
        }

        sb.append("\n---\n\n*Artifacts: `").append(artifactBasePath).append(".json`, `")
                .append(artifactBasePath).append(".md`, `benchmark/evaluation/latest/manifest.json`*\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String renderMarkdownReport(Map<String, Object> fullReport, String artifactBasePath) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SecWiki-Bench v2 — MRP & WikiLink Benchmark Report\n\n");
        sb.append("**Generated:** ").append(fullReport.get("timestamp")).append("\n\n");
        sb.append("**Methodology:** ").append(fullReport.get("methodology")).append("\n\n");
        sb.append("---\n\n");

        Map<String, Object> suites = (Map<String, Object>) fullReport.get("suites");

        appendSuiteSection(sb, "1. MRP Pipeline Accuracy (Ragas / TruLens / LLMStructBench)",
                (Map<String, Object>) suites.get("mrp_accuracy"));
        appendSuiteSection(sb, "2. WikiLink Entity Linking (GERBIL)",
                (Map<String, Object>) suites.get("wikilink"));
        appendSuiteSection(sb, "3. Agent Robustness (AgentBench)",
                (Map<String, Object>) suites.get("robustness"));

        sb.append("---\n\n## Summary Scorecard\n\n");
        sb.append("| Suite | Key Metric | Value | Threshold | Status |\n");
        sb.append("|-------|-----------|-------|-----------|--------|\n");
        appendScorecardRow(sb, "MRP", "faithfulness", suites, "mrp_accuracy", 0.90);
        appendScorecardRow(sb, "MRP", "groundedness", suites, "mrp_accuracy", 0.90);
        appendScorecardRow(sb, "MRP", "answerRelevancy", suites, "mrp_accuracy", 0.60);
        appendScorecardRow(sb, "MRP", "extractionF1", suites, "mrp_accuracy", 0.70);
        appendScorecardRow(sb, "WikiLink", "linkF1", suites, "wikilink", 0.80);
        appendScorecardRow(sb, "WikiLink", "nilCount", suites, "wikilink", 0.0);
        appendScorecardRow(sb, "Robustness", "retrySuccessRate", suites, "robustness", 1.0);
        appendScorecardRow(sb, "Robustness", "stateMachineCompliance", suites, "robustness", 1.0);

        sb.append("\n## Methodology Reference\n\n");
        sb.append("| Framework | Metrics | Purpose |\n");
        sb.append("|-----------|---------|----------|\n");
        sb.append("| Ragas | faithfulness (atomic claims), answerRelevancy, extractionF1 | Extraction & hallucination |\n");
        sb.append("| TruLens | groundedness, llmJudgeScoreAvg | Claim verification |\n");
        sb.append("| GERBIL | linkPrecision, linkRecall, linkF1, nilCount | Entity linking |\n");
        sb.append("| LLMStructBench | mapSchemaCompliance, reduceSchemaCompliance | JSON schema |\n");
        sb.append("| AgentBench | retrySuccessRate, stateMachineCompliance | E2E resilience |\n");

        sb.append("\n---\n\n*Artifacts: `").append(artifactBasePath).append(".json`, `")
                .append(artifactBasePath).append(".md`, `benchmark/mrp-wiki/latest/manifest.json`*\n");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendSuiteSection(StringBuilder sb, String title, Map<String, Object> suite) {
        sb.append("## ").append(title).append("\n\n");
        if (suite == null || suite.isEmpty()) {
            sb.append("_No data recorded._\n\n");
            return;
        }
        sb.append("| Metric | Value |\n|--------|-------|\n");
        for (Map.Entry<String, Object> e : suite.entrySet()) {
            if ("details".equals(e.getKey())) continue;
            sb.append("| `").append(e.getKey()).append("` | ").append(formatValue(e.getValue())).append(" |\n");
        }
        if (suite.containsKey("details")) {
            sb.append("\n**Details:**\n\n```json\n");
            try {
                sb.append(MAPPER.writeValueAsString(suite.get("details")));
            } catch (Exception ex) {
                sb.append(suite.get("details"));
            }
            sb.append("\n```\n");
        }
        sb.append("\n");
    }

    private void appendEvalScorecard(StringBuilder sb, String metric, Map<String, Object> metrics, double threshold) {
        if (!metrics.containsKey(metric)) {
            sb.append("| `").append(metric).append("` | N/A | ").append(threshold).append(" | SKIP |\n");
            return;
        }
        double val = toDouble(metrics.get(metric));
        String status = val >= threshold ? "PASS" : "FAIL";
        sb.append("| `").append(metric).append("` | ")
                .append(String.format("%.4f", val)).append(" | ")
                .append(threshold).append(" | ").append(status).append(" |\n");
    }

    @SuppressWarnings("unchecked")
    private void appendScorecardRow(StringBuilder sb, String suiteLabel, String metric,
                                    Map<String, Object> suites, String suiteKey, double threshold) {
        Map<String, Object> suite = (Map<String, Object>) suites.get(suiteKey);
        if (suite == null || !suite.containsKey(metric)) {
            sb.append("| ").append(suiteLabel).append(" | ").append(metric).append(" | N/A | ")
                    .append(threshold).append(" | SKIP |\n");
            return;
        }
        double val = toDouble(suite.get(metric));
        String status = metric.equals("nilCount") ? (val <= threshold ? "PASS" : "FAIL") : (val >= threshold ? "PASS" : "FAIL");
        sb.append("| ").append(suiteLabel).append(" | ").append(metric).append(" | ")
                .append(String.format("%.3f", val)).append(" | ").append(threshold).append(" | ")
                .append(status).append(" |\n");
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private static String formatValue(Object v) {
        if (v instanceof Double d) return String.format("%.4f", d);
        if (v instanceof Number n) return String.format("%.4f", n.doubleValue());
        return String.valueOf(v);
    }
}