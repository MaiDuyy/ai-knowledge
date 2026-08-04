# 🕸️ GIAI ĐOẠN 3 — XÂY DỰNG GRAPHRAG VỚI $graphLookup
## Thời gian: Tuần 3 (7 ngày) | Mục tiêu: Entity Extraction + Graph Store + Hybrid Search

> **Điều kiện tiên quyết:** ✅ Giai Đoạn 2 Done — MongoDB Vector Search + RBAC hoạt động đúng  
> **AI Tools hỗ trợ:** Claude (thiết kế graph schema, viết prompt extraction), Copilot (code), ChatGPT (debug $graphLookup)  
> **Output bắt buộc cuối tuần:** Knowledge Graph được xây dựng + $graphLookup traverse được + GraphRAG trả lời câu Type C/D tốt hơn plain RAG

---

## 💡 HIỂU RÕ VẤN ĐỀ TRƯỚC KHI CODE

### Tại sao plain Vector Search không đủ cho câu hỏi Type C/D?

```
Câu hỏi: "Nếu identity-service sập, service nào bị ảnh hưởng?"

Plain Vector Search → tìm chunks chứa từ "identity-service" → 
trả về: "identity-service chạy ở port 3010, xử lý JWT..."
❌ KHÔNG biết messaging-service phụ thuộc vào identity-service

GraphRAG → traverse đồ thị: identity-service --[REQUIRED_BY]--> messaging-service
                                              --[REQUIRED_BY]--> api-gateway
✅ Biết chính xác ai phụ thuộc vào ai
```

### Kiến trúc GraphRAG cần xây dựng:

```
INGEST PHASE:
Document Chunks → LLM Entity Extractor →
  Entities: [identity-service, PostgreSQL, JWT, gRPC, api-gateway, ...]
  Relations: [(identity-service)-[USES]->(PostgreSQL),
              (api-gateway)-[DEPENDS_ON]->(identity-service), ...]
→ MongoDB Collection: "knowledge_graph"

QUERY PHASE:
Question → Embed question → $vectorSearch top-3 Entity nodes →
$graphLookup depth=2 → Collect subgraph (entities + relations) →
Format as triplets → LLM: "Dựa vào đồ thị: [triplets], trả lời: [question]"
```

---

## 📅 LỊCH THEO TỪNG NGÀY

### 🗓️ Ngày 1 (Thứ 2) — Knowledge Graph Schema + Entity Extractor

**Dev 1 (Quỳnh Gia) — Code:**

```java
// src/main/java/com/security/security/mongo/entity/MongoGraphNode.java
@Document(collection = "knowledge_graph")
@Data
@Builder
public class MongoGraphNode {

    @Id
    private String id;
    private String entityId;
    private String name;           // "identity-service", "PostgreSQL", "JWT"
    private String entityType;     // "SERVICE", "DATABASE", "PROTOCOL", "TECHNOLOGY"
    private String workspaceId;
    private List<String> allowedRoles;
    private float[] embedding;     // Embed entity name+description (768 dim)
    private String description;    // "Service xử lý JWT, User, RBAC..."

    private List<GraphRelation> relationships;

    @Data
    @Builder
    public static class GraphRelation {
        private String targetId;       // entity ID của node đích
        private String targetName;     // "PostgreSQL"
        private String relationType;   // "USES_DATABASE", "DEPENDS_ON", "COMMUNICATES_VIA"
        private Double weight;         // Độ mạnh của mối quan hệ (0.0-1.0)
        private String description;    // "identity-service dùng PostgreSQL lưu User table"
    }
}
```

