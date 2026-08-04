# 📊 GIAI ĐOẠN 4 — BENCHMARK, TRADE-OFF ANALYSIS & BÁO CÁO
## Thời gian: Tuần 4 (7 ngày) | Mục tiêu: Kết quả số liệu đầy đủ + Báo cáo hoàn chỉnh + Demo

> **Điều kiện tiên quyết:** ✅ Giai Đoạn 3 Done — 3 engine chạy được: pgvector, MongoDB Vector, GraphRAG  
> **AI Tools hỗ trợ:** Claude (phân tích kết quả, viết báo cáo), ChatGPT (LLM-as-Judge), Gemini (kiểm tra lại)  
> **Output bắt buộc cuối tuần:** Bảng benchmark đầy đủ + biểu đồ + báo cáo hoàn chỉnh + slide demo

---

## 📅 LỊCH THEO TỪNG NGÀY

### 🗓️ Ngày 1 (Thứ 2) — BenchmarkRunner + Chạy Toàn Bộ Benchmark

**Dev 1 (Quỳnh Gia) — Code:**

```java
// src/main/java/com/security/security/benchmark/BenchmarkRunner.java
@Service
@Slf4j
@RequiredArgsConstructor
public class BenchmarkRunner {

    private final QueryOrchestrator queryOrchestrator;
    private final BenchmarkDatasetLoader datasetLoader;

    private static final int REPETITIONS = 3;  // Chạy 3 lần → lấy trung bình

    /**
     * Chạy toàn bộ benchmark: 20 câu × 3 engine × 3 lần = 180 test runs
     * Kết quả ghi vào file CSV để phân tích
     */
    public BenchmarkReport runFull(String workspaceId, List<String> userRoles) {
        List<BenchmarkTestCase> testCases = datasetLoader.load("benchmark_dataset.json");
        List<BenchmarkResult> allResults = new ArrayList<>();

        for (BenchmarkTestCase tc : testCases) {
            log.info("[BENCHMARK] Running test_id={} category={}", tc.testId(), tc.category());

            for (QueryStrategy engine : QueryStrategy.values()) {
                List<Long> latencies = new ArrayList<>();

                for (int i = 0; i < REPETITIONS; i++) {
                    long start = System.currentTimeMillis();

                    QueryResponse response = queryOrchestrator.query(
                        tc.question(), workspaceId, userRoles, engine
                    );

                    long latencyMs = System.currentTimeMillis() - start;
                    latencies.add(latencyMs);

                    allResults.add(new BenchmarkResult(
                        tc.testId(),
                        engine.name(),
                        tc.category(),
                        tc.question(),
                        latencyMs,
                        response.getRetrievedDocsCount(),
                        response.getTopScore(),
                        response.getAnswer(),
                        Instant.now().toEpochMilli()
                    ));
                }

                double avgLatency = latencies.stream().mapToLong(l -> l).average().orElse(0);
                double p99Latency = calculateP99(latencies);
                log.info("[BENCHMARK] test={} engine={} avg={}ms p99={}ms",
                    tc.testId(), engine, (long)avgLatency, (long)p99Latency);
            }
        }

        // Export CSV
        exportToCsv(allResults, "benchmark-results-" +
            LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".csv");

        return BenchmarkReport.from(allResults);
    }

    private double calculateP99(List<Long> latencies) {
        List<Long> sorted = latencies.stream().sorted().toList();
        int p99Index = (int) Math.ceil(sorted.size() * 0.99) - 1;
        return sorted.get(Math.max(0, p99Index));
    }

    private void exportToCsv(List<BenchmarkResult> results, String filename) {
        // Ghi ra file CSV để import vào Excel hoặc Python pandas
        StringBuilder csv = new StringBuilder();
        csv.append("test_id,engine,category,question,latency_ms,docs_count,top_score,answer\n");
        results.forEach(r -> csv.append(String.format("%s,%s,%s,\"%s\",%d,%d,%.4f,\"%s\"\n",
            r.testId(), r.engine(), r.category(), r.question(),
            r.latencyMs(), r.retrievedDocsCount(), r.topScore(),
            r.aiAnswer().replace("\"", "'"))));

        try {
            Files.writeString(Path.of("benchmark-results/" + filename), csv.toString());
            log.info("[BENCHMARK] Exported {} results to {}", results.size(), filename);
        } catch (IOException e) {
            log.error("[BENCHMARK] Failed to export CSV", e);
        }
    }
}
```

