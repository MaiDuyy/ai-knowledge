# Technical Specification: Dual-Engine Knowledge Store (PostgreSQL Baseline vs. MongoDB Module)

> **Document Version:** 1.2.0  
> **Target Module:** `source/ai-knowledge` (Spring Boot 3.x, Java 21, Spring Data JPA + Spring Data MongoDB, NATS JetStream)  
> **Architecture Pattern:** Dual-Engine Architecture (Consistency Baseline vs. Empirical Scalability & Graph Engine)  
> **Trạng thái Baseline:** PostgreSQL JPA (`pgvector`) hoàn thiện 100% chức năng (Document, Chunk, Embedding, WikiPage, WikiLink, RBAC).  

---

## 1. Mục tiêu & Định hướng Kiến trúc (Architectural Vision & Objectives)

1. **PostgreSQL (`pgvector` + JPA) làm Consistency Baseline:**
   - Đảm bảo tính nhất quán dữ liệu ACID, tận dụng hệ sinh thái Spring AI chuẩn.
   - Làm mốc so sánh (baseline) về độ chính xác và tính nhất quán dữ liệu cho báo cáo khóa luận.
2. **MongoDB (Vector Search + `$graphLookup`) làm Scalability & Graph Engine:**
   - Triển khai độc lập và song song dưới dạng module bổ trợ (`MongoStorageEngine`).
   - Khai thác lợi thế NoSQL: BSON Document impedance matching với JSON của LLM, lồng ghép metadata filtering (`MongoRbacFilterBuilder`), và duyệt đồ thị tri thức không dùng SQL JOIN (`$graphLookup`).
3. **Đồng bộ Dữ liệu Bất đồng bộ (Asynchronous Event-Driven Dual-Sync):**
   - Mặc định sử dụng luồng Event-driven qua **NATS JetStream** (`app.storage.dual-write.mode=async`).
   - PostgreSQL xử lý Ingest trước → phát xuất event (`document.ingested`, `wiki.page.published`) → `MongoStorageEngine` NATS subscriber consume và đồng bộ dữ liệu với Idempotency Key và Retry Policy.
   - Hỗ trợ cờ cấu hình `app.storage.dual-write.mode=sync` cho môi trường testing/demo trực tiếp.

---

## 2. Bản đồ Đối ứng Chức năng (Functional Parity Mapping)

| Chức năng Baseline (PostgreSQL JPA) | Thực thi tương đương ở Module MongoDB | Kỹ thuật MongoDB / Spring Data Mongo |
| :--- | :--- | :--- |
| `Document` (SQL `documents`) | `MongoDocument` (Collection `documents`) | Nested BSON Document (`@Document`) |
| `Embedding` (SQL `embeddings`) | `MongoChunk` (Nested) & `MongoFlatChunk` (Flat Collection) | Array embedded trong Document + Flat collection cho Vector Search |
| `WikiPage` (SQL `wiki_pages`) | `MongoWikiPage` (Collection `wiki_pages`) | Document chứa mảng `outboundSlugs` |
| `WikiLink` (SQL `wiki_links` + FK) | `outboundSlugs` mảng chuỗi trong `MongoWikiPage` | Tham chiếu Slug trực tiếp trong BSON Document |
| `graphReachable()` (SQL Recursive CTE) | `WikiGraphService` (`$graphLookup`) | `$graphLookup` aggregation pipeline (Default `maxDepth: 2`) |
| `PermissionUtils` (SQL WHERE) | `MongoRbacFilterBuilder` | Spring Data `Criteria` (Nested fields, `$in`, `$or`) |
| `PostgresStorageEngine` | `MongoStorageEngine` | Dynamic `MongoTemplate` & Repository operations |

---

## 3. Cấu hình Vector Search & Graph Traversal Detail

### 3.1. Vector Search Dual-Strategy
* **Mode 1: MongoDB Atlas Vector Search (Production/Cloud)**
  - Cấu hình Index `$vectorSearch` với metric Cosine Distance trên field `vector`.
  - Hỗ trợ Pre-filtering theo thuộc tính RBAC (`workspaceId`, `departmentId`, `classificationLevel`).
