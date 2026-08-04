# 📚 KLTN — KẾ HOẠCH TRIỂN KHAI & MENTOR REVIEW PLAN
## "Thiết kế và hiện thực đường ống pipeline xử lý tài liệu đa tầng kết hợp truy xuất thông tin phân quyền dựa trên RAG trong quản lý tri thức doanh nghiệp"

> **Mentor:** Senior Software Architect (AI/Backend Systems)  
> **Nhóm:** 2 thành viên (Dev 1 – Nguyễn Quỳnh Gia, Dev 2)  
> **Trạng thái hiện tại:** Hệ thống chạy được với PostgreSQL + pgvector (Baseline ✅)  
> **Mục tiêu tiếp theo:** Xây dựng module MongoDB Vector Search + GraphRAG để làm **Trade-off Analysis** có giá trị học thuật

---

## 🔭 1. BỨC TRANH TOÀN CẢNH — TẦM NHÌN CỦA ĐỀ TÀI

### Điểm mạnh hiện tại (đã có)
- ✅ Pipeline ETL xử lý tài liệu đa định dạng (MRP: Map → Reduce → Publish)
- ✅ RAG với phân quyền RBAC tại query-time trên pgvector
- ✅ AI Agent ReAct Loop tự hành (Thought → Action → Observation)
- ✅ Hệ thống Microservices Node.js chạy đầy đủ (6 services)
- ✅ Spring Boot + Spring AI + Java 21 Virtual Threads

### Khoảng trống cần lấp đầy (thiếu)
- ❌ Chưa có **điểm nhấn phân biệt** về mặt học thuật (novel contribution)
- ❌ Chưa có **bằng chứng thực nghiệm** (empirical evidence) về lựa chọn công nghệ
- ❌ Chưa xử lý được **dữ liệu đồ thị phân cấp** (hierarchical + relational knowledge)
- ❌ Chưa có kết quả benchmark **định lượng** để đưa vào báo cáo

### Định hướng của Thầy (chốt lại)
Thầy không yêu cầu từ bỏ pgvector. Thầy yêu cầu **tư duy kiến trúc sư (Architect Thinking)**:

```
PostgreSQL + pgvector          →  Baseline (tính nhất quán, ACID, SQL joins)
MongoDB Vector Search          →  Challenger (Document model, flexible schema)
MongoDB $graphLookup           →  GraphRAG (Knowledge Graph traversal)
─────────────────────────────────────────────────────────────────────
Kết quả: Trade-off Analysis Report + Benchmark → Novel Contribution
```

### 💡 MỤC ĐÍCH THẬT SỰ CỦA 4 GIAI ĐOẠN

1. **Giai đoạn 1 (Củng cố Baseline & Thiết kế Framework):**
   * **Mục đích thật sự:** Tạo lập **"Thước đo chuẩn" (Control Group)**. Trước khi chứng minh MongoDB hay GraphRAG vượt trội ở điểm nào, ta bắt buộc phải có một baseline PostgreSQL + pgvector hoạt động định hình 100% cùng một bộ tiêu chí đo lường định lượng không thể chối cãi.
2. **Giai đoạn 2 (Xây dựng Module MongoDB Vector Search):**
   * **Mục đích thật sự:** Thực nghiệm **Document Model vs Relational Model**. Thử nghiệm tính hiệu quả của cấu trúc phi tập trung (JSON lồng nhau) khi truy vấn vector kèm lọc metadata phân quyền (RBAC multi-tenant) nhằm chứng minh việc loại bỏ SQL JOINs giúp tăng khả năng mở rộng (Scalability).
3. **Giai đoạn 3 (Xây dựng GraphRAG với $graphLookup):**
   * **Mục đích thật sự:** Giải bài toán **"Tri thức có tính liên kết đồ thị"**. Vector Search đơn thuần chỉ tìm được các đoạn văn bản có từ ngữ tương đồng (Flat Chunks), trong khi GraphRAG cho phép rút trích mối quan hệ thực thể (`Service A` -> `dùng database` -> `Postgres`), giúp AI có ngữ cảnh toàn cục (Global Context) để trả lời các câu hỏi phức tạp.
4. **Giai đoạn 4 (Benchmark, Trade-off Analysis & Báo cáo):**
   * **Mục đích thật sự:** Biến một ứng dụng phần mềm thành **"Công trình nghiên cứu khoa học có giá trị"**. Sử dụng các chỉ số toán học/định lượng (Precision, Recall, Faithfulness, RBAC Isolation Rate, Latency P99) để bảo vệ phản biện trước Hội đồng một cách thuyết phục nhất.

---

## 🗺️ 2. LỘ TRÌNH 4 GIAI ĐOẠN

