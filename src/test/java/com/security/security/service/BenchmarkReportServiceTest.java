package com.security.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkReportServiceTest {

    @TempDir
    Path tempDir;

    private BenchmarkReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new BenchmarkReportService();
    }

    @Test
    @DisplayName("Writes timestamped run dir and latest dashboard copy")
    void publishTimestampedReportWithLatest() throws Exception {
        String root = tempDir.toString();
        reportService.record("mrp_accuracy", "faithfulness", 1.0);
        reportService.writeFullReport(root);

        String runDir = reportService.getLastMrpWikiRunDir();
        assertThat(runDir).contains("mrp-wiki");

        File json = new File(runDir, "mrp-wiki-benchmark-report.json");
        File latestJson = new File(root, "mrp-wiki/latest/mrp-wiki-benchmark-report.json");
        File manifest = new File(root, "mrp-wiki/latest/manifest.json");

        assertThat(json).exists();
        assertThat(latestJson).exists();
        assertThat(manifest).exists();
        assertThat(Files.readString(manifest.toPath())).contains("runId");
    }
}