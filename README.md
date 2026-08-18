# 🧠 AI Knowledge Service

<div align="center">

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.9-green?style=for-the-badge&logo=springboot)
![Spring AI](https://img.shields.io/badge/Spring_AI-1.1.2-brightgreen?style=for-the-badge)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-blue?style=for-the-badge&logo=postgresql)
![MongoDB](https://img.shields.io/badge/MongoDB-Atlas-47A248?style=for-the-badge&logo=mongodb)
![Redis](https://img.shields.io/badge/Redis-Cache-red?style=for-the-badge&logo=redis)
![Docker](https://img.shields.io/badge/Docker-Container-2496ED?style=for-the-badge&logo=docker)
![AWS](https://img.shields.io/badge/AWS-EC2%2FECR-FF9900?style=for-the-badge&logo=amazonaws)

**Hệ thống RAG (Retrieval-Augmented Generation) thông minh với kiến trúc Dual-Engine**  
Hỗ trợ quản lý tri thức nội bộ, AI Agent với Function Calling, và quy trình biên soạn Wiki tự động.

</div>

---

## 📋 Mục lục

- [Tổng quan](#-tổng-quan)
- [Kiến trúc hệ thống](#-kiến-trúc-hệ-thống)
- [Tính năng chính](#-tính-năng-chính)
- [Tech Stack](#-tech-stack)
- [Cài đặt & Chạy](#-cài-đặt--chạy)
- [Biến môi trường](#-biến-môi-trường)
- [API Reference](#-api-reference)
- [Dual-Engine Architecture](#-dual-engine-architecture)
- [RAG Pipeline](#-rag-pipeline)
- [MRP Workflow](#-mrp-workflow)
- [Benchmark & Evaluation](#-benchmark--evaluation)
- [CI/CD & Deployment](#-cicd--deployment)
- [Cấu trúc thư mục](#-cấu-trúc-thư-mục)

---

## 🎯 Tổng quan

**AI Knowledge Service** là một microservice backend được xây dựng bằng Spring Boot 3.x + Java 21, đóng vai trò là **bộ não AI** trong hệ thống chat nội bộ doanh nghiệp. Service này cung cấp:

- **RAG (Retrieval-Augmented Generation)**: Tra cứu và tổng hợp tri thức từ tài liệu nội bộ thông qua Google Gemini LLM.
- **AI Agent với Function Calling**: Trợ lý thông minh có khả năng gọi các công cụ nội bộ để trả lời câu hỏi phức tạp.
- **MRP Pipeline (Map-Reduce-Publish)**: Quy trình tự động biên soạn tài liệu thành các trang Wiki có cấu trúc.
- **Dual-Engine Storage**: Kiến trúc song song PostgreSQL (ACID baseline) và MongoDB (scalability experimental) để nghiên cứu so sánh hiệu năng.
- **Wiki Knowledge Graph**: Đồ thị tri thức kết nối các trang Wiki theo mối quan hệ ngữ nghĩa.

```
┌──────────────────────────────────────────────────────────────────────┐
│                         AI Knowledge Service                          │
│                                                                        │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  ┌───────────┐ │
│  │  RAG Engine │  │  AI Agent    │  │  MRP Pipeline│  │  Wiki     │ │
│  │  + Chat     │  │  (FC + SSE)  │  │  (Compile →  │  │  Graph    │ │
│  │  Memory     │  │              │  │   Draft →    │  │  ($lookup)│ │
│  └──────┬──────┘  └──────┬───────┘  │   Publish)   │  └─────┬─────┘ │
│         │                │          └──────┬───────┘        │       │
│  ┌──────▼──────────────▼────────────────▼──────────────────▼──────┐ │
│  │              Dual-Engine Storage Layer                           │ │
│  │  ┌────────────────────────┐    ┌─────────────────────────────┐  │ │
│  │  │  PostgreSQL + pgvector │    │  MongoDB Atlas Vector Search │  │ │
│  │  │  (ACID Baseline)       │◄──►│  (Scalability Experimental) │  │ │
│  │  │  JPA + Spring Data     │    │  $graphLookup + BSON         │  │ │
│  │  └────────────────────────┘    └─────────────────────────────┘  │ │
│  │            ▲   Async sync via NATS JetStream   ▲                 │ │
│  └────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 🏗 Kiến trúc hệ thống

### Sơ đồ luồng dữ liệu tổng thể

```
Client (Frontend / ws-gateway)
         │
         ▼
   ┌─────────────┐     JWT Auth (từ Identity Service)
   │  REST APIs  │◄────────────────────────────────
   │  (Port 8080)│
   └──────┬──────┘
          │
    ┌─────▼──────────────────────────────────────────┐
    │              Business Logic Layer               │
    │                                                  │
    │  ┌──────────┐ ┌──────────┐ ┌─────────────────┐ │
    │  │RAGService│ │AgentSvc  │ │MrpPipelineService│ │
    │  └────┬─────┘ └────┬─────┘ └────────┬────────┘ │
    │       │            │                 │          │
    │  ┌────▼────────────▼─────────────────▼────────┐ │
    │  │         HybridSearchService                  │ │
    │  │  Vector Search + Keyword Search + Rerank    │ │
    │  └────────────────────┬────────────────────────┘ │
    └───────────────────────│────────────────────────-─┘
                            │
    ┌───────────────────────▼───────────────────────────┐
    │              Storage Layer                         │
    │                                                     │
    │  PostgreSQL (pgvector)  MongoDB Atlas               │
    │  ┌──────────────────┐  ┌──────────────────────┐   │
    │  │ documents        │  │ MongoDocument         │   │
    │  │ embeddings       │  │ MongoFlatChunk        │   │
    │  │ wiki_pages       │  │ MongoWikiPage         │   │
    │  │ wiki_links       │  │ outboundSlugs[]       │   │
    │  │ conversations    │  └──────────────────────┘   │
    │  │ messages         │         ▲                    │
    │  └──────────────────┘         │ NATS JetStream     │
    │           │ ─────────────────►│ (async sync)       │
    │  Redis (Cache + Rate Limit)   │                    │
    └───────────────────────────────────────────────────┘
                            │
    ┌───────────────────────▼───────────────────────────┐
    │              External Services                     │
    │  Google Gemini API  │  Identity Service (gRPC)     │
    │  NATS JetStream     │  Messaging Service (HTTP)    │
    └───────────────────────────────────────────────────┘
```

### Các luồng xử lý chính

| Luồng | Mô tả |
|-------|-------|
| **Upload → Ingest** | File → Apache Tika parse → Gemini OCR → Chunk → Embed (gemini-embedding-001) → pgvector |
| **Chat → RAG** | Query → Hybrid Search (vector + keyword) → Rerank → Wiki Graph Expansion → Gemini generate → SSE stream |
| **Agent Chat** | Message → Function Calling (Gemini) → Tool execution → SSE stream |
| **MRP Pipeline** | Document → Compile (Map-Reduce) → Draft → Review → Approve → Publish Wiki |
| **Dual-Sync** | PostgreSQL ingest → NATS event → MongoDB async subscriber |

---

## ✨ Tính năng chính

### 1. 📄 Quản lý tài liệu (Document Management)

Hệ thống hỗ trợ upload, phân tích, và lập chỉ mục nhiều loại tài liệu:

- **Upload đa định dạng**: PDF, DOCX, TXT, HTML, hình ảnh (qua OCR Gemini)
- **Preview trước khi ingest**: Người dùng review nội dung Markdown được trích xuất trước khi đẩy vào vector store
- **AI Refactor**: Dùng Gemini để cải thiện cấu trúc Markdown của tài liệu
- **Metadata RBAC**: Gán `workspaceId`, `departmentId`, `allowedRoles`, `securityClassification` cho từng tài liệu
- **Phân trang & tìm kiếm**: Hỗ trợ phân trang server-side và tìm kiếm ngữ nghĩa theo chunk

```
Upload File
    │
    ▼
Apache Tika / Gemini Vision OCR
    │ (extract text/markdown)
    ▼
Preview Markdown (optional user edit)
    │
    ▼
POST /documents/{id}/ingest
    │
    ▼
TextChunkingService (semantic chunking)
    │
    ▼
EmbeddingService (gemini-embedding-001, 768 dims)
    │
    ▼
pgvector / MongoDB Atlas Vector Store
```

**Trạng thái tài liệu:**
```
PENDING → PREVIEW → PROCESSING → COMPLETED
                               → FAILED
```

---

### 2. 💬 RAG Chat (Retrieval-Augmented Generation)

Chat thông minh dựa trên tri thức nội bộ với streaming SSE:

- **Hybrid Search**: Kết hợp vector similarity search (pgvector) + keyword search (full-text) → Rerank
- **Wiki Graph Expansion**: Mở rộng context qua đồ thị liên kết Wiki (1-2 hop)
- **Chat Memory**: Lịch sử hội thoại được lưu PostgreSQL, inject vào context mỗi lượt chat
- **Query Rewriting**: Tự động viết lại câu hỏi follow-up thành câu độc lập trước khi tìm kiếm
- **Confidence Scoring**: Tự động tính điểm tin cậy (HIGH/MEDIUM/LOW/NONE) dựa trên similarity scores
- **"I don't know" Guard**: Từ chối trả lời khi không tìm được context đủ ngưỡng, tránh hallucination
- **Suggested Follow-ups**: Gợi ý 2-3 câu hỏi tiếp theo sau mỗi câu trả lời
- **Permission-aware**: Lọc tài liệu theo workspace, department, và role của người dùng

```json
// Ví dụ response từ RAG
{
  "summary": "Quy trình onboarding nhân viên mới gồm 5 bước...",
  "details": ["Bước 1: Ký hợp đồng...", "Bước 2: Cấp thiết bị..."],
  "sources": ["HR Policy 2025.pdf > Section 3"],
  "confidence": "HIGH",
  "confidenceScore": 0.782,
  "suggestedFollowUps": [
    "Thời gian thử việc là bao lâu?",
    "Phòng ban nào quản lý onboarding?"
  ]
}
```

---

### 3. 🤖 AI Agent với Function Calling

Trợ lý AI thông minh có khả năng thực thi các công cụ nội bộ:

- **Function Calling (Gemini)**: Agent tự quyết định gọi tool phù hợp dựa trên câu hỏi
- **Agent Skills**: Quản lý danh sách skill/tool được phép gọi theo workspace
- **Multi-Provider**: Hỗ trợ chuyển đổi giữa Gemini, OpenAI, Anthropic
- **Rate Limiting**: Giới hạn concurrent requests để bảo vệ quota API
- **Streaming SSE**: Phản hồi realtime theo từng token
- **Admin Agent**: Endpoint riêng cho admin với quyền truy cập toàn hệ thống

**Ví dụ công cụ Agent có thể gọi:**
- Tìm kiếm tài liệu nội bộ
- Tra cứu thông tin Wiki
- Tóm tắt tài liệu theo yêu cầu
- Gửi thông báo qua Messaging Service

---

### 4. 📚 MRP Pipeline (Map-Reduce-Publish)

Quy trình tự động biên soạn Wiki từ tài liệu nội bộ:

```
Document
    │
    ▼
[COMPILE] POST /api/mrp/compile
    │ Map: Phân tách tài liệu thành các nguồn (SourceChunkExtract)
    │ Reduce: Tổng hợp, dedup, đối soát nội dung
    │ → SourceCompilationPlan
    │
    ▼
[REVIEW] Admin/Reviewer xem xét Plan
    │
    ├── Approve → POST /api/mrp/plan/{id}/approve
    │       │
    │       ▼
    │   [DRAFT] WikiPageDraft được tạo/cập nhật
    │       │
    │       ▼
    │   [REVIEW DRAFT] Reviewer xem xét từng Draft
    │       │
    │       ├── Approve → POST /api/mrp/drafts/{id}/approve
    │       │       └── → WikiPage published + vector index updated
    │       │
    │       ├── Reject → POST /api/mrp/drafts/{id}/reject
    │       │
    │       └── Request Changes → POST /api/mrp/drafts/{id}/request-changes
    │               └── Author submits revision → POST /api/mrp/drafts/{id}/submit-revision
    │
    └── Reject → POST /api/mrp/plan/{id}/reject
```

**Trạng thái Draft:**
```
PENDING → APPROVED → (published to WikiPage)
        → REJECTED
        → NEEDS_REVISION → PENDING (after submit-revision)
        → WITHDRAWN
```

---

### 5. 🌐 Wiki Knowledge Graph

Hệ thống quản lý trang Wiki kết nối với đồ thị tri thức:

- **CRUD Wiki Pages**: Tạo, xem, sửa, xóa trang Wiki
- **Graph Traversal**: Duyệt đồ thị liên kết bằng JGraphT (PostgreSQL) hoặc `$graphLookup` (MongoDB)
- **Auto-linking**: Tự động chèn `[[slug]]` liên kết nội bộ vào nội dung markdown
- **Wiki Issues**: Theo dõi và xử lý vấn đề chất lượng trên từng trang Wiki
- **Wiki Fixer Agent**: AI tự động phát hiện và sửa lỗi nội dung Wiki qua streaming SSE
- **Wiki Health Check**: Dashboard sức khỏe tổng quan của knowledge base
- **Image Management**: Upload và phục vụ hình ảnh inline cho Wiki pages
- **Reindex**: Đồng bộ lại toàn bộ vector index cho workspace

---

### 6. 🔐 Phân quyền RBAC đa cấp

Kiểm soát truy cập chi tiết theo cấu trúc tổ chức:

```
SUPER_ADMIN
    └── ADMIN
            └── WORKSPACE_MANAGER / WORKSPACE_ADMIN
                        └── DEPARTMENT HEAD / DEPUTY_HEAD
                                    └── MEMBER
```

**Các cấp độ phân quyền:**
- **SecurityClassification**: `PUBLIC`, `INTERNAL`, `CONFIDENTIAL`, `SECRET`
- **Workspace scope**: Người dùng chỉ thấy tài liệu trong workspace của mình
- **Department scope**: Lọc tài liệu theo phòng ban
- **AllowedRoles**: `ALL`, `HEAD`, `MEMBER`

---

### 7. ⚙️ Cài đặt hệ thống (Settings)

Quản lý cấu hình AI động qua API:

- Cấu hình API keys (Gemini, OpenAI, Anthropic)
- Chuyển đổi LLM model động không cần restart
- Xem catalog LLM models được hỗ trợ
- Masked display cho các giá trị nhạy cảm

---

### 8. 📊 Benchmark & Đánh giá

Hệ thống đo hiệu năng so sánh PostgreSQL vs MongoDB:

- **Database Tradeoff Benchmark**: Đo write throughput, search latency (avg/p95/p99), graph traversal time
- **Evaluation Benchmark**: Đánh giá chất lượng RAG trên Golden Dataset
- **Scale levels**: `small` (100 docs), `medium` (500 docs), `large` (1000 docs)
- **Concurrent Write**: Sử dụng Java 21 Virtual Threads cho benchmark song song
- **Report Generation**: Xuất báo cáo JSON + Markdown tự động

---

## 🛠 Tech Stack

| Thành phần | Công nghệ |
|-----------|-----------|
| **Language** | Java 21 (Virtual Threads) |
| **Framework** | Spring Boot 3.5.9 |
| **AI Framework** | Spring AI 1.1.2 |
| **LLM** | Google Gemini (gemini-3.1-flash-lite, gemini-2.5-flash) |
| **Embedding** | Google gemini-embedding-001 (768 dims) |
| **Vector DB (Primary)** | PostgreSQL 16 + pgvector (IVFFLAT, Cosine) |
| **Vector DB (Experimental)** | MongoDB Atlas Vector Search |
| **Cache** | Redis |
| **Messaging** | NATS JetStream |
| **gRPC** | gRPC 1.62.2 (Identity Service integration) |
| **Graph** | JGraphT 1.5.2 (PostgreSQL path) + `$graphLookup` (MongoDB path) |
| **Document Parser** | Apache Tika 2.9.2 + jsoup 1.17.2 |
| **Security** | Spring Security + JWT (jjwt 0.12.7) |
| **Reactive** | Spring WebFlux + Project Reactor (SSE streaming) |
| **ORM** | Spring Data JPA + Hibernate |
| **Build** | Maven 3.9.6 + protobuf-maven-plugin |
| **Container** | Docker (eclipse-temurin:21-jre-alpine) |
| **CI/CD** | GitHub Actions + AWS ECR + EC2 |
| **Testing** | JUnit 5 + Testcontainers + AssertJ |

---

## 🚀 Cài đặt & Chạy

### Yêu cầu hệ thống

- **JDK 21+**
- **Maven 3.9+**
- **Docker & Docker Compose** (khuyến nghị)
- **PostgreSQL 16** với extension `pgvector`
- **Redis 7+**
- **NATS Server** (cho event-driven sync)
- **Google Gemini API Key**

### 1. Clone repository

```bash
git clone <repository-url>
cd ai-knowledge
```

### 2. Cấu hình biến môi trường

Tạo file `.env` từ template (xem phần [Biến môi trường](#-biến-môi-trường)):

```bash
cp .env.example .env
# Chỉnh sửa .env với các giá trị thực
```

### 3. Khởi động với Docker (khuyến nghị)

```bash
# Build image
docker build -t ai-knowledge .

# Chạy với docker-compose (cần file docker-compose.yml phù hợp)
docker compose up -d
```

### 4. Chạy local (development)

```bash
# Build
./mvnw clean package -DskipTests

# Chạy với profile dev (PostgreSQL only)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Chạy với profile mongodb (MongoDB + PostgreSQL)
./mvnw spring-boot:run -Dspring-boot.run.profiles=mongodb
```

### 5. Cài đặt PostgreSQL pgvector

```sql
-- Trong psql hoặc DBeaver
CREATE EXTENSION IF NOT EXISTS vector;
CREATE SCHEMA IF NOT EXISTS ai_knowledge;
```

### 6. Kiểm tra service đang chạy

```bash
curl http://localhost:8080/actuator/health
# → {"status":"UP"}

curl http://localhost:8080/agent/health
# → {"status":"ok","service":"ai-agent","phase":"2"}
```

---

## 🔧 Biến môi trường

### Bắt buộc

| Biến | Mô tả | Ví dụ |
|------|-------|-------|
| `POSTGRES_USER` | PostgreSQL username | `ott_user` |
| `POSTGRES_PASSWORD` | PostgreSQL password | `ott_password` |
| `POSTGRES_HOST` | PostgreSQL host | `localhost` |
| `POSTGRES_PORT` | PostgreSQL port | `5432` |
| `POSTGRES_DB` | Tên database | `ott_chat` |
| `JWT_SECRET` | JWT signing key (64+ chars) | `bc228a2b...` |
| `JWT_EXPIRATION` | JWT expiry (ms) | `432000000` |
| `GEMINI_API_KEY` | Google Gemini API key | `AQ.Ab8RN6...` |
| `NATS_URL` | NATS server URL | `nats://localhost:4222` |
| `MESSAGING_SERVICE_URL` | Messaging microservice URL | `http://localhost:3020` |

### Tùy chọn

| Biến | Mô tả | Mặc định |
|------|-------|---------|
| `GEMINI_MODEL` | Model chat | `gemini-3.1-flash-lite` |
| `GEMINI_MODEL_IMAGE` | Model xử lý ảnh | `gemini-3.5-flash` |
| `REDIS_URL` | Redis connection URL | `redis://localhost:6379` |
| `REDIS_HOST` | Redis host | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password | _(empty)_ |
| `MONGODB_URI` | MongoDB Atlas URI | `mongodb+srv://...` |
| `MONGODB_DATABASE` | Tên MongoDB database | `ai_knowledge` |
| `ACTIVE_PROFILE` | Spring profile | `mongodb` |
| `IDENTITY_GRPC_HOST` | Identity service gRPC host | `localhost` |
| `IDENTITY_GRPC_PORT` | Identity service gRPC port | `50051` |
| `OPENAI_API_KEY` | OpenAI API key (optional) | _(empty)_ |
| `ANTHROPIC_API_KEY` | Anthropic API key (optional) | _(empty)_ |
| `EMAIL_HOST` | SMTP host | `smtp.gmail.com` |
| `EMAIL_PORT` | SMTP port | `587` |
| `EMAIL_ID` | Email gửi | `...` |
| `EMAIL_PASSWORD` | App password email | `...` |

### Cấu hình RAG (application.properties)

```properties
rag.top-k=5                    # Số chunk tối đa lấy về
rag.similarity-threshold=0.2   # Ngưỡng similarity tối thiểu
rag.rerank.enabled=true        # Bật reranking
rag.rerank.top-n=5             # Số chunk giữ lại sau rerank
```

---

## 📡 API Reference

> **Base URL**: `http://localhost:8080`  
> **Auth**: JWT Bearer token qua header `Authorization: Bearer <token>`, hoặc `x-user-id` / `x-user-role` headers (được inject bởi API Gateway)

### 📄 Documents API

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/documents/upload` | Upload tài liệu mới |
| `POST` | `/documents/{id}/ingest` | Ingest tài liệu vào vector store |
| `POST` | `/documents/{id}/ai-refactor` | AI cải thiện cấu trúc Markdown |
| `GET` | `/documents` | Lấy danh sách tài liệu (phân trang) |
| `GET` | `/documents/{id}` | Lấy chi tiết tài liệu |
| `GET` | `/documents/{id}/raw` | Tải file gốc |
| `DELETE` | `/documents/{id}` | Xóa tài liệu |
| `GET` | `/documents/{id}/chunks` | Lấy tất cả chunks |
| `GET` | `/documents/{id}/chunks/{index}` | Lấy chunk theo index |
| `GET` | `/documents/{id}/stats` | Thống kê tài liệu |
| `POST` | `/documents/search` | Tìm kiếm semantic |
| `POST` | `/documents/search/hybrid` | Tìm kiếm hybrid (vector + keyword) |
| `POST` | `/documents/{id}/approve` | Phê duyệt tài liệu |
| `PATCH` | `/documents/{id}/metadata` | Cập nhật metadata |
| `GET` | `/documents/admin` | [Admin] Xem tất cả tài liệu |
| `DELETE` | `/documents/admin/{id}` | [Admin] Xóa bất kỳ tài liệu |

**Ví dụ Upload:**
```bash
curl -X POST http://localhost:8080/documents/upload \
  -H "x-user-id: user-123" \
  -H "x-workspace-id: ws-001" \
  -F "file=@document.pdf" \
  -F "parser=gemini" \
  -F "securityClassification=INTERNAL"
```

---

### 💬 Chat API

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/chat/conversations` | Tạo conversation mới |
| `GET` | `/chat/conversations` | Lấy danh sách conversations |
| `GET` | `/chat/conversations/{id}/messages` | Lấy lịch sử chat |
| `POST` | `/chat/messages` | Gửi tin nhắn RAG (SSE stream) |
| `POST` | `/chat/admin/messages` | [Admin] RAG chat toàn hệ thống |
| `DELETE` | `/chat/conversations/{id}` | Xóa conversation |

**Ví dụ Chat (SSE streaming):**
```bash
curl -X POST http://localhost:8080/chat/messages \
  -H "Content-Type: application/json" \
  -H "x-user-id: user-123" \
  -H "x-workspace-id: ws-001" \
  -H "x-user-roles: [\"MEMBER\"]" \
  -d '{"conversationId": 1, "message": "Quy trình onboarding nhân viên mới?"}' \
  --no-buffer
```

---

### 🤖 Agent API

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/agent/chat` | Agent chat (SSE stream + Function Calling) |
| `POST` | `/agent/admin/chat` | [Admin] Agent chat toàn hệ thống |
| `GET` | `/agent/health` | Health check agent |

**Ví dụ Agent Chat:**
```bash
curl -X POST http://localhost:8080/agent/chat \
  -H "Content-Type: application/json" \
  -H "x-user-id: user-123" \
  -d '{
    "message": "Tìm và tóm tắt tài liệu về chính sách bảo mật",
    "chatId": "chat-456",
    "workspaceId": "ws-001"
  }' \
  --no-buffer
```

---

### 🔧 Agent Skills API

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/skills` | Tạo skill mới |
| `GET` | `/skills` | Lấy danh sách skills |
| `GET` | `/skills/{id}` | Lấy chi tiết skill |
| `PUT` | `/skills/{id}` | Cập nhật skill |
| `DELETE` | `/skills/{id}` | Xóa skill |

---

### 📚 MRP & Wiki API

#### Compilation Pipeline

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/api/mrp/compile` | Khởi động compile document → plan |
| `POST` | `/api/mrp/plan/{id}/approve` | Phê duyệt compilation plan |
| `POST` | `/api/mrp/plan/{id}/reject` | Từ chối compilation plan |

#### Draft Management

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `GET` | `/api/mrp/drafts` | Lấy danh sách drafts (lọc theo status) |
| `GET` | `/api/mrp/drafts/workspace/{wsId}` | Drafts theo workspace |
| `GET` | `/api/mrp/drafts/{id}` | Chi tiết draft |
| `PATCH` | `/api/mrp/drafts/{id}` | [Admin] Sửa trực tiếp draft |
| `POST` | `/api/mrp/drafts/{id}/approve` | Phê duyệt draft → publish Wiki |
| `POST` | `/api/mrp/drafts/{id}/reject` | Từ chối draft |
| `POST` | `/api/mrp/drafts/{id}/request-changes` | Yêu cầu chỉnh sửa |
| `POST` | `/api/mrp/drafts/{id}/submit-revision` | Tác giả gửi lại bản sửa |
| `POST` | `/api/mrp/drafts/{id}/withdraw` | Rút lại draft |
| `POST` | `/api/mrp/wiki/drafts/auto-link` | Tự động chèn internal links |

#### Wiki Pages

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `GET` | `/api/mrp/wiki` | Lấy danh sách trang Wiki |
| `GET` | `/api/mrp/wiki/{slug}` | Lấy trang Wiki theo slug |
| `GET` | `/api/mrp/wiki/graph` | Đồ thị liên kết Wiki |
| `POST` | `/api/mrp/wiki/reindex` | [Admin] Reindex toàn bộ Wiki |
| `GET` | `/api/mrp/wiki/health` | [Admin] Sức khỏe knowledge base |

#### Wiki Issues

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `GET` | `/api/mrp/wiki/issues` | Issues theo trang |
| `GET` | `/api/mrp/wiki/issues/all` | Tất cả issues theo workspace |
| `GET` | `/api/mrp/wiki/issues/count` | Đếm open issues |
| `POST` | `/api/mrp/wiki/issues` | Tạo issue mới |
| `PATCH` | `/api/mrp/wiki/issues/{id}` | Cập nhật trạng thái issue |
| `DELETE` | `/api/mrp/wiki/issues/{id}` | Xóa issue |

#### Wiki Fixer Agent

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/api/mrp/wiki/fixer/chat` | AI tự động sửa lỗi Wiki (SSE stream) |

---

### 🖼️ Wiki Images API

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/api/wiki/images/upload` | Upload ảnh cho Wiki |
| `POST` | `/api/wiki/images/resolve` | Giải mã batch UUID → URL |
| `GET` | `/api/wiki/images/raw/{id}` | Serve raw image (cached) |

---

### ⚙️ Settings API

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `GET` | `/api/settings` | Lấy toàn bộ cấu hình AI |
| `PATCH` | `/api/settings` | Cập nhật batch cấu hình |
| `GET` | `/api/settings/llm/catalog` | Danh sách LLM models |
| `POST` | `/api/settings/llm/switch` | Chuyển đổi LLM model |
| `GET` | `/api/settings/llm/active` | LLM model đang sử dụng |

---

### 🔌 Internal API (gọi bởi Node.js services)

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/api/rag/query` | RAG query có phân quyền |
| `POST` | `/api/documents/index` | Index document từ service khác |
| `DELETE` | `/api/documents/{id}` | Xóa document khỏi vector index |

---

### 📊 Benchmark API (chỉ profile `mongodb-benchmark`)

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `POST` | `/api/benchmark/database-tradeoff/seed` | Seed dữ liệu test |
| `POST` | `/api/benchmark/database-tradeoff/run` | Chạy benchmark đo lường |
| `GET` | `/api/benchmark/database-tradeoff/report` | Lấy kết quả benchmark |

---

### 🏠 Dashboard & Health

| Method | Endpoint | Mô tả |
|--------|----------|-------|
| `GET` | `/dashboard/daily-brief` | Tóm tắt hàng ngày (AI summary) |
| `GET` | `/actuator/health` | Spring Boot health check |

---

## 🔄 Dual-Engine Architecture

Kiến trúc song song cho phép nghiên cứu so sánh giữa hai storage engine:

### Chế độ vận hành

```properties
# Mode 1: Chỉ PostgreSQL (mặc định)
app.storage.engine=postgres

# Mode 2: Chỉ MongoDB
app.storage.engine=mongodb

# Mode 3: Dual-write với sync bất đồng bộ qua NATS
app.storage.engine=postgres
app.storage.dual-write.enabled=true
app.storage.dual-write.mode=async   # hoặc sync cho test
```

### So sánh kỹ thuật

| Tính năng | PostgreSQL (Baseline) | MongoDB (Experimental) |
|----------|----------------------|------------------------|
| **Vector Search** | pgvector IVFFLAT (Cosine) | Atlas Vector Search / Brute-force fallback |
| **Graph Traversal** | JGraphT BFS in-memory | `$graphLookup` aggregation |
| **RBAC Filtering** | SQL WHERE + JPA | `MongoRbacFilterBuilder` (Criteria) |
| **Document Model** | SQL tables + FK | Nested BSON Document |
| **Chunk Storage** | `embeddings` table | Nested array / flat `flat_chunks` |
| **Wiki Links** | `wiki_links` bảng riêng | `outboundSlugs[]` array trong document |
| **Consistency** | ACID (strong) | Eventual (async via NATS) |
| **Strength** | Correctness, maturity | Scalability, flexible schema |

### NATS JetStream Sync

```
PostgreSQL Ingest
    │
    ▼ publish event
NATS Subject: document.ingested
              wiki.page.published
    │
    ▼ subscribe
NatsMongoSyncSubscriber
    │ (Idempotency Key + Retry Policy)
    ▼
MongoDB Sync
```

---

## 🔍 RAG Pipeline

Chi tiết luồng xử lý RAG với Hybrid Search:

```
User Question
    │
    ▼
[1] Query Rewriting (if follow-up)
    │ → Gemini rewrites to standalone query
    │
    ▼
[2] Hybrid Search
    ├── Vector Search (pgvector / MongoDB Atlas)
    │   └── Filter by RBAC (workspace, dept, role)
    └── Keyword Search (PostgreSQL full-text)
    │
    ▼
[3] Reranking (gemini rerank)
    │ → Top-N most relevant chunks
    │
    ▼
[4] Wiki Graph Expansion
    │ → Find related Wiki pages (1-2 hop)
    │
    ▼
[5] Confidence Scoring
    │ → Blended score: max*0.7 + avg*0.3
    │ → HIGH (≥0.65) / MEDIUM (≥0.40) / LOW / NONE
    │
    ▼
[6] Guard Check (score < 0.25 → "I don't know")
    │
    ▼
[7] Gemini LLM Generation (streaming SSE)
    │ → System prompt với confidence
    │ → Context = direct results + wiki graph
    │ → JSON response schema
    │
    ▼
[8] Save to Chat Memory + Conversation DB
    │
    ▼
Response Stream to Client
```

---

## 📋 MRP Workflow

Quy trình biên soạn tài liệu thành Wiki pages:

### Phase 1: Compile (Map & Reduce)

Khi gọi `POST /api/mrp/compile?documentId=X`:

1. **Map phase**: Phân tách tài liệu thành các `SourceChunkExtract` song song (Virtual Threads)
2. **Reduce phase**: Tổng hợp chunks thành các topic cluster
3. **Dedup & Reconcile**: Loại bỏ trùng lặp, đối soát với Wiki pages hiện có
4. **Output**: `SourceCompilationPlan` với danh sách các trang cần tạo/cập nhật

### Phase 2: Review Plan

Admin/Manager xem xét plan:
- **Approve**: Tiến hành tạo drafts
- **Reject**: Hủy với ghi chú

### Phase 3: Draft Generation

Khi plan được approve (`POST /api/mrp/plan/{id}/approve`):
1. Gemini tạo nội dung cho từng `WikiPageDraft`
2. Sử dụng `MrpProductionPrompts` với template chuyên biệt
3. Draft được lưu với trạng thái `PENDING`

### Phase 4: Draft Review

Reviewer (HEAD/WORKSPACE_MANAGER) xem xét từng draft:
- **Approve**: Merge nội dung vào WikiPage + cập nhật vector index
- **Reject**: Ghi chú lý do
- **Request Changes**: Gửi lại cho tác giả chỉnh sửa

### Phase 5: Publish

Khi draft được approve:
1. `WikiPage` được tạo/cập nhật với nội dung mới
2. Vector embeddings được cập nhật
3. Wiki links được xây dựng lại
4. NATS event `wiki.page.published` được phát ra (nếu dual-write enabled)

---

## 🧪 Benchmark & Evaluation

### Chạy Database Tradeoff Benchmark

```bash
# Kích hoạt profile mongodb-benchmark
./mvnw test -P mongodb-benchmark

# Hoặc qua REST API (khi chạy với profile experimental)
curl -X POST "http://localhost:8080/api/benchmark/database-tradeoff/run?scale=medium&searchIterations=50"
```

**Kết quả benchmark mẫu:**

| Metric | PostgreSQL | MongoDB |
|--------|-----------|---------|
| Write Throughput | ~X docs/sec | ~Y docs/sec |
| Search Avg (ms) | ~X ms | ~Y ms |
| Search P95 (ms) | ~X ms | ~Y ms |
| Graph Traversal | JGraphT BFS | `$graphLookup` |

_📁 Báo cáo được lưu tại: `benchmark/database-tradeoff/latest/`_

### Chạy Evaluation Benchmark (RAG Quality)

```bash
./mvnw test -P evaluation-benchmark \
  -DGEMINI_API_KEY=your_key \
  -DPOSTGRES_USER=user \
  -DPOSTGRES_PASSWORD=pass
```

_📁 Báo cáo được lưu tại: `benchmark/evaluation/latest/`_

---

## 🔄 CI/CD & Deployment

### GitHub Actions Pipelines

#### CI Pipeline (`ci.yml`)

Trigger: push/PR vào `main`, `develop`, `dev`, `product`

```
build job         test job          deploy jobs
─────────         ────────          ───────────
Checkout    →     Checkout    →     staging (develop branch)
Setup JDK         Setup JDK         production (main branch)
Build Maven       Start Postgres
Docker build      Start Redis
                  Run Tests
```

#### AWS Deployment (`deploy-aws.yml`)

Trigger: push vào `main`/`develop`

```
build-and-push          deploy
──────────────          ──────
AWS ECR Login     →     SSH to EC2
Docker build            ECR Login
Tag & Push              docker compose pull ai-knowledge
                        docker compose up --no-deps ai-knowledge
                        nginx -s reload
```

### Docker Production Build

```dockerfile
# Multi-stage build
Stage 1 (builder): maven:3.9.6-eclipse-temurin-21
Stage 2 (production): eclipse-temurin:21-jre-alpine

# Non-root user: spring:spring
# Healthcheck: GET /actuator/health
# Port: 8080
```

---

## 📁 Cấu trúc thư mục

```
ai-knowledge/
├── .github/
│   └── workflows/
│       ├── ci.yml                          # CI/CD pipeline
│       └── deploy-aws.yml                  # AWS EC2 deployment
├── benchmark/                              # Benchmark reports
│   ├── database-tradeoff/                  # PostgreSQL vs MongoDB
│   └── evaluation/                         # RAG quality evaluation
├── src/
│   ├── main/
│   │   ├── java/com/security/security/
│   │   │   ├── ai/                         # AI tools & function calling
│   │   │   ├── cache/                      # Redis cache services
│   │   │   ├── client/                     # gRPC & HTTP clients
│   │   │   │   └── WorkspaceServiceClient  # Messaging service client
│   │   │   ├── config/                     # Spring configurations
│   │   │   ├── constants/                  # App constants
│   │   │   ├── domain/                     # Domain models
│   │   │   ├── dto/                        # Data Transfer Objects
│   │   │   ├── dtorequest/                 # Request DTOs
│   │   │   ├── entity/                     # JPA entities
│   │   │   │   ├── mongo/                  # MongoDB documents
│   │   │   │   │   ├── MongoDocument.java
│   │   │   │   │   ├── MongoChunk.java
│   │   │   │   │   ├── MongoFlatChunk.java
│   │   │   │   │   └── MongoWikiPage.java
│   │   │   │   ├── enumeration/            # Enums
│   │   │   │   ├── Document.java           # Document entity
│   │   │   │   ├── Embedding.java          # Vector chunk entity
│   │   │   │   ├── WikiPage.java           # Wiki page entity
│   │   │   │   ├── WikiPageDraft.java      # Draft entity
│   │   │   │   ├── WikiLink.java           # Wiki graph edge
│   │   │   │   ├── WikiIssue.java          # Issue tracking
│   │   │   │   ├── Conversation.java       # Chat conversation
│   │   │   │   └── Message.java            # Chat message
│   │   │   ├── event/                      # NATS event handlers
│   │   │   ├── exception/                  # Exception handlers
│   │   │   ├── function/                   # Agent tool functions
│   │   │   ├── provider/                   # LLM provider adapters
│   │   │   ├── repository/                 # Spring Data repositories
│   │   │   │   └── mongo/                  # MongoDB repositories
│   │   │   ├── resource/                   # REST controllers
│   │   │   │   ├── AgentController.java
│   │   │   │   ├── AgentSkillController.java
│   │   │   │   ├── BenchmarkController.java
│   │   │   │   ├── ChatController.java
│   │   │   │   ├── DashboardController.java
│   │   │   │   ├── DocumentController.java
│   │   │   │   ├── InternalAIController.java
│   │   │   │   ├── MrpController.java
│   │   │   │   ├── SettingsController.java
│   │   │   │   ├── WikiFixerController.java
│   │   │   │   ├── WikiImageController.java
│   │   │   │   └── WikiIssueController.java
│   │   │   ├── security/                   # JWT & Spring Security
│   │   │   ├── service/                    # Business logic
│   │   │   │   ├── impl/
│   │   │   │   │   ├── PostgresStorageEngine.java  # Baseline engine
│   │   │   │   │   └── MongoStorageEngine.java     # Experimental engine
│   │   │   │   ├── RAGService.java
│   │   │   │   ├── AgentService.java
│   │   │   │   ├── MrpPipelineService.java
│   │   │   │   ├── WikiDraftService.java
│   │   │   │   ├── WikiGraphService.java
│   │   │   │   ├── HybridSearchService.java
│   │   │   │   ├── EmbeddingService.java
│   │   │   │   ├── DocumentService.java
│   │   │   │   ├── KnowledgeStorageEngine.java     # Interface chung
│   │   │   │   ├── MongoRbacFilterBuilder.java
│   │   │   │   ├── NatsMongoSyncSubscriber.java
│   │   │   │   └── ...
│   │   │   └── utils/                      # Utilities
│   │   ├── proto/                          # gRPC proto files
│   │   └── resources/
│   │       ├── application.properties      # Main config
│   │       ├── application-dev.properties  # Dev profile
│   │       ├── application-mongodb.properties # MongoDB profile
│   │       └── db/migration/               # SQL migration scripts
│   └── test/
│       └── java/...
│           ├── GoldenDatasetEvaluationBenchmarkTest.java
│           ├── DatabaseTradeOffBenchmarkTest.java
│           └── MongoPostgresParityTest.java
├── Dockerfile                              # Multi-stage Docker build
├── pom.xml                                 # Maven dependencies
└── SPEC.md                                 # Technical specification
```

---

## 🔒 Bảo mật

- **JWT Authentication**: Stateless authentication, token được verify từ Identity Service
- **Non-root Docker**: Container chạy với user `spring` (không phải root)
- **RBAC đa cấp**: Kiểm soát truy cập theo workspace, department, role, security classification
- **Rate Limiting**: Giới hạn concurrent LLM calls cho chat, agent, và RAG queries
- **Input Validation**: Spring Validation cho tất cả request payloads
- **SQL Injection Prevention**: JPA parameterized queries
- **Sensitive config masking**: API keys được che trong Settings API response

---

## 📝 Ghi chú phát triển

### Thêm Storage Engine mới

Implement interface `KnowledgeStorageEngine`:

```java
public interface KnowledgeStorageEngine {
    void storeDocument(Document doc, List<Embedding> chunks);
    void storeWikiPage(WikiPage page, List<String> outboundSlugs);
    List<StorageSearchHit> similaritySearch(String query, List<Double> vector,
                                             int topK, UserPermissionContext perms);
    StorageEngineType getEngineType();
}
```

### Thêm Agent Tool mới

Tạo class trong package `function/` với annotation `@Bean` + `@Description` của Spring AI.

### Chạy chỉ benchmark PostgreSQL vs MongoDB

```bash
# Benchmark đo lường song song
./mvnw test -P mongodb-benchmark

# Evaluation chất lượng RAG với MongoDB
./mvnw test -P evaluation-mongodb-benchmark
```

---

## 📄 License

Project này là luận văn tốt nghiệp — xem chi tiết trong [SPEC.md](./SPEC.md).

---

<div align="center">
  <sub>Built with ❤️ using Spring Boot 3 + Spring AI + Google Gemini</sub>
</div>
