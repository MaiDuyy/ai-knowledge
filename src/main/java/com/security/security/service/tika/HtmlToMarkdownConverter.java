package com.security.security.service.tika;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HtmlToMarkdownConverter — Docling "SimplePipeline / HTMLDocumentBackend" equivalent.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  Docling Architecture Mapping                                           │
 * ├──────────────────────────────┬──────────────────────────────────────────┤
 * │  Docling (Python)            │  This class (Java)                       │
 * ├──────────────────────────────┼──────────────────────────────────────────┤
 * │  HTMLDocumentBackend         │  HtmlToMarkdownConverter                 │
 * │  SimplePipeline._build_doc() │  convert()                               │
 * │  DoclingDocument             │  String (Markdown)                       │
 * │  DoclingDocument.body.items  │  Markdown structural elements            │
 * │  export_to_markdown()        │  (already in Markdown format)            │
 * └──────────────────────────────┴──────────────────────────────────────────┘
 *
 * Pipeline stages:
 *
 *  G3 — DEDUPLICATION
 *       PDF produces duplicate text (text layer + OCR layer).
 *       Jaccard similarity on word sets removes duplicates at element level.
 *       cleanTandemSubstrings() catches intra-line "TextText" duplicates.
 *       removeRepeatedHeadersFooters() removes repeated page header/footer lines
 *       (e.g., "Trường Đại học X" appearing on every page).
 *
 *  G4 — HTML → MARKDOWN  (Jsoup NodeTraversor visitor)
 *       NodeVisitor with skipDepth: block elements (h1, p, table, ul, pre)
 *       are handled atomically by their head() call, children are skipped.
 *       Container elements (div, section, body) let children through.
 *
 *  G4+ — PDF HEADING PROMOTION
 *         Tika outputs PDF text as flat <p> tags with no h1-h6.
 *         promotePdfHeadings() converts structured <p> to <h2>/<h3>/<h4>
 *         based on ALL-CAPS, bold-wrap, numbered-section, and VN/EN patterns.
 *
 *  G4++ — MARKDOWN HEADING NORMALIZER (post-conversion safety net)
 *          normalizeMarkdownHeadings() re-scans final Markdown and promotes
 *          any plain-text lines that pattern-match as headings but were missed
 *          by HTML-level promotion (e.g., TXT files, unusual PDF encodings).
 */
@Component
@Slf4j
public class HtmlToMarkdownConverter {

    // ── Dedup thresholds ──────────────────────────────────────────────────────
    /** Jaccard threshold for element-level dedup (0.0–1.0). */
    private static final double DEDUP_THRESHOLD              = 0.88;
    /**
     * Header/footer frequency threshold: a line appearing in >= this fraction
     * of heading-bounded segments is considered a repeated header/footer.
     */
    private static final double HEADER_FOOTER_FREQ_THRESHOLD = 0.6;
    /** Minimum number of segments to apply header/footer detection. */
    private static final int    MIN_HEADER_FOOTER_SEGMENTS   = 3;

    // ── Heading normalizer patterns (post-Markdown safety net) ────────────────
    private static final Pattern NP_NUM = Pattern.compile(
            "^(\\d{1,2}(\\.\\d{1,2}){0,3})\\.\\s{1,4}(\\S.{2,100})$");
    private static final Pattern NP_VN  = Pattern.compile(
            "(?i)^(Chương|Phần|Bài|Mục|Điều|Tiết|Khoản)\\s+([\\dIVXivx]+\\.?)(.*)");
    private static final Pattern NP_EN  = Pattern.compile(
            "(?i)^(Chapter|Section|Part|Article|Appendix)\\s+([\\dIVXivx]+\\.?)(.*)");
    private static final Pattern NP_CAP = Pattern.compile(
            "^[A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯ][A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯ\\s\\d\\-/&()]{3,79}$");

    // =========================================================================
    //  PUBLIC API
    // =========================================================================