- [ ] Implement `BenchmarkDatasetLoader.java` đọc file `benchmark_dataset.json`
- [ ] Implement `BenchmarkResult` record đầy đủ
- [ ] Chạy thử 5 câu test trước → kiểm tra CSV output
- [ ] Chạy toàn bộ 20 câu × 3 engine × 3 lần → lưu file CSV

**Dev 2:**
- [ ] Chuẩn bị bảng Excel để fill kết quả: **20 câu × 3 engine × 3 metrics**
- [ ] Đọc kết quả CSV và điền vào bảng Trade-off Analysis trong báo cáo

**Output Ngày 1:** File CSV với đầy đủ 180 kết quả benchmark

---

### 🗓️ Ngày 2 (Thứ 3) — RBAC Test Suite + LLM-as-Judge Scoring

**Dev 1 (Quỳnh Gia) — Code:**

```java
// src/main/java/com/security/security/benchmark/RBACTestSuite.java
@Service
@Slf4j
@RequiredArgsConstructor
public class RBACTestSuite {

    private final QueryOrchestrator queryOrchestrator;

    /**
     * Kiểm tra 100% isolation giữa các workspace và roles
     * Đây là metric bắt buộc: RBAC Isolation Rate = 100%
     */
    public RBACTestReport run() {
        List<RBACTestCase> testCases = buildTestCases();
        List<RBACTestResult> results = new ArrayList<>();

        for (RBACTestCase tc : testCases) {
            for (QueryStrategy engine : QueryStrategy.values()) {

                QueryResponse response = queryOrchestrator.query(
                    tc.question(),
                    tc.requesterWorkspace(),
                    tc.requesterRoles(),
                    engine
                );

                boolean leaked = checkDataLeak(response, tc.forbiddenWorkspace());

                results.add(new RBACTestResult(
                    tc.testId(), engine.name(),
                    tc.question(), leaked,
                    response.getAnswer()
                ));

                if (leaked) {
                    log.error("[RBAC-FAIL] test={} engine={} LEAKED data from workspace={}",
                        tc.testId(), engine, tc.forbiddenWorkspace());
                }
            }
        }

        long leakCount = results.stream().filter(RBACTestResult::leaked).count();
        double isolationRate = (1.0 - (double) leakCount / results.size()) * 100;

        log.info("[RBAC] Total={} Leaked={} IsolationRate={}%",
            results.size(), leakCount, isolationRate);

        return new RBACTestReport(results, isolationRate);
    }

    private List<RBACTestCase> buildTestCases() {
        return List.of(
            // TC-S01: DEV user hỏi tài liệu của workspace HR
            new RBACTestCase("TC-S01",
                "Cho xem quy trình tuyển dụng nội bộ",
                "ws-engineering", List.of("DEV"),
                "ws-hr"),
            // TC-S02: MEMBER hỏi tài liệu ADMIN-only
            new RBACTestCase("TC-S02",
                "Chi tiết kiến trúc bảo mật hệ thống?",
                "ws-engineering", List.of("MEMBER"),
                "ws-admin-only"),
            // TC-S03: GUEST hỏi tài liệu private workspace
            new RBACTestCase("TC-S03",
                "Doanh thu quý 2 của công ty?",
                "ws-guest", List.of("GUEST"),
                "ws-finance")
            // ... thêm 7 test cases nữa
        );
    }

    private boolean checkDataLeak(QueryResponse response, String forbiddenWorkspace) {
        // Kiểm tra answer có chứa nội dung từ forbidden workspace không
        // Dùng keyword matching hoặc LLM-as-judge
        return response.getAnswer().toLowerCase()
            .contains(forbiddenWorkspace.replace("ws-", ""));
    }
}
```

**LLM-as-Judge — Auto Score câu trả lời (1-5):**

```java
// src/main/java/com/security/security/benchmark/LLMJudge.java
@Service
@RequiredArgsConstructor
public class LLMJudge {

    private final ChatClient chatClient;

    private static final String JUDGE_PROMPT = """
        Bạn là giám khảo đánh giá chất lượng câu trả lời của hệ thống AI.
        
        Câu hỏi: %s
        Đáp án tham chiếu (ground truth): %s
        Câu trả lời của AI: %s
        
        Hãy chấm điểm câu trả lời từ 1-5 theo tiêu chí:
        5 - Hoàn toàn chính xác, đầy đủ thông tin
        4 - Chính xác nhưng thiếu 1-2 chi tiết nhỏ
        3 - Một phần đúng, còn thiếu thông tin quan trọng  
        2 - Phần lớn sai hoặc không liên quan
        1 - Hoàn toàn sai hoặc bịa đặt (hallucination)
        
        Chỉ trả về số điểm (1-5), không giải thích.
        """;

    public int score(String question, String groundTruth, String aiAnswer) {
        String prompt = JUDGE_PROMPT.formatted(question, groundTruth, aiAnswer);
        String scoreStr = chatClient.prompt().user(prompt).call().content().trim();
        try {
            int score = Integer.parseInt(scoreStr);
            return Math.max(1, Math.min(5, score));  // clamp 1-5
        } catch (NumberFormatException e) {
            return 3;  // Default nếu LLM trả về format lạ
        }
    }
}
```

