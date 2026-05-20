package com.security.security.service.tika;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DocumentProfiler — Docling-style document statistics analyzer.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Docling Architecture Mapping                                           │
 * ├────────────────────────────────────┬────────────────────────────────────┤
 * │  Docling (Python)                  │  This class (Java)                 │
 * ├────────────────────────────────────┼────────────────────────────────────┤
 * │  DoclingDocument.iterate_items()   │  count from Markdown text          │
 * │  DoclingDocument.export_to_dict()  │  ProfileResult record              │
 * │  profiler module (docling-core)    │  profile() method                  │
 * │  DoclingDocument stats             │  numHeadings, numTables, etc.      │
 * └────────────────────────────────────┴────────────────────────────────────┘
 *
 * Computed from the final Markdown (after HtmlToMarkdownConverter) and the
 * chunk list (from SemanticMarkdownChunker), this provides:
 *
 *  - Structural counts: headings by level, paragraphs, tables, lists, code
 *  - Token statistics: total, avg/chunk, min/chunk, max/chunk
 *  - Used to populate the Document entity for dashboard analytics
 */
@Component
@Slf4j
public class DocumentProfiler {

    private static final double CHARS_PER_TOKEN = 3.8;

    // ── Markdown element detection patterns ───────────────────────────────────
    private static final Pattern P_HEADING   = Pattern.compile("^#{1,6}\\s+.+$", Pattern.MULTILINE);
    private static final Pattern P_TABLE_ROW = Pattern.compile("^\\|.+\\|\\s*$", Pattern.MULTILINE);
    private static final Pattern P_LIST_ITEM = Pattern.compile("^\\s*([\\-*•]|\\d+[.)]) .+$", Pattern.MULTILINE);
    private static final Pattern P_CODE_FENCE= Pattern.compile("^```", Pattern.MULTILINE);
    // Paragraph: non-blank line ≥ 10 chars that isn't a heading, table, list, or code
    private static final Pattern P_PARAGRAPH = Pattern.compile(
            "^(?!#|\\||\\s*[-*•]|\\s*\\d+[.)]|```)\\S.{10,}$", Pattern.MULTILINE);

    // =========================================================================
    //  PUBLIC TYPES
    // =========================================================================

    /**
     * Full document profile result.
     *
     * Mirrors docling-core's DoclingDocument statistics:
     *  numHeadings     → number of # lines (any level)
     *  numParagraphs   → number of text paragraphs
     *  numTables       → number of distinct Markdown tables
     *  numListItems    → number of list item lines
     *  numCodeBlocks   → number of ``` ... ``` fences (pairs)
     *  headingsByLevel → Map<level, count> e.g. {1→3, 2→8, 3→12}
     *  totalChars      → total character count in Markdown
     *  estimatedTokens → total token estimate
     *  numChunks       → number of final chunks
     *  avgTokensPerChunk, minTokensPerChunk, maxTokensPerChunk → chunk stats
     */
    public record ProfileResult(
            int numHeadings,
            int numParagraphs,
            int numTables,
            int numListItems,
            int numCodeBlocks,
            int totalChars,
            int estimatedTokens,
            int numChunks,
            int avgTokensPerChunk,
            int minTokensPerChunk,
            int maxTokensPerChunk,
            Map<Integer, Integer> headingsByLevel
    ) {
        /** Zero-value result for error/empty cases. */
        public static ProfileResult empty() {
            return new ProfileResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
        }
    }

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    /**
     * Profile a document from its Markdown representation and chunk results.
     *
     * @param markdown     Final Markdown from HtmlToMarkdownConverter
     * @param chunkResults Final chunks from SemanticMarkdownChunker
     * @return ProfileResult with all structural and token statistics
     */
    public ProfileResult profile(String markdown,
                                 List<SemanticMarkdownChunker.ChunkResult> chunkResults) {
        if (markdown == null || markdown.isBlank()) return ProfileResult.empty();

        // ── Structural counts ──────────────────────────────────────────────────
        int numHeadings  = countMatches(P_HEADING,   markdown);
        int numParagraphs= countMatches(P_PARAGRAPH, markdown);
        int numListItems = countMatches(P_LIST_ITEM, markdown);

        // Code blocks: count pairs of ```
        int numCodeFences = countMatches(P_CODE_FENCE, markdown);
        int numCodeBlocks = numCodeFences / 2;

        // Tables: distinct groups of consecutive | rows
        int numTables = estimateTableCount(markdown);

        // ── Heading level distribution ─────────────────────────────────────────
        Map<Integer, Integer> headingsByLevel = new TreeMap<>();
        Matcher hm = Pattern.compile("^(#{1,6})\\s+", Pattern.MULTILINE).matcher(markdown);
        while (hm.find()) {
            int level = hm.group(1).length();
            headingsByLevel.merge(level, 1, Integer::sum);
        }

        // ── Total token estimate ───────────────────────────────────────────────
        int totalChars      = markdown.length();
        int estimatedTokens = estimateTokens(totalChars);

        // ── Chunk-level statistics ─────────────────────────────────────────────
        int numChunks = chunkResults.size();
        int avgTokens = 0, minTokens = 0, maxTokens = 0;

        if (numChunks > 0) {
            int[] tokenCounts = chunkResults.stream()
                    .mapToInt(cr -> estimateTokens(cr.text().length()))
                    .toArray();

            int sum = 0;
            minTokens = Integer.MAX_VALUE;
            maxTokens = Integer.MIN_VALUE;
            for (int tc : tokenCounts) {
                sum      += tc;
                minTokens = Math.min(minTokens, tc);
                maxTokens = Math.max(maxTokens, tc);
            }
            avgTokens = sum / numChunks;
        }

        ProfileResult result = new ProfileResult(
                numHeadings, numParagraphs, numTables, numListItems, numCodeBlocks,
                totalChars, estimatedTokens,
                numChunks, avgTokens, minTokens, maxTokens,
                headingsByLevel
        );

        log.info("[Profile] h={} p={} t={} l={} code={} tokens={} chunks={} avg={}/chunk",
                numHeadings, numParagraphs, numTables, numListItems, numCodeBlocks,
                estimatedTokens, numChunks, avgTokens);

        return result;
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private int countMatches(Pattern p, String text) {
        int count = 0;
        Matcher m = p.matcher(text);
        while (m.find()) count++;
        return count;
    }

    private int estimateTokens(int charCount) {
        return Math.max(1, (int) Math.ceil(charCount / CHARS_PER_TOKEN));
    }

    /**
     * Count distinct tables by detecting groups of consecutive table rows.
     * A table starts when the first | row appears and ends at a blank/non-table line.
     */
    private int estimateTableCount(String markdown) {
        int     count   = 0;
        boolean inTable = false;

        for (String line : markdown.split("\n")) {
            String t = line.strip();
            boolean isTableLine = (t.startsWith("|") && t.endsWith("|"))
                    || t.matches("^[|\\-:\\s]{3,}$");

            if (isTableLine && !inTable) {
                inTable = true;
                count++;
            } else if (!isTableLine && !t.isEmpty()) {
                inTable = false;
            }
        }
        return count;
    }
}