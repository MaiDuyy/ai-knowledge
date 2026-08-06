package com.security.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finalizes evaluation-tier benchmark report and Ragas export. Runs after GoldenDatasetEvaluationBenchmarkTest.
 */
@Tag("evaluation")
@EnabledIf(value = "com.security.security.service.BenchmarkConditions#evaluationBenchmarkEnabled",
        disabledReason = "API_KEY not set — skip evaluation report finalizer")
@DisplayName("SecWiki Evaluation: Report Finalizer")
class Z_EvaluationBenchmarkReportFinalizerTest {

    @Test
    @DisplayName("Write evaluation report + Ragas JSONL export with latest dashboard")
    void finalizeEvaluationReport() throws Exception {
        String pinned = BenchmarkEvaluationAssertions.report().getPinnedEvaluationRunId();
        String runId = (pinned != null && !pinned.isBlank())
                ? pinned
                : BenchmarkReportService.currentRunId();
        BenchmarkEvaluationAssertions.report().setEvaluationRunId(runId);

        Path runDirPath = Path.of(BenchmarkReportService.BENCHMARK_ROOT, "evaluation", runId);
        Path exportDir = runDirPath.resolve("export");

        Map<String, Object> exportMeta = new LinkedHashMap<>();
        exportMeta.put("runId", runId);
        exportMeta.put("javaReport", "../evaluation-benchmark-report.json");
        Path flushed = BenchmarkEvaluationAssertions.exporter().flushTo(exportDir, exportMeta);

        BenchmarkEvaluationAssertions.recordEvaluation(
                "exportDir", flushed.toString().replace('\\', '/'));
        BenchmarkEvaluationAssertions.recordEvaluation(
                "exportClaimSamples", BenchmarkEvaluationAssertions.exporter().claimSampleCount());
        BenchmarkEvaluationAssertions.recordEvaluation(
                "exportStructuredSamples", BenchmarkEvaluationAssertions.exporter().structuredSampleCount());
        BenchmarkEvaluationAssertions.recordEvaluation(
                "exportLinkSamples", BenchmarkEvaluationAssertions.exporter().linkSampleCount());

        BenchmarkEvaluationAssertions.writeEvaluationReport();

        String runDir = BenchmarkEvaluationAssertions.report().getLastEvaluationRunDir();
        assertThat(runDir).isNotBlank();
        assertThat(Path.of(runDir)).isEqualTo(runDirPath);

        File json = new File(runDir, "evaluation-benchmark-report.json");
        File md = new File(runDir, "evaluation-benchmark-report.md");
        File latestJson = new File("benchmark/evaluation/latest/evaluation-benchmark-report.json");
        File latestMd = new File("benchmark/evaluation/latest/evaluation-benchmark-report.md");
        File manifest = new File("benchmark/evaluation/latest/manifest.json");

        assertThat(json).exists();
        assertThat(md).exists();
        assertThat(latestJson).exists();
        assertThat(latestMd).exists();
        assertThat(manifest).exists();

        assertThat(exportDir.resolve(BenchmarkDataExporter.CLAIMS_FILE)).exists();
        assertThat(exportDir.resolve(BenchmarkDataExporter.STRUCTURED_FILE)).exists();
        assertThat(exportDir.resolve(BenchmarkDataExporter.LINKS_FILE)).exists();
        assertThat(exportDir.resolve(BenchmarkDataExporter.MANIFEST_FILE)).exists();

        Path latestExport = Path.of("benchmark/evaluation/latest/export");
        copyDirectory(exportDir, latestExport);
        assertThat(latestExport.resolve(BenchmarkDataExporter.MANIFEST_FILE)).exists();

        String mdContent = Files.readString(md.toPath());
        assertThat(mdContent).contains("Evaluation Benchmark Report");
        assertThat(mdContent).contains("Corpus-Level Metrics");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("EVALUATION BENCHMARK REPORT WRITTEN:");
        System.out.println("  Run:  " + new File(runDir).getAbsolutePath());
        System.out.println("  Export: " + exportDir.toAbsolutePath());
        System.out.println("  Latest JSON: " + latestJson.getAbsolutePath());
        System.out.println("  Latest export: " + latestExport.toAbsolutePath());
        System.out.println("  Manifest:    " + manifest.getAbsolutePath());
        System.out.println("  Judge: " + BenchmarkCrossModelJudge.resolveJudgeModel());
        System.out.println("  Claims exported: " + BenchmarkEvaluationAssertions.exporter().claimSampleCount());
        System.out.println("  Structured exported: " + BenchmarkEvaluationAssertions.exporter().structuredSampleCount());
        System.out.println("=".repeat(60));
        System.out.println("Next: .\\scripts\\run-ragas-eval.ps1  (requires .venv-bench + OPENAI_API_KEY)");
        System.out.println("=".repeat(60));
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
}