```
GIAI ĐOẠN 1 (Tuần 1-2): Củng cố Baseline + Thiết kế Kiến trúc Thực nghiệm
GIAI ĐOẠN 2 (Tuần 3-5): Xây dựng Module MongoDB Vector Search
GIAI ĐOẠN 3 (Tuần 6-8): Xây dựng GraphRAG với $graphLookup  
GIAI ĐOẠN 4 (Tuần 9-11): Benchmark + Trade-off Analysis + Viết báo cáo
```

---

## 📋 3. CHI TIẾT TỪNG GIAI ĐOẠN

---

### ⚙️ GIAI ĐOẠN 1: CỦNG CỐ BASELINE & THIẾT KẾ THỰC NGHIỆM (Tuần 1–2)

**Mục tiêu:** Đảm bảo module pgvector đang chạy là baseline "sạch" và thiết kế toàn bộ kiến trúc thực nghiệm trước khi code.

#### Dev 1 (Nguyễn Quỳnh Gia) — Kiến trúc & Baseline

| Nhiệm vụ | Chi tiết | Output |
|---|---|---|
| **Review & Document Pipeline ETL** | Vẽ lại sơ đồ luồng dữ liệu hiện tại (PDF → Docling → Chunking → Embedding → pgvector) | Sơ đồ kiến trúc chuẩn cho báo cáo |
| **Thiết kế MongoDB Schema** | Xây dựng schema Document Model với 3 mức: `Document (Wiki)` → `Section` → `Chunk`. Mỗi Chunk chứa embedding vector inline | File `mongodb-schema-design.md` |
| **Thiết kế Benchmark Framework** | Xác định rõ: (1) Dataset dùng để test, (2) Query types, (3) Metrics đo lường | File `benchmark-design.md` |
| **Setup môi trường MongoDB** | Cài MongoDB Community 7.x local (không phụ thuộc Atlas cloud) | MongoDB chạy được local |

#### Dev 2 — Tài liệu & SRS Update

| Nhiệm vụ | Chi tiết |
|---|---|
| **Update SRS v2.0** | Bổ sung Use Case mới: UC-MONGO-001 (Ingest MongoDB), UC-MONGO-002 (Query MongoDB), UC-GRAPH-001 (GraphRAG Query) |
| **Nghiên cứu tài liệu MongoDB** | Đọc kỹ 6 tài liệu trong `HuongDi_KLTN.md`, ghi chú key concepts |
| **Viết Background chương 2** | Viết phần lý thuyết về Document Model vs Relational Model, Vector Search trên NoSQL |

#### 🔴 Checkpoint 1 — Cuối tuần 2
> **Câu hỏi Mentor sẽ hỏi:**
> 1. Schema MongoDB của em có thể xử lý nested permission (workspace → wiki → chunk level) không?
> 2. Em định đo benchmark bằng những metric nào? Tại sao những metric đó phản ánh đúng use case?
> 3. Dataset test có bao nhiêu documents? Có đủ quy mô để kết quả có ý nghĩa không?

---

### 🍃 GIAI ĐOẠN 2: XÂY DỰNG MODULE MONGODB VECTOR SEARCH (Tuần 3–5)

**Mục tiêu:** Xây dựng đường ống Ingest → Query song song với pgvector, đảm bảo cả 2 nhận cùng input data.

#### Kiến trúc module MongoDB trong Spring Boot

```
DocumentService
    ├── IngestPipeline (hiện có - pgvector)
    │       └── PgVectorStore.add(chunks)
    └── [MỚI] MongoIngestPipeline
            ├── MongoDocumentRepository.save(document + embedded chunks)
            └── MongoVectorIndex.createIndex()

QueryOrchestrator
    ├── PgVectorRetriever.query(question, rbacFilter)   ← Baseline
    └── [MỚI] MongoVectorRetriever.query(question, rbacFilter)   ← Challenger
```

#### Dev 1 — Triển khai Core

| Tuần | Nhiệm vụ | Chi tiết kỹ thuật |
|---|---|---|
| Tuần 3 | **MongoDB Repository Layer** | Spring Data MongoDB + MongoTemplate. Tạo `MongoDocument`, `MongoChunk` entity với `@Document`. Thiết kế index `workspaceId_1_permissions_1` |
| Tuần 3 | **Ingest Pipeline MongoDB** | Service nhận cùng chunk list từ Docling → serialize thành Document Model → lưu vào MongoDB kèm embedding vector |
| Tuần 4 | **MongoDB Vector Search** | Cấu hình `$vectorSearch` aggregation pipeline. Pre-filter theo `workspaceId` và `allowedRoles` (RBAC tại query-time) |
| Tuần 4 | **RBAC Filter trên MongoDB** | Thay vì SQL `WHERE workspace_id = ? AND role IN (?)`, dùng MongoDB `$match: { workspaceId: X, allowedRoles: { $in: roles } }` |
| Tuần 5 | **A/B Query Interface** | Tạo `QueryStrategyEnum { PGVECTOR, MONGODB }`. QueryOrchestrator nhận flag → route đến đúng retriever |
| Tuần 5 | **Integration Test** | Đảm bảo cùng 1 câu hỏi → 2 retriever → so sánh kết quả docs trả về |

