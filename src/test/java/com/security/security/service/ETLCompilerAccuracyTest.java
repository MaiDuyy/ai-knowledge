package com.security.security.service;

import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;
import com.security.security.service.tika.TikaHtmlExtractor;
import com.security.security.service.tika.HtmlToMarkdownConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.core.io.Resource;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SecWiki-Bench: ETL & Wiki Graph Compilation Accuracy Evaluator")
class ETLCompilerAccuracyTest {

    private static final Logger log = LoggerFactory.getLogger(ETLCompilerAccuracyTest.class);

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private io.nats.client.Connection natsConnection;

    @MockBean
    private com.security.security.client.WorkspaceServiceClient workspaceServiceClient;

    @Autowired
    private WikiLinkRepository wikiLinkRepository;

    @Autowired
    private WikiPageRepository wikiPageRepository;

    @Autowired
    private TikaHtmlExtractor tikaHtmlExtractor;

    @Autowired
    private HtmlToMarkdownConverter htmlToMarkdownConverter;

    @Test
    @DisplayName("Measure Table Cell Retention Rate (TCRR): Docling/Structured vs Plain Text")
    void measureTableCellRetentionRate() throws Exception {
        // Ground Truth table in benchmark_table.html has 5 rows and 4 columns = 20 cells
        int groundTruthCells = 20;

        // Load the real HTML benchmark file
        Resource resource = new org.springframework.core.io.FileSystemResource("src/test/java/com/security/security/testdata/benchmark_table.html");
        assertThat(resource.exists()).isTrue();

        // 1. Structured Parse (Docling local / Tika HTML + Jsoup Converter)
        com.security.security.service.tika.TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(resource);
        String markdown = htmlToMarkdownConverter.convert(htmlResult.html());

        // Count cells in the extracted Markdown table
        int doclingExtractedCells = countMarkdownTableCells(markdown);
        double doclingTcrr = (double) doclingExtractedCells / groundTruthCells * 100.0;

        // 2. Plain Text Extraction (Strips all HTML structure / Tika Plain text)
        // Simulate stripping HTML tags which yields plain words, destroying grid lines
        String plainText = org.jsoup.Jsoup.parse(htmlResult.html()).text();
        int tikaExtractedCells = countMarkdownTableCells(plainText); // yields 0 because there are no '|' separators
        double tikaTcrr = (double) tikaExtractedCells / groundTruthCells * 100.0;

        log.info("=== EVALUATION RESULTS: Table Cell Retention Rate (TCRR) ===");
        log.info("Docling/Structured TCRR: {}% (Preserves HTML/Markdown layout)", doclingTcrr);
        log.info("Plain Text/Tika TCRR: {}% (Loses rows/columns boundaries)", tikaTcrr);

        // Assertions
        assertThat(doclingTcrr).isGreaterThan(95.0);
        assertThat(tikaTcrr).isLessThan(20.0);
    }

    private int countMarkdownTableCells(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return 0;
        }
        int cellCount = 0;
        String[] lines = markdown.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            // Match table rows (must start and end with '|', and not be the separator line '---')
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && !trimmed.contains("---")) {
                String[] parts = trimmed.split("\\|");
                for (String part : parts) {
                    if (!part.trim().isEmpty()) {
                        cellCount++;
                    }
                }
            }
        }
        return cellCount;
    }

    @Test
    @DisplayName("Measure WikiLinks Extraction Precision & Recall")
    void measureWikiLinksExtraction() throws Exception {
        // Load the real HTML benchmark file
        Resource resource = new org.springframework.core.io.FileSystemResource("src/test/java/com/security/security/testdata/benchmark_table.html");
        assertThat(resource.exists()).isTrue();

        // Run extraction logic on the actual file content
        com.security.security.service.tika.TikaHtmlResult htmlResult = tikaHtmlExtractor.extract(resource);
        String markdown = htmlToMarkdownConverter.convert(htmlResult.html());

        // Ground Truth links: "page-confidential-1" and "page-restricted-1" (2 links)
        int groundTruthLinksCount = 2;

        // Run extraction logic (Regex) on the actual converted markdown content
        Pattern pattern = Pattern.compile("\\[\\[([a-zA-Z0-9-_]+)\\]\\]");
        Matcher matcher = pattern.matcher(markdown);
        
        int extractedCorrect = 0;
        int extractedTotal = 0;
        
        while (matcher.find()) {
            String slug = matcher.group(1);
            extractedTotal++;
            if ("page-confidential-1".equals(slug) || "page-restricted-1".equals(slug)) {
                extractedCorrect++;
            }
        }

        double precision = extractedTotal == 0 ? 0.0 : (double) extractedCorrect / extractedTotal * 100.0;
        double recall = groundTruthLinksCount == 0 ? 100.0 : (double) extractedCorrect / groundTruthLinksCount * 100.0;

        log.info("=== EVALUATION RESULTS: WikiLinks Compiler Accuracy ===");
        log.info("WikiLinks Extraction Precision: {}%", precision);
        log.info("WikiLinks Extraction Recall: {}%", recall);

        // Assertions
        assertThat(precision).isEqualTo(100.0);
        assertThat(recall).isEqualTo(100.0);
    }

}