    /**
     * Convert raw Tika HTML → clean Markdown.
     *
     * @param html  HTML string from TikaHtmlExtractor
     * @return clean Markdown preserving headings, tables, lists, code blocks
     */
    public String convert(String html) {
        // Parse HTML with Jsoup
        Document doc = Jsoup.parse(html);

        // Remove purely non-semantic tags
        doc.select("style, script, meta, link, head, noscript").remove();

        // ── G3: Deduplication (PDF text-layer + OCR-layer fix) ────────────────
        String deduped = deduplicateHtmlContent(doc);

        // Re-parse deduped HTML
        Document mdDoc = Jsoup.parse(deduped);

        // ── G4: PDF heading promotion ─────────────────────────────────────────
        int nativeBefore = mdDoc.select("h1, h2, h3, h4, h5, h6").size();
        promotePdfHeadings(mdDoc);
        int nativeAfter = mdDoc.select("h1, h2, h3, h4, h5, h6").size();
        log.debug("[Convert] Headings: native={}, after promotion={} (+{})",
                nativeBefore, nativeAfter, nativeAfter - nativeBefore);

        // ── G4: DOM → Markdown via NodeVisitor ────────────────────────────────
        StringBuilder out = new StringBuilder();
        Element body = mdDoc.body() != null ? mdDoc.body() : mdDoc.root();
        NodeTraversor.traverse(new MarkdownNodeVisitor(out), body);

        // ── Sanitize ──────────────────────────────────────────────────────────
        String markdown = sanitizeMarkdown(out.toString());

        // ── Remove repeated header/footer lines (n-gram heuristic) ───────────
        markdown = removeRepeatedHeadersFooters(markdown);

        // ── G4++: Markdown heading normalizer (post-conversion safety net) ────
        long hBefore = markdown.lines().filter(l -> l.strip().startsWith("#")).count();
        markdown = normalizeMarkdownHeadings(markdown);
        long hAfter  = markdown.lines().filter(l -> l.strip().startsWith("#")).count();
        log.debug("[Convert] Markdown headings: before normalizer={}, after={} (+{})",
                hBefore, hAfter, hAfter - hBefore);

        return markdown;
    }

    // =========================================================================
    //  G3 — DEDUPLICATION
    // =========================================================================