#### Dev 2 — UI & Monitoring

| Nhiệm vụ | Chi tiết |
|---|---|
| **Frontend: Strategy Selector** | Thêm toggle "Nguồn dữ liệu: PostgreSQL / MongoDB" trong Chat AI UI |
| **Logging Pipeline** | Log đầy đủ: latency (ms), số documents retrieved, similarity score cho mỗi query |
| **Viết báo cáo chương 3.1** | Thiết kế hệ thống MongoDB - schema, indexing strategy, RBAC filter approach |

#### 🔴 Checkpoint 2 — Cuối tuần 5
> **Demo cần đạt được:** Gửi 1 câu hỏi → query cả pgvector lẫn MongoDB → hiển thị 2 kết quả song song. RBAC filter hoạt động đúng.
>
> **Câu hỏi Mentor sẽ hỏi:**
> 1. Tại sao chọn Embedding document strategy (không phải Referencing)?
> 2. Index nào đặt trên MongoDB để tối ưu vector search + metadata filter?
> 3. Kết quả top-K documents của 2 hệ thống có khác nhau không? Tại sao?

---

### 🕸️ GIAI ĐOẠN 3: XÂY DỰNG GRAPHRAG VỚI $graphLookup (Tuần 6–8)

**Mục tiêu:** Điểm nhấn học thuật cao nhất của đề tài. GraphRAG cho phép LLM nhận **ngữ cảnh có cấu trúc đồ thị** thay vì "chunks rời".

#### Kiến trúc GraphRAG

```
[Ingest Phase - Graph Builder]
Document Text
    → Entity Extraction (LLM call: "Extract entities + relationships")
    → Graph Store: { entity: "Spring Boot", relatesTo: "Java", type: "uses" }
    → MongoDB Collection: "knowledge_graph"
           { _id, entity, entityType, relationships: [{ target, type, weight }] }

[Query Phase - GraphRAG Retriever]
User Question
    → Vector Search: tìm top-3 entity nodes liên quan nhất
    → $graphLookup: traverse đồ thị từ top entities (maxDepth: 2)
    → Collect subgraph as context
    → LLM: "Dựa vào đồ thị kiến thức: [subgraph], trả lời: [question]"
```

#### Dev 1 — GraphRAG Core

| Tuần | Nhiệm vụ | Chi tiết kỹ thuật |
|---|---|---|
| Tuần 6 | **Knowledge Graph Schema** | Collection `knowledge_graph`: `{ entityId, name, type, embedding, workspace, relationships: [{ targetId, relationType, weight }] }` |
| Tuần 6 | **Entity Extractor** | Spring AI call LLM với prompt "Extract named entities and relationships from: [chunk text]". Parse JSON → lưu graph nodes |
| Tuần 7 | **Graph Traversal** | `$graphLookup` aggregation: từ seed entities tìm bằng vector search → traverse relationships đến depth=2, filter theo workspace |
| Tuần 7 | **GraphRAG Context Builder** | Serialize subgraph → format thành "triplets" ngôn ngữ tự nhiên → ghép vào prompt cho LLM |
| Tuần 8 | **Hybrid Search** | Kết hợp: Vector Search (semantic similarity) + $graphLookup (graph context) → merge context → LLM generation |
| Tuần 8 | **RBAC trên Graph** | Permission propagation: nếu user không có quyền xem node cha, không traverse đến node con |

#### Dev 2 — Phần mềm & Báo cáo

| Nhiệm vụ | Chi tiết |
|---|---|
| **Graph Visualization (optional)** | UI hiển thị subgraph context được sử dụng (dùng d3-force đã có trong FE) |
| **Viết báo cáo chương 3.2** | Thiết kế GraphRAG — schema đồ thị, thuật toán traversal, RBAC propagation |
| **Chuẩn bị test cases** | 20 câu hỏi test: 10 câu "fact lookup" + 10 câu "relationship reasoning" |

#### 🔴 Checkpoint 3 — Cuối tuần 8
> **Câu hỏi Mentor sẽ hỏi:**
> 1. Tại sao GraphRAG trả lời câu hỏi về *mối quan hệ* tốt hơn plain RAG?
> 2. Chi phí latency của $graphLookup là bao nhiêu ms so với plain vector search?
> 3. Khi graph lớn, em sẽ tối ưu như thế nào? (Gợi ý: maxDepth, restrictSearchWithMatch)

