package com.security.security.service.tika;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SemanticMarkdownChunker — Docling HierarchicalChunker + HybridChunker in Java.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Docling Architecture Mapping                                           │
 * ├─────────────────────────────────────┬───────────────────────────────────┤
 * │  Docling (Python)                   │  This class (Java)                │
 * ├─────────────────────────────────────┼───────────────────────────────────┤
 * │  DoclingDocument.iterate_items()    │  parseItems()                     │
 * │  DocItem / TextItem / TableItem     │  DocItem (enum ItemType)          │
 * │  HierarchicalChunker.chunk()        │  hierarchicalChunk()              │
 * │  heading_by_level dict              │  TreeMap<Integer, String>         │
 * │  keys_to_del (level shadow)         │  headingByLevel.tailMap().clear() │
 * │  HybridChunker._split_plain_text()  │  plainTextSplit()                 │
 * │  HybridChunker (table split)        │  tableSplit() with header repeat  │
 * │  repeat_table_header                │  tableSplit() header prepend      │
 * │  _merge_chunks_matching_metadata()  │  mergePeers()                     │
 * │  BaseChunker.contextualize()        │  contextualize()                  │
 * └─────────────────────────────────────┴───────────────────────────────────┘
 *
 * Pipeline phases:
 *
 *  Phase 1 — PARSE ITEMS
 *    Scan Markdown line-by-line, produce flat List<DocItem>.
 *    Item types: HEADING, PARAGRAPH, TABLE, LIST, CODE.
 *    Preserves block integrity (code fences, table rows, list continuation).
 *
 *  Phase 2 — HIERARCHICAL CHUNK  (HierarchicalChunker)
 *    Each content item (non-HEADING) becomes a RawChunk.
 *    A TreeMap<level, title> maintains the live heading stack.
 *    When heading level N appears: all entries with key >= N are removed
 *    (shadowed). This is exactly docling's `keys_to_del` pattern.
 *    Headings are NOT included in chunk text — only in metadata.
 *
 *  Phase 3 — SPLIT OVERSIZED  (HybridChunker._split_using_plain_text)
 *    Chunks exceeding MAX_TOKENS are split.
 *    Tables: row-by-row split with header lines prepended (repeat_table_header).
 *    Text/Lists: split at paragraph → sentence → word boundary.
 *
 *  Phase 4 — MERGE PEERS  (HybridChunker._merge_chunks_with_matching_metadata)
 *    Consecutive undersized chunks (< MIN_TOKENS) with IDENTICAL heading stacks
 *    are merged. This mirrors docling exactly: only same-heading peers merge.
 *
 *  Phase 5 — CONTEXTUALIZE  (BaseChunker.contextualize)
 *    Prepend the full heading stack to each chunk text:
 *      "# H1\n\n## H2\n\n### H3\n\nchunk text..."
 *    This is the string stored in VectorStore and used by LLM for RAG.
 *    The title field uses the deepest (most specific) heading.
 */
@Component
@Slf4j
public class SemanticMarkdownChunker {

    // ── Token budget (same as Docling default 512-token window) ───────────────
    private static final int    MAX_TOKENS      = 400;
    private static final int    MIN_TOKENS      = 60;
    private static final double CHARS_PER_TOKEN = 3.8;  // Vietnamese+English mixed
    private static final int    MAX_CHARS       = (int)(MAX_TOKENS * CHARS_PER_TOKEN);