    /**
     * Element-level deduplication using Jaccard similarity on word sets.
     *
     * Root cause: many PDF files contain two text layers:
     *  (a) the native text from font/glyph data
     *  (b) an OCR text overlay for searchability
     * Tika reads both layers → every paragraph appears twice in HTML.
     *
     * Strategy: for each element type (p, li, td, th, h1-h6), compare
     * consecutive siblings. If Jaccard(prev, curr) ≥ threshold → keep the
     * longer one (more complete copy), remove the shorter duplicate.
     */
    private String deduplicateHtmlContent(Document doc) {
        for (String sel : new String[]{"p", "li", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6"}) {
            deduplicateSelector(doc, sel);
        }
        return doc.outerHtml();
    }

    private void deduplicateSelector(Document doc, String selector) {
        List<Element> elements = doc.select(selector);
        Element prevEl = null;

        for (Element el : elements) {
            String curr = el.text().strip();
            if (curr.isBlank()) continue;

            if (prevEl != null) {
                String prev = prevEl.text().strip();
                if (jaccardSimilarity(prev, curr) >= DEDUP_THRESHOLD) {
                    // Keep the longer (more complete) version
                    if (curr.length() >= prev.length()) {
                        prevEl.remove();
                        prevEl = el;
                    } else {
                        el.remove();
                        // prevEl stays unchanged
                    }
                } else {
                    prevEl = el;
                }
            } else {
                prevEl = el;
            }
        }
    }

    /**
     * Jaccard coefficient on word sets.
     * Only computed if length ratio ≥ 0.6 (prevents false positives on short phrases).
     */
    private double jaccardSimilarity(String a, String b) {
        if (a.isBlank() || b.isBlank()) return 0.0;
        double ratio = (double) Math.min(a.length(), b.length()) / Math.max(a.length(), b.length());
        if (ratio < 0.6) return 0.0;

        Set<String> wa = new HashSet<>(Arrays.asList(a.toLowerCase().split("\\s+")));
        Set<String> wb = new HashSet<>(Arrays.asList(b.toLowerCase().split("\\s+")));

        Set<String> intersect = new HashSet<>(wa);
        intersect.retainAll(wb);

        Set<String> union = new HashSet<>(wa);
        union.addAll(wb);

        return union.isEmpty() ? 0.0 : (double) intersect.size() / union.size();
    }

    // =========================================================================
    //  G4 — PDF HEADING PROMOTION
    // =========================================================================

    /**
     * Promote <p> elements that are structural headings to <h2>/<h3>/<h4>.
     *
     * Why only <p> tags?
     *  - DOCX: Tika already outputs native <h1>-<h6> for styled headings
     *  - PDF:  Tika outputs ALL content as <p>, losing heading structure
     *  - TXT:  Tika outputs <p>; heading normalizer catches the rest
     *
     * Detection heuristics (in priority order):
     *  1. CSS class: "title", "heading", "h1", "h2", "h3"
     *  2. Bold-wrapped single child: <p><b>Title</b></p>
     *  3. Numbered section: "1. Intro", "2.1. Backend", "2.1.1. Feature"
     *  4. Vietnamese legal: "Chương I", "Điều 5", "Mục 2"
     *  5. English legal: "Chapter 1", "Section 3", "Article 7"
     *  6. ALL-CAPS short line: "TỔNG QUAN", "ABSTRACT"
     */
    private void promotePdfHeadings(Document doc) {
        for (Element p : new ArrayList<>(doc.select("p"))) {
            String text = p.text().strip();
            if (text.isEmpty() || text.length() > 200) continue;

            String cls = p.className().toLowerCase();

            // 1. CSS class-based
            boolean isClass = cls.contains("title") || cls.contains("heading")
                    || cls.contains("h1") || cls.contains("h2") || cls.contains("h3");

            // 2. Bold-wrapped single child
            boolean isBoldWrapped = false;
            if (p.children().size() == 1) {
                String ct = p.child(0).tagName().toLowerCase();
                if (ct.equals("b") || ct.equals("strong")) {
                    String inner = p.child(0).text().strip();
                    isBoldWrapped = inner.length() >= 3 && inner.length() <= 120
                            && !inner.endsWith(".") && !inner.contains(",");
                }
            }

            // 3. Numbered section detection
            boolean isNumberedSection = (text.matches("^\\d{1,2}\\.\\s+\\S.{2,100}") && !text.endsWith("."))
                    || (text.matches("^\\d{1,2}\\.\\d{1,2}\\.\\s+\\S.{2,100}") && !text.endsWith("."))
                    || (text.matches("^\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}\\.\\s+\\S.{2,60}") && !text.endsWith("."));

            // 4. Vietnamese legal/academic headings
            boolean isVnHeading = text.matches("(?i)^(Chương|Phần|Bài|Mục|Điều|Tiết|Khoản)\\s+.+");

            // 5. English legal headings
            boolean isEnHeading = text.matches("(?i)^(Chapter|Section|Part|Article|Appendix)\\s+.+")
                    && text.length() <= 100;

            // 6. ALL-CAPS detection (letters only, excluding digits/punctuation)
            String lettersOnly = text.replaceAll("[\\d\\s\\-/:()&.,]", "");
            boolean isAllCap = text.length() >= 4
                    && lettersOnly.length() >= 3
                    && lettersOnly.equals(lettersOnly.toUpperCase())
                    && !text.endsWith(".")
                    && !text.contains(",");

            // Promote to appropriate heading level
            if (isClass || isAllCap || isVnHeading || isEnHeading || isBoldWrapped) {
                p.tagName("h2");
            } else if (isNumberedSection) {
                if (text.matches("^\\d+\\.\\d+\\.\\d+\\.\\s+.*")) p.tagName("h4");
                else if (text.matches("^\\d+\\.\\d+\\.\\s+.*"))  p.tagName("h3");
                else                                               p.tagName("h2");
            }
        }
    }

    // =========================================================================
    //  G4 — DOM → MARKDOWN  (Jsoup NodeTraversor)
    // =========================================================================

    /**
     * Jsoup NodeTraversor with MarkdownNodeVisitor.
     *
     * Design:
     *  - Block elements (h1-h6, p, table, ul, ol, pre, code):
     *    → head() handles entire element via inlineText() or dedicated appender
     *    → skipDepth increments so NodeTraversor skips all descendants
     *  - Container elements (div, section, body, etc.):
     *    → head() does nothing; children are visited normally
     *  - Inline elements (b, strong, i, em, a, br):
     *    → handled inline by inlineText() when called from a block element
     *    → at top-level: head()/tail() emit bold/italic markers
     *
     * skipDepth tracks nesting: when skipDepth > 0, all descendant nodes are
     * skipped. tail() decrements skipDepth when an Element closes.
     */
    private class MarkdownNodeVisitor implements NodeVisitor {
        private final StringBuilder out;
        /** Tracks last emitted heading to suppress consecutive duplicates. */
        private String lastHeadingKey = "";
        /**
         * Depth counter for skipping descendants of a fully-handled block element.
         * 0 = normal traversal. >0 = skipping.
         */
        private int skipDepth = 0;

        MarkdownNodeVisitor(StringBuilder out) { this.out = out; }

        @Override
        public void head(Node node, int depth) {
            // Skip descendants of a handled block element
            if (skipDepth > 0) {
                if (node instanceof Element) skipDepth++;
                return;
            }

            // Text node at top level (outside any tracked block element)
            if (node instanceof TextNode tn) {
                String t = tn.text();
                if (!t.isBlank()) out.append(t).append(" ");
                return;
            }
            if (!(node instanceof Element el)) return;

            String tag = el.tagName().toLowerCase();

            switch (tag) {
                // ── Headings ──────────────────────────────────────────────────
                case "h1" -> { appendHeading(el, 1); skipDepth = 1; }
                case "h2" -> { appendHeading(el, 2); skipDepth = 1; }
                case "h3" -> { appendHeading(el, 3); skipDepth = 1; }
                case "h4" -> { appendHeading(el, 4); skipDepth = 1; }
                case "h5" -> { appendHeading(el, 5); skipDepth = 1; }
                case "h6" -> { appendHeading(el, 6); skipDepth = 1; }

                // ── Paragraph ─────────────────────────────────────────────────
                case "p" -> {
                    String text = inlineText(el).strip();
                    if (!text.isEmpty()) out.append(text).append("\n\n");
                    skipDepth = 1;
                }

                // ── Lists ──────────────────────────────────────────────────────
                case "ul" -> { appendList(el, false, 0); skipDepth = 1; }
                case "ol" -> { appendList(el, true, 0);  skipDepth = 1; }

                // ── Table ──────────────────────────────────────────────────────
                case "table" -> { appendTable(el); skipDepth = 1; }

                // ── Code blocks ───────────────────────────────────────────────
                case "pre" -> {
                    String code = el.wholeText().strip();
                    if (!code.isEmpty()) out.append("```\n").append(code).append("\n```\n\n");
                    skipDepth = 1;
                }
                case "code" -> {
                    // Only inline code (not inside <pre>)
                    if (!isDescendantOf(el, "pre")) {
                        String c = el.text().strip();
                        if (!c.isEmpty()) out.append("`").append(c).append("` ");
                    }
                    skipDepth = 1;
                }

                // ── Structural separators ─────────────────────────────────────
                case "br" -> out.append("\n");
                case "hr" -> out.append("\n---\n\n");

                // ── Skip: no semantic content for us ──────────────────────────
                case "script", "style", "meta", "link", "noscript",
                     "form", "input", "button", "select", "option" -> skipDepth = 1;

                // ── Blockquote ────────────────────────────────────────────────
                case "blockquote" -> {
                    if (!hasBlockChild(el)) {
                        String text = inlineText(el).strip();
                        if (!text.isEmpty()) out.append("> ").append(text.replace("\n", "\n> ")).append("\n\n");
                        skipDepth = 1;
                    }
                    // if it has block children, fall through to recurse
                }

                // ── Container elements: let children through ──────────────────
                case "body", "html", "thead", "tbody", "tfoot" -> { /* recurse */ }

                // ── Inline formatting (top-level edge case) ───────────────────
                case "b", "strong" -> out.append("**");
                case "i", "em"     -> out.append("*");

                // ── Fallback ──────────────────────────────────────────────────
                default -> {
                    if (hasBlockChild(el)) {
                        // Act as container, let NodeTraversor visit children
                    } else {
                        // Leaf element
                        String text = inlineText(el).strip();
                        if (!text.isEmpty()) {
                            if (isBlockTag(tag)) {
                                // Block elements with no block children act as paragraphs (Docling parity)
                                out.append(text).append("\n\n");
                            } else {
                                // Inline elements act as text
                                out.append(text).append(" ");
                            }
                        }
                        skipDepth = 1;
                    }
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {
            if (skipDepth > 0) {
                if (node instanceof Element) skipDepth--;
                return;
            }
            if (!(node instanceof Element el)) return;
            // Close inline bold/italic markers
            String tag = el.tagName().toLowerCase();
            switch (tag) {
                case "b", "strong" -> out.append("**");
                case "i", "em"     -> out.append("*");
            }
        }

        // ── appendHeading ─────────────────────────────────────────────────────

        /**
         * Emit a Markdown heading.
         *
         * Level correction: even if Tika emits <h2> for "1. Title", we correct
         * to ##(level 2) based on numbered-section pattern detection.
         * Consecutive identical headings (dedup missed one) are suppressed.
         */
        private void appendHeading(Element el, int level) {
            String title = inlineText(el).strip();
            if (title.isEmpty()) return;

            // Suppress consecutive duplicate headings
            String key = title.toLowerCase();
            if (key.equals(lastHeadingKey)) return;
            lastHeadingKey = key;

            // Correct level from numbered-section pattern
            int correctedLevel = correctLevelFromTitle(title, level);

            // Ensure double newline before heading
            String current = out.toString();
            if (!current.isEmpty() && !current.endsWith("\n\n")) {
                out.append(current.endsWith("\n") ? "\n" : "\n\n");
            }

            out.append("#".repeat(Math.min(correctedLevel, 6)))
                    .append(" ").append(title).append("\n\n");
        }

        /**
         * Correct heading level from numbered-section title.
         * "1. Title"       → level 2 (##)
         * "2.1. SubTitle"  → level 3 (###)
         * "2.1.1. Feature" → level 4 (####)
         */
        private int correctLevelFromTitle(String title, int fallback) {
            if (title.matches("^\\d+\\.\\d+\\.\\d+\\.\\s+\\S.*")) return 4;
            if (title.matches("^\\d+\\.\\d+\\.\\s+\\S.*"))         return 3;
            if (title.matches("^\\d+\\.\\s+\\S.*"))                 return 2;
            return fallback;
        }

        // ── appendList ────────────────────────────────────────────────────────

        private void appendList(Element listEl, boolean ordered, int depth) {
            String indent = "  ".repeat(depth);
            int n = 1;

            for (Element li : listEl.select("> li")) {
                // Collect direct text from <li>, skipping nested <ul>/<ol>
                StringBuilder liTxt = new StringBuilder();
                for (Node nd : li.childNodes()) {
                    if (nd instanceof TextNode tn && !tn.text().isBlank()) {
                        liTxt.append(tn.text().strip()).append(" ");
                    } else if (nd instanceof Element ch) {
                        String ct = ch.tagName().toLowerCase();
                        if (!ct.equals("ul") && !ct.equals("ol")) {
                            liTxt.append(inlineText(ch).strip()).append(" ");
                        }
                    }
                }

                String itemText = liTxt.toString().strip();
                if (!itemText.isEmpty()) {
                    if (ordered) out.append(indent).append(n++).append(". ").append(itemText).append("\n");
                    else         out.append(indent).append("- ").append(itemText).append("\n");
                }

                // Recurse into nested lists
                for (Element child : li.select("> ul, > ol")) {
                    appendList(child, child.tagName().equals("ol"), depth + 1);
                }
            }
            if (depth == 0) out.append("\n");
        }

        // ── appendTable ───────────────────────────────────────────────────────

        /**
         * Convert <table> → Markdown table.
         *
         * Features:
         *  - thead/tbody/tfoot aware (correct row ordering)
         *  - colspan: fills empty cells to maintain column alignment
         *  - Pipe escape: | → \| in cell text
         *  - Auto header separator: |---|---|
         *  - Fallback: if no rows found, emit as inline text
         */
        private void appendTable(Element table) {
            List<Element> trList = new ArrayList<>();
            // Preserve section order: thead → tbody → tfoot
            for (Element sec : table.select("thead, tbody, tfoot")) {
                trList.addAll(sec.select("> tr"));
            }
            if (trList.isEmpty()) trList.addAll(table.select("> tr"));

            if (trList.isEmpty()) {
                // Fallback: no table structure found, emit as text
                String text = inlineText(table).strip();
                if (!text.isEmpty()) out.append(text).append("\n\n");
                return;
            }

            // Build cell matrix
            List<List<String>> rows = new ArrayList<>();
            for (Element tr : trList) {
                List<String> row = new ArrayList<>();
                for (Element cell : tr.select("td, th")) {
                    String txt = cell.text()
                            .replace("|", "\\|")   // escape pipe
                            .replace("\n", " ")
                            .replaceAll("\\s{2,}", " ")
                            .strip();
                    if (txt.isEmpty()) txt = " ";
                    row.add(txt);
                    // Handle colspan
                    int cs = parseIntAttr(cell, "colspan", 1);
                    for (int c = 1; c < cs; c++) row.add(" ");
                }
                if (!row.isEmpty()) rows.add(row);
            }
            if (rows.isEmpty()) return;

            // Normalize: all rows must have same column count
            int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
            if (maxCols == 0) return;
            for (List<String> row : rows) {
                while (row.size() < maxCols) row.add(" ");
            }

            out.append("\n");
            // Header row
            out.append("| ").append(String.join(" | ", rows.get(0))).append(" |\n");
            // Separator
            out.append("|");
            for (int c = 0; c < maxCols; c++) out.append(" --- |");
            out.append("\n");
            // Data rows
            for (int r = 1; r < rows.size(); r++) {
                out.append("| ").append(String.join(" | ", rows.get(r))).append(" |\n");
            }
            out.append("\n");
        }

        // ── inlineText ────────────────────────────────────────────────────────

        /**
         * Extract text from an element's inline content, recursively.
         * Block-level children are skipped (they are handled by NodeTraversor).
         * Inline formatting (bold, italic, code, link) is rendered as Markdown.
         */
        private String inlineText(Element el) {
            StringBuilder sb = new StringBuilder();
            for (Node node : el.childNodes()) {
                if (node instanceof TextNode tn) {
                    sb.append(tn.text());
                } else if (node instanceof Element ch) {
                    String ct = ch.tagName().toLowerCase();
                    if (isBlockTag(ct)) continue;
                    switch (ct) {
                        case "code" -> sb.append("`").append(ch.text()).append("`");
                        case "br"   -> sb.append(" ");
                        case "b", "strong" -> {
                            String inner = inlineText(ch).strip();
                            if (!inner.isEmpty()) sb.append(" **").append(inner).append("** ");
                        }
                        case "i", "em" -> {
                            String inner = inlineText(ch).strip();
                            if (!inner.isEmpty()) sb.append(" *").append(inner).append("* ");
                        }
                        case "a" -> sb.append(inlineText(ch)); // keep text, drop href
                        default   -> sb.append(inlineText(ch));
                    }
                }
            }
            return sb.toString().replaceAll("\\s{2,}", " ");
        }

        // ── predicates ────────────────────────────────────────────────────────

        private boolean isBlockTag(String tag) {
            return switch (tag) {
                case "p", "div", "section", "article", "aside", "main", "header", "footer", "nav",
                     "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6",
                     "ul", "ol", "li", "dl", "dt", "dd",
                     "table", "thead", "tbody", "tfoot", "tr", "td", "th",
                     "pre", "blockquote", "hr", "form", "fieldset" -> true;
                default -> false;
            };
        }

        private boolean hasBlockChild(Element el) {
            return el.children().stream().anyMatch(c -> isBlockTag(c.tagName().toLowerCase()));
        }

        private boolean isDescendantOf(Element el, String parentTag) {
            Element p = el.parent();
            while (p != null) {
                if (p.tagName().equalsIgnoreCase(parentTag)) return true;
                p = p.parent();
            }
            return false;
        }

        private int parseIntAttr(Element el, String attr, int fallback) {
            try {
                String v = el.attr(attr);
                return v.isBlank() ? fallback : Integer.parseInt(v.strip());
            } catch (NumberFormatException e) { return fallback; }
        }
    }

    // =========================================================================
    //  SANITIZE MARKDOWN
    // =========================================================================

    /**
     * Final cleanup of Markdown output:
     *  1. Normalize encoding artifacts (BOM, NBSP, control chars, etc.)
     *  2. Remove page numbers and very short noise lines
     *  3. Deduplicate consecutive lines with Jaccard ≥ threshold
     *     (catches residual text-layer duplicates missed by HTML dedup)
     *  4. cleanTandemSubstrings: remove inline "TextText" duplicates
     *     (e.g., "Chất lượng phần mềm Chất lượng phần mềm" → "Chất lượng phần mềm")
     *  5. Collapse blank lines (max 2 consecutive)
     */
    private String sanitizeMarkdown(String raw) {
        if (raw == null || raw.isBlank()) return "";

        // 1. Encoding normalization
        String s = raw
                .replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
                .replace("\u00A0", " ").replace("\u200B", "").replace("\u200C", "")
                .replace("\u200D", "").replace("\uFFFD", "").replace("\t", "    ")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        String[] lines  = s.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int    blanks   = 0;
        String prevTxt  = "";

        for (String line : lines) {
            String t = line.stripTrailing();

            // 4. Clean intra-line tandem duplicates
            t = cleanTandemSubstrings(t);

            // 2. Remove page numbers and noise
            if (t.matches("^[-–—]?\\s*\\d{1,4}\\s*[-–—]?$"))  continue;
            if (t.matches("(?i)^(page|trang)\\s+\\d+.*$"))     continue;
            // Remove very short lines that are not structural markers
            if (!t.isEmpty() && t.length() < 3
                    && !t.startsWith("#") && !t.startsWith("|")
                    && !t.startsWith("-") && !t.startsWith("`")) continue;

            // 5. Collapse blank lines
            if (t.isBlank()) {
                if (++blanks <= 2) out.append("\n");
                continue;
            }
            blanks = 0;

            // 3. Dedup consecutive similar lines (text only; preserve structure)
            String tClean = t.replaceAll("^#+\\s*", "").strip();
            boolean isStructural = t.startsWith("#") || t.startsWith("|")
                    || t.startsWith("-") || t.startsWith("`") || t.startsWith("*");
            if (!isStructural && jaccardSimilarity(tClean, prevTxt) >= DEDUP_THRESHOLD) {
                continue;
            }

            prevTxt = tClean;
            out.append(t).append("\n");
        }

        return out.toString().trim();
    }

    /**
     * Detect and remove intra-line tandem (consecutive duplicate) substrings.
     *
     * PDF rendering sometimes produces "Word Word" or "Sentence Sentence"
     * within a single <p> due to overlay layers that aren't handled by
     * element-level dedup. Example:
     *   "Chất lượng phần mềm Chất lượng phần mềm" → "Chất lượng phần mềm"
     *
     * Algorithm: scan all substrings of length 10–200 chars.
     * If substring[i..i+len] equals substring[i+len..i+2len] → remove second copy.
     * Recursive until no more duplicates.
     */
    private String cleanTandemSubstrings(String text) {
        if (text == null || text.length() < 16) return text;

        int n = text.length();
        int maxLen = Math.min(200, n / 2);
        for (int len = maxLen; len >= 10; len--) {
            for (int i = 0; i <= n - 2 * len; i++) {
                String sub1 = text.substring(i, i + len);
                int    end2 = Math.min(i + 2 * len, n);
                String sub2 = text.substring(i + len, end2);

                boolean match = sub1.equals(sub2)
                        || sub1.strip().equals(sub2.strip())
                        || sub2.startsWith(sub1)
                        || sub2.strip().startsWith(sub1.strip());

                if (match) {
                    String next = text.substring(0, i) + text.substring(i + len);
                    return cleanTandemSubstrings(next); // recurse for chained duplicates
                }
            }
        }
        return text;
    }

    // =========================================================================
    //  N-GRAM HEADER / FOOTER DETECTOR
    // =========================================================================

    /**
     * Remove repeated header/footer lines that appear in most document sections.
     *
     * Algorithm (inspired by Docling's reading-order normalization):
     *  1. Split Markdown into segments at each heading boundary (# markers)
     *  2. For each segment: record the first and last non-blank line
     *  3. Count frequency of each first/last line across all segments
     *  4. Lines appearing in ≥ HEADER_FOOTER_FREQ_THRESHOLD of segments → remove
     *
     * Example: a university document with "Trường Đại học ABC" on every page
     * will have that line in 90% of segments → removed.
     *
     * Only active if there are ≥ MIN_HEADER_FOOTER_SEGMENTS sections (prevents
     * false positives on short documents).
     */
    private String removeRepeatedHeadersFooters(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;

        // Split at heading boundaries
        String[] segments = markdown.split("(?=\\n#)", -1);
        if (segments.length < MIN_HEADER_FOOTER_SEGMENTS) return markdown;

        Map<String, Integer> firstFreq = new HashMap<>();
        Map<String, Integer> lastFreq  = new HashMap<>();
        int actualSegments = 0;

        for (String seg : segments) {
            String[] lines = seg.strip().split("\n", -1);
            String firstNonBlank = null, lastNonBlank = null;

            // Find first non-blank line (3–120 chars)
            for (String line : lines) {
                String t = line.strip();
                if (!t.isEmpty() && t.length() >= 3 && t.length() <= 120) {
                    firstNonBlank = t;
                    break;
                }
            }
            // Find last non-blank line (3–120 chars)
            for (int i = lines.length - 1; i >= 0; i--) {
                String t = lines[i].strip();
                if (!t.isEmpty() && t.length() >= 3 && t.length() <= 120) {
                    lastNonBlank = t;
                    break;
                }
            }

            if (firstNonBlank != null || lastNonBlank != null) actualSegments++;
            if (firstNonBlank != null) firstFreq.merge(firstNonBlank, 1, Integer::sum);
            if (lastNonBlank != null && !lastNonBlank.equals(firstNonBlank)) {
                lastFreq.merge(lastNonBlank, 1, Integer::sum);
            }
        }

        if (actualSegments < MIN_HEADER_FOOTER_SEGMENTS) return markdown;

        int threshold = (int) Math.ceil(actualSegments * HEADER_FOOTER_FREQ_THRESHOLD);

        Set<String> toRemove = new HashSet<>();
        firstFreq.entrySet().stream().filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey).forEach(toRemove::add);
        lastFreq.entrySet().stream().filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey).forEach(toRemove::add);

        if (toRemove.isEmpty()) return markdown;

        log.debug("[Convert] Removing {} repeated header/footer patterns", toRemove.size());

        StringBuilder result = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            if (!toRemove.contains(line.strip())) result.append(line).append("\n");
        }
        return result.toString().replaceAll("\n{3,}", "\n\n").strip();
    }

    // =========================================================================
    //  G4++ — MARKDOWN HEADING NORMALIZER (post-conversion safety net)
    // =========================================================================

    /**
     * Second-pass safety net over final Markdown text.
     *
     * Catches structural headings that HTML-level promotion missed:
     *  - TXT files: no HTML, all content is flat text
     *  - PDFs with unusual font encodings where bold detection fails
     *  - DOCX with custom heading styles not recognized by Tika
     *
     * Respects existing structure:
     *  - Lines already starting with # → untouched
     *  - Lines inside code fences (``` ... ```) → untouched
     *  - Lines inside Markdown tables (| ... |) → untouched
     *  - Lines ending with "." or containing "," → not headings → untouched
     */
    private String normalizeMarkdownHeadings(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;

        String[] lines = markdown.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inCode  = false;
        boolean inTable = false;

        for (String line : lines) {
            String t = line.strip();

            // Respect code fences
            if (t.startsWith("```")) {
                inCode = !inCode;
                out.append(line).append("\n");
                continue;
            }
            if (inCode) { out.append(line).append("\n"); continue; }

            // Respect table rows
            if (t.startsWith("|") || t.matches("^[|\\-:\\s]{3,}$")) {
                inTable = true; out.append(line).append("\n"); continue;
            }
            if (inTable && t.isEmpty()) { inTable = false; out.append(line).append("\n"); continue; }
            if (inTable) { out.append(line).append("\n"); continue; }

            // Already a heading or structural marker → untouched
            if (t.startsWith("#") || t.startsWith("-") || t.startsWith("*")) {
                out.append(line).append("\n"); continue;
            }

            // Skip empty, too-long, or sentence-ending lines
            if (t.isEmpty() || t.length() > 200 || t.endsWith(".") || t.endsWith(",")) {
                out.append(line).append("\n"); continue;
            }

            Matcher m;

            // Numbered sections: "1. Title", "2.1. SubTitle", "2.1.1. Deep"
            m = NP_NUM.matcher(t);
            if (m.matches()) {
                String tp = m.group(3);
                if (!tp.endsWith(".") && tp.chars().filter(c -> c == ',').count() <= 2) {
                    int dots  = (int) m.group(1).chars().filter(c -> c == '.').count();
                    int level = Math.min(dots + 2, 4);
                    out.append("\n").append("#".repeat(level)).append(" ").append(t).append("\n\n");
                    continue;
                }
            }

            // Vietnamese: "Chương I", "Điều 5", "Mục 2"
            if (NP_VN.matcher(t).matches()) {
                out.append("\n## ").append(t).append("\n\n"); continue;
            }

            // English: "Chapter 1", "Section 3", "Article 7"
            if (NP_EN.matcher(t).matches()) {
                out.append("\n## ").append(t).append("\n\n"); continue;
            }

            // ALL CAPS short lines
            if (NP_CAP.matcher(t).matches() && !t.contains(",") && t.length() <= 80) {
                out.append("\n## ").append(t).append("\n\n"); continue;
            }

            // Default: keep as-is
            out.append(line).append("\n");
        }

        return out.toString().replaceAll("\n{4,}", "\n\n\n").trim();
    }
}