---

### 📊 GIAI ĐOẠN 4: BENCHMARK + TRADE-OFF ANALYSIS + BÁO CÁO (Tuần 9–11)

**Mục tiêu:** Phần quyết định chất lượng học thuật. Phải có số liệu thực nghiệm cụ thể.

#### Metrics cần đo

| Metric | Đo bằng cách nào | Ý nghĩa |
|---|---|---|
| **Latency (ms)** | System.currentTimeMillis() trước/sau query | Tốc độ phản hồi |
| **Precision@K** | Reviewer đánh giá top-5 docs | Độ chính xác |
| **Recall@K** | Với 20 câu hỏi đã biết đáp án, bao nhiêu docs được tìm thấy | Độ bao phủ |
| **MRR** | Rank của document đúng đầu tiên | Chất lượng ranking |
| **LLM Answer Quality** | LLM-as-Judge (GPT-4 chấm điểm 1-5) | Chất lượng câu trả lời cuối |
| **RBAC Correctness** | 100% test cases isolation | Tính đúng đắn bảo mật |

**Query Types phải cover:**
```
Type A - Factual Lookup: "Cổng nào API Gateway chạy?"          → Ưu thế: Vector Search
Type B - Conceptual:     "Giải thích cơ chế RBAC-RAG?"         → Ưu thế: Hybrid  
Type C - Relational:     "Service nào phụ thuộc vào identity?"  → Ưu thế: GraphRAG
Type D - Cross-Domain:   "So sánh cơ chế auth của 2 service?"   → Ưu thế: GraphRAG
```

#### Bảng Trade-off Analysis (điền kết quả thực nghiệm)

| Tiêu chí | pgvector (SQL) | MongoDB Vector | MongoDB GraphRAG |
|---|---|---|---|
| Latency P50 (ms) | ___ | ___ | ___ |
| Latency P99 (ms) | ___ | ___ | ___ |
| Precision@5 | ___ | ___ | ___ |
| LLM Answer Score (1-5) | ___ | ___ | ___ |
| Schema Flexibility | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| ACID / Consistency | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| Relational Query | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Best for** | Structured data, Compliance | Flexible docs, Multi-tenant | Complex knowledge graphs |

#### 🔴 Final Review — Câu hỏi Hội đồng (luyện trước)
> 1. **"Đóng góp mới của em là gì?"**  
>    → Trade-off Analysis thực nghiệm về RBAC-RAG trên 2 paradigm (Relational vs Document) trong ngữ cảnh quản lý tri thức doanh nghiệp phân quyền đa cấp.
> 2. **"Tại sao chọn MongoDB thay vì Elasticsearch hay Qdrant?"**  
>    → MongoDB cho phép kết hợp Document Store + Vector Search + Graph Traversal trên cùng một engine, phù hợp cấu trúc phân cấp Document → Wiki → Chunk.
> 3. **"MongoDB chậm hơn pgvector, vậy kết luận là gì?"**  
>    → Không phải "cái nào tốt hơn" mà là "mỗi approach phù hợp với use case nào". pgvector tốt cho factual lookup; MongoDB tốt cho relational reasoning và flexible schema.

---

## 🧠 4. MENTOR NOTES — NGUYÊN TẮC LUÔN GHI NHỚ

**Nguyên tắc #1: "Tại sao" quan trọng hơn "Cái gì"**  
Mọi quyết định kỹ thuật phải trả lời được: *"Tại sao em không chọn cách khác?"*

**Nguyên tắc #2: Code chỉ là phương tiện, kiến trúc mới là đóng góp**  
Hội đồng không chấm "em code được bao nhiêu". Họ chấm "em giải quyết bài toán gì theo tư duy kiến trúc sư".

**Nguyên tắc #3: Số liệu không nói dối**  
Tuyệt đối không "ước chừng". Mọi claim phải có bảng số liệu, biểu đồ, điều kiện thực nghiệm rõ ràng.

**Nguyên tắc #4: Giới hạn là một phần của nghiên cứu**  
Thừa nhận limitations chủ động cho thấy sự trưởng thành học thuật.

---

## ⚠️ 5. RỦI RO & PHÒNG NGỪA

| Rủi ro | Xác suất | Phòng ngừa |
|---|---|---|
| MongoDB Atlas free tier bị giới hạn | Cao | Setup local MongoDB Community 7.x ngay từ tuần 1 |
| Entity Extraction LLM không chính xác | Trung bình | Thử prompt manual trước; fallback: extract "service", "technology", "depends_on" |
| Latency GraphRAG quá cao | Trung bình | Cache kết quả $graphLookup, pre-compute graph cho top entities |
| Thiếu thời gian viết báo cáo | Cao | Mỗi giai đoạn phải có output báo cáo, không dồn cuối |

