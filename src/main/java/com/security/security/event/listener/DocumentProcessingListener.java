package com.security.security.event.listener;

import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.event.DocumentUploadedEvent;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.EmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║          Docling-style Document ETL Pipeline  —  v3                     ║
 * ╠══════════════════════════════════════════════════════════════════════════╣
 * ║                                                                          ║
 * ║  GIAI ĐOẠN 1 — INGESTION                                                ║
 * ║    UrlResource / FileSystemResource → InputStream                       ║
 * ║                                                                          ║
 * ║  GIAI ĐOẠN 2 — DOM EXTRACTION  (Tika → HTML)                           ║
 * ║    AutoDetectParser + ToHTMLContentHandler                               ║
 * ║    → Giữ nguyên <h1-h6>, <table>, <p>, <ul>, <ol>, <pre>               ║
 * ║    ⚠ Dùng ToHTMLContentHandler (KHÔNG phải ToXMLContentHandler vì       ║
 * ║      ToXML sinh XHTML namespace → Jsoup parse sai tag)                  ║
 * ║                                                                          ║
 * ║  GIAI ĐOẠN 3 — DEDUPLICATION  (Root cause lỗi lặp đôi nội dung)       ║
 * ║    PDF có 2 lớp text: text layer + OCR layer → Tika xuất cả 2          ║
 * ║    → deduplicateHtmlContent(): Jaccard similarity ≥ 88% → xóa bản trùng║
 * ║    → Đây là bước bị THIẾU trong phiên bản cũ → vì thế bị lặp           ║
 * ║                                                                          ║
 * ║  GIAI ĐOẠN 4 — HTML → MARKDOWN  (Jsoup DOM Traversal + Mapping Rules)  ║
 * ║    <h1-h6> → # / ## / ###                                               ║
 * ║    <p class="title"> → ## (PDF heading detection)                       ║
 * ║    <table>  → | col | col | + |---|---| + escape \|                     ║
 * ║    <ul/ol>  → - item / 1. item (nested: 2-space indent)                 ║
 * ║    <pre>    → ``` block ```                                              ║
 * ║    Sanitization: encoding, số trang, blank lines dư                     ║
 * ║                                                                          ║
 * ║  GIAI ĐOẠN 5 — SEMANTIC CHUNKING                                        ║
 * ║    parseStructure() → heading → List<Section>                           ║
 * ║    chunkSection()   → sliding window (TARGET=300 tok, MAX=480 tok)      ║
 * ║    mergeOrphanChunks() → gộp chunk < 60 tok vào chunk liền kề          ║
 * ║    addBreadcrumb()  → "[Phần X > Mục Y]" prefix                        ║
 * ║                                                                          ║
 * ║  GIAI ĐOẠN 6 — LOAD                                                     ║
 * ║    VectorStore.add() + EmbeddingRepository.saveAll() (batch=30)         ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessingListener {

    private final VectorStore         vectorStore;
    private final DocumentRepository  documentRepository;
    private final EmbeddingRepository embeddingRepository;

    // ── Token / Chunk budget ──────────────────────────────────────────────────
    private static final int    TARGET_TOKENS   = 300;
    private static final int    MAX_TOKENS      = 480;
    private static final int    MIN_TOKENS      = 60;
    private static final int    OVERLAP_TOKENS  = 50;
    private static final double CHARS_PER_TOKEN = 3.8;

    private static final int TARGET = (int)(TARGET_TOKENS * CHARS_PER_TOKEN);
    private static final int MAX    = (int)(MAX_TOKENS    * CHARS_PER_TOKEN);
    private static final int MIN    = (int)(MIN_TOKENS    * CHARS_PER_TOKEN);
    private static final int BATCH_SIZE = 30;

    /** Jaccard similarity threshold cho dedup (0.0-1.0) */
    private static final double DEDUP_THRESHOLD = 0.88;

    // ── Heading patterns ──────────────────────────────────────────────────────
    private static final Pattern P_MD  = Pattern.compile("^(#{1,4})\\s+(.+)$");
    private static final Pattern P_VN  = Pattern.compile(
            "^(CHƯƠNG|Chương|PHẦN|Phần|BÀI|Bài|MỤC|Mục|ĐIỀU|Điều|TIẾT|Tiết|KHOẢN|Khoản|ĐIỂM|Điểm)" +
                    "\\s+([\\dIVXivxA-Za-z]+\\.?)(.{0,160})$");
    private static final Pattern P_EN  = Pattern.compile(
            "^(ARTICLE|Article|SECTION|Section|CHAPTER|Chapter|CLAUSE|Clause|PART|Part|APPENDIX|Appendix)" +
                    "\\s+([\\dIVXivx]+\\.?)(.{0,160})$");
    private static final Pattern P_NUM = Pattern.compile(
            "^(\\d{1,2}(\\.\\d{1,2}){0,3})\\.\\s{1,4}(\\S.{2,100})$");
    private static final Pattern P_CAP = Pattern.compile(
            "^[A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯ][A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯ\\s\\d\\-/&()]{3,79}$");

    // ── Block / structural detectors ─────────────────────────────────────────
    private static final Pattern P_CODE  = Pattern.compile("^```.*$", Pattern.DOTALL);
    private static final Pattern P_TROW  = Pattern.compile("^\\|.+\\|\\s*$");
    private static final Pattern P_TSEP  = Pattern.compile("^[|\\-:\\s]{3,}$");
    private static final Pattern P_BULL  = Pattern.compile("^([\\-*•]|\\d+[.)]) .+");
    private static final Pattern P_BLANK = Pattern.compile("^\\s*$");
    private static final Pattern P_SENT  = Pattern.compile(
            "(?<!(?:TS|PGS|GS|ThS|BS|KS|CN|Mr|Mrs|Ms|Dr|Prof|vs|etc|e\\.g|i\\.e|v\\.v|v\\.d|\\d))" +
                    "[.!?](?=[\\s\"']|$)");

    private record Section(String heading, int level, String body) {}
    private record HeadingResult(String title, int level) {}

    // =========================================================================
    //  MAIN EVENT HANDLER
    // =========================================================================

    @EventListener
    @Async
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        Long docId = event.getDocument().getId();
        log.info("[ETL] ▶ Start doc={}", docId);

        Document document = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + docId));

        try {
            setStatus(document, DocStatus.PROCESSING, null);

            // ── G1: Ingestion ─────────────────────────────────────────────────
            org.springframework.core.io.Resource resource;
            if (document.getFileUrl() != null && !document.getFileUrl().isBlank()) {
                log.info("[ETL][1] URL: {}", document.getFileUrl());
                resource = new UrlResource(document.getFileUrl());
            } else {
                log.info("[ETL][1] Disk: {}", document.getFilePath());
                resource = new FileSystemResource(document.getFilePath());
            }

            // ── G2: DOM Extraction ────────────────────────────────────────────
            String rawHtml = extractToHtml(resource);
            log.info("[ETL][2] HTML: {} chars, doc={}", rawHtml.length(), docId);
            if (rawHtml.isBlank()) throw new IllegalStateException("Tika empty HTML, doc=" + docId);

            // ── G3: Deduplication ─────────────────────────────────────────────
            String dedupedHtml = deduplicateHtmlContent(rawHtml);
            log.info("[ETL][3] Dedup: {} → {} chars, doc={}", rawHtml.length(), dedupedHtml.length(), docId);

            // ── G4: HTML → Markdown ───────────────────────────────────────────
            String markdown = htmlToMarkdown(dedupedHtml);
            log.info("[ETL][4] Markdown: {} chars, doc={}", markdown.length(), docId);
            if (markdown.length() < MIN)
                throw new IllegalStateException("Markdown too short: " + markdown.length());

            // ── G5: Semantic Chunking ─────────────────────────────────────────
            List<String> chunks = semanticChunk(markdown);
            log.info("[ETL][5] Chunks: {}, doc={}", chunks.size(), docId);
            if (chunks.isEmpty()) throw new IllegalStateException("No chunks produced");

            // ── G6: Load ──────────────────────────────────────────────────────
            embeddingRepository.deleteByDocumentId(docId);

            List<org.springframework.ai.document.Document> vBatch = new ArrayList<>(BATCH_SIZE);
            List<Embedding>                                 eBatch = new ArrayList<>(BATCH_SIZE);

            for (int i = 0; i < chunks.size(); i++) {
                String text  = chunks.get(i);
                String title = detectChunkTitle(text, document.getFileName());

                Map<String, Object> meta = new HashMap<>();
                meta.put("documentId", document.getId().toString());
                meta.put("userId",     document.getUserId());
                meta.put("fileName",   document.getFileName());
                meta.put("chunkIndex", String.valueOf(i));
                meta.put("chunkTitle", title);
                meta.put("tokenCount", String.valueOf(estimateTokens(text)));
                meta.put("charCount",  String.valueOf(text.length()));

                vBatch.add(new org.springframework.ai.document.Document(text, meta));
                eBatch.add(Embedding.builder()
                        .documentId(document.getId())
                        .chunkIndex(i)
                        .chunkText(text)
                        .chunkTitle(title)
                        .tokenCount(estimateTokens(text))
                        .charCount(text.length())
                        .build());

                if (vBatch.size() >= BATCH_SIZE) {
                    vectorStore.add(new ArrayList<>(vBatch));
                    embeddingRepository.saveAll(new ArrayList<>(eBatch));
                    log.info("[ETL][6] Batch [{}-{}] doc={}", i - BATCH_SIZE + 1, i, docId);
                    vBatch.clear();
                    eBatch.clear();
                }
            }
            if (!vBatch.isEmpty()) {
                vectorStore.add(vBatch);
                embeddingRepository.saveAll(eBatch);
                log.info("[ETL][6] Final batch doc={}", docId);
            }

            log.info("[ETL] ✓ {} chunks stored, doc={}", chunks.size(), docId);
            document.setStatus(DocStatus.COMPLETED);
            document.setChunkCount(chunks.size());
            document.setErrorMessage(null);
            documentRepository.save(document);

        } catch (Exception e) {
            log.error("[ETL] ✗ Failed doc={}: {}", docId, e.getMessage(), e);
            setStatus(document, DocStatus.FAILED, e.getMessage());
        }
    }

    // =========================================================================
    //  G2 — Tika DOM Extraction
    // =========================================================================

    /**
     * Dùng ToHTMLContentHandler (KHÔNG phải ToXMLContentHandler).
     *
     * Lý do:
     *  - ToXMLContentHandler → XHTML + XML namespace → Jsoup parse miss tag
     *  - ToHTMLContentHandler → HTML5 chuẩn → Jsoup parse chính xác
     *  - TikaDocumentReader (Spring AI) → BodyContentHandler → plain text
     *    (mất <table>, <h1-h6> → không thể làm Docling-style)
     */
    private String extractToHtml(org.springframework.core.io.Resource resource) throws Exception {
        AutoDetectParser parser   = new AutoDetectParser();
        Metadata         metadata = new Metadata();
        ParseContext     context  = new ParseContext();
        ContentHandler   handler  = new ToHTMLContentHandler();

        try (InputStream stream = resource.getInputStream()) {
            parser.parse(stream, handler, metadata, context);
        }

        log.debug("[ETL][2] title={}, pages={}, type={}",
                metadata.get("dc:title"),
                metadata.get("xmpTPg:NPages"),
                metadata.get("Content-Type"));

        return handler.toString();
    }

    // =========================================================================
    //  G3 — Deduplication  (fix lỗi lặp đôi nội dung)
    // =========================================================================

    /**
     * Root cause của lỗi lặp đôi trong phiên bản cũ:
     *
     * Nhiều PDF (scan hoặc export từ Word) có 2 lớp text:
     *   - Text layer gốc (font/glyph)
     *   - OCR layer (searchable text layer)
     *
     * Tika đọc cả 2 → mỗi <p> xuất hiện 2 lần trong HTML.
     * Ví dụ thực tế từ output cũ:
     *   "Phần mềm (Software): Là một tập hợp các chương trình máy tính..."
     *   "Phần mềm (Software): Là một tập hợp các chương trình máy tính..." ← duplicate
     *
     * Giải pháp: Jaccard similarity trên set từ.
     * Ưu điểm over Levenshtein: O(n) thay vì O(n²), đủ chính xác cho đoạn văn.
     */
    private String deduplicateHtmlContent(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);

        // Dedup ở các cấp độ element cơ bản
        for (String sel : new String[]{"p", "li", "td", "th"}) {
            deduplicateSelector(doc, sel);
        }

        return doc.outerHtml();
    }

    private void deduplicateSelector(org.jsoup.nodes.Document doc, String selector) {
        List<Element> elements = doc.select(selector);
        String prev = "";

        for (Element el : elements) {
            String curr = el.text().strip();
            if (curr.isBlank()) continue;

            if (jaccardSimilarity(prev, curr) >= DEDUP_THRESHOLD) {
                el.remove();
            } else {
                prev = curr;
            }
        }
    }

    /**
     * Jaccard coefficient dựa trên set từ.
     * Chỉ tính khi length ratio >= 0.6 (tránh false positive câu ngắn).
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
    //  G4 — HTML → Markdown
    // =========================================================================

    private String htmlToMarkdown(String html) {
        org.jsoup.nodes.Document doc = Jsoup.parse(html);

        // Strip non-semantic
        doc.select("style, script, meta, link, head, noscript").remove();

        // Promote PDF-style headings (<p class="title">, ALL-CAPS <p>)
        promotePdfHeadings(doc);

        StringBuilder md = new StringBuilder();
        traverseToMarkdown(doc.body() != null ? doc.body() : doc.root(), md);

        return sanitizeMarkdown(md.toString());
    }

    /**
     * PDF heading promotion:
     * Tika export PDF tiêu đề thành <p class="title"> hoặc <p> với ALL-CAPS text.
     * Promote → <h2> để traverseToMarkdown() map đúng thành ## heading.
     */
    private void promotePdfHeadings(org.jsoup.nodes.Document doc) {
        for (Element p : doc.select("p")) {
            String cls  = p.className().toLowerCase();
            String text = p.text().strip();
            if (text.isEmpty() || text.length() > 200) continue;

            boolean isClass  = cls.contains("title") || cls.contains("heading")
                    || cls.contains("h1")   || cls.contains("h2")   || cls.contains("h3");
            boolean isAllCap = text.length() >= 4
                    && text.replaceAll("[\\d\\s\\-/:()&.,]", "").equals(
                    text.replaceAll("[\\d\\s\\-/:()&.,]", "").toUpperCase())
                    && !text.endsWith(".")
                    && !text.contains(",");

            if (isClass || isAllCap) p.tagName("h2");
        }
    }

    /**
     * Đệ quy duyệt DOM → Markdown.
     * Block elements được xử lý trực tiếp.
     * Inline elements được gom bởi inlineText().
     */
    private void traverseToMarkdown(Element root, StringBuilder out) {
        for (Node node : root.childNodes()) {

            if (node instanceof TextNode tn) {
                String t = tn.text();
                if (!t.isBlank()) out.append(t).append(" ");
                continue;
            }
            if (!(node instanceof Element el)) continue;

            String tag = el.tagName().toLowerCase();

            switch (tag) {
                case "h1" -> appendHeading(el, 1, out);
                case "h2" -> appendHeading(el, 2, out);
                case "h3" -> appendHeading(el, 3, out);
                case "h4" -> appendHeading(el, 4, out);
                case "h5" -> appendHeading(el, 5, out);
                case "h6" -> appendHeading(el, 6, out);

                case "p"  -> {
                    String text = inlineText(el).strip();
                    if (!text.isEmpty()) out.append(text).append("\n\n");
                }

                case "ul" -> appendList(el, false, 0, out);
                case "ol" -> appendList(el, true,  0, out);

                case "table" -> appendTable(el, out);

                case "pre" -> {
                    String code = el.wholeText().strip();
                    if (!code.isEmpty()) out.append("```\n").append(code).append("\n```\n\n");
                }
                case "code" -> {
                    if (!isDescendantOf(el, "pre")) {
                        String c = el.text().strip();
                        if (!c.isEmpty()) out.append("`").append(c).append("` ");
                    }
                }

                case "br" -> out.append("\n");
                case "hr" -> out.append("\n---\n\n");

                case "script", "style", "meta", "link",
                     "noscript", "form", "input", "button" -> { /* skip */ }

                // Containers: recurse
                case "div", "section", "article", "main",
                     "header", "footer", "nav", "aside",
                     "figure", "figcaption", "blockquote",
                     "body", "html", "dl", "dd", "dt",
                     "thead", "tbody", "tfoot" -> traverseToMarkdown(el, out);

                default -> {
                    if (hasBlockChild(el)) {
                        traverseToMarkdown(el, out);
                    } else {
                        String text = inlineText(el).strip();
                        if (!text.isEmpty()) out.append(text).append(" ");
                    }
                }
            }
        }
    }

    private void appendHeading(Element el, int level, StringBuilder out) {
        String title = inlineText(el).strip();
        if (title.isEmpty()) return;

        // Anti-duplicate: skip nếu heading này giống dòng vừa emit
        String last = lastNonBlankLine(out.toString()).replaceAll("^#+\\s*", "").strip();
        if (jaccardSimilarity(title, last) >= DEDUP_THRESHOLD) return;

        out.append("\n")
                .append("#".repeat(Math.min(level, 6)))
                .append(" ")
                .append(title)
                .append("\n\n");
    }

    private void appendList(Element listEl, boolean ordered, int depth, StringBuilder out) {
        String indent = "  ".repeat(depth);
        int    n      = 1;

        for (Element li : listEl.select("> li")) {
            // Lấy text trực tiếp trong li (bỏ nested list)
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

            // Nested list
            for (Element child : li.select("> ul, > ol")) {
                appendList(child, child.tagName().equals("ol"), depth + 1, out);
            }
        }
        if (depth == 0) out.append("\n");
    }

    /**
     * <table> → Markdown table.
     *
     * Key behaviors:
     *  - Escape | → \| trong mỗi cell (tránh vỡ cột)
     *  - Auto-generate |---|---| separator sau header row
     *  - Xử lý colspan (điền ô trống)
     *  - Chuẩn hóa số cột (padding nếu thiếu)
     */
    private void appendTable(Element table, StringBuilder out) {
        // Thu thập rows theo thứ tự thead → tbody → tfoot → trực tiếp
        List<Element> trList = new ArrayList<>();
        for (Element sec : table.select("thead, tbody, tfoot")) {
            trList.addAll(sec.select("> tr"));
        }
        if (trList.isEmpty()) trList.addAll(table.select("> tr"));
        if (trList.isEmpty()) { traverseToMarkdown(table, out); return; }

        List<List<String>> rows = new ArrayList<>();
        for (Element tr : trList) {
            List<String> row = new ArrayList<>();
            for (Element cell : tr.select("td, th")) {
                String txt = cell.text()
                        .replace("|", "\\|")
                        .replace("\n", " ")
                        .replaceAll("\\s{2,}", " ")
                        .strip();
                if (txt.isEmpty()) txt = " ";
                row.add(txt);

                // colspan
                int cs = parseIntAttr(cell, "colspan", 1);
                for (int c = 1; c < cs; c++) row.add(" ");
            }
            if (!row.isEmpty()) rows.add(row);
        }
        if (rows.isEmpty()) return;

        // Normalize column count
        int maxCols = rows.stream().mapToInt(List::size).max().orElse(0);
        if (maxCols == 0) return;
        for (List<String> row : rows) {
            while (row.size() < maxCols) row.add(" ");
        }

        out.append("\n");
        // Header
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

    private String inlineText(Element el) {
        StringBuilder sb = new StringBuilder();
        for (Node node : el.childNodes()) {
            if (node instanceof TextNode tn) {
                sb.append(tn.text());
            } else if (node instanceof Element ch) {
                String ct = ch.tagName().toLowerCase();
                if (isBlockTag(ct)) continue;
                if (ct.equals("code"))  sb.append("`").append(ch.text()).append("`");
                else if (ct.equals("br")) sb.append(" ");
                else sb.append(inlineText(ch));
            }
        }
        return sb.toString();
    }

    private boolean isBlockTag(String tag) {
        return switch (tag) {
            case "p","div","section","article","aside","main","header","footer","nav",
                 "figure","figcaption","h1","h2","h3","h4","h5","h6",
                 "ul","ol","li","dl","dt","dd",
                 "table","thead","tbody","tfoot","tr","td","th",
                 "pre","blockquote","hr","form","fieldset" -> true;
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
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String lastNonBlankLine(String s) {
        String[] lines = s.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) return lines[i].strip();
        }
        return "";
    }

    /**
     * Cleanup Markdown:
     *  1. Normalize encoding
     *  2. Xóa số trang / noise
     *  3. Chuẩn hóa blank lines (max 2)
     *  4. Dedup dòng liên tiếp giống nhau (fallback nếu HTML dedup miss)
     */
    private String sanitizeMarkdown(String raw) {
        if (raw == null || raw.isBlank()) return "";

        String s = raw
                .replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
                .replace("\u00A0", " ").replace("\u200B", "").replace("\u200C", "")
                .replace("\u200D", "").replace("\uFFFD", "").replace("\t", "    ")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");

        String[]      lines  = s.split("\n", -1);
        StringBuilder out    = new StringBuilder();
        int           blanks = 0;
        String        prevTxt = "";

        for (String line : lines) {
            String t = line.stripTrailing();
            t = cleanTandemSubstrings(t);

            // Xóa số trang
            if (t.matches("^[-–—]?\\s*\\d{1,4}\\s*[-–—]?$"))   continue;
            if (t.matches("(?i)^(page|trang)\\s+\\d+.*$"))      continue;
            // Xóa dòng quá ngắn không phải cấu trúc
            if (!t.isEmpty() && t.length() < 3
                    && !t.startsWith("#") && !t.startsWith("|")
                    && !t.startsWith("-") && !t.startsWith("`")) continue;

            if (t.isBlank()) {
                if (++blanks <= 2) out.append("\n");
                continue;
            }
            blanks = 0;

            // Dedup dòng text giống nhau liên tiếp (không áp cho table/heading/bullet)
            String tClean = t.replaceAll("^#+\\s*", "").strip();
            if (!t.startsWith("|") && !t.startsWith("-") && !t.startsWith("#") && !t.startsWith("`")
                    && jaccardSimilarity(tClean, prevTxt) >= DEDUP_THRESHOLD) {
                continue;
            }

            prevTxt = tClean;
            out.append(t).append("\n");
        }

        return out.toString().trim();
    }

    /**
     * Khử lặp chuỗi/cụm từ liên tiếp (tandem repeats) trong một dòng đơn.
     * Thường xảy ra khi PDF có 2 layer trùng nhau (text gốc + OCR) bị gộp làm một.
     */
    private String cleanTandemSubstrings(String text) {
        if (text == null || text.length() < 16) return text;

        int n = text.length();
        int maxLen = Math.min(200, n / 2);
        for (int len = maxLen; len >= 10; len--) {
            for (int i = 0; i <= n - 2 * len; i++) {
                String sub1 = text.substring(i, i + len);
                String sub2 = text.substring(i + len, i + 2 * len);

                if (sub1.equals(sub2)) {
                    String nextText = text.substring(0, i) + text.substring(i + len);
                    return cleanTandemSubstrings(nextText);
                }

                if (sub2.startsWith(sub1)) {
                    String nextText = text.substring(0, i) + text.substring(i + len);
                    return cleanTandemSubstrings(nextText);
                }
            }
        }
        return text;
    }

    // =========================================================================
    //  G5 — SEMANTIC CHUNKER
    // =========================================================================

    private List<String> semanticChunk(String text) {
        List<Section> sections = parseStructure(text);
        log.debug("[Chunk] sections={}", sections.size());

        // raw chunks + crumb list phải sync index
        List<String> rawChunks = new ArrayList<>();
        List<String> rawCrumbs = new ArrayList<>();

        String[] breadcrumb = {"", ""};

        for (Section sec : sections) {
            if (sec.level() <= 1) { breadcrumb[0] = sec.heading(); breadcrumb[1] = ""; }
            else                    breadcrumb[1] = sec.heading();

            String crumb = buildCrumb(breadcrumb);
            for (String chunk : chunkSection(sec.body())) {
                rawChunks.add(chunk);
                rawCrumbs.add(crumb);
            }
        }

        // Merge orphan chunks (< MIN_TOKENS)
        mergeOrphanChunks(rawChunks, rawCrumbs);

        // Apply breadcrumb + filter still-too-small
        List<String> result = new ArrayList<>();
        for (int i = 0; i < rawChunks.size(); i++) {
            String chunk = rawChunks.get(i);
            if (estimateTokens(chunk) < MIN_TOKENS) continue;
            result.add(addBreadcrumb(chunk, rawCrumbs.get(i)));
        }
        return result;
    }

    /**
     * In-place merge: duyệt rawChunks, gộp chunk < MIN_TOKENS vào chunk kế tiếp
     * (hoặc chunk trước nếu đã là cuối). rawCrumbs được sync theo.
     */
    private void mergeOrphanChunks(List<String> chunks, List<String> crumbs) {
        int i = 0;
        while (i < chunks.size()) {
            String chunk = chunks.get(i);
            if (estimateTokens(chunk) < MIN_TOKENS) {
                if (i + 1 < chunks.size()) {
                    // Gộp vào chunk tiếp theo
                    String merged = chunk + "\n\n" + chunks.get(i + 1);
                    if (estimateTokens(merged) <= MAX_TOKENS) {
                        chunks.set(i + 1, merged.strip());
                        // Giữ crumb của chunk tiếp (chunk lớn hơn)
                        chunks.remove(i);
                        crumbs.remove(i);
                        continue; // không tăng i, kiểm tra lại chunk mới
                    }
                } else if (i > 0) {
                    // Gộp vào chunk trước
                    String merged = chunks.get(i - 1) + "\n\n" + chunk;
                    if (estimateTokens(merged) <= MAX_TOKENS) {
                        chunks.set(i - 1, merged.strip());
                        chunks.remove(i);
                        crumbs.remove(i);
                        i = Math.max(0, i - 1);
                        continue;
                    }
                }
            }
            i++;
        }
    }

    private String buildCrumb(String[] bc) {
        String b1 = bc[0], b2 = bc[1];
        if (b1.isBlank() && b2.isBlank()) return "";
        if (!b1.isBlank() && !b2.isBlank()) return "[" + b1 + " > " + b2 + "]";
        return "[" + (b1.isBlank() ? b2 : b1) + "]";
    }

    private String addBreadcrumb(String chunk, String crumb) {
        if (crumb == null || crumb.isBlank()) return chunk;
        if (chunk.startsWith(crumb)) return chunk;
        return crumb + "\n" + chunk;
    }

    // ── parseStructure ────────────────────────────────────────────────────────

    private List<Section> parseStructure(String text) {
        List<Section> sections = new ArrayList<>();
        String[]      lines    = text.split("\n", -1);

        String        heading = "General";
        int           level   = 0;
        StringBuilder body    = new StringBuilder();

        boolean inCode = false, inTable = false, inList = false;

        for (String line : lines) {
            String t = line.strip();

            // Code fence
            if (P_CODE.matcher(t).matches()) {
                inCode = !inCode;
                body.append(line).append("\n");
                continue;
            }
            if (inCode) { body.append(line).append("\n"); continue; }

            // Table
            if (P_TROW.matcher(t).matches() || P_TSEP.matcher(t).matches()) {
                inTable = true; body.append(line).append("\n"); continue;
            }
            if (inTable) {
                body.append(line).append("\n");
                if (P_BLANK.matcher(t).matches()) inTable = false;
                continue;
            }

            // List
            if (P_BULL.matcher(t).matches()) {
                inList = true; body.append(line).append("\n"); continue;
            }
            if (inList) {
                boolean cont = P_BLANK.matcher(t).matches()
                        || line.startsWith("  ") || line.startsWith("\t");
                body.append(line).append("\n");
                if (!cont) inList = false;
                continue;
            }

            // Blank
            if (P_BLANK.matcher(t).matches()) { body.append("\n"); continue; }

            // Heading
            HeadingResult hr = detectHeading(t);
            if (hr != null) {
                String bs = body.toString().trim();
                if (!bs.isEmpty()) sections.add(new Section(heading, level, bs));
                heading = hr.title();
                level   = hr.level();
                body.setLength(0);
                body.append(line).append("\n");
            } else {
                body.append(line).append("\n");
            }
        }

        String bs = body.toString().trim();
        if (!bs.isEmpty()) sections.add(new Section(heading, level, bs));
        if (sections.isEmpty()) sections.add(new Section("General", 0, text.trim()));
        return sections;
    }

    private HeadingResult detectHeading(String line) {
        if (line == null || line.isBlank() || line.length() > 200) return null;
        Matcher m;

        m = P_MD.matcher(line);
        if (m.matches()) return new HeadingResult(m.group(2).trim(), m.group(1).length());

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

        m = P_NUM.matcher(line);
        if (m.matches()) {
            String tp = m.group(3);
            if (!tp.endsWith(".") && tp.chars().filter(c -> c == ',').count() <= 2) {
                int dots = (int) m.group(1).chars().filter(c -> c == '.').count();
                return new HeadingResult(line.trim(), Math.min(dots + 1, 4));
            }
        }

        if (P_CAP.matcher(line).matches() && !line.contains(",") && !line.endsWith("."))
            return new HeadingResult(line.trim(), 1);

        return null;
    }

    // ── chunkSection ──────────────────────────────────────────────────────────

    private List<String> chunkSection(String body) {
        List<String> sents = splitIntoSentences(body);
        if (sents.isEmpty()) return List.of();

        List<String> chunks = new ArrayList<>();
        int i = 0;

        while (i < sents.size()) {
            StringBuilder win = new StringBuilder();
            int j = i;

            while (j < sents.size()) {
                String next      = sents.get(j);
                String candidate = win.isEmpty() ? next : win + " " + next;
                if (estimateTokens(candidate) > MAX_TOKENS && !win.isEmpty()) break;
                win = new StringBuilder(candidate);
                j++;
                if (estimateTokens(win.toString()) >= TARGET_TOKENS) break;
            }

            String chunk = formatChunk(win.toString());
            if (!chunk.isBlank()) chunks.add(chunk);

            if (j == i) {
                chunks.addAll(hardSplit(sents.get(i)));
                i++;
            } else {
                int overlapTok = 0, overlapStart = j;
                for (int k = j - 1; k > i; k--) {
                    int t = estimateTokens(sents.get(k));
                    if (overlapTok + t > OVERLAP_TOKENS) break;
                    overlapTok += t;
                    overlapStart = k;
                }
                i = Math.max(i + 1, overlapStart);
            }
        }
        return chunks;
    }

    private List<String> splitIntoSentences(String text) {
        List<String> result = new ArrayList<>();
        for (String para : text.split("\n\n+", -1)) {
            String p = para.strip();
            if (p.isEmpty()) continue;

            String  fl      = p.split("\n", 2)[0].strip();
            boolean isBlock = P_CODE.matcher(fl).matches()
                    || P_TROW.matcher(fl).matches()
                    || P_BULL.matcher(fl).matches();

            if (isBlock) { result.add(p); continue; }

            String  flat = para.replace("\n", " ").replaceAll("\\s{2,}", " ").strip();
            Matcher m    = P_SENT.matcher(flat);
            int     last = 0;

            while (m.find()) {
                String sent = flat.substring(last, m.end()).strip();
                if (!sent.isEmpty()) result.add(sent);
                last = m.end();
                if (last < flat.length() && flat.charAt(last) == ' ') last++;
            }
            if (last < flat.length()) {
                String rem = flat.substring(last).strip();
                if (!rem.isEmpty()) result.add(rem);
            }
            if (last == 0 && !flat.isEmpty()) result.add(flat);
        }
        return result;
    }

    private List<String> hardSplit(String s) {
        List<String> r = new ArrayList<>();
        int pos = 0;
        while (pos < s.length()) {
            int end = Math.min(pos + MAX, s.length());
            if (end < s.length()) {
                int sp = s.lastIndexOf(' ', end);
                if (sp > pos) end = sp;
            }
            String piece = s.substring(pos, end).strip();
            if (!piece.isEmpty()) r.add(piece);
            pos = end + 1;
        }
        return r;
    }

    private String formatChunk(String chunk) {
        if (chunk == null || chunk.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        int blanks = 0;
        for (String line : chunk.split("\n", -1)) {
            String n = line.stripTrailing().replaceAll("(?<=\\S)\\s{2,}", " ");
            if (n.isBlank()) { if (++blanks <= 2) sb.append("\n"); }
            else             { blanks = 0; sb.append(n).append("\n"); }
        }
        return sb.toString().trim();
    }

    // ── detectChunkTitle ──────────────────────────────────────────────────────

    private String detectChunkTitle(String chunkText, String fallback) {
        if (chunkText == null || chunkText.isBlank()) return fallback;
        for (String line : chunkText.split("\n", 8)) {
            String t = line.strip();
            if (t.isEmpty() || t.length() < 3 || t.length() > 150) continue;
            if (t.startsWith("[") && t.endsWith("]")) continue; // breadcrumb
            HeadingResult hr = detectHeading(t);
            if (hr != null) {
                String title = t.replaceAll("^#+\\s*", "").trim();
                if (!title.isBlank()) return title;
            }
        }
        String flat  = chunkText.replaceAll("^\\[.+?]\\n", "")
                .replaceAll("^#+\\s*", "")
                .replaceAll("\\s+", " ").trim();
        String[] words = flat.split(" ");
        int      take  = Math.min(10, words.length);
        String   prev  = String.join(" ", Arrays.copyOf(words, take));
        return prev.length() > 80 ? prev.substring(0, 80) + "…" : prev;
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private void setStatus(Document doc, DocStatus status, String error) {
        doc.setStatus(status);
        doc.setErrorMessage(error);
        documentRepository.save(doc);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }
}