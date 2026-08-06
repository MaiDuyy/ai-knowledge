package com.security.security.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finalizes and validates the consolidated SecWiki-Bench v2 report artifacts.
 */
@Tag("benchmark")
@DisplayName("SecWiki-Bench v2: Report Finalizer")
class Z_MrpWikiBenchmarkReportFinalizerTest {

    @Test
    @DisplayName("Write and validate consolidated JSON + Markdown report with latest dashboard")
    void finalizeReport() throws Exception {
        BenchmarkEvaluationAssertions.writeFullReport();

        String runDir = BenchmarkEvaluationAssertions.report().getLastMrpWikiRunDir();
        assertThat(runDir).isNotBlank();

        File json = new File(runDir, "mrp-wiki-benchmark-report.json");
        File md = new File(runDir, "mrp-wiki-benchmark-report.md");
        File latestJson = new File("benchmark/mrp-wiki/latest/mrp-wiki-benchmark-report.json");
        File latestMd = new File("benchmark/mrp-wiki/latest/mrp-wiki-benchmark-report.md");
        File manifest = new File("benchmark/mrp-wiki/latest/manifest.json");

        assertThat(json).exists();
        assertThat(md).exists();
        assertThat(latestJson).exists();
        assertThat(latestMd).exists();
        assertThat(manifest).exists();

        String mdContent = Files.readString(md.toPath());
        assertThat(mdContent).contains("SecWiki-Bench v2");
        assertThat(mdContent).contains("Summary Scorecard");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("BENCHMARK REPORT WRITTEN:");
        System.out.println("  Run:  " + new File(runDir).getAbsolutePath());
        System.out.println("  Latest JSON: " + latestJson.getAbsolutePath());
        System.out.println("  Manifest:    " + manifest.getAbsolutePath());
        System.out.println("=".repeat(60));
    }
}