---

## 📅 6. LỊCH TRÌNH TỔNG HỢP

| Tuần | Dev 1 (Quỳnh Gia) | Dev 2 | Checkpoint |
|---|---|---|---|
| 1 | Thiết kế MongoDB Schema | Nghiên cứu tài liệu MongoDB | - |
| 2 | Benchmark Framework + Setup MongoDB | Update SRS v2.0 | **✅ Checkpoint 1** |
| 3 | MongoDB Repository + Ingest Pipeline | Frontend toggle A/B | - |
| 4 | MongoDB Vector Search + RBAC Filter | Logging pipeline | - |
| 5 | A/B Query Interface + Integration Test | Viết chương 3.1 | **✅ Checkpoint 2** |
| 6 | Knowledge Graph Schema + Entity Extractor | Chuẩn bị test cases | - |
| 7 | $graphLookup Traversal + Context Builder | Viết chương 3.2 | - |
| 8 | Hybrid Search + RBAC on Graph | Graph Visualization | **✅ Checkpoint 3** |
| 9 | BenchmarkRunner + RBACTestSuite | Viết chương 1 & 2 | - |
| 10 | Phân tích kết quả + Biểu đồ + Chương 4 | Review toàn báo cáo | - |
| 11 | Chương 5 + Final polish | Chuẩn bị slide demo | **✅ Final Review** |

---

## 🎯 7. ĐỊNH NGHĨA "DONE" CHO TỪNG GIAI ĐOẠN

| Giai đoạn | Done khi... |
|---|---|
| GĐ 1 | Schema MongoDB được approve. Benchmark framework có metrics cụ thể. MongoDB chạy local. |
| GĐ 2 | Cùng 1 document ingest được vào cả 2 engine. RBAC filter hoạt động đúng. A/B query chạy được. |
| GĐ 3 | Entity extraction chạy được. $graphLookup traverse được graph. GraphRAG trả về context khác biệt rõ ràng cho query Type C/D. |
| GĐ 4 | Có bảng benchmark 3 engines × 4 query types. Có biểu đồ. Báo cáo hoàn chỉnh. Demo chạy được. |

---

---

## 🔬 8. FRAMEWORK BENCHMARK KIỂM TRA TÍNH ĐÚNG ĐẮN CỦA AI (AI CORRECTNESS & EVALUATION FRAMEWORK)

Để đánh giá tính **Đúng Đắn (Correctness)**, **Bảo Mật (Security/RBAC)** và **Độ Tin Cậy (Reliability)** của mô hình AI giữa 3 phương pháp (pgvector, MongoDB Vector Search, MongoDB GraphRAG), hệ thống áp dụng bộ tiêu chí đánh giá chuẩn quốc tế (Ragas Framework & LLM-as-a-Judge):

### 📐 1. Bộ Chỉ Số Đánh Giá (Evaluation Metrics)

| Tầng Đánh Giá | Chỉ Số (Metric) | Công Thức / Cơ Chế Đánh Giá | Mục Tiêu Kỹ Thuật |
|---|---|---|---|
| **Retrieval (Tầng Truy Xuất)** | **Context Precision** | $\frac{\| \text{Chunks Tương Quan} \cap \text{Chunks Trả Về} \|}{\| \text{Chunks Trả Về} \|}$ | Đo độ nhiễu. Tránh đưa đoạn văn bản thừa vào Prompt làm tăng token cost. |
| | **Context Recall** | $\frac{\| \text{Chunks Tương Quan} \cap \text{Ground Truth Chunks} \|}{\| \text{Ground Truth Chunks} \|}$ | Đo độ phủ. Đảm bảo tìm đủ tài liệu cần thiết để trả lời. |
| | **RBAC Isolation Rate** | $\frac{\text{Số câu test không rò rỉ dữ liệu ngoài quyền}}{\text{Tổng số câu test RBAC}} \times 100\%$ | **Bắt buộc 100%**. Người dùng không xem được tri thức ngoài thẩm quyền. |
| **Generation (Tầng Sinh Câu Trả Lời)** | **Faithfulness (Độ Trung Thực)** | $\frac{\text{Số phát biểu có minh chứng trong Context}}{\text{Tổng số phát biểu trong AI Answer}}$ | Đo ảo giác (Hallucination). Tránh việc AI tự bịa ra thông tin ngoài tài liệu. |
| | **Answer Relevancy** | $Similarity(\text{Embedding(AI Answer)}, \text{Embedding(Question)})$ | Đo độ đi thẳng vào trọng tâm câu hỏi. |
| **Performance (Hiệu Năng)** | **P99 Latency (ms)** | Thời gian phản hồi ở bách phân vị 99 | Đánh giá tốc độ truy vấn ở kịch bản xấu nhất. |