    // ── Heading detection patterns ────────────────────────────────────────────
    private static final Pattern P_MD = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern P_VN = Pattern.compile(
            "^(CHƯƠNG|Chương|PHẦN|Phần|BÀI|Bài|MỤC|Mục|ĐIỀU|Điều|TIẾT|Tiết|KHOẢN|Khoản|ĐIỂM|Điểm)" +
                    "\\s+([\\dIVXivxA-Za-z]+\\.?)(.{0,160})$");
    private static final Pattern P_EN = Pattern.compile(
            "^(ARTICLE|Article|SECTION|Section|CHAPTER|Chapter|CLAUSE|Clause|PART|Part|APPENDIX|Appendix)" +
                    "\\s+([\\dIVXivx]+\\.?)(.{0,160})$");
    private static final Pattern P_NUM = Pattern.compile(
            "^(\\d{1,2}(\\.\\d{1,2}){0,3})\\.?\\s{1,4}(\\S.{2,100})$");
    private static final Pattern P_CAP = Pattern.compile(
            "^[A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯ][A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯ\\s\\d\\-/&()]{3,79}$");

    // ── Block patterns ─────────────────────────────────────────────────────────
    private static final Pattern P_CODE  = Pattern.compile("^```.*$", Pattern.DOTALL);
    private static final Pattern P_TROW  = Pattern.compile("^\\|.+\\|\\s*$");
    private static final Pattern P_TSEP  = Pattern.compile("^[|\\-:\\s]{3,}$");
    private static final Pattern P_BULL  = Pattern.compile("^([\\-*•]|\\d+[.)]) .+");
    private static final Pattern P_BLANK = Pattern.compile("^\\s*$");

    // ── Item types (mirrors docling DocItemLabel) ──────────────────────────────
    private enum ItemType { HEADING, PARAGRAPH, TABLE, LIST, CODE }

    /** DocItem: one structural element of the document. */
    private record DocItem(ItemType type, String text, int headingLevel) {}

    /** HeadingResult: detected heading title + hierarchy level. */
    private record HeadingResult(String title, int level) {}

    /** RawChunk: chunk before contextualization, with heading metadata. */
    private record RawChunk(String text, List<String> headings, ItemType itemType, Integer parentHeadingIdx) {}

    /** ChunkResult: final public output — contextualized text + display title. */
    public record ChunkResult(String text, String title, Integer parentHeadingIdx, Boolean isParent, Integer headingIdx) {
        public ChunkResult(String text, String title) {
            this(text, title, null, false, null);
        }
    }

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    /**
     * Main entry point: Markdown text → final chunk list.
     *
     * @param markdown  Clean Markdown from HtmlToMarkdownConverter
     * @return          List of ChunkResult, each with contextualized text + title
     */
    public List<ChunkResult> chunk(String markdown) {
        // Phase 1: Parse Markdown → flat structural item list
        List<DocItem> items = parseItems(markdown);
        log.debug("[Chunk] Phase1 items={}", items.size());

        // Phase 2: HierarchicalChunker — assign heading context to each item
        List<RawChunk> rawChunks = hierarchicalChunk(items);
        log.debug("[Chunk] Phase2 hierarchical chunks={}", rawChunks.size());

        // Phase 3: HybridChunker — split oversized chunks
        List<RawChunk> splitChunks = new ArrayList<>();
        for (RawChunk rc : rawChunks) {
            if (estimateTokens(rc.text()) > MAX_TOKENS) {
                List<String> pieces = (rc.itemType() == ItemType.TABLE)
                        ? tableSplit(rc.text())
                        : plainTextSplit(rc.text());
                for (String piece : pieces) {
                    splitChunks.add(new RawChunk(piece, rc.headings(), rc.itemType(), rc.parentHeadingIdx()));
                }
            } else {
                splitChunks.add(rc);
            }
        }
        log.debug("[Chunk] Phase3 after split={}", splitChunks.size());

        // Phase 4: HybridChunker — merge undersized peer chunks
        List<RawChunk> merged = mergePeers(splitChunks);
        log.debug("[Chunk] Phase4 after merge={}", merged.size());

        // Phase 5: Contextualize + filter + build results (children and parent sections)
        List<ChunkResult> children = new ArrayList<>();
        Set<Integer> activeParentIndices = new HashSet<>();
        for (RawChunk rc : merged) {
            if (estimateTokens(rc.text()) < MIN_TOKENS) continue; // drop tiny fragments
            children.add(new ChunkResult(
                    contextualize(rc),
                    detectChunkTitle(rc),
                    rc.parentHeadingIdx(),
                    false,
                    null
            ));
            activeParentIndices.add(rc.parentHeadingIdx());
        }

        List<ChunkResult> parents = new ArrayList<>();
        for (Integer parentIdx : activeParentIndices) {
            String parentText = buildParentSectionText(items, parentIdx);
            String parentTitle = buildParentSectionTitle(items, parentIdx);
            parents.add(new ChunkResult(
                    parentText,
                    parentTitle,
                    null,
                    true,
                    parentIdx
            ));
        }

        List<ChunkResult> result = new ArrayList<>();
        result.addAll(parents);
        result.addAll(children);
        log.debug("[Chunk] Phase5 final parents={}, children={}", parents.size(), children.size());
        return result;
    }

