# ⚙️ GIAI ĐOẠN 1 — CỦNG CỐ BASELINE & THIẾT KẾ THỰC NGHIỆM
## Thời gian: Tuần 1 (7 ngày) | Mục tiêu: Baseline sạch + MongoDB setup + Benchmark Framework

> **AI Tools hỗ trợ giai đoạn này:** GitHub Copilot, Claude (thiết kế schema), ChatGPT (viết SRS), Cursor IDE  
> **Output bắt buộc cuối tuần:** `mongodb-schema-design.md` + `benchmark-design.md` + MongoDB chạy local

---

## 📅 LỊCH THEO TỪNG NGÀY

### 🗓️ Ngày 1 (Thứ 2) — Review & Document Pipeline ETL Hiện Tại

**Dev 1 (Quỳnh Gia):**
- [ ] Vẽ lại sơ đồ luồng dữ liệu **HIỆN TẠI** từ đầu đến cuối:
  ```
  PDF/DOCX Upload → Docling Parser → Chunking Strategy → 
  multilingual-e5-base Embedding → pgvector Store → 
  RBAC Filter (workspace + roles) → LLM Generate Answer
  ```
- [ ] Ghi lại các thông số kỹ thuật thực tế:
  - Chunk size hiện tại là bao nhiêu token?
  - Overlap size?
  - Dimension của vector embedding (768?)
  - Index type trên pgvector (IVFFlat hay HNSW?)
- [ ] **Dùng AI:** Paste code `PgVectorIngestPipeline.java` vào Claude → hỏi *"Phân tích chunking strategy đang dùng và đề xuất cải thiện"*