---

### 🧪 2. Bộ Dữ Liệu Thực Nghiệm Mẫu (Ground-Truth Benchmark Dataset)

Dưới đây là bộ dữ liệu định dạng chuẩn JSON (`benchmark_dataset.json`) dùng để cho nạp tự động vào `BenchmarkRunner.java` nhằm kiểm thử tự động 3 phương pháp:

```json
[
  {
    "test_id": "TC-001",
    "category": "Factual Lookup (Type A)",
    "question": "Cổng API Gateway chạy ở port mặc định nào và kết nối đến các service nào?",
    "user_context": {
      "workspace_id": "ws-engineering",
      "user_roles": ["MEMBER", "DEV"]
    },
    "expected_relevant_doc_ids": ["DOC-GATEWAY-01"],
    "ground_truth_answer": "API Gateway chạy mặc định ở port 3000, kết nối đến identity-service (3010), messaging-service (3020), file-service (3014), notification-service (3019).",
    "evaluation_criteria": {
      "must_contain": ["3000", "3010", "3020"],
      "rbac_strict": true
    }
  },
  {
    "test_id": "TC-002",
    "category": "Security & RBAC Isolation (Type S)",
    "question": "Cho tôi xem báo cáo doanh thu tài chính quý 2 của tổ chức?",
    "user_context": {
      "workspace_id": "ws-engineering",
      "user_roles": ["DEV"]
    },
    "expected_relevant_doc_ids": [],
    "ground_truth_answer": "Bạn không có quyền truy cập thông tin tài chính của tổ chức.",
    "evaluation_criteria": {
      "must_contain": ["không có quyền", "bảo mật"],
      "rbac_strict": true,
      "expected_forbidden_leak": false
    }
  },
  {
    "test_id": "TC-003",
    "category": "Relational Reasoning & Graph (Type C)",
    "question": "Nếu identity-service bị sự cố thì các service nào trong hệ thống sẽ bị ảnh hưởng trực tiếp?",
    "user_context": {
      "workspace_id": "ws-engineering",
      "user_roles": ["ADMIN"]
    },
    "expected_relevant_doc_ids": ["DOC-ARCH-01", "DOC-MESSAGING-01"],
    "ground_truth_answer": "messaging-service và api-gateway sẽ bị ảnh hưởng trực tiếp do phụ thuộc vào gRPC verify token của identity-service (port 50051).",
    "evaluation_criteria": {
      "must_contain": ["messaging-service", "api-gateway", "gRPC"],
      "ideal_engine": "MongoDB GraphRAG"
    }
  },
  {
    "test_id": "TC-004",
    "category": "Cross-Domain Reasoning (Type D)",
    "question": "So sánh cơ chế xác thực giữa HTTP REST API và WebSocket Gateway?",
    "user_context": {
      "workspace_id": "ws-engineering",
      "user_roles": ["DEV"]
    },
    "expected_relevant_doc_ids": ["DOC-GATEWAY-01", "DOC-WS-01"],
    "ground_truth_answer": "HTTP REST API xác thực qua Authorization Bearer Header tại api-gateway (port 3000), còn WebSocket Gateway xác thực token khi kết nối handshaking (port 3001).",
    "evaluation_criteria": {
      "must_contain": ["Bearer Header", "handshaking"],
      "ideal_engine": "Hybrid / GraphRAG"
    }
  }
]
```

---

---

## 📖 9. TÀI LIỆU THAM KHẢO BỔ SUNG (CURATED BY SENIOR MENTOR)

> Đây là bộ tài liệu được tuyển chọn kỹ lưỡng theo 5 nhóm chuyên đề, phục vụ trực tiếp cho từng giai đoạn phát triển của đề tài. Mỗi tài liệu được gắn nhãn **[ĐỌC NGAY]**, **[ĐỌC TUẦN 3+]**, hoặc **[ĐỌC CUỐI]** để giúp nhóm ưu tiên đúng thứ tự.

---

### 📚 NHÓM 1 — NỀN TẢNG RAG (Đọc trước khi bắt đầu)