```java
// src/main/java/com/security/security/graph/EntityExtractor.java
@Service
@Slf4j
@RequiredArgsConstructor
public class EntityExtractor {

    private final ChatClient chatClient;  // Spring AI Chat Client (Gemini/GPT)

    private static final String EXTRACTION_PROMPT = """
        Phân tích đoạn văn bản kỹ thuật sau và trích xuất:
        1. CÁC THỰC THỂ (entities): Tên service, database, công nghệ, protocol, khái niệm kỹ thuật
        2. CÁC MỐI QUAN HỆ (relationships): Liên kết giữa 2 thực thể
        
        Trả về JSON theo đúng format:
        {
          "entities": [
            { "name": "identity-service", "type": "SERVICE", "description": "mô tả ngắn" }
          ],
          "relationships": [
            { "from": "identity-service", "to": "PostgreSQL", "type": "USES_DATABASE", "description": "mô tả quan hệ" }
          ]
        }
        
        Văn bản cần phân tích:
        {{chunk_text}}
        
        Chỉ trả về JSON, không giải thích thêm.
        """;

    public ExtractionResult extract(String chunkText) {
        String prompt = EXTRACTION_PROMPT.replace("{{chunk_text}}", chunkText);

        String jsonResponse = chatClient.prompt()
            .user(prompt)
            .call()
            .content();

        return parseJsonResponse(jsonResponse);
    }

    private ExtractionResult parseJsonResponse(String json) {
        // Parse JSON → ExtractionResult (dùng Jackson ObjectMapper)
        // Có fallback: nếu LLM trả về JSON sai format → trả về empty result, không crash
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(json, ExtractionResult.class);
        } catch (Exception e) {
            log.warn("[ENTITY-EXTRACT] Failed to parse LLM response: {}", e.getMessage());
            return ExtractionResult.empty();
        }
    }
}
```

- [ ] Implement `ExtractionResult.java` record/class
- [ ] Test với 1 đoạn văn bản → kiểm tra LLM có trích xuất đúng entities không
- [ ] **Dùng AI:** Paste EXTRACTION_PROMPT vào Claude → hỏi *"Cải thiện prompt để extract chính xác hơn cho tài liệu kỹ thuật tiếng Việt"*