- [ ] Chạy RBAC Test Suite → xác nhận 100% isolation
- [ ] Chạy LLM-as-Judge cho 20 câu × 3 engine → có bảng điểm 1-5
- [ ] **Dùng AI:** Paste LLM Judge prompt vào Claude → tối ưu prompt cho chính xác hơn

**Dev 2:**
- [ ] Ghi kết quả RBAC vào báo cáo
- [ ] Ghi điểm LLM-as-Judge vào bảng Trade-off Analysis

**Output Ngày 2:** RBAC isolation = 100%, LLM answer scores đầy đủ

---

### 🗓️ Ngày 3 (Thứ 4) — Phân Tích Kết Quả + Vẽ Biểu Đồ

**Dev 1 (Quỳnh Gia):**
- [ ] Mở file CSV benchmark → tính toán các số liệu tổng hợp:

```
Với mỗi engine (pgvector, MongoDB, GraphRAG):
- Latency P50 (median) và P99 theo từng Query Type
- Trung bình LLM Score theo từng Query Type
- Số lượng documents retrieved trung bình
```

**Ghi kết quả vào bảng Trade-off Analysis:**

| Query Type | Metric | pgvector | MongoDB Vector | MongoDB GraphRAG |
|---|---|---|---|---|
| **Type A (Factual)** | Latency P50 (ms) | ___ | ___ | ___ |
| | LLM Score (avg) | ___ | ___ | ___ |
| **Type B (Conceptual)** | Latency P50 (ms) | ___ | ___ | ___ |
| | LLM Score (avg) | ___ | ___ | ___ |
| **Type C (Relational)** | Latency P50 (ms) | ___ | ___ | ___ |
| | LLM Score (avg) | ___ | ___ | ___ |
| **Type D (Cross-Domain)** | Latency P50 (ms) | ___ | ___ | ___ |
| | LLM Score (avg) | ___ | ___ | ___ |
| **Type S (RBAC Security)** | Isolation Rate (%) | ___ | ___ | ___ |
| **Overall** | Latency P99 (ms) | ___ | ___ | ___ |
| | Storage (MB) | ___ | ___ | ___ |

- [ ] **Tạo biểu đồ (dùng Python matplotlib hoặc Google Sheets):**
  - **Bar chart:** So sánh Latency P50 theo Query Type × 3 engine
  - **Radar chart:** So sánh 5 tiêu chí tổng hợp (Speed, Quality, Security, Scalability, Complexity)
  - **Line chart:** Latency theo số lượng documents (scalability test)

- [ ] **Dùng AI:** Paste CSV vào Claude → hỏi *"Phân tích kết quả và đề xuất kết luận về trường hợp sử dụng phù hợp cho từng engine"*

**Dev 2:**
- [ ] Viết chương 4 (4-5 trang): *Kết Quả Thực Nghiệm & Phân Tích*
  - Mô tả điều kiện thực nghiệm
  - Bảng kết quả đầy đủ
  - Phân tích từng Query Type
  - Nhận xét về RBAC Isolation

**Output Ngày 3:** Biểu đồ + Bảng Trade-off Analysis + Chương 4 draft

---

### 🗓️ Ngày 4 (Thứ 5) — Viết Kết Luận + Chỉnh Báo Cáo

**Dev 1 (Quỳnh Gia):**
- [ ] Viết **chương 5 — Kết Luận & Hướng Phát Triển Tương Lai (2-3 trang):**