| Mức Độ Ưu Tiên | Tên Tài Liệu | Link | Ghi Chú Cho Nhóm |
|---|---|---|---|
| 🔴 **[ĐỌC NGAY]** | **Lewis et al. (2020) — RAG: Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks** | [arxiv.org/abs/2005.11401](https://arxiv.org/abs/2005.11401) | **Paper gốc tạo ra khái niệm RAG** — bắt buộc phải đọc và trích dẫn trong chương 2. Đây là nền tảng học thuật toàn bộ đề tài của nhóm dựa vào. |
| 🟡 **[ĐỌC NGAY]** | **Gao et al. (2024) — Retrieval-Augmented Generation for Large Language Models: A Survey** | [arxiv.org/abs/2312.10997](https://arxiv.org/abs/2312.10997) | **Survey toàn cảnh** về RAG từ naive → advanced → modular. Đọc để biết đề tài của mình đang ở đâu trong bức tranh nghiên cứu thế giới. Có phần so sánh chunking strategies cực kỳ hữu ích. |
| 🟡 **[ĐỌC NGAY]** | **Yao et al. (2023) — ReAct: Synergizing Reasoning and Acting in Language Models (ICLR 2023)** | [arxiv.org/abs/2210.03629](https://arxiv.org/abs/2210.03629) | **Paper gốc về AI Agent ReAct Loop** (Thought → Action → Observation). Hệ thống của nhóm đã dùng pattern này, phải hiểu lý thuyết gốc để diễn giải trong báo cáo. |

---

### 📚 NHÓM 2 — GRAPHRAG & KNOWLEDGE GRAPH (Đọc Giai Đoạn 3)

| Mức Độ Ưu Tiên | Tên Tài Liệu | Link | Ghi Chú Cho Nhóm |
|---|---|---|---|
| 🔴 **[ĐỌC TUẦN 3+]** | **Edge et al. (2024) — From Local to Global: A Graph RAG Approach to Query-Focused Summarization (Microsoft Research)** | [arxiv.org/abs/2404.16130](https://arxiv.org/abs/2404.16130) | **Paper GraphRAG chính thức của Microsoft (2024)** — mô tả chi tiết cách xây dựng Knowledge Graph từ documents và dùng community summarization để trả lời câu hỏi cấp độ global. Đây là kim chỉ nam cho Giai Đoạn 3. |
| 🟡 **[ĐỌC TUẦN 3+]** | **MongoDB Official — GraphRAG with MongoDB and LangChain** | [mongodb.com/docs](https://www.mongodb.com/developer/products/atlas/graphrag-mongodb-langchain) | Tutorial chính thức dùng MongoDBGraphStore + $graphLookup làm GraphRAG — thực chiến trực tiếp cho code của nhóm. |
| 🟡 **[ĐỌC TUẦN 3+]** | **$graphLookup Aggregation Stage (MongoDB Official Docs)** | [mongodb.com/docs/manual/reference/operator/aggregation/graphLookup](https://www.mongodb.com/docs/manual/reference/operator/aggregation/graphLookup/) | Tài liệu kỹ thuật chính xác về cú pháp $graphLookup, maxDepth, restrictSearchWithMatch. Cần đọc trước khi code Giai Đoạn 3. |

---

### 📚 NHÓM 3 — BENCHMARK, ĐÁNH GIÁ & ĐỘ ĐÚNG ĐẮN CỦA AI

| Mức Độ Ưu Tiên | Tên Tài Liệu | Link | Ghi Chú Cho Nhóm |
|---|---|---|---|
| 🔴 **[ĐỌC NGAY]** | **Es et al. (2023) — RAGAS: Automated Evaluation of Retrieval Augmented Generation** | [arxiv.org/abs/2309.15217](https://arxiv.org/abs/2309.15217) | **Framework chuẩn quốc tế để đánh giá hệ thống RAG** — định nghĩa chính xác Context Precision, Context Recall, Faithfulness, Answer Relevancy. Đây là framework benchmark nhóm phải triển khai trong Giai Đoạn 4. |
| 🟠 **[ĐỌC TUẦN 3+]** | **RAGAS Documentation (Python Library)** | [docs.ragas.io](https://docs.ragas.io/) | Library Python tự động tính toán các metrics RAGAS. Nhóm có thể dùng để đánh giá nhanh trước khi viết BenchmarkRunner.java tự xây. |
| 🟡 **[ĐỌC CUỐI]** | **LangChain Docs — LLM-as-a-Judge (Evaluation)** | [python.langchain.com/docs/guides/evaluation](https://python.langchain.com/docs/guides/evaluation) | Hướng dẫn dùng một LLM (GPT-4 hoặc Gemini) để tự động chấm điểm chất lượng câu trả lời từ 1-5. Thay thế khi không có đủ nhân lực chấm tay. |

---

### 📚 NHÓM 4 — BẢO MẬT RAG & RBAC (Điểm Nhấn Kỹ Thuật Của Đề Tài)

| Mức Độ Ưu Tiên | Tên Tài Liệu | Link | Ghi Chú Cho Nhóm |
|---|---|---|---|
| 🔴 **[ĐỌC NGAY]** | **MongoDB Official — Multi-Tenant Architecture for Vector Search** | [mongodb.com/docs](https://www.mongodb.com/docs/atlas/atlas-vector-search/tutorials/multi-tenant-vector-search/) | **Kiến trúc pre-filter theo tenant_id cho multi-tenant** — trực tiếp áp dụng cho RBAC filter trong đề tài của nhóm. Giải thích vì sao pre-filter lại bảo mật hơn post-filter. |
| 🟡 **[ĐỌC NGAY]** | **OWASP LLM Top 10 (2025) — LLM08: Vector and Embedding Weaknesses** | [owasp.org/www-project-top-10-for-large-language-model-applications](https://owasp.org/www-project-top-10-for-large-language-model-applications/) | **Danh sách lỗ hổng bảo mật AI tiêu chuẩn của OWASP** — đặc biệt LLM08 về Vector DB Weakness và LLM01 về Prompt Injection. Nhóm cần đề cập đến trong phần Threat Modeling của báo cáo. |
| 🟠 **[ĐỌC TUẦN 3+]** | **"Secure Multi-Tenant RAG Architecture" — Defense-in-Depth Approach (Medium / InfoQ)** | [brudite.com/secure-rag-rbac](https://brudite.com) | Phân tích kiến trúc bảo mật đa lớp (Authorization tại Gateway → Filter tại Vector DB → Validation tại LLM response). Dùng để xây dựng phần "Defense in Depth" trong chương kiến trúc hệ thống. |

---

### 📚 NHÓM 5 — MONGODB VECTOR SEARCH & SPRING AI (Công Nghệ Triển Khai)

| Mức Độ Ưu Tiên | Tên Tài Liệu | Link | Ghi Chú Cho Nhóm |
|---|---|---|---|
| 🔴 **[ĐỌC NGAY]** | **MongoDB Official — Vector Search Overview ($vectorSearch Aggregation)** | [mongodb.com/docs/atlas/atlas-vector-search/vector-search-overview](https://www.mongodb.com/docs/atlas/atlas-vector-search/vector-search-overview/) | Tổng quan kỹ thuật $vectorSearch, ANN/ENN, pre-filter metadata, numCandidates. Nền tảng kỹ thuật bắt buộc cho Giai Đoạn 2. |
| 🟡 **[ĐỌC NGAY]** | **MongoDB Official — Build a Local RAG Implementation (No API Key)** | [mongodb.com/developer/products/atlas/build-local-rag-implementation](https://www.mongodb.com/developer/products/atlas/build-local-rag-implementation/) | Tutorial chạy RAG local với MongoDB Community (không cần Atlas, không cần API key) — phù hợp môi trường phát triển của nhóm. |
| 🟠 **[ĐỌC TUẦN 3+]** | **Spring AI Documentation — MongoDB Atlas Vector Store** | [docs.spring.io/spring-ai/reference/api/vectordbs/mongodb-atlas.html](https://docs.spring.io/spring-ai/reference/api/vectordbs/mongodb-atlas.html) | API chính thức của Spring AI để tích hợp MongoDB Vector Store vào Spring Boot. Code mẫu trực tiếp cho Dev 1. |
| 🟠 **[ĐỌC TUẦN 3+]** | **InfoQ — Building a RAG Application with Spring Boot, Spring AI, and MongoDB Atlas Vector Search** | [infoq.com](https://www.infoq.com/articles/spring-ai-mongodb-rag/) | Hướng dẫn đầy đủ end-to-end Spring Boot + Spring AI + MongoDB Vector Search — bao gồm cả phần embedding và retrieval. |
| 🟡 **[ĐỌC TUẦN 3+]** | **MongoDB — Vector Search over Nested Embeddings (Public Preview 2026)** | [mongodb.com/docs/atlas/atlas-vector-search/tutorials/nested-embeddings](https://www.mongodb.com/docs/atlas/atlas-vector-search/tutorials) | Tìm kiếm vector trong nested array/subdocument — rất phù hợp với cấu trúc `Document → chunks[]` của hệ thống. |

---

### 🔑 CHEAT SHEET — KEYWORDS ĐỂ GOOGLE KHI CẦN

> Copy các từ khóa dưới đây vào Google Scholar hoặc arxiv.org để tìm thêm tài liệu liên quan:

```
"RAG security" "access control" "multi-tenant" site:arxiv.org
"knowledge graph RAG" "entity extraction" "graph traversal" site:arxiv.org
"vector database benchmark" "pgvector" "MongoDB" comparison performance
"RBAC" "permission filtering" "vector search" enterprise knowledge management
"document hierarchical chunking" "parent-child" "RAG pipeline" 2024
"hallucination detection" "faithfulness" "retrieval augmented generation" evaluation
```

---

*Cập nhật lần cuối: 04/08/2026 | Version: 1.2 (Bổ sung Curated References by Senior Mentor)*  
*Senior Mentor Review: Passed ✅*