**Dev 2:**
- [ ] Đọc paper: **Lewis et al. (2020) RAG** → [arxiv.org/abs/2005.11401](https://arxiv.org/abs/2005.11401)
  - Ghi chú 5 điểm chính ra file `notes/rag-paper-notes.md`
- [ ] Đọc abstract + introduction: **Gao et al. (2024) RAG Survey** → [arxiv.org/abs/2312.10997](https://arxiv.org/abs/2312.10997)

**Output Ngày 1:** File `docs/architecture/current-etl-pipeline.md` có sơ đồ + thông số kỹ thuật

---

### 🗓️ Ngày 2 (Thứ 3) — Thiết Kế MongoDB Schema

**Dev 1 (Quỳnh Gia):**
- [ ] Thiết kế **Document Model** cho MongoDB theo cấu trúc phân cấp 3 mức:

```json
// Collection: wiki_documents
{
  "_id": ObjectId,
  "wikiId": "wiki-001",
  "title": "Tài liệu kiến trúc hệ thống KTMP Nexus",
  "workspaceId": "ws-engineering",
  "allowedRoles": ["ADMIN", "DEV", "MEMBER"],
  "createdBy": "user-123",
  "createdAt": ISODate,
  "metadata": {
    "fileType": "PDF",
    "pageCount": 15,
    "language": "vi"
  },
  "sections": [
    {
      "sectionId": "sec-001",
      "title": "1. Giới thiệu kiến trúc",
      "chunks": [
        {
          "chunkId": "chunk-001",
          "text": "Hệ thống KTMP Nexus được xây dựng theo kiến trúc Microservices...",
          "embedding": [0.123, -0.456, ...],   // 768 chiều
          "chunkIndex": 0,
          "tokenCount": 256,
          "allowedRoles": ["ADMIN", "DEV", "MEMBER"]  // RBAC ở chunk level
        }
      ]
    }
  ]
}

// Collection: knowledge_graph (Giai Đoạn 3)
{
  "_id": ObjectId,
  "entityId": "entity-001",
  "name": "identity-service",
  "entityType": "SERVICE",
  "workspaceId": "ws-engineering",
  "embedding": [...],   // embedding của entity name
  "relationships": [
    {
      "targetId": "entity-002",
      "targetName": "PostgreSQL",
      "relationType": "USES_DATABASE",
      "weight": 1.0
    }
  ]
}
```

- [ ] Viết file `docs/mongodb-schema-design.md` đầy đủ
- [ ] **Dùng AI:** Hỏi Claude: *"So sánh Embedding vs Referencing strategy cho cấu trúc Document → chunks trong MongoDB, use case phân quyền RBAC"*

**Dev 2:**
- [ ] Update `kltn-srs.md` thêm 3 Use Cases mới:
  - **UC-MONGO-001:** Ingest document vào MongoDB
  - **UC-MONGO-002:** Query RAG từ MongoDB Vector Search
  - **UC-GRAPH-001:** GraphRAG Query với $graphLookup

**Output Ngày 2:** File `docs/mongodb-schema-design.md` hoàn chỉnh

---

### 🗓️ Ngày 3 (Thứ 4) — Setup MongoDB Local + Docker

**Dev 1 (Quỳnh Gia):**
- [ ] Thêm MongoDB vào `docker-compose.yml` của `ai-knowledge`:

```yaml
# Thêm vào docker-compose.yml
mongodb:
  image: mongodb/mongodb-community-server:7.0-ubuntu2204
  container_name: ktmp-mongodb
  ports:
    - "27017:27017"
  environment:
    - MONGODB_INITDB_ROOT_USERNAME=admin
    - MONGODB_INITDB_ROOT_PASSWORD=password123
  volumes:
    - mongodb_data:/data/db
  healthcheck:
    test: echo 'db.runCommand("ping").ok' | mongosh localhost:27017/test --quiet
    interval: 10s
    timeout: 5s
    retries: 5

volumes:
  mongodb_data:
```

- [ ] Thêm dependency vào `pom.xml` của `ai-knowledge`:

```xml
<!-- MongoDB Spring Data -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>

<!-- Spring AI MongoDB Vector Store -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mongodb-atlas-store-spring-boot-autoconfigure</artifactId>
</dependency>
```

- [ ] Cấu hình `application.yml`:

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://admin:password123@localhost:27017/ktmp_knowledge?authSource=admin
      database: ktmp_knowledge
```

- [ ] Verify: `docker-compose up -d mongodb` → chạy được
- [ ] Test kết nối Spring Boot → MongoDB thành công

**Dev 2:**
- [ ] Đọc tài liệu: **MongoDB Multi-Tenant Vector Search** (từ HuongDi_KLTN.md)
- [ ] Viết lý thuyết chương 2: *Tổng quan về MongoDB Document Model và Vector Search*

**Output Ngày 3:** MongoDB chạy local, Spring Boot kết nối thành công

---

### 🗓️ Ngày 4 (Thứ 5) — Thiết Kế Benchmark Framework

**Dev 1 (Quỳnh Gia):**
- [ ] Viết file `docs/benchmark-design.md` với nội dung sau:

**Điều kiện thực nghiệm (phải ghi rõ vào báo cáo):**
- Hardware: CPU [của máy nhóm], RAM [dung lượng], SSD/HDD
- Dataset: [số lượng] documents, [số lượng] chunks, [tổng số] tokens
- Embedding model: `multilingual-e5-base` (768 dim) — cùng model cho cả 3 engine
- LLM: Gemini Flash / GPT-3.5 (chọn 1, nhất quán)
- Số lần lặp benchmark: 3 lần → lấy trung bình (tránh outlier)

**20 câu hỏi test phân loại:**

| ID | Loại | Câu hỏi | Engine Lý Tưởng |
|---|---|---|---|
| Q1 | Type A (Factual) | API Gateway chạy ở port nào? | pgvector / MongoDB |
| Q2 | Type A (Factual) | identity-service dùng database nào? | pgvector / MongoDB |
| Q3 | Type A (Factual) | NATS JetStream dùng để làm gì trong hệ thống? | pgvector / MongoDB |
| Q4 | Type A (Factual) | Embedding model nào được dùng? | pgvector / MongoDB |
| Q5 | Type A (Factual) | messaging-service lắng nghe ở port mấy? | pgvector / MongoDB |
| Q6 | Type B (Conceptual) | Giải thích cơ chế RBAC-RAG hoạt động? | Hybrid |
| Q7 | Type B (Conceptual) | Tại sao dùng pgvector thay vì Qdrant? | Hybrid |
| Q8 | Type B (Conceptual) | MRP Pipeline là gì và gồm mấy bước? | Hybrid |
| Q9 | Type B (Conceptual) | Sự khác biệt giữa IVFFlat và HNSW index? | Hybrid |
| Q10 | Type B (Conceptual) | Tại sao dùng NATS JetStream thay vì Kafka? | Hybrid |
| Q11 | Type C (Relational) | Nếu identity-service sập, service nào bị ảnh hưởng? | GraphRAG |
| Q12 | Type C (Relational) | messaging-service phụ thuộc vào những service nào? | GraphRAG |
| Q13 | Type C (Relational) | Luồng upload file đi qua những service nào? | GraphRAG |
| Q14 | Type C (Relational) | Tất cả service nào kết nối đến PostgreSQL? | GraphRAG |
| Q15 | Type C (Relational) | Notification được kích hoạt bởi event nào từ service nào? | GraphRAG |
| Q16 | Type D (Cross-Domain) | So sánh xác thực REST API vs WebSocket? | GraphRAG |
| Q17 | Type D (Cross-Domain) | Điểm khác nhau giữa gRPC và REST trong hệ thống? | GraphRAG |
| Q18 | Type D (Cross-Domain) | Chat realtime dùng công nghệ gì và hoạt động ra sao? | GraphRAG |
| Q19 | Type S (RBAC Security) | [User DEV hỏi] Cho xem tài liệu HR nội bộ? | Cả 3 phải từ chối |
| Q20 | Type S (RBAC Security) | [User GUEST hỏi] Kiến trúc bảo mật chi tiết là gì? | Cả 3 phải từ chối |

**Dev 2:**
- [ ] Chuẩn bị nội dung tài liệu để ingest:
  - File `benchmark-docs/doc-gateway.md` — mô tả API Gateway
  - File `benchmark-docs/doc-identity.md` — mô tả identity-service
  - File `benchmark-docs/doc-architecture.md` — sơ đồ kiến trúc tổng thể

**Output Ngày 4:** File `docs/benchmark-design.md` + 3 file tài liệu benchmark

---

### 🗓️ Ngày 5 (Thứ 6) — Verify Baseline pgvector + Tạo Entity Test

**Dev 1 (Quỳnh Gia):**
- [ ] Ingest bộ tài liệu benchmark vào pgvector (đây sẽ là baseline)
- [ ] Chạy thử 5 câu hỏi Type A → ghi lại latency và kết quả
- [ ] Viết class `BenchmarkLogger.java` để log tự động:

```java
public record BenchmarkResult(
    String testId,
    String engine,         // "PGVECTOR" | "MONGODB" | "GRAPHRAG"
    String question,
    long latencyMs,
    int retrievedDocsCount,
    double topScore,
    String aiAnswer,
    long timestamp
) {}
```

**Dev 2:**
- [ ] Hoàn thiện phần lý thuyết chương 2 (2-3 trang):
  - Document Model vs Relational Model
  - Vector Search: Dense vs Sparse vs Hybrid
  - Knowledge Graph và Graph Traversal cơ bản

**Output Ngày 5:** pgvector baseline hoạt động, có log kết quả thử nghiệm

---

### 🗓️ Ngày 6-7 (Cuối Tuần) — Buffer + Review + Checkpoint

- [ ] **Dev 1:** Hoàn thiện tất cả output còn thiếu từ ngày 1-5
- [ ] **Dev 2:** Review và chỉnh sửa nội dung lý thuyết
- [ ] **Cả nhóm:** Tự trả lời Checkpoint Questions bên dưới

---

## 🔴 CHECKPOINT 1 — CUỐI TUẦN 1

> **Nhóm phải tự trả lời được 3 câu hỏi này trước khi bước sang Giai Đoạn 2:**

**Câu 1:** Schema MongoDB của nhóm xử lý nested permission (workspace → wiki → chunk level) như thế nào?  
→ **Gợi ý trả lời:** `allowedRoles` field ở **cả 2 tầng**: document level (quyền đọc wiki) và chunk level (quyền đọc đoạn cụ thể). Query filter: `{ workspaceId: X, allowedRoles: { $in: userRoles } }`.

**Câu 2:** Benchmark framework đo những metrics nào và tại sao chọn chúng?  
→ **Gợi ý trả lời:** Context Precision (độ nhiễu), Context Recall (độ phủ), Faithfulness (ảo giác), Latency P99 (hiệu năng worst case), RBAC Isolation Rate (bảo mật).

**Câu 3:** Dataset benchmark có bao nhiêu documents? Có đủ để kết quả có ý nghĩa thống kê không?  
→ **Gợi ý trả lời:** Tối thiểu 10+ documents, 50+ chunks. Benchmark chạy 3 lần → lấy trung bình.

---

## ✅ DEFINITION OF DONE — GIAI ĐOẠN 1

- [x] `docs/architecture/current-etl-pipeline.md` — sơ đồ pipeline hiện tại
- [x] `docs/mongodb-schema-design.md` — schema MongoDB đầy đủ 2 collections
- [x] `docs/benchmark-design.md` — 20 câu hỏi + điều kiện thực nghiệm
- [x] `benchmark-docs/` — ít nhất 3 tài liệu kỹ thuật đã ingest vào pgvector
- [x] MongoDB Community 7.x chạy được local qua Docker
- [x] Spring Boot kết nối MongoDB thành công (test ping)
- [x] `BenchmarkLogger.java` — class ghi log kết quả benchmark

---

*Giai Đoạn 1 | Version: 1.0 | Tuần 1 (7 ngày)*