* **Mode 2: Local Self-Hosted Fallback (Dev/Lab)**
  - Tự động fallback sang tính toán Cosine Similarity trong Java Application trên collection `flat_chunks` nếu không có dịch vụ Atlas Vector Search Index.

### 3.2. Knowledge Graph Traversal (`$graphLookup`)
* **Mặc định `maxDepth: 2`**: Truy vấn 1-hop (liên kết trực tiếp) + 2-hop (liên kết mở rộng 2 cấp).
* **Động theo Use-case**:
  - Passive Context RAG: `maxDepth = 1` (tiết kiệm token).
  - Deep AI Agent Exploration: `maxDepth = 2` (ngữ cảnh phong phú).
  - Hard limit bảo vệ hệ thống: `maxDepth <= 3`.

---

## 4. Cấu trúc Mô đun & Chi tiết Lớp (Project Architecture)

```
source/ai-knowledge/src/main/java/com/security/security/
├── entity/
│   └── mongo/
│       ├── MongoDocument.java       # Document chứa nested chunks
│       ├── MongoChunk.java          # Sub-document chunk lồng nhau
│       ├── MongoFlatChunk.java      # Collection phẳng cho Vector Search
│       └── MongoWikiPage.java       # Wiki Document chứa mảng outboundSlugs
├── repository/
│   └── mongo/
│       ├── MongoDocumentRepository.java
│       ├── MongoFlatChunkRepository.java
│       └── MongoWikiPageRepository.java
├── service/
│   ├── KnowledgeStorageEngine.java  # Interface dùng chung cho 2 Storage Engine
│   ├── MongoRbacFilterBuilder.java  # Xây dựng Mongo Criteria phân quyền RBAC
│   ├── WikiGraphService.java        # Xử lý $graphLookup đồ thị tri thức
│   ├── NatsMongoSyncSubscriber.java # Consumer xử lý event async từ NATS JetStream
│   └── impl/
│       ├── PostgresStorageEngine.java # Baseline engine (JPA + pgvector)
│       └── MongoStorageEngine.java    # Empirical engine (MongoDB)
```

---

## 5. Kịch bản Vận hành (Execution & Configuration Modes)

1. **Single Engine Mode (Postgres Baseline - Default):**  
   `app.storage.engine=postgres`
2. **Single Engine Mode (MongoDB Empirical):**  
   `app.storage.engine=mongodb`
3. **Dual Engine / Event-Driven Sync Mode:**  
   `app.storage.engine=postgres`  
   `app.storage.dual-write.enabled=true`  
   `app.storage.dual-write.mode=async` (Mặc định qua NATS JetStream event `document.ingested` và `wiki.page.published`).

---

## 6. Chiến lược Kiểm thử & Xác minh (Testing & Verification Strategy)

1. **Unit Testing:** Kiểm thử isolated cho `MongoRbacFilterBuilder`, `MongoStorageEngine`, và `WikiGraphService`.
2. **Integration Parity Test (`MongoPostgresParityTest.java`):**
   - Nạp cùng file Document / Wiki Page vào cả PostgreSQL và MongoDB.
   - So sánh kết quả `similaritySearch()` với cùng query vector và UserPermissionContext.
   - So sánh kết quả `graphReachable()` về danh sách Slug thu được.
3. **Async Sync Verification Test:**
   - Ingest qua PostgreSQL → kiểm tra event NATS → xác nhận MongoDB cập nhật thành công với cùng `idempotencyKey`.

---

## 7. Operational Boundaries (Ranh giới thực thi)

- **ALWAYS:** Giữ nguyên 100% mã nguồn JPA PostgreSQL, DB migration scripts, và entities gốc làm baseline.
- **NEVER:** Chỉnh sửa schema PostgreSQL gốc hoặc thực hiện benchmark so sánh khi chưa vượt qua Integration Parity Test.
- **ASK FIRST:** Trước khi bổ sung external library mới vào `pom.xml`.