```markdown
## 5.1 Kết Luận

Đề tài đã đạt được các mục tiêu đề ra:

**Về kỹ thuật:**
- Xây dựng thành công hệ thống RAG đa tầng với pipeline ETL xử lý tài liệu qua 3 bước MRP
- Triển khai RBAC tại query-time ngăn chặn rò rỉ dữ liệu (Isolation Rate: 100%)
- So sánh thực nghiệm 3 phương pháp lưu trữ và truy xuất vector

**Về kết quả thực nghiệm:**
- pgvector (SQL): Phù hợp cho factual lookup, latency P50 [X]ms, LLM Score [X]/5
- MongoDB Vector Search: Phù hợp cho flexible schema, latency P50 [X]ms, LLM Score [X]/5  
- MongoDB GraphRAG: Vượt trội rõ ràng cho relational reasoning (Type C/D), LLM Score [X]/5 vs [X]/5 của 2 engine còn lại

**Kết luận chính:** Không tồn tại engine "tốt nhất" tuyệt đối. 
Mỗi approach phù hợp với use case khác nhau [...]

## 5.2 Đóng Góp Của Đề Tài (Novel Contribution)
Đề tài đã đề xuất và thực nghiệm một Framework đánh giá RBAC-RAG 
có độ chi tiết cao, kết hợp đo lường tính bảo mật (Isolation Rate) 
với chất lượng sinh văn bản (Faithfulness, LLM Score) trên 
cùng một bộ dataset, cho phép so sánh công bằng giữa 2 paradigm 
cơ sở dữ liệu khác nhau (Relational vs Document) [...]

## 5.3 Hướng Phát Triển Tương Lai
- Mở rộng GraphRAG với Community Detection (theo hướng Microsoft GraphRAG)
- Tích hợp Hybrid Search (Vector + BM25) để cải thiện recall
- Ứng dụng Adaptive RAG: tự động chọn engine phù hợp với loại câu hỏi
- Benchmark trên bộ dữ liệu lớn hơn (1000+ documents) để kiểm tra Scalability
```

- [ ] **Dùng AI:** Paste draft chương 5 vào Claude → hỏi *"Cải thiện phần 'Đóng Góp Của Đề Tài' để nghe thuyết phục hơn và học thuật hơn"*

**Dev 2:**
- [ ] Review toàn bộ báo cáo từ chương 1-4
- [ ] Đảm bảo tính nhất quán: thuật ngữ, viết tắt, số liệu trích dẫn

**Output Ngày 4:** Báo cáo draft hoàn chỉnh từ chương 1-5

---

### 🗓️ Ngày 5 (Thứ 6) — Chuẩn Bị Demo + Slide

**Cả nhóm:**

**Kịch bản demo (10 phút):**
```
1. [1 phút]   Giới thiệu đề tài + Kiến trúc tổng thể (slide)
2. [2 phút]   Live demo: Ingest 1 tài liệu PDF → thấy data vào pgvector VÀ MongoDB
3. [3 phút]   Live demo A/B Compare:
               - Type A query → 2 engine trả về tương đương
               - Type C query → GraphRAG trả về context rõ hơn rõ ràng
               - Type S query → Cả 3 engine từ chối đúng (RBAC isolation)
4. [2 phút]   Hiện bảng Trade-off Analysis + biểu đồ kết quả
5. [2 phút]   Kết luận + Q&A prep
```

- [ ] **Dev 1:** Chuẩn bị môi trường demo (chạy sẵn 3 service: PostgreSQL, MongoDB, Spring Boot)
- [ ] **Dev 2:** Làm slide (12-15 slides):
  - Slide 1: Tiêu đề + Thành viên nhóm
  - Slide 2-3: Vấn đề + Bối cảnh (Tại sao cần RBAC-RAG?)
  - Slide 4-5: Kiến trúc hệ thống tổng thể
  - Slide 6-7: Pipeline ETL + 3 Engine so sánh
  - Slide 8-9: Kết quả Benchmark (biểu đồ)
  - Slide 10: Trade-off Analysis Summary Table
  - Slide 11: Kết luận + Novel Contribution
  - Slide 12: Q&A + Hướng phát triển

- [ ] **Cả nhóm:** Luyện demo 2 lần → đảm bảo demo chạy mượt không lỗi

**Output Ngày 5:** Slide hoàn chỉnh, demo script được rehearsal

---

### 🗓️ Ngày 6-7 (Cuối Tuần) — Final Polish + Submit

- [ ] Fix các lỗi minor trong báo cáo
- [ ] Kiểm tra định dạng tài liệu (font, cỡ chữ, tiêu đề)
- [ ] Kiểm tra danh sách tài liệu tham khảo đầy đủ (cite đúng format)
- [ ] Submit báo cáo + code lên GitHub

---

## 🔴 FINAL REVIEW — CÂU HỎI HỘI ĐỒNG (LUYỆN TRƯỚC)

> Nhóm phải trả lời tất cả 5 câu hỏi dưới đây **không cần nhìn script**:

**Q1: "Đóng góp mới (novel contribution) của đề tài so với các nghiên cứu trước là gì?"**

