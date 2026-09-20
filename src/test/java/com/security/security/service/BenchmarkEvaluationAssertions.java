package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.provider.LlmProvider;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Test-side facade delegating to production benchmark services.
 * Keeps benchmark test classes stable while metrics live in {@code src/main}.
 */
public final class BenchmarkEvaluationAssertions {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final EvaluationMetricsService METRICS = new EvaluationMetricsService();
    private static final BenchmarkReportService REPORT = new BenchmarkReportService();
    private static final BenchmarkJudgeService JUDGE = new BenchmarkJudgeService(METRICS);
    private static final BenchmarkDataExporter EXPORTER = new BenchmarkDataExporter();

    private BenchmarkEvaluationAssertions() {}

    public static void record(String suite, String metric, Object value) {
        REPORT.record(suite, metric, value);
    }

    public static void recordEvaluation(String metric, Object value) {
        REPORT.recordEvaluation(metric, value);
    }

    public static void recordEvaluationDoc(String documentId, String metric, Object value) {
        REPORT.recordEvaluationDoc(documentId, metric, value);
    }

    public static void recordDetail(String suite, String key, Object value) {
        REPORT.recordDetail(suite, key, value);
    }

    public static void recordPipelineMetrics(String suite, String documentId, PipelineBenchmarkTracker.RunMetrics metrics) {
        REPORT.recordPipelineMetrics(suite, documentId, metrics);
    }

    public static Map<String, Double> computeContextRecallPrecision(Set<String> predicted, Set<String> groundTruth) {
        return METRICS.computeContextRecallPrecision(predicted, groundTruth);
    }

    static boolean fuzzyTopicMatch(String a, String b) {
        return METRICS.fuzzyTopicMatch(a, b);
    }

    public static double computeFaithfulness(List<String> claims, String sourceText) {
        return METRICS.computeRagasFaithfulness(claims, sourceText);
    }

    public static double computeRagasFaithfulness(List<String> claims, String sourceText) {
        return METRICS.computeRagasFaithfulness(claims, sourceText);
    }

    public static double computeGroundednessRate(Collection<String> texts, String sourceText) {
        return METRICS.computeGroundednessRate(texts, sourceText);
    }

    public static double computeAnswerRelevancy(String generatedContent, Set<String> expectedTopics) {
        return METRICS.computeAnswerRelevancy(generatedContent, expectedTopics);
    }

    public static List<String> detectHallucinations(Collection<String> extractedNames, String sourceText, List<String> forbidden) {
        return METRICS.detectHallucinations(extractedNames, sourceText, forbidden);
    }

    public static boolean verifySourceContextSuffix(Collection<String> keyClaims) {
        return METRICS.verifySourceContextSuffix(keyClaims);
    }

    public static double computeSchemaComplianceMap(JsonNode root) {
        return METRICS.computeSchemaComplianceMap(root);
    }

    public static double computeSchemaComplianceReduce(JsonNode root) {
        return METRICS.computeSchemaComplianceReduce(root);
    }

    public static Map<String, Double> computeEntityLinkingMetrics(Set<String> actual, Set<String> expected) {
        return METRICS.computeEntityLinkingMetrics(actual, expected);
    }

    public static EvaluationMetricsService.GerbilLinkingMetrics computeGerbilLinkingMetrics(
            Set<String> actual, Set<String> expected, Set<String> existingSlugs) {
        return METRICS.computeGerbilLinkingMetrics(actual, expected, existingSlugs);
    }

    public static double computeMacroF1(List<Map<String, Double>> perScenarioF1) {
        return METRICS.computeMacroF1(perScenarioF1);
    }

    public static Optional<Double> llmJudgeFaithfulness(LlmProvider provider, String claim, String sourcePassage) {
        return JUDGE.llmJudgeFaithfulness(provider, claim, sourcePassage);
    }

    public static void writeFullReport() throws Exception {
        REPORT.writeFullReport();
    }

    public static void writeEvaluationReport() throws Exception {
        writeEvaluationReport("evaluation");
    }

    /** Write evaluation report under a custom tier folder (e.g. {@code evaluation-mongodb}). */
    public static void writeEvaluationReport(String tierFolder) throws Exception {
        boolean openAi = BenchmarkConditions.openAiKeyAvailable();
        REPORT.writeEvaluationReport(BenchmarkReportService.BENCHMARK_ROOT, tierFolder, JUDGE.resolveJudgeModel(openAi));
    }

    public static String loadResource(String path) throws Exception {
        var stream = BenchmarkEvaluationAssertions.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) throw new IllegalArgumentException("Resource not found: " + path);
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    public static JsonNode loadResourceJson(String path) throws Exception {
        return MAPPER.readTree(loadResource(path));
    }

    public static void reset() {
        REPORT.reset();
    }

    public static void resetEvaluation() {
        REPORT.resetEvaluation();
        EXPORTER.reset();
    }

    public static EvaluationMetricsService metrics() {
        return METRICS;
    }

    public static BenchmarkJudgeService judge() {
        return JUDGE;
    }

    public static BenchmarkReportService report() {
        return REPORT;
    }

    public static BenchmarkDataExporter exporter() {
        return EXPORTER;
    }
}