**Dev 2:**
- [ ] Đọc: **"From Local to Global: GraphRAG" (Microsoft 2024)** → [arxiv.org/abs/2404.16130](https://arxiv.org/abs/2404.16130) (chỉ cần đọc Introduction + Method)
- [ ] Chuẩn bị 10 câu hỏi Type C và Type D từ benchmark-design.md cho việc test cuối tuần

**Output Ngày 1:** EntityExtractor chạy được, trích xuất entities từ chunk text

---

### 🗓️ Ngày 2 (Thứ 3) — Graph Builder Service

**Dev 1 (Quỳnh Gia) — Code:**

```java
// src/main/java/com/security/security/graph/GraphBuilderService.java
@Service
@Slf4j
@RequiredArgsConstructor
public class GraphBuilderService {

    private final EntityExtractor entityExtractor;
    private final MongoTemplate mongoTemplate;
    private final EmbeddingModel embeddingModel;

    /**
     * Xây dựng Knowledge Graph từ tất cả chunks của 1 document
     * Gọi sau khi document đã được ingest vào MongoDB Vector Store
     */
    public void buildGraph(MongoWikiDocument document) {
        Map<String, MongoGraphNode> entityMap = new HashMap<>();

        // Step 1: Extract entities + relationships từ mỗi chunk
        for (MongoWikiDocument.MongoSection section : document.getSections()) {
            for (MongoWikiDocument.MongoChunk chunk : section.getChunks()) {

                ExtractionResult result = entityExtractor.extract(chunk.getText());

                // Step 2: Upsert entities vào graph
                for (ExtractionResult.Entity entity : result.getEntities()) {
                    entityMap.computeIfAbsent(entity.getName(), name -> {
                        float[] embedding = embeddingModel.embed(name + ": " + entity.getDescription());
                        return MongoGraphNode.builder()
                            .entityId(UUID.randomUUID().toString())
                            .name(name)
                            .entityType(entity.getType())
                            .workspaceId(document.getWorkspaceId())
                            .allowedRoles(document.getAllowedRoles())
                            .embedding(embedding)
                            .description(entity.getDescription())
                            .relationships(new ArrayList<>())
                            .build();
                    });
                }

                // Step 3: Link relationships
                for (ExtractionResult.Relationship rel : result.getRelationships()) {
                    MongoGraphNode fromNode = entityMap.get(rel.getFrom());
                    MongoGraphNode toNode   = entityMap.get(rel.getTo());
                    if (fromNode != null && toNode != null) {
                        fromNode.getRelationships().add(
                            MongoGraphNode.GraphRelation.builder()
                                .targetId(toNode.getEntityId())
                                .targetName(toNode.getName())
                                .relationType(rel.getType())
                                .weight(1.0)
                                .description(rel.getDescription())
                                .build()
                        );
                    }
                }
            }
        }

        // Step 4: Save tất cả graph nodes vào MongoDB
        mongoTemplate.insertAll(entityMap.values());
        log.info("[GRAPH-BUILD] Built {} entities for wikiId={}",
            entityMap.size(), document.getWikiId());
    }
}
```

- [ ] Tích hợp `GraphBuilderService.buildGraph()` vào ingest flow
- [ ] Test: sau khi ingest document → kiểm tra collection `knowledge_graph` có data không
- [ ] Verify: các entity có embedding đúng 768 dim

**Dev 2:**
- [ ] Thiết kế cấu trúc UI hiển thị graph context (dropdown hoặc side panel)
- [ ] Viết lý thuyết chương 3.2: *Thiết kế Knowledge Graph Schema*

**Output Ngày 2:** Graph được build tự động sau khi ingest document

---

### 🗓️ Ngày 3 (Thứ 4) — $graphLookup Traversal (Core GraphRAG Logic)

**Dev 1 (Quỳnh Gia) — Code:**

```java
// src/main/java/com/security/security/retriever/MongoGraphRetriever.java
@Service
@Slf4j
@RequiredArgsConstructor
public class MongoGraphRetriever {

    private final MongoTemplate mongoTemplate;
    private final EmbeddingModel embeddingModel;

    /**
     * GraphRAG Retrieval:
     * 1. Vector Search → tìm top-3 seed entities gần nhất với câu hỏi
     * 2. $graphLookup → traverse đồ thị từ seed entities (maxDepth=2)
     * 3. Collect subgraph → format thành context cho LLM
     */
    public GraphContext query(String question, String workspaceId,
                               List<String> userRoles, int maxDepth) {

        long startTime = System.currentTimeMillis();
        float[] queryEmbedding = embeddingModel.embed(question);

        // === STAGE 1: Tìm seed entities bằng vector search ===
        Aggregation seedSearch = Aggregation.newAggregation(
            ctx -> new Document("$vectorSearch", new Document()
                .append("index", "entity-vector-index")
                .append("path", "embedding")
                .append("queryVector", toList(queryEmbedding))
                .append("numCandidates", 30)
                .append("limit", 3)
                .append("filter", new Document()
                    .append("workspaceId", workspaceId)
                    .append("allowedRoles", new Document("$in", userRoles))
                )
            ),
            Aggregation.project("entityId", "name", "entityType", "description")
        );

        List<Document> seedEntities = mongoTemplate
            .aggregate(seedSearch, "knowledge_graph", Document.class)
            .getMappedResults();

        if (seedEntities.isEmpty()) {
            return GraphContext.empty();
        }

        // === STAGE 2: $graphLookup từ seed entities ===
        List<String> seedIds = seedEntities.stream()
            .map(d -> d.getString("entityId"))
            .toList();

        Aggregation graphTraversal = Aggregation.newAggregation(
            // Match seed nodes
            Aggregation.match(Criteria.where("entityId").in(seedIds)),
            // $graphLookup: traverse relationships
            ctx -> new Document("$graphLookup", new Document()
                .append("from", "knowledge_graph")
                .append("startWith", "$relationships.targetId")
                .append("connectFromField", "relationships.targetId")
                .append("connectToField", "entityId")
                .append("as", "connectedNodes")
                .append("maxDepth", maxDepth)        // depth=2 = đi qua 2 bậc quan hệ
                .append("restrictSearchWithMatch",    // RBAC: chỉ traverse node có quyền
                    new Document("allowedRoles", new Document("$in", userRoles))
                )
            )
        );

        List<Document> subgraphDocs = mongoTemplate
            .aggregate(graphTraversal, "knowledge_graph", Document.class)
            .getMappedResults();

        long latencyMs = System.currentTimeMillis() - startTime;
        log.info("[GRAPH-QUERY] seeds={} subgraph_nodes={} latency={}ms",
            seedIds.size(), subgraphDocs.size(), latencyMs);

        return buildGraphContext(seedEntities, subgraphDocs, question, latencyMs);
    }

    /**
     * Format subgraph thành text "triplets" cho LLM prompt
     * VD: "identity-service [USES_DATABASE] PostgreSQL: Lưu trữ User, RefreshToken"
     */
    private GraphContext buildGraphContext(List<Document> seeds,
                                            List<Document> subgraph,
                                            String question, long latencyMs) {
        StringBuilder triplets = new StringBuilder();

        // Seed entities description
        seeds.forEach(seed ->
            triplets.append(String.format("[ENTITY] %s (%s): %s\n",
                seed.getString("name"),
                seed.getString("entityType"),
                seed.getString("description")))
        );

        // Relationships từ subgraph
        subgraph.forEach(node -> {
            List<Document> relations = (List<Document>) node.get("connectedNodes");
            if (relations != null) {
                relations.forEach(rel ->
                    triplets.append(String.format("[RELATION] %s -[%s]-> %s\n",
                        node.getString("name"),
                        rel.getString("relationType"),
                        rel.getString("name")))
                );
            }
        });

        return new GraphContext(triplets.toString(), seeds.size(),
            subgraph.size(), latencyMs);
    }
}
```

- [ ] Tạo Vector Search Index cho collection `knowledge_graph`
- [ ] Test với câu hỏi Type C: *"Nếu identity-service sập, service nào bị ảnh hưởng?"*
- [ ] So sánh kết quả context: GraphRAG vs plain Vector Search → ghi lại sự khác biệt

**Dev 2:**
- [ ] Viết lý thuyết chương 3.2 tiếp theo: *Thuật toán $graphLookup traversal*
- [ ] Chạy thử 5 câu hỏi Type C/D bằng tay → ghi kết quả thủ công để so sánh

**Output Ngày 3:** $graphLookup traverse được đồ thị, trả về subgraph context

---

### 🗓️ Ngày 4 (Thứ 5) — Hybrid Search + GraphRAG Context Builder

**Dev 1 (Quỳnh Gia) — Code:**

```java
// Mở rộng QueryOrchestrator với GRAPHRAG strategy
@Service
@RequiredArgsConstructor
public class QueryOrchestrator {

    private final PgVectorRetriever pgVectorRetriever;
    private final MongoVectorRetriever mongoVectorRetriever;
    private final MongoGraphRetriever mongoGraphRetriever;
    private final ChatClient chatClient;

    public QueryResponse query(String question, String workspaceId,
                                List<String> userRoles, QueryStrategy strategy) {
        return switch (strategy) {
            case PGVECTOR  -> pgVectorRetriever.query(question, workspaceId, userRoles, 5);
            case MONGODB   -> mongoVectorRetriever.query(question, workspaceId, userRoles, 5);
            case GRAPHRAG  -> graphRagQuery(question, workspaceId, userRoles);
        };
    }

    private QueryResponse graphRagQuery(String question, String workspaceId,
                                         List<String> userRoles) {
        // Step 1: Lấy graph context
        GraphContext graphContext = mongoGraphRetriever.query(question, workspaceId, userRoles, 2);

        // Step 2: Build prompt với graph context (khác với plain RAG dùng flat chunks)
        String graphRagPrompt = """
            Bạn là trợ lý tri thức kỹ thuật. Hãy trả lời câu hỏi dựa vào đồ thị kiến thức sau:
            
            === ĐỒ THỊ KIẾN THỨC ===
            %s
            
            === CÂU HỎI ===
            %s
            
            Yêu cầu: Chỉ sử dụng thông tin từ đồ thị kiến thức. Nếu không đủ thông tin, hãy nói rõ.
            """.formatted(graphContext.getTriplets(), question);

        String answer = chatClient.prompt()
            .user(graphRagPrompt)
            .call()
            .content();

        return QueryResponse.builder()
            .question(question)
            .answer(answer)
            .engine("GRAPHRAG")
            .latencyMs(graphContext.getLatencyMs())
            .contextSummary("Entities: " + graphContext.getSeedCount() +
                            ", Graph nodes: " + graphContext.getSubgraphSize())
            .build();
    }
}
```

- [ ] Thêm `QueryStrategy.GRAPHRAG` vào endpoint `/api/ai/query?strategy=GRAPHRAG`
- [ ] Test: chạy câu Q11 và Q12 từ benchmark-design.md → so sánh 3 engine
- [ ] Ghi nhận: GraphRAG có trả lời đúng hơn plain Vector Search cho câu hỏi phụ thuộc service không?

**Dev 2:**
- [ ] Frontend: thêm tab "GraphRAG" vào toggle
- [ ] Frontend: hiển thị graph context (triplets) dưới dạng expandable section

**Output Ngày 4:** GraphRAG pipeline hoạt động end-to-end

---

### 🗓️ Ngày 5 (Thứ 6) — RBAC on Graph + Chạy Benchmark Type C/D

**Dev 1 (Quỳnh Gia):**
- [ ] Verify RBAC propagation trên graph:
  - Khi user GUEST query → `restrictSearchWithMatch` ngăn traverse sang node ADMIN-only
  - Test: user GUEST không thể traverse sang entity thuộc workspace khác
- [ ] Chạy benchmark 10 câu hỏi Type C và D qua cả 3 engine:

| Câu hỏi | pgvector Answer Quality | MongoDB Answer Quality | GraphRAG Answer Quality |
|---|---|---|---|
| Q11: identity-service sập ảnh hưởng ai? | | | |
| Q12: messaging-service phụ thuộc vào đâu? | | | |
| ... | | | |

- [ ] Đánh giá bằng mắt thường (1-5 stars) → sẽ được xác nhận lại bằng LLM-Judge ở Giai Đoạn 4

**Dev 2:**
- [ ] Hoàn thiện chương 3.2 báo cáo (4-5 trang):
  - Schema đồ thị
  - Thuật toán Entity Extraction
  - $graphLookup traversal
  - RBAC propagation trên graph

**Output Ngày 5:** Benchmark Type C/D sơ bộ hoàn thành, chương 3.2 draft xong

---

### 🗓️ Ngày 6-7 (Cuối Tuần) — Buffer + Tối Ưu Graph

**Dev 1 (Quỳnh Gia):**
- [ ] Tối ưu latency $graphLookup nếu quá chậm:
  - Giảm `maxDepth` từ 2 xuống 1 nếu cần
  - Pre-compute graph cho top entities (cache)
  - Thêm compound index: `{ workspaceId: 1, entityType: 1 }`
- [ ] **Fallback nếu Entity Extraction quá phức tạp:** Manual seed một số entity cơ bản:
  - Danh sách services: identity-service, messaging-service, api-gateway, ...
  - Danh sách công nghệ: PostgreSQL, Redis, NATS, Spring Boot, ...
  - Quan hệ: điền tay từ README và SRS

**Dev 2:**
- [ ] Review toàn bộ code từ ngày 1-5
- [ ] Chuẩn bị 20 câu hỏi test đầy đủ cho Giai Đoạn 4

---

## 🔴 CHECKPOINT 3 — CUỐI TUẦN 3

> **Demo cần đạt được:** Câu hỏi "Nếu identity-service sập thì service nào bị ảnh hưởng?" → GraphRAG trả về context đồ thị có cấu trúc HÃY rõ hơn plain Vector Search.

**Câu 1:** Tại sao GraphRAG trả lời câu hỏi về *mối quan hệ* tốt hơn plain RAG?  
→ **Trả lời:** Plain RAG chỉ tìm chunks tương đồng về mặt từ ngữ (semantic similarity). GraphRAG traverse đồ thị quan hệ thực thể → biết được "api-gateway DEPENDS_ON identity-service" dù 2 service này không xuất hiện cùng một đoạn văn bản nào.

**Câu 2:** Chi phí latency của $graphLookup là bao nhiêu ms so với plain vector search?  
→ **Trả lời dựa trên kết quả thực tế. Dự đoán: GraphRAG chậm hơn 2-5x do có thêm traversal step.**

**Câu 3:** Khi graph lớn, em sẽ tối ưu như thế nào?  
→ **Trả lời:** Giảm `maxDepth`, dùng `restrictSearchWithMatch` để prune sớm, pre-compute "hot entities" hay được query, đánh index compound `{ workspaceId: 1, entityType: 1 }`.

---

## ✅ DEFINITION OF DONE — GIAI ĐOẠN 3

- [ ] `MongoGraphNode.java` — entity Knowledge Graph
- [ ] `EntityExtractor.java` — LLM-based entity + relationship extraction
- [ ] `GraphBuilderService.java` — build graph sau khi ingest document
- [ ] `MongoGraphRetriever.java` — $graphLookup traversal với RBAC
- [ ] `QueryOrchestrator.java` — hỗ trợ `QueryStrategy.GRAPHRAG`
- [ ] Benchmark Type C/D: GraphRAG cho kết quả context khác biệt rõ ràng vs plain RAG
- [ ] RBAC on graph: user không traverse được sang node ngoài thẩm quyền
- [ ] Chương 3.2 báo cáo: draft hoàn thành

---

*Giai Đoạn 3 | Version: 1.0 | Tuần 3 (7 ngày)*
