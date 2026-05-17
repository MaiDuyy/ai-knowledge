package com.security.security.event.listener;

import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.event.DocumentUploadedEvent;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.EmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================================
 *  Spring AI ETL Pipeline  —  Semantic Chunking
 * ============================================================
 *
 *  VẤN ĐỀ với TokenTextSplitter (Spring AI mặc định):
 *   ✗ Cắt theo số token đơn thuần → không quan tâm heading, paragraph
 *   ✗ Một section bị cắt ngang giữa chừng → chunk mất ngữ nghĩa
 *   ✗ Overlap là ký tự ngẫu nhiên → gây lặp nội dung y hệt nhau
 *   ✗ Không có breadcrumb → LLM không biết chunk thuộc phần nào
 *
 *  GIẢI PHÁP — Custom Semantic Chunker thay TokenTextSplitter:
 *
 *  E   TikaDocumentReader
 *        → extract PDF/DOCX/TXT thành raw text
 *
 *  T1  cleanAndReconstruct()
 *        → xóa noise (page number, ký tự lạ)
 *        → reconstruct PDF broken lines (join dòng bị xuống hàng sai)
 *
 *  T2  semanticChunk()  ← THAY THẾ TokenTextSplitter
 *        → parseStructure(): phát hiện heading → List<Section>
 *        → chunkSection(): sliding window theo câu, TARGET=300 tokens
 *        → addBreadcrumb(): prepend "[Phần X > Mục Y]" vào mỗi chunk
 *        → Overlap là câu hoàn chỉnh, không phải ký tự ngẫu nhiên
 *
 *  L   VectorStore.add() + EmbeddingRepository.saveAll()
 *        → batch 30, lưu MariaDB + vector store
 *
 *  KẾT QUẢ:
 *   ✓ Chunk luôn bắt đầu/kết thúc ở ranh giới câu hoàn chỉnh
 *   ✓ Không bao giờ cắt ngang heading hay bảng
 *   ✓ Mỗi chunk biết nó thuộc phần nào của tài liệu (breadcrumb)
 *   ✓ Overlap là câu thật, giúp RAG có context liên tục
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DocumentProcessingListener {

    private final VectorStore         vectorStore;
    private final DocumentRepository  documentRepository;
    private final EmbeddingRepository embeddingRepository;

    // ── Token budget ──────────────────────────────────────────────────────────
    private static final int TARGET_TOKENS  = 300;   // kích thước chunk lý tưởng
    private static final int MAX_TOKENS     = 500;   // hard ceiling
    private static final int MIN_TOKENS     = 40;    // bỏ chunk quá nhỏ
    private static final int OVERLAP_TOKENS = 50;    // overlap tính theo token
    private static final double CHARS_PER_TOKEN = 3.8; // Vietnamese ~3.5, English ~4.0

    private static final int TARGET  = (int)(TARGET_TOKENS  * CHARS_PER_TOKEN); // ~1140
    private static final int MAX     = (int)(MAX_TOKENS     * CHARS_PER_TOKEN); // ~1900
    private static final int MIN     = (int)(MIN_TOKENS     * CHARS_PER_TOKEN); // ~152

    private static final int BATCH_SIZE = 30;

    // ── Heading patterns (priority: most specific first) ─────────────────────
    // Markdown: # Title / ## Section
    private static final Pattern P_MD  = Pattern.compile("^(#{1,4})\\s+(.+)$");

    // Vietnamese legal: Chương I, Điều 5, Mục 2, Khoản 3
    private static final Pattern P_VN  = Pattern.compile(
            "^(CHƯƠNG|Chương|PHẦN|Phần|BÀI|Bài|MỤC|Mục|ĐIỀU|Điều|TIẾT|Tiết|KHOẢN|Khoản|ĐIỂM|Điểm)" +
                    "\\s+([\\dIVXivxA-Za-z]+\\.?)(.{0,160})$");

    // English legal: Article 1, Section 2, Chapter III
    private static final Pattern P_EN  = Pattern.compile(
            "^(ARTICLE|Article|SECTION|Section|CHAPTER|Chapter|CLAUSE|Clause|PART|Part|APPENDIX|Appendix)" +
                    "\\s+([\\dIVXivx]+\\.?)(.{0,160})$");

    // Numbered: "1. Title"  "1.1 Title"  "1.1.1 Title"
    // BẮT BUỘC dấu chấm sau số, text ngắn (không phải câu văn)
    private static final Pattern P_NUM = Pattern.compile(
            "^(\\d{1,2}(\\.\\d{1,2}){0,3})\\.\\s{1,4}(\\S.{2,100})$");

    // ALL-CAPS title: "NỘI DUNG"  "PHẦN MỞ ĐẦU"
    private static final Pattern P_CAP = Pattern.compile(
            "^[A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯ][A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯ\\s\\d\\-/]{3,79}$");

    // Block detectors
    private static final Pattern P_CODE  = Pattern.compile("^```.*$");
    private static final Pattern P_TROW  = Pattern.compile("^\\|.+\\|\\s*$");
    private static final Pattern P_TSEP  = Pattern.compile("^[|\\-:\\s]{3,}$");
    private static final Pattern P_BULL  = Pattern.compile("^([\\-*•]|\\d+[.)]) .+");
    private static final Pattern P_BLANK = Pattern.compile("^\\s*$");

    // Sentence boundary — tránh false positive với viết tắt TS., v.v., 1.2
    private static final Pattern P_SENT = Pattern.compile(
            "(?<!" +
                    "(?:TS|PGS|GS|ThS|BS|KS|CN|Mr|Mrs|Ms|Dr|Prof|vs|etc|e\\.g|i\\.e|v\\.v|v\\.d|\\d)" +
                    ")[.!?](?=[\\s\"']|$)");

    // ── Internal types ────────────────────────────────────────────────────────
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

            // ── E: Extract với Tika ───────────────────────────────────────────
            // Support both URL (NATS event from file-service) and local disk (legacy)
            org.springframework.core.io.Resource fileResource;
            if (document.getFileUrl() != null && !document.getFileUrl().isBlank()) {
                log.info("[ETL] Loading from URL: {}", document.getFileUrl());
                fileResource = new UrlResource(document.getFileUrl());
            } else {
                log.info("[ETL] Loading from disk: {}", document.getFilePath());
                fileResource = new FileSystemResource(document.getFilePath());
            }

            TikaDocumentReader reader = new TikaDocumentReader(
                    fileResource,
                    ExtractedTextFormatter.builder()
                            .withNumberOfTopPagesToSkipBeforeDelete(0)
                            .withNumberOfBottomTextLinesToDelete(0)
                            .withLeftAlignment(true)
                            .build()
            );

            List<org.springframework.ai.document.Document> rawDocs = reader.get();
            int totalChars = rawDocs.stream().mapToInt(d -> d.getText().length()).sum();
            log.info("[ETL] Tika extracted {} doc(s), totalChars={}", rawDocs.size(), totalChars);

            if (rawDocs.isEmpty() || rawDocs.stream().allMatch(d -> d.getText().isBlank())) {
                throw new IllegalStateException("Extracted text is empty");
            }

            // Gộp tất cả text thành 1 (Tika có thể trả về nhiều doc cho multi-page PDF)
            StringBuilder fullText = new StringBuilder();
            for (org.springframework.ai.document.Document raw : rawDocs) {
                if (raw.getText() != null && !raw.getText().isBlank()) {
                    fullText.append(raw.getText()).append("\n\n");
                }
            }

            // ── T1: Clean + Reconstruct broken PDF lines ──────────────────────
            String cleaned = cleanAndReconstruct(fullText.toString());
            log.debug("[ETL] After clean: {} chars", cleaned.length());

            if (cleaned.length() < MIN) {
                throw new IllegalStateException("Text too short after cleaning: " + cleaned.length() + " chars");
            }

            // ── T2: Semantic Chunking (thay TokenTextSplitter) ────────────────
            List<String> chunks = semanticChunk(cleaned);
            log.info("[ETL] SemanticChunker → {} chunks for doc={}", chunks.size(), docId);

            if (chunks.isEmpty()) {
                throw new IllegalStateException("No chunks produced after chunking");
            }

            // ── L: Load — VectorStore + DB ────────────────────────────────────
            embeddingRepository.deleteByDocumentId(docId);

            List<org.springframework.ai.document.Document> vectorBatch    = new ArrayList<>(BATCH_SIZE);
            List<Embedding>                                 embeddingBatch = new ArrayList<>(BATCH_SIZE);

            for (int i = 0; i < chunks.size(); i++) {
                String text       = chunks.get(i);
                String chunkTitle = detectChunkTitle(text, document.getFileName());

                Map<String, Object> meta = new HashMap<>();
                meta.put("documentId", document.getId().toString());
                meta.put("userId",     document.getUserId());
                meta.put("fileName",   document.getFileName());
                meta.put("chunkIndex", String.valueOf(i));
                meta.put("chunkTitle", chunkTitle);
                meta.put("tokenCount", String.valueOf(estimateTokens(text)));
                meta.put("charCount",  String.valueOf(text.length()));

                vectorBatch.add(new org.springframework.ai.document.Document(text, meta));
                embeddingBatch.add(Embedding.builder()
                        .documentId(document.getId())
                        .chunkIndex(i)
                        .chunkText(text)
                        .tokenCount(estimateTokens(text))
                        .charCount(text.length())
                        .build());

                if (vectorBatch.size() >= BATCH_SIZE) {
                    vectorStore.add(new ArrayList<>(vectorBatch));
                    embeddingRepository.saveAll(new ArrayList<>(embeddingBatch));
                    log.info("[ETL] Flushed batch [{}-{}] for doc={}", i - BATCH_SIZE + 1, i, docId);
                    vectorBatch.clear();
                    embeddingBatch.clear();
                }
            }

            // Flush batch cuối
            if (!vectorBatch.isEmpty()) {
                vectorStore.add(vectorBatch);
                embeddingRepository.saveAll(embeddingBatch);
                log.info("[ETL] Flushed final batch for doc={}", docId);
            }

            log.info("[ETL] ✓ Stored {} chunks for doc={}", chunks.size(), docId);
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
    //  T1 — CLEAN + RECONSTRUCT PDF BROKEN LINES
    // =========================================================================

    /**
     * Bước 1: normalize encoding, xóa noise
     * Bước 2: reconstruct PDF broken lines
     *
     * PDF extractor thường bẻ gãy 1 đoạn văn thành nhiều dòng ngắn.
     * Rule join line[i] với line[i+1] khi:
     *  - line[i] KHÔNG kết thúc bằng dấu câu cứng (.!?:;)
     *  - line[i] KHÔNG phải heading candidate
     *  - line[i+1] bắt đầu bằng chữ thường HOẶC từ nối
     *  - line[i+1] KHÔNG phải heading
     */
    private String cleanAndReconstruct(String raw) {
        if (raw == null || raw.isBlank()) return "";

        // Phase 1: normalize encoding
        String normalized = raw
                .replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n")
                .replace("\u00A0", " ").replace("\u200B", "").replace("\u200C", "")
                .replace("\u200D", "").replace("\uFFFD", "").replace("\t", " ")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                .replaceAll("\n{4,}", "\n\n\n");

        // Phase 2: line-by-line clean + reconstruct
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int blanks = 0;

        for (int i = 0; i < lines.length; i++) {
            String line    = lines[i];
            String trimmed = line.strip();

            // Bỏ số trang: "1", "- 2 -", "Page 3", "Trang 4"
            if (trimmed.matches("^[-–—]?\\s*\\d{1,4}\\s*[-–—]?$")) continue;
            if (trimmed.matches("(?i)^(page|trang)\\s+\\d+.*$"))    continue;
            // Bỏ dòng noise quá ngắn (< 3 ký tự) nhưng không phải blank
            if (!trimmed.isEmpty() && trimmed.length() < 3)         continue;

            if (trimmed.isEmpty()) {
                if (++blanks <= 2) out.append("\n");
                continue;
            }
            blanks = 0;

            // Kiểm tra có nên join với dòng tiếp theo không
            if (i + 1 < lines.length) {
                String next        = lines[i + 1].strip();
                boolean nextBlank  = next.isEmpty();
                boolean nextHeading = !next.isEmpty() && isHeadingLine(next);

                // Dòng hiện tại kết thúc "cứng" → KHÔNG join
                boolean endsHard = trimmed.matches(".*[.!?:;]\\s*$")
                        || trimmed.length() > 80
                        || isHeadingLine(trimmed)
                        || trimmed.matches("^[§•].*");

                // Dòng tiếp theo bắt đầu bằng chữ thường hoặc từ nối → nên join
                boolean nextContinues = !nextBlank && !nextHeading && (
                        (!next.isEmpty() && Character.isLowerCase(next.charAt(0)))
                                || startsWithConnector(next)
                );

                if (!endsHard && nextContinues) {
                    // Soft break → join với space, không xuống hàng
                    out.append(trimmed).append(" ");
                    continue;
                }
            }

            out.append(trimmed).append("\n");
        }

        return out.toString().trim();
    }

    private boolean isHeadingLine(String line) {
        if (line == null || line.length() > 200) return false;
        HeadingResult hr = detectHeading(line);
        return hr != null;
    }

    private boolean startsWithConnector(String text) {
        if (text.isEmpty()) return false;
        String lower = text.substring(0, Math.min(20, text.length())).toLowerCase();
        for (String c : new String[]{
                "và ", "hoặc ", "nhưng ", "mà ", "vì ", "nên ", "thì ", "là ",
                "của ", "trong ", "với ", "để ", "cho ", "khi ", "theo ", "từ ",
                "and ", "or ", "but ", "which ", "that ", "who ", "where ", "when "}) {
            if (lower.startsWith(c)) return true;
        }
        return false;
    }

    // =========================================================================
    //  T2 — SEMANTIC CHUNKER
    // =========================================================================

    /**
     * Entry point: text đã clean → List<String> chunks
     *
     * Pipeline:
     *  1. parseStructure()  → phát hiện heading → List<Section>
     *  2. chunkSection()    → sliding window theo câu cho mỗi section
     *  3. addBreadcrumb()   → prepend "[Phần X > Mục Y]\n" vào chunk
     *
     * Đảm bảo:
     *  - Chunk không bao giờ cắt ngang câu
     *  - Heading không bao giờ bị tách khỏi nội dung của nó
     *  - Overlap là câu hoàn chỉnh, không phải ký tự ngẫu nhiên
     *  - Mỗi chunk mang breadcrumb để LLM biết context vị trí
     */
    private List<String> semanticChunk(String text) {
        List<Section> sections = parseStructure(text);
        log.debug("[Chunk] sections={}", sections.size());

        List<String> result = new ArrayList<>();
        // Track heading stack để build breadcrumb
        String[] breadcrumb = new String[]{"", ""};  // [level1, level2]

        for (Section section : sections) {
            // Cập nhật breadcrumb theo level
            if (section.level() <= 1) {
                breadcrumb[0] = section.heading();
                breadcrumb[1] = "";
            } else {
                breadcrumb[1] = section.heading();
            }

            List<String> sectionChunks = chunkSection(section.body());
            for (String chunk : sectionChunks) {
                if (estimateTokens(chunk) < MIN_TOKENS) continue;
                String withBreadcrumb = addBreadcrumb(chunk, breadcrumb);
                result.add(withBreadcrumb);
            }
        }
        return result;
    }

    /**
     * Thêm breadcrumb vào đầu chunk.
     * Ví dụ: "[2. Trade-offs trong kiến trúc > Performance vs Maintainability]\n"
     *
     * Lý do quan trọng: khi LLM nhận chunk này, nó biết ngay đây là nội dung
     * của mục nào → trả lời chính xác hơn, ít hallucinate hơn.
     */
    private String addBreadcrumb(String chunk, String[] breadcrumb) {
        String b1 = breadcrumb[0];
        String b2 = breadcrumb[1];

        if (b1.isBlank() && b2.isBlank()) return chunk;

        String crumb;
        if (!b1.isBlank() && !b2.isBlank()) {
            crumb = "[" + b1 + " > " + b2 + "]";
        } else {
            crumb = "[" + (b1.isBlank() ? b2 : b1) + "]";
        }

        // Không thêm nếu chunk đã bắt đầu bằng heading đó
        if (chunk.startsWith(crumb) || chunk.startsWith(b1)) return chunk;
        return crumb + "\n" + chunk;
    }

    // ─── parseStructure ───────────────────────────────────────────────────────

    /**
     * Line-by-line scan, phát hiện heading → flush section → bắt đầu section mới.
     * KHÔNG có dòng nào bị mất (zero content loss).
     * Block (code, table, list) được giữ nguyên, không bị heading detect bên trong.
     */
    private List<Section> parseStructure(String text) {
        List<Section> sections = new ArrayList<>();
        String[] lines  = text.split("\n", -1);

        String        heading = "General";
        int           level   = 0;
        StringBuilder body    = new StringBuilder();

        boolean inCode  = false;
        boolean inTable = false;
        boolean inList  = false;

        for (String line : lines) {
            String t = line.strip();

            // ── Code fence ────────────────────────────────────────────────────
            if (P_CODE.matcher(t).matches()) {
                inCode = !inCode;
                body.append(line).append("\n");
                continue;
            }
            if (inCode) { body.append(line).append("\n"); continue; }

            // ── Table ─────────────────────────────────────────────────────────
            if (P_TROW.matcher(t).matches() || P_TSEP.matcher(t).matches()) {
                inTable = true;
                body.append(line).append("\n");
                continue;
            }
            if (inTable) {
                body.append(line).append("\n");
                if (P_BLANK.matcher(t).matches()) inTable = false;
                continue;
            }

            // ── List ──────────────────────────────────────────────────────────
            if (P_BULL.matcher(t).matches()) {
                inList = true;
                body.append(line).append("\n");
                continue;
            }
            if (inList) {
                // Thoát list khi gặp dòng không phải bullet và không phải indent
                boolean isContinuation = P_BLANK.matcher(t).matches()
                        || line.startsWith("  ") || line.startsWith("\t");
                body.append(line).append("\n");
                if (!isContinuation) inList = false;
                continue;
            }

            // ── Blank line ─────────────────────────────────────────────────────
            if (P_BLANK.matcher(t).matches()) {
                body.append("\n");
                continue;
            }

            // ── Heading detection ──────────────────────────────────────────────
            HeadingResult hr = detectHeading(t);
            if (hr != null) {
                // Flush section hiện tại
                String bodyStr = body.toString().trim();
                if (!bodyStr.isEmpty()) {
                    sections.add(new Section(heading, level, bodyStr));
                }
                heading = hr.title();
                level   = hr.level();
                body.setLength(0);
                // Giữ dòng heading trong body để chunk có context
                body.append(line).append("\n");
            } else {
                body.append(line).append("\n");
            }
        }

        // Flush section cuối
        String bodyStr = body.toString().trim();
        if (!bodyStr.isEmpty()) sections.add(new Section(heading, level, bodyStr));
        if (sections.isEmpty()) sections.add(new Section("General", 0, text.trim()));

        return sections;
    }

    private HeadingResult detectHeading(String line) {
        if (line == null || line.isBlank() || line.length() > 200) return null;
        Matcher m;

        // 1. Markdown
        m = P_MD.matcher(line);
        if (m.matches()) return new HeadingResult(m.group(2).trim(), m.group(1).length());

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

        // 4. Numbered heading — BẮT BUỘC dấu chấm, text không kết thúc bằng dấu chấm
        m = P_NUM.matcher(line);
        if (m.matches()) {
            String textPart = m.group(3);
            // Reject nếu là câu văn bình thường (kết thúc dấu chấm hoặc nhiều dấu phẩy)
            if (!textPart.endsWith(".") && textPart.chars().filter(c -> c == ',').count() <= 2) {
                int dots = (int) m.group(1).chars().filter(c -> c == '.').count();
                return new HeadingResult(line.trim(), Math.min(dots + 1, 4));
            }
        }

        // 5. ALL-CAPS title (không trong markdown doc, không có dấu phẩy, không kết thúc dấu chấm)
        if (P_CAP.matcher(line).matches() && !line.contains(",") && !line.endsWith(".")) {
            return new HeadingResult(line.trim(), 1);
        }

        return null;
    }

    // ─── chunkSection ─────────────────────────────────────────────────────────

    /**
     * Sliding window chunker theo câu cho một section body.
     *
     * Thuật toán:
     *  1. Split body → sentences (theo paragraph boundary trước, rồi dấu câu)
     *  2. Accumulate sentences vào window cho đến khi đạt TARGET tokens
     *  3. Emit chunk → slide window lùi OVERLAP_TOKENS (tính theo câu hoàn chỉnh)
     *  4. Lặp đến hết sentences
     *
     * Đảm bảo i luôn tiến ít nhất 1 → không bao giờ infinite loop.
     */
    private List<String> chunkSection(String body) {
        List<String> sentences = splitIntoSentences(body);
        if (sentences.isEmpty()) return List.of();

        List<String> chunks = new ArrayList<>();
        int i = 0;

        while (i < sentences.size()) {
            StringBuilder window = new StringBuilder();
            int j = i;

            // Tích lũy câu vào window
            while (j < sentences.size()) {
                String next      = sentences.get(j);
                String candidate = window.isEmpty() ? next : window + " " + next;

                if (estimateTokens(candidate) > MAX_TOKENS && !window.isEmpty()) {
                    break; // window đầy
                }
                window = new StringBuilder(candidate);
                j++;

                if (estimateTokens(window.toString()) >= TARGET_TOKENS) {
                    break; // đạt target → emit
                }
            }

            String chunk = formatChunk(window.toString());
            if (!chunk.isBlank()) chunks.add(chunk);

            if (j == i) {
                // Câu đơn quá dài → hard split
                chunks.addAll(hardSplit(sentences.get(i)));
                i++;
            } else {
                // Tính overlap: đếm ngược từ j-1
                int overlapTokens = 0;
                int overlapStart  = j; // mặc định không overlap
                for (int k = j - 1; k > i; k--) {
                    int t = estimateTokens(sentences.get(k));
                    if (overlapTokens + t > OVERLAP_TOKENS) break;
                    overlapTokens += t;
                    overlapStart   = k;
                }
                // i phải tiến ít nhất 1 để tránh vòng lặp vô hạn
                i = Math.max(i + 1, overlapStart);
            }
        }

        return chunks;
    }

    /**
     * Split text thành sentences.
     * Ưu tiên paragraph boundary (\n\n) trước, rồi dấu câu.
     * Block (code, table, list) giữ nguyên không split.
     */
    private List<String> splitIntoSentences(String text) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+", -1);

        for (String para : paragraphs) {
            String p = para.strip();
            if (p.isEmpty()) continue;

            // Block → giữ nguyên
            String firstLine = p.split("\n", 2)[0].strip();
            boolean isBlock = P_CODE.matcher(firstLine).matches()
                    || P_TROW.matcher(firstLine).matches()
                    || P_BULL.matcher(firstLine).matches();

            if (isBlock) {
                result.add(p);
                continue;
            }

            // Flatten paragraph thành 1 dòng rồi split theo dấu câu
            String flat = para.replace("\n", " ").replaceAll(" {2,}", " ").strip();

            Matcher m     = P_SENT.matcher(flat);
            int     last  = 0;

            while (m.find()) {
                int end = m.end();
                String sent = flat.substring(last, end).strip();
                if (!sent.isEmpty()) result.add(sent);
                last = end;
                if (last < flat.length() && flat.charAt(last) == ' ') last++;
            }

            // Phần còn lại sau dấu câu cuối
            if (last < flat.length()) {
                String rem = flat.substring(last).strip();
                if (!rem.isEmpty()) result.add(rem);
            }

            // Không tìm thấy dấu câu nào → cả paragraph là 1 câu
            if (last == 0 && !flat.isEmpty()) result.add(flat);
        }

        return result;
    }

    /** Hard split câu quá dài tại word boundary */
    private List<String> hardSplit(String sentence) {
        List<String> result = new ArrayList<>();
        int pos = 0;
        while (pos < sentence.length()) {
            int end = Math.min(pos + MAX, sentence.length());
            if (end < sentence.length()) {
                int space = sentence.lastIndexOf(' ', end);
                if (space > pos) end = space;
            }
            String piece = sentence.substring(pos, end).strip();
            if (!piece.isEmpty()) result.add(piece);
            pos = end + 1;
        }
        return result;
    }

    /** Normalize chunk output */
    private String formatChunk(String chunk) {
        if (chunk == null || chunk.isBlank()) return "";
        String[] lines = chunk.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int blanks = 0;
        for (String line : lines) {
            String norm = line.stripTrailing().replaceAll("(?<=\\S) {2,}", " ");
            if (norm.isBlank()) {
                if (++blanks <= 2) sb.append("\n");
            } else {
                blanks = 0;
                sb.append(norm).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // =========================================================================
    //  T3 — CHUNK TITLE DETECTION (no LLM)
    // =========================================================================

    /**
     * Scan 5 dòng đầu của chunk tìm heading → dùng làm title.
     * Fallback: 10 từ đầu tiên.
     */
    private String detectChunkTitle(String chunkText, String fallbackFileName) {
        if (chunkText == null || chunkText.isBlank()) return fallbackFileName;

        // Bỏ qua dòng breadcrumb (bắt đầu bằng "[")
        String[] lines = chunkText.split("\n", 8);
        for (String line : lines) {
            String t = line.strip();
            if (t.isEmpty() || t.length() < 3 || t.length() > 150) continue;
            if (t.startsWith("[") && t.endsWith("]")) continue; // breadcrumb line

            HeadingResult hr = detectHeading(t);
            if (hr != null) {
                String title = t.replaceAll("^#+\\s*", "").trim();
                if (!title.isBlank()) return title;
            }
        }

        // Fallback: 10 từ đầu (bỏ qua breadcrumb)
        String flat = chunkText
                .replaceAll("^\\[.+]\\n", "")   // bỏ dòng breadcrumb
                .replaceAll("\\s+", " ").trim();
        String[] words = flat.split(" ");
        int take    = Math.min(10, words.length);
        String preview = String.join(" ", Arrays.copyOf(words, take));
        return preview.length() > 80 ? preview.substring(0, 80) + "…" : preview;
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================

    private void setStatus(Document doc, DocStatus status, String error) {
        doc.setStatus(status);
        doc.setErrorMessage(error);
        documentRepository.save(doc);
    }

    /** 1 token ≈ 3.8 chars (Vietnamese + English mixed) */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }
}