    private String buildParentSectionText(List<DocItem> items, Integer headingIdx) {
        StringBuilder sb = new StringBuilder();
        int startIdx;
        int endIdx = items.size();

        if (headingIdx == -1) {
            // Root Section: all items up to the first heading
            startIdx = 0;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).type() == ItemType.HEADING) {
                    endIdx = i;
                    break;
                }
            }
        } else {
            // Section starting at headingIdx
            startIdx = headingIdx;
            int level = items.get(headingIdx).headingLevel();
            for (int i = headingIdx + 1; i < items.size(); i++) {
                if (items.get(i).type() == ItemType.HEADING && items.get(i).headingLevel() <= level) {
                    endIdx = i;
                    break;
                }
            }
        }

        for (int i = startIdx; i < endIdx; i++) {
            DocItem item = items.get(i);
            if (item.type() == ItemType.HEADING) {
                String prefix = "#".repeat(Math.min(item.headingLevel(), 6));
                sb.append(prefix).append(" ").append(item.text()).append("\n\n");
            } else {
                sb.append(item.text()).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private String buildParentSectionTitle(List<DocItem> items, Integer headingIdx) {
        if (headingIdx == -1) {
            return "Document Overview";
        }
        return items.get(headingIdx).text();
    }

    /** Public token estimator (used by DocumentProcessingListener for metadata). */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }

    // =========================================================================
    //  PHASE 1: PARSE ITEMS
    // =========================================================================

    /**
     * Scan Markdown line by line, produce List<DocItem>.
     *
     * State machine with 4 modes: normal / inCode / inTable / inList.
     * Transitions:
     *  ``` → toggle inCode
     *  | row → enter inTable; blank line → exit inTable
     *  bullet/number → enter inList; non-continuation line → exit inList
     *  # heading → flush pending block, emit HEADING item
     *  blank → flush pending paragraph block
     *  text → append to pending paragraph block
     */
    private List<DocItem> parseItems(String text) {
        List<DocItem>  items  = new ArrayList<>();
        String[]       lines  = text.split("\n", -1);
        StringBuilder  block  = new StringBuilder();

        boolean inCode  = false;
        boolean inTable = false;
        boolean inList  = false;

        for (String line : lines) {
            String t = line.strip();

            // ── Code fence ────────────────────────────────────────────────────
            if (P_CODE.matcher(t).matches()) {
                if (inCode) {
                    // Closing fence: flush code block
                    block.append(line).append("\n");
                    addItem(items, ItemType.CODE, block.toString().trim());
                    block.setLength(0);
                    inCode = false;
                } else {
                    // Opening fence
                    flushBlock(items, block, inTable, inList);
                    inTable = false; inList = false;
                    inCode = true;
                    block.append(line).append("\n");
                }
                continue;
            }
            if (inCode) { block.append(line).append("\n"); continue; }

            // ── Table ─────────────────────────────────────────────────────────
            if (P_TROW.matcher(t).matches() || P_TSEP.matcher(t).matches()) {
                if (!inTable) { flushBlock(items, block, false, inList); inList = false; }
                inTable = true;
                block.append(line).append("\n");
                continue;
            }
            if (inTable) {
                if (P_BLANK.matcher(t).matches()) {
                    // Blank line ends the table
                    addItem(items, ItemType.TABLE, block.toString().trim());
                    block.setLength(0);
                    inTable = false;
                } else {
                    block.append(line).append("\n");
                }
                continue;
            }

            // ── List ──────────────────────────────────────────────────────────
            if (P_BULL.matcher(t).matches()) {
                if (!inList) { flushBlock(items, block, false, false); }
                inList = true;
                block.append(line).append("\n");
                continue;
            }
            if (inList) {
                boolean continuation = P_BLANK.matcher(t).matches()
                        || line.startsWith("  ") || line.startsWith("\t");
                if (continuation) {
                    block.append(line).append("\n");
                    continue;
                }
                // End of list — flush and fall through to process current line
                addItem(items, ItemType.LIST, block.toString().trim());
                block.setLength(0);
                inList = false;
            }

            // ── Blank ─────────────────────────────────────────────────────────
            if (P_BLANK.matcher(t).matches()) {
                flushBlock(items, block, false, false);
                continue;
            }

            // ── Heading ───────────────────────────────────────────────────────
            HeadingResult hr = detectHeading(t);
            if (hr != null) {
                flushBlock(items, block, false, false);
                items.add(new DocItem(ItemType.HEADING, hr.title(), hr.level()));
                continue;
            }

            // ── Paragraph text ────────────────────────────────────────────────
            if (!block.isEmpty() && !block.toString().endsWith(" ")
                    && !block.toString().endsWith("\n")) {
                block.append(" ");
            }
            block.append(t);
        }

        // Flush remaining block
        if (inCode)  addItem(items, ItemType.CODE,  block.toString().trim());
        else if (inTable) addItem(items, ItemType.TABLE, block.toString().trim());
        else if (inList)  addItem(items, ItemType.LIST,  block.toString().trim());
        else flushBlock(items, block, false, false);

        return items;
    }

    private void flushBlock(List<DocItem> items, StringBuilder block, boolean isTable, boolean isList) {
        String content = block.toString().trim();
        if (!content.isEmpty()) {
            ItemType type = isTable ? ItemType.TABLE : isList ? ItemType.LIST : ItemType.PARAGRAPH;
            items.add(new DocItem(type, content, 0));
        }
        block.setLength(0);
    }

    private void addItem(List<DocItem> items, ItemType type, String text) {
        if (!text.isBlank()) items.add(new DocItem(type, text, 0));
    }

    // =========================================================================
    //  PHASE 2: HIERARCHICAL CHUNK  (exact Docling HierarchicalChunker)
    // =========================================================================

    /**
     * Docling HierarchicalChunker logic — faithfully ported to Java.
     *
     * Key invariants (same as Python implementation):
     *
     *  1. headingByLevel is a TreeMap<level, title> (level 1=chapter, 2=section, …)
     *
     *  2. When a HEADING of level N appears:
     *     headingByLevel.tailMap(N).clear()  // shadow: remove N and all deeper
     *     headingByLevel.put(N, title)        // add current heading
     *
     *     This produces the "keys_to_del" behavior in docling:
     *       heading_by_level = {k: v for k, v in heading_by_level.items() if k < new_level}
     *       heading_by_level[new_level] = heading.text
     *
     *  3. Content items produce a RawChunk with headings = List of title values
     *     in ascending key order from the TreeMap. Headings are METADATA only,
     *     not part of chunk text.
     *
     *  4. List items: in this Java implementation we keep them as individual
     *     LIST chunks (not merged per item) because the merge phase will
     *     merge undersized LIST peers with matching headings — same net result.
     */
    private List<RawChunk> hierarchicalChunk(List<DocItem> items) {
        List<RawChunk>          chunks       = new ArrayList<>();
        TreeMap<Integer, String> headingByLevel = new TreeMap<>();
        TreeMap<Integer, Integer> headingIdxByLevel = new TreeMap<>();

        for (int i = 0; i < items.size(); i++) {
            DocItem item = items.get(i);
            if (item.type() == ItemType.HEADING) {
                int level = item.headingLevel();
                // Shadow: remove current level AND all deeper levels (docling's keys_to_del)
                headingByLevel.tailMap(level).clear();
                headingIdxByLevel.tailMap(level).clear();
                headingByLevel.put(level, item.text());
                headingIdxByLevel.put(level, i);
                continue;
            }

            // Content item → emit as chunk with current heading stack
            List<String> headings = headingByLevel.isEmpty()
                    ? List.of()
                    : new ArrayList<>(headingByLevel.values());  // level-ordered

            Integer parentHeadingIdx = headingIdxByLevel.isEmpty()
                    ? -1
                    : headingIdxByLevel.lastEntry().getValue();

            String text = item.text().trim();
            if (!text.isBlank()) {
                chunks.add(new RawChunk(text, headings, item.type(), parentHeadingIdx));
            }
        }

        return chunks;
    }

    // =========================================================================
    //  PHASE 3: SPLIT OVERSIZED
    // =========================================================================

    /**
     * Split oversized TABLE chunks by rows, repeating header lines.
     *
     * Docling repeat_table_header: when a table exceeds the token limit,
     * it is split into sub-tables each prefixed with the original header
     * (first row + separator row). This preserves column semantics for LLMs.
     *
     * Example:
     *   | Col1 | Col2 |     ← header (repeated in every sub-table)
     *   | ---  | ---  |     ← separator (repeated in every sub-table)
     *   | data | data |     ← split here
     *   ...
     */
    private List<String> tableSplit(String tableText) {
        String[] lines = tableText.split("\n");
        if (lines.length < 3) return List.of(tableText);

        // Detect header: first row(s) before the separator line
        List<String> headerLines = new ArrayList<>();
        int bodyStart = 0;
        for (int i = 0; i < lines.length && i < 4; i++) {
            String t = lines[i].strip();
            if (t.matches("^[|\\-:\\s]{3,}$")) {
                // This IS the separator line
                headerLines.add(lines[i]);
                bodyStart = i + 1;
                break;
            }
            headerLines.add(lines[i]);
        }
        if (bodyStart == 0) bodyStart = headerLines.size();

        String header         = String.join("\n", headerLines);
        int    headerTokens   = estimateTokens(header);
        int    availTokens    = MAX_TOKENS - headerTokens - 10; // safety buffer
        if (availTokens <= 0) return List.of(tableText);

        List<String>  result  = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);

        for (int i = bodyStart; i < lines.length; i++) {
            String candidate = current + "\n" + lines[i];
            if (estimateTokens(candidate) > MAX_TOKENS && current.length() > header.length()) {
                result.add(current.toString().trim());
                current = new StringBuilder(header); // repeat header
            }
            current.append("\n").append(lines[i]);
        }
        if (current.length() > header.length()) {
            result.add(current.toString().trim());
        }

        return result.isEmpty() ? List.of(tableText) : result;
    }

    /**
     * Split oversized text/list chunks at natural boundaries:
     *  1. Paragraph boundary (\n\n)
     *  2. Sentence boundary (". ")
     *  3. Word boundary (space)
     * Falls back to hard split if none found.
     */
    private List<String> plainTextSplit(String text) {
        List<String> result = new ArrayList<>();
        int pos = 0;

        while (pos < text.length()) {
            int end = Math.min(pos + MAX_CHARS, text.length());

            if (end < text.length()) {
                // Try paragraph boundary first
                int paraBreak = text.lastIndexOf("\n\n", end);
                if (paraBreak > pos) {
                    end = paraBreak;
                } else {
                    // Try sentence boundary
                    int sentBreak = text.lastIndexOf(". ", end);
                    if (sentBreak > pos) {
                        end = sentBreak + 1;
                    } else {
                        // Try word boundary
                        int spBreak = text.lastIndexOf(' ', end);
                        if (spBreak > pos) end = spBreak;
                    }
                }
            }

            String piece = text.substring(pos, end).trim();
            if (!piece.isEmpty()) result.add(piece);

            pos = end;
            // Skip leading whitespace after break point
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        return result.isEmpty() ? List.of(text) : result;
    }

    // =========================================================================
    //  PHASE 4: MERGE PEERS  (exact Docling _merge_chunks_with_matching_metadata)
    // =========================================================================

    /**
     * Merge consecutive undersized chunks that share the SAME heading stack.
     *
     * Docling condition: chunks are "peers" if heading metadata is identical.
     * Merge stops when:
     *  (a) heading stacks differ (different section)
     *  (b) merged token count would exceed MAX_TOKENS
     *
     * This is a greedy left-to-right merge — same as Docling's implementation.
     */
    private List<RawChunk> mergePeers(List<RawChunk> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<RawChunk> output = new ArrayList<>();
        int i = 0;

        while (i < chunks.size()) {
            RawChunk      current       = chunks.get(i);
            StringBuilder mergedText   = new StringBuilder(current.text());
            List<String>  curHeadings  = current.headings();
            int j = i + 1;

            // Greedily merge subsequent peers with same heading stack
            while (j < chunks.size()) {
                RawChunk next = chunks.get(j);

                // Condition 1: same heading stack (docling: matching metadata)
                if (!next.headings().equals(curHeadings)) break;

                // Condition 2: merged size within budget
                String candidate = mergedText + "\n\n" + next.text();
                if (estimateTokens(candidate) > MAX_TOKENS) break;

                mergedText.append("\n\n").append(next.text());
                j++;
            }

            output.add(new RawChunk(mergedText.toString().trim(), curHeadings, current.itemType(), current.parentHeadingIdx()));
            i = j; // advance past all merged chunks
        }

        return output;
    }

    // =========================================================================
    //  PHASE 5: CONTEXTUALIZE + TITLE  (Docling BaseChunker.contextualize)
    // =========================================================================

    /**
     * Serialize chunk with full heading context prepended.
     *
     * Docling BaseChunker.contextualize() prepends headings as context so the
     * embedding model and LLM receive the full document position:
     *
     *   # Chapter 1: Introduction
     *
     *   ## 1.1 Background
     *
     *   ### 1.1.1 Related Work
     *
     *   <chunk body text>
     *
     * The heading lines use correct # depth (1 → #, 2 → ##, 3 → ###, etc.).
     * This mirrors docling's serialization format exactly.
     */
    private String contextualize(RawChunk rc) {
        if (rc.headings().isEmpty()) return rc.text();

        StringBuilder sb = new StringBuilder();
        List<String> headings = rc.headings();
        for (int i = 0; i < headings.size(); i++) {
            String prefix = "#".repeat(Math.min(i + 1, 6));
            sb.append(prefix).append(" ").append(headings.get(i)).append("\n\n");
        }
        sb.append(rc.text());
        return sb.toString().trim();
    }

    /**
     * Detect display title for the chunk.
     *
     * Priority:
     *  1. Deepest (most specific) heading from the heading stack
     *  2. First sentence from chunk text (if ≤ 80 chars)
     *  3. First 80 chars of chunk text + "…"
     */
    private String detectChunkTitle(RawChunk rc) {
        // Use deepest heading as title (most specific context)
        if (!rc.headings().isEmpty()) {
            return rc.headings().get(rc.headings().size() - 1);
        }

        // Fallback: first sentence or truncated text
        String text = rc.text();
        if (text.length() <= 80) return text.replaceAll("\\s+", " ").trim();

        int dot = text.indexOf(". ");
        if (dot > 10 && dot < 80) return text.substring(0, dot + 1);

        return text.substring(0, 80).replaceAll("\\s+", " ").trim() + "\u2026";
    }

    // =========================================================================
    //  HEADING DETECTION
    // =========================================================================

    /**
     * Detect whether a Markdown line is a structural heading.
     * Returns HeadingResult with title and hierarchy level, or null if not a heading.
     *
     * Detection order:
     *  1. Markdown # markers (most reliable, output by HtmlToMarkdownConverter)
     *  2. Vietnamese legal: Chương / Phần / Điều / Mục / Khoản / Tiết / Điểm
     *  3. English legal: Chapter / Section / Article / Part / Clause / Appendix
     *  4. Numbered: "1. Title" / "2.1. Sub" / "2.1.1. Deep"
     *  5. ALL-CAPS short line (no comma, no trailing period)
     */
    private HeadingResult detectHeading(String line) {
        if (line == null || line.isBlank() || line.length() > 200) return null;
        Matcher m;

        // 1. Markdown heading: "# Title", "## Subtitle"
        m = P_MD.matcher(line);
        if (m.matches()) {
            String title       = m.group(2).trim();
            int    rawLevel    = m.group(1).length();
            int    finalLevel  = correctNumberedLevel(title, rawLevel);
            return new HeadingResult(title, finalLevel);
        }

        // 2. Vietnamese legal
        m = P_VN.matcher(line);
        if (m.matches()) {
            int lv = switch (m.group(1).toLowerCase()) {
                case "chương", "phần", "bài" -> 1;
                case "mục", "điều"           -> 2;
                case "khoản", "tiết"         -> 3;
                case "điểm"                  -> 4;
                default                      -> 2;
            };
            return new HeadingResult(line.trim(), lv);
        }

        // 3. English legal
        m = P_EN.matcher(line);
        if (m.matches()) {
            int lv = switch (m.group(1).toLowerCase()) {
                case "chapter", "part", "appendix" -> 1;
                case "article", "section"          -> 2;
                case "clause"                      -> 3;
                default                            -> 2;
            };
            return new HeadingResult(line.trim(), lv);
        }

        // 4. Numbered sections
        m = P_NUM.matcher(line);
        if (m.matches()) {
            String tp = m.group(3);
            if (!tp.endsWith(".") && tp.chars().filter(c -> c == ',').count() <= 2) {
                int dots  = (int) m.group(1).chars().filter(c -> c == '.').count();
                // "1" (0 dots) → level 2; "1.1" (1 dot) → level 3; "1.1.1" (2 dots) → level 4
                int level = Math.min(dots + 2, 4);
                return new HeadingResult(line.trim(), level);
            }
        }

        // 5. ALL-CAPS
        if (P_CAP.matcher(line).matches() && !line.contains(",") && !line.endsWith("."))
            return new HeadingResult(line.trim(), 1);

        return null;
    }

    /**
     * Correct heading level when title text reveals actual nesting depth.
     * Applied after # detection to override incorrect HTML-level heading depth.
     *
     * "1. Title"       → level 2 (##)
     * "2.1. SubTitle"  → level 3 (###)
     * "2.1.1. Feature" → level 4 (####)
     */
    private int correctNumberedLevel(String title, int rawLevel) {
        if (title.matches("^\\d+\\.\\d+\\.\\d+\\s+.*") || title.matches("^\\d+\\.\\d+\\.\\d+\\.\\s+.*")) return 4;
        if (title.matches("^\\d+\\.\\d+\\s+.*") || title.matches("^\\d+\\.\\d+\\.\\s+.*"))         return 3;
        if (title.matches("^\\d+\\s+.*") || title.matches("^\\d+\\.\\s+.*"))                 return 2;
        return rawLevel;
    }
}