# 🍃 GIAI ĐOẠN 2 — XÂY DỰNG MODULE MONGODB VECTOR SEARCH
## Thời gian: Tuần 2 (7 ngày) | Mục tiêu: MongoDB Ingest + RBAC Filter + A/B Query

> **Điều kiện tiên quyết:** ✅ Giai Đoạn 1 Done — MongoDB chạy local, schema đã thiết kế  
> **AI Tools hỗ trợ:** GitHub Copilot (viết code), Claude (review logic), Cursor IDE  
> **Output bắt buộc cuối tuần:** MongoDB nhận ingest được document + RBAC filter hoạt động + A/B query so sánh 2 engine

---

## 📅 LỊCH THEO TỪNG NGÀY

### 🗓️ Ngày 1 (Thứ 2) — MongoDB Entity Layer (Document + Repository)

**Dev 1 (Quỳnh Gia) — Code:**

Tạo các entity và repository cho MongoDB:

```java
// src/main/java/com/security/security/mongo/entity/MongoWikiDocument.java
@Document(collection = "wiki_documents")
@Data
@Builder
public class MongoWikiDocument {

    @Id
    private String id;
    private String wikiId;
    private String title;
    private String workspaceId;
    private List<String> allowedRoles;
    private String createdBy;
    private LocalDateTime createdAt;
    private DocumentMetadata metadata;
    private List<MongoSection> sections;

    @Data
    @Builder
    public static class DocumentMetadata {
        private String fileType;
        private Integer pageCount;
        private String language;
    }

    @Data
    @Builder
    public static class MongoSection {
        private String sectionId;
        private String title;
        private List<MongoChunk> chunks;
    }

    @Data
    @Builder
    public static class MongoChunk {
        private String chunkId;
        private String text;
        private float[] embedding;      // 768 chiều - multilingual-e5-base
        private Integer chunkIndex;
        private Integer tokenCount;
        private List<String> allowedRoles;  // RBAC at chunk level
        private Map<String, Object> metadata;
    }
}
```

```java
// src/main/java/com/security/security/mongo/repository/MongoWikiRepository.java
@Repository
public interface MongoWikiRepository extends MongoRepository<MongoWikiDocument, String> {

    List<MongoWikiDocument> findByWorkspaceIdAndAllowedRolesIn(
        String workspaceId, 
        List<String> roles
    );

    Optional<MongoWikiDocument> findByWikiId(String wikiId);
}
```

- [ ] Tạo 2 file entity và repository
- [ ] Verify: `mvn compile` không lỗi
- [ ] **Dùng AI:** Paste entity vào Copilot → auto-generate thêm các method query cần thiết

**Dev 2:**
- [ ] Đọc: **Spring AI MongoDB Atlas Vector Store docs** → ghi chú API cần dùng
- [ ] Đọc tiếp: **"Build a Local RAG Implementation" (MongoDB)** — tutorial không cần API key

**Output Ngày 1:** Entity + Repository layer compile thành công

---

### 🗓️ Ngày 2 (Thứ 3) — MongoDB Ingest Pipeline

**Dev 1 (Quỳnh Gia) — Code:**

```java
// src/main/java/com/security/security/pipeline/MongoIngestPipeline.java
@Service
@Slf4j
@RequiredArgsConstructor
public class MongoIngestPipeline {

    private final MongoWikiRepository mongoWikiRepository;
    private final EmbeddingModel embeddingModel;  // multilingual-e5-base (đã có)

    /**
     * Ingest document vào MongoDB — chạy SONG SONG với PgVector ingest
     * Input: cùng WikiDocument + chunks từ Docling Parser
     */
    public MongoWikiDocument ingest(String wikiId, String title,
                                     String workspaceId, List<String> allowedRoles,
                                     List<ParsedChunk> chunks) {

        List<MongoWikiDocument.MongoSection> sections = buildSections(chunks, allowedRoles);

        MongoWikiDocument document = MongoWikiDocument.builder()
            .wikiId(wikiId)
            .title(title)
            .workspaceId(workspaceId)
            .allowedRoles(allowedRoles)
            .createdAt(LocalDateTime.now())
            .sections(sections)
            .build();

        MongoWikiDocument saved = mongoWikiRepository.save(document);
        log.info("[MONGO-INGEST] Saved wikiId={} chunks={}", wikiId, chunks.size());
        return saved;
    }

    private List<MongoWikiDocument.MongoSection> buildSections(
            List<ParsedChunk> chunks, List<String> allowedRoles) {

        // Group chunks by section, compute embedding for each chunk
        return chunks.stream()
            .collect(Collectors.groupingBy(ParsedChunk::getSectionTitle))
            .entrySet().stream()
            .map(entry -> {
                List<MongoWikiDocument.MongoChunk> mongoChunks = IntStream
                    .range(0, entry.getValue().size())
                    .mapToObj(i -> {
                        ParsedChunk chunk = entry.getValue().get(i);
                        float[] embedding = embeddingModel.embed(chunk.getText());

                        return MongoWikiDocument.MongoChunk.builder()
                            .chunkId(UUID.randomUUID().toString())
                            .text(chunk.getText())
                            .embedding(embedding)
                            .chunkIndex(i)
                            .tokenCount(chunk.getTokenCount())
                            .allowedRoles(allowedRoles)
                            .build();
                    })
                    .toList();

                return MongoWikiDocument.MongoSection.builder()
                    .sectionId(UUID.randomUUID().toString())
                    .title(entry.getKey())
                    .chunks(mongoChunks)
                    .build();
            })
            .toList();
## Tiến độ hiện tại

- [x] Tạo `Workspace`, `Document`, `DocumentChunk` entity class (Java).
- [x] Áp dụng các Annotation `@Document` và `@CompoundIndex`.
- [x] Viết `MongoRepository` interface cho từng collection.
- [x] Bơm dữ liệu (Data Seeding) dựa trên tập mock-data.
- [x] Chỉnh sửa logic Ingest (Bơm dữ liệu từ PDF) để ghi thông tin phân quyền vào MongoDB (đã hoàn thành, ghi dữ liệu song song Dual-DB).
- [ ] Viết API `/search` có tích hợp Filter Context từ MongoDB (Sẽ làm ở Phase 3).

**Output Ngày 2:** Ingest pipeline chạy được, document xuất hiện trong MongoDB

---

### 🗓️ Ngày 3 (Thứ 4) — MongoDB Vector Search Index + RBAC Filter

**Dev 1 (Quỳnh Gia) — Code:**

**Bước 1:** Tạo Vector Search Index trên MongoDB (chạy trong mongosh):
```javascript
// Tạo Atlas Search Index (local mode với MongoDB 7.x Community)
db.wiki_documents.createSearchIndex({
  "name": "vector-index",
  "type": "vectorSearch",
  "definition": {
    "fields": [{
      "type": "vector",
      "path": "sections.chunks.embedding",
      "numDimensions": 768,
      "similarity": "cosine"
    }, {
      "type": "filter",
      "path": "workspaceId"
    }, {
      "type": "filter",
      "path": "allowedRoles"
    }]
  }
})
```

**Bước 2:** Viết MongoDB Vector Retriever:

```java
// src/main/java/com/security/security/retriever/MongoVectorRetriever.java
@Service
@Slf4j
@RequiredArgsConstructor
public class MongoVectorRetriever {

    private final MongoTemplate mongoTemplate;
    private final EmbeddingModel embeddingModel;

    /**
     * Vector search với RBAC filter tại query-time
     * Pre-filter: workspaceId + allowedRoles → TRƯỚC KHI tính cosine similarity
     */
    public List<RetrievedChunk> query(String question,
                                       String workspaceId,
                                       List<String> userRoles,
                                       int topK) {

        long startTime = System.currentTimeMillis();
        float[] queryEmbedding = embeddingModel.embed(question);

        // $vectorSearch với RBAC pre-filter — đây là điểm khác biệt chính vs pgvector
        Aggregation aggregation = Aggregation.newAggregation(
            // Stage 1: Vector Search + RBAC Pre-filter
            ctx -> new Document("$vectorSearch", new Document()
                .append("index", "vector-index")
                .append("path", "sections.chunks.embedding")
                .append("queryVector", toList(queryEmbedding))
                .append("numCandidates", topK * 10)  // ANN candidates
                .append("limit", topK)
                .append("filter", new Document()
                    .append("workspaceId", workspaceId)
                    .append("allowedRoles", new Document("$in", userRoles))
                )
            ),
            // Stage 2: Project required fields + similarity score
            Aggregation.project("wikiId", "title", "sections")
                .and(ScoreOperators.meta("vectorSearchScore")).as("score"),
            // Stage 3: Sort by score descending
            Aggregation.sort(Sort.by(Sort.Direction.DESC, "score"))
        );

        AggregationResults<Document> results = mongoTemplate.aggregate(
            aggregation, "wiki_documents", Document.class
        );

        long latencyMs = System.currentTimeMillis() - startTime;
        log.info("[MONGO-QUERY] workspace={} roles={} latency={}ms docs={}",
            workspaceId, userRoles, latencyMs, results.getMappedResults().size());

        return mapToChunks(results.getMappedResults(), latencyMs);
    }
}
```

- [ ] Implement đầy đủ method `mapToChunks()`
- [ ] Test: gửi 1 câu hỏi → MongoDB Vector Search trả kết quả đúng workspace
- [ ] Test RBAC: user GUEST query → không được thấy doc của ADMIN workspace

**Dev 2:**
- [ ] Viết test case RBAC: `MongoRBACTest.java` với JUnit 5

**Output Ngày 3:** MongoDB Vector Search với RBAC filter hoạt động đúng

---

### 🗓️ Ngày 4 (Thứ 5) — A/B Query Interface + QueryStrategy

**Dev 1 (Quỳnh Gia) — Code:**

```java
// Enum chiến lược query
public enum QueryStrategy {
    PGVECTOR,   // Baseline (hiện có)
    MONGODB,    // Challenger (mới)
    GRAPHRAG    // Giai Đoạn 3
}

// QueryOrchestrator mở rộng
@Service
@RequiredArgsConstructor
public class QueryOrchestrator {

    private final PgVectorRetriever pgVectorRetriever;
    private final MongoVectorRetriever mongoVectorRetriever;
    private final BenchmarkLogger benchmarkLogger;

    public QueryResponse query(String question, String workspaceId,
                                List<String> userRoles,
                                QueryStrategy strategy) {
        return switch (strategy) {
            case PGVECTOR -> pgVectorRetriever.query(question, workspaceId, userRoles, 5);
            case MONGODB  -> mongoVectorRetriever.query(question, workspaceId, userRoles, 5);
            case GRAPHRAG -> throw new UnsupportedOperationException("Phase 3 not ready");
        };
    }