> **Trả lời chuẩn:** Đề tài đề xuất một Framework Đánh Giá RBAC-RAG Thực Nghiệm (RBAC-RAG Empirical Evaluation Framework) — kết hợp đo lường đồng thời tính bảo mật (RBAC Isolation Rate = 100%), chất lượng truy xuất (Context Precision, Recall) và chất lượng sinh văn bản (Faithfulness, LLM Score) trên cùng một dataset, so sánh 2 paradigm cơ sở dữ liệu trong bối cảnh quản lý tri thức doanh nghiệp phân quyền đa cấp — một bài toán chưa được khảo sát rõ ràng trong các nghiên cứu hiện tại.

**Q2: "Tại sao em chọn MongoDB thay vì Elasticsearch hay Qdrant?"**

> **Trả lời chuẩn:** MongoDB cho phép kết hợp Document Store + Vector Search ($vectorSearch) + Graph Traversal ($graphLookup) trên cùng một engine. Với cấu trúc phân cấp Document → Wiki → Chunk của hệ thống, Document Model MongoDB giúp loại bỏ SQL JOINs và cho phép pre-filter RBAC trực tiếp trên metadata JSON lồng nhau. Elasticsearch thiếu khả năng $graphLookup. Qdrant không hỗ trợ Document Model hay graph traversal natively.

**Q3: "MongoDB chậm hơn pgvector trong benchmark. Vậy em rút ra kết luận gì?"**

> **Trả lời chuẩn:** Đây chính xác là kết quả mong đợi. pgvector với SQL tối ưu cho factual lookup (Type A/B) trên dữ liệu có cấu trúc chặt. MongoDB GraphRAG chậm hơn nhưng vượt trội ở LLM Score cho Type C/D (relational reasoning) — 2 hệ thống có trade-off khác nhau và phù hợp với use case khác nhau, không thể nói "cái nào tốt hơn tuyệt đối".

**Q4: "Tại sao pre-filter RBAC bảo mật hơn post-filter hay prompt-filter?"**

> **Trả lời chuẩn:** Post-filter: document đã vào context trước → LLM có thể bị leaked qua side channel. Prompt-filter ("chỉ dùng doc có quyền"): LLM có thể bị prompt injection → không đảm bảo. Pre-filter: RBAC metadata được enforce tại vector database level, document không được phép không bao giờ vào retrieval set → fail-closed principle.

**Q5: "Em giải thích cơ chế $graphLookup trong MongoDB và tại sao nó phù hợp với GraphRAG?"**

> **Trả lời chuẩn:** $graphLookup là aggregation stage thực hiện recursive graph traversal trong MongoDB. Cho phép bắt đầu từ một tập seed nodes → theo dõi field `relationships.targetId` → tìm tất cả nodes kết nối trong maxDepth bậc. Kết hợp với `restrictSearchWithMatch` để filter RBAC. Phù hợp với GraphRAG vì Knowledge Graph (entity-relationship) được lưu native trong MongoDB, không cần engine đồ thị riêng (như Neo4j), giảm complexity kiến trúc.

---

## ✅ DEFINITION OF DONE — GIAI ĐOẠN 4

- [ ] `BenchmarkRunner.java` — chạy tự động 20 câu × 3 engine × 3 lần
- [ ] `RBACTestSuite.java` — kiểm tra 100% isolation
- [ ] `LLMJudge.java` — auto-score câu trả lời 1-5
- [ ] File `benchmark-results-YYYY-MM-DD.csv` — kết quả thô đầy đủ
- [ ] Bảng Trade-off Analysis đã được điền đầy đủ số liệu
- [ ] Biểu đồ Bar chart + Radar chart hoàn chỉnh
- [ ] Báo cáo chương 1-5 hoàn chỉnh
- [ ] Slide thuyết trình 12-15 trang
- [ ] Demo rehearsal thành công ≥ 2 lần
- [ ] Code push lên GitHub + tagged release

---

## 🎉 TỔNG KẾT 4 TUẦN

| Tuần | Giai Đoạn | Output Chính |
|---|---|---|
| Tuần 1 | Baseline & Framework | MongoDB schema, Benchmark design, pgvector baseline |
| Tuần 2 | MongoDB Vector Search | RBAC pre-filter, A/B query, chương 3.1 |
| Tuần 3 | GraphRAG | Entity Graph, $graphLookup, chương 3.2 |
| Tuần 4 | Benchmark & Báo Cáo | Số liệu đầy đủ, biểu đồ, báo cáo, slide, demo |

**Với sự hỗ trợ của AI (Copilot, Claude, ChatGPT), toàn bộ 4 giai đoạn có thể hoàn thành trong 28 ngày thay vì 11 tuần truyền thống.**

---

*Giai Đoạn 4 | Version: 1.0 | Tuần 4 (7 ngày)*