    /**
     * A/B Test mode: chạy cả 2 engine song song → trả về cả 2 kết quả để so sánh
     */
    public ABTestResponse queryAB(String question, String workspaceId, List<String> userRoles) {
        var pgResult   = pgVectorRetriever.query(question, workspaceId, userRoles, 5);
        var mongoResult = mongoVectorRetriever.query(question, workspaceId, userRoles, 5);

        return new ABTestResponse(question, pgResult, mongoResult);
    }
}
```

- [ ] Thêm endpoint `/api/ai/query?strategy=MONGODB` vào controller
- [ ] Thêm endpoint `/api/ai/query/ab` cho A/B test mode

**Dev 2:**
- [ ] Frontend: thêm toggle "PostgreSQL | MongoDB" trong chat AI UI
- [ ] Frontend: hiển thị 2 kết quả song song khi chọn chế độ A/B Compare

**Output Ngày 4:** A/B query interface chạy được từ cả API lẫn Frontend

---

### 🗓️ Ngày 5 (Thứ 6) — Integration Test + First Benchmark Run

**Dev 1 (Quỳnh Gia):**
- [ ] Chạy 5 câu hỏi Type A (Factual) qua cả 2 engine → ghi kết quả vào bảng:

| Câu hỏi | pgvector Latency | MongoDB Latency | pgvector Docs | MongoDB Docs |
|---|---|---|---|---|
| Q1: API Gateway port? | _ms | _ms | _docs | _docs |
| Q2: identity-service DB? | _ms | _ms | _docs | _docs |
| Q3: NATS JetStream dùng để làm gì? | _ms | _ms | _docs | _docs |
| Q4: Embedding model nào? | _ms | _ms | _docs | _docs |
| Q5: messaging-service port? | _ms | _ms | _docs | _docs |

- [ ] Quan sát và ghi nhận ban đầu: engine nào nhanh hơn với Type A queries?
- [ ] **Dùng AI:** Paste kết quả vào Claude → hỏi *"Phân tích nguyên nhân sự khác biệt latency"*

**Dev 2:**
- [ ] Viết phần 3.1 báo cáo (1-2 trang đầu):
  - Thiết kế MongoDB Schema
  - Chiến lược Embedding Document Model
  - RBAC Filter Approach

**Output Ngày 5:** Có kết quả benchmark ban đầu Type A, viết được chương 3.1

---

### 🗓️ Ngày 6-7 (Cuối Tuần) — Buffer + RBAC Test Suite

**Dev 1 (Quỳnh Gia):**
- [ ] Viết `MongoRBACTestSuite.java`:
  - Tạo 3 workspace: `ws-engineering`, `ws-hr`, `ws-finance`
  - Tạo 6 users với roles khác nhau
  - Chạy 10 RBAC test cases → verify 100% isolation

**Dev 2:**
- [ ] Hoàn thiện chương 3.1 báo cáo
- [ ] Review code của Dev 1 từ ngày 1-5

---

## 🔴 CHECKPOINT 2 — CUỐI TUẦN 2

> **Demo cần đạt được:** Gửi 1 câu hỏi → 2 kết quả từ pgvector và MongoDB hiển thị song song trong UI. RBAC isolation hoạt động đúng 100%.

**Câu 1:** Tại sao chọn Embedding Document Strategy (không phải Referencing)?  
→ **Trả lời:** Embedding lưu chunks trong cùng document → 1 query $vectorSearch đọc được cả vector + metadata + allowedRoles mà không cần lookup thêm collection khác → giảm latency, phù hợp với use case read-heavy của RAG.

**Câu 2:** Index nào đặt trên MongoDB để tối ưu vector search + metadata filter?  
→ **Trả lời:** Atlas Vector Search Index với `vectorSearch` type, index trên field `sections.chunks.embedding` (768 dim, cosine), pre-filter fields `workspaceId` và `allowedRoles`. Compound index `{ workspaceId: 1, allowedRoles: 1 }` cho metadata filter.

**Câu 3:** Kết quả top-K documents của 2 engine có khác nhau không? Tại sao?  
→ **Quan sát và trả lời dựa trên kết quả thực tế từ Ngày 5.**

---

## ✅ DEFINITION OF DONE — GIAI ĐOẠN 2

- [ ] `MongoWikiDocument.java` + `MongoWikiRepository.java` — entity layer hoàn chỉnh
- [ ] `MongoIngestPipeline.java` — ingest song song với pgvector
- [ ] `MongoVectorRetriever.java` — RBAC pre-filter tại query-time
- [ ] `QueryOrchestrator.java` — A/B strategy routing
- [ ] Endpoint `/api/ai/query?strategy=MONGODB` hoạt động
- [ ] Frontend toggle pgvector/MongoDB hoạt động
- [ ] RBAC test: 100% isolation giữa 3 workspace
- [ ] Benchmark Type A: kết quả đầu tiên được ghi lại

---

*Giai Đoạn 2 | Version: 1.0 | Tuần 2 (7 ngày)*
