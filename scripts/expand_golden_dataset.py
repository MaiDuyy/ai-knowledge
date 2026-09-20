#!/usr/bin/env python3
"""Generate golden dataset docs, ground truth, and manifest with linkEvaluation."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "src/test/resources/benchmark/evaluation"
DOCS = RES / "docs"
GT = RES / "ground-truth"

EXISTING_LINK_EVAL = {
    "short-note": {
        "seedPages": [
            {"title": "JWT Authentication", "slug": "concept/jwt-auth"},
            {"title": "Spring Security Filter", "slug": "concept/spring-security"},
        ],
        "fromSlug": "concept/short-note",
        "content": "JWT tích hợp [[concept/jwt-auth]] và filter [[concept/spring-security]].",
        "expectedLinks": ["concept/jwt-auth", "concept/spring-security"],
        "forbiddenLinks": ["topic/react"],
    },
    "distinct-topics": {
        "seedPages": [
            {"title": "AI Safety", "slug": "concept/ai-safety"},
            {"title": "Content Review", "slug": "concept/content-review"},
        ],
        "fromSlug": "concept/distinct-topics",
        "content": "[[concept/ai-safety]] và [[concept/content-review]] là hai chủ đề riêng.",
        "expectedLinks": ["concept/ai-safety", "concept/content-review"],
        "forbiddenLinks": ["topic/kubernetes"],
    },
    "conflicting-doc": {
        "seedPages": [{"title": "API Timeout Policy", "slug": "concept/api-timeout"}],
        "fromSlug": "concept/api-policy",
        "content": "Tài liệu mâu thuẫn về timeout — tham chiếu [[concept/api-timeout]].",
        "expectedLinks": ["concept/api-timeout"],
        "forbiddenLinks": ["concept/jwt-auth"],
    },
    "rbac-model": {
        "seedPages": [
            {"title": "RBAC Model", "slug": "concept/rbac"},
            {"title": "Identity Service", "slug": "concept/identity-service"},
            {"title": "Redis Permission Cache", "slug": "concept/redis-cache"},
        ],
        "fromSlug": "concept/rbac-model",
        "content": "[[concept/rbac]] gọi [[concept/identity-service]] và cache [[concept/redis-cache]].",
        "expectedLinks": ["concept/rbac", "concept/identity-service", "concept/redis-cache"],
        "forbiddenLinks": ["topic/graphql"],
    },
    "nats-messaging": {
        "seedPages": [
            {"title": "NATS JetStream", "slug": "concept/jetstream"},
            {"title": "AI Knowledge Worker", "slug": "concept/ai-knowledge-worker"},
        ],
        "fromSlug": "concept/nats",
        "content": "Event bus dùng [[concept/jetstream]] và consumer [[concept/ai-knowledge-worker]].",
        "expectedLinks": ["concept/jetstream", "concept/ai-knowledge-worker"],
        "forbiddenLinks": ["topic/kafka"],
    },
    "redis-caching": {
        "seedPages": [
            {"title": "Rate Limiting", "slug": "concept/rate-limit"},
            {"title": "JWT Blacklist", "slug": "concept/jwt-blacklist"},
        ],
        "fromSlug": "concept/redis-caching",
        "content": "Redis lưu [[concept/rate-limit]] và [[concept/jwt-blacklist]].",
        "expectedLinks": ["concept/rate-limit", "concept/jwt-blacklist"],
        "forbiddenLinks": ["topic/mongodb"],
    },
    "grpc-service": {
        "seedPages": [
            {"title": "Protocol Buffers", "slug": "concept/protobuf"},
            {"title": "Messaging Service", "slug": "concept/messaging-service"},
        ],
        "fromSlug": "concept/grpc",
        "content": "gRPC serializes bằng [[concept/protobuf]] tới [[concept/messaging-service]].",
        "expectedLinks": ["concept/protobuf", "concept/messaging-service"],
        "forbiddenLinks": ["topic/rest-only"],
    },
    "rate-limiting": {
        "seedPages": [
            {"title": "Redis Cache", "slug": "concept/redis"},
            {"title": "API Gateway", "slug": "concept/api-gateway"},
        ],
        "fromSlug": "concept/rate-limit",
        "content": "Giới hạn tần suất qua [[concept/redis]] tại [[concept/api-gateway]].",
        "expectedLinks": ["concept/redis", "concept/api-gateway"],
        "forbiddenLinks": [],
    },
    "postgresql-schema": {
        "seedPages": [
            {"title": "pgvector Extension", "slug": "concept/pgvector"},
            {"title": "Prisma ORM", "slug": "concept/prisma"},
        ],
        "fromSlug": "concept/postgresql",
        "content": "PostgreSQL schema dùng [[concept/pgvector]] và [[concept/prisma]].",
        "expectedLinks": ["concept/pgvector", "concept/prisma"],
        "forbiddenLinks": ["topic/mongodb"],
    },
    "vector-embedding": {
        "seedPages": [
            {"title": "pgvector Extension", "slug": "concept/pgvector"},
            {"title": "Gemini Embedding", "slug": "concept/gemini-embedding"},
        ],
        "fromSlug": "concept/vector-embedding",
        "content": "Vector search lưu [[concept/pgvector]] qua [[concept/gemini-embedding]].",
        "expectedLinks": ["concept/pgvector", "concept/gemini-embedding"],
        "forbiddenLinks": ["topic/openai-only"],
    },
    "socket-io": {
        "seedPages": [
            {"title": "WebSocket Gateway", "slug": "concept/ws-gateway"},
            {"title": "LiveKit Video", "slug": "concept/livekit"},
        ],
        "fromSlug": "concept/socket-io",
        "content": "Realtime chat qua [[concept/ws-gateway]] và call [[concept/livekit]].",
        "expectedLinks": ["concept/ws-gateway", "concept/livekit"],
        "forbiddenLinks": ["topic/polling-only"],
    },
    "file-upload-s3": {
        "seedPages": [
            {"title": "Amazon S3", "slug": "concept/s3"},
            {"title": "Cloudinary CDN", "slug": "concept/cloudinary"},
        ],
        "fromSlug": "concept/file-upload",
        "content": "File service upload [[concept/s3]] hoặc [[concept/cloudinary]].",
        "expectedLinks": ["concept/s3", "concept/cloudinary"],
        "forbiddenLinks": ["topic/ftp"],
    },
    "spring-csrf": {
        "seedPages": [
            {"title": "Spring Security Filter", "slug": "concept/spring-security"},
            {"title": "CORS Policy", "slug": "concept/cors"},
        ],
        "fromSlug": "concept/csrf",
        "content": "CSRF protection cùng [[concept/spring-security]] và [[concept/cors]].",
        "expectedLinks": ["concept/spring-security", "concept/cors"],
        "forbiddenLinks": ["topic/no-csrf"],
    },
}

NEW_DOCS = [
    {
        "id": "livekit-calls",
        "category": "realtime",
        "md": """# LiveKit Video và Voice Calls

## Tổng quan

LiveKit cung cấp SFU cho video/voice call trong OTT Chat. ws-gateway cấp token join room và đồng bộ trạng thái participant qua Socket.IO.

## Luồng call

1. Client gọi API Gateway lấy LiveKit token.
2. ws-gateway publish sự kiện `call.started` qua NATS.
3. Participant join room qua WebRTC.

## Bảo mật

Token LiveKit có TTL 1 giờ, gắn `userId` và `workspaceId`. RBAC kiểm tra quyền join room trước khi phát token.
""",
        "gt": {
            "expectedTopics": ["LiveKit", "WebRTC", "ws-gateway", "RBAC", "NATS"],
            "forbiddenHallucinations": ["Zoom SDK", "GraphQL", "MongoDB"],
            "passingMentions": ["Zoom"],
        },
        "link": {
            "seedPages": [
                {"title": "WebSocket Gateway", "slug": "concept/ws-gateway"},
                {"title": "RBAC Model", "slug": "concept/rbac"},
            ],
            "fromSlug": "concept/livekit-calls",
            "content": "LiveKit tích hợp [[concept/ws-gateway]] và kiểm tra [[concept/rbac]].",
            "expectedLinks": ["concept/ws-gateway", "concept/rbac"],
            "forbiddenLinks": ["topic/zoom"],
        },
    },
    {
        "id": "ws-gateway",
        "category": "realtime",
        "md": """# WebSocket Gateway (Socket.IO)

## Vai trò

ws-gateway (:3001) quản lý kết nối Socket.IO, presence, typing indicator và relay sự kiện chat tới messaging-service.

## Kiến trúc

- Xác thực JWT từ handshake.
- Subscribe NATS subject `ott.events.message.created`.
- Publish presence vào Redis với TTL 30 giây.

## Scale

Hỗ trợ sticky session qua Redis adapter. Mỗi pod expose health check `/healthz`.
""",
        "gt": {
            "expectedTopics": ["Socket.IO", "ws-gateway", "JWT", "NATS", "Redis"],
            "forbiddenHallucinations": ["GraphQL", "Kafka", "MongoDB"],
            "passingMentions": ["Pusher"],
        },
        "link": {
            "seedPages": [
                {"title": "JWT Authentication", "slug": "concept/jwt-auth"},
                {"title": "NATS JetStream", "slug": "concept/jetstream"},
            ],
            "fromSlug": "concept/ws-gateway",
            "content": "Socket.IO xác thực [[concept/jwt-auth]] và nhận event [[concept/jetstream]].",
            "expectedLinks": ["concept/jwt-auth", "concept/jetstream"],
            "forbiddenLinks": ["topic/pusher"],
        },
    },
    {
        "id": "identity-service-auth",
        "category": "security",
        "md": """# Identity Service — Authentication

## Tổng quan

identity-service (:3010) sở hữu schema auth, userorg và rbac. Cung cấp gRPC ValidateToken và REST login/register.

## JWT

Access token TTL 15 phút, refresh token 7 ngày. Claims gồm `sub`, `roles`, `workspaceId`, `departmentId`.

## Tích hợp

api-gateway gọi gRPC ValidateToken. Redis cache kết quả permission TTL 5 phút.
""",
        "gt": {
            "expectedTopics": ["identity-service", "JWT", "gRPC", "RBAC", "Redis"],
            "forbiddenHallucinations": ["Auth0", "GraphQL", "MongoDB"],
            "passingMentions": ["Auth0"],
        },
        "link": {
            "seedPages": [
                {"title": "API Gateway", "slug": "concept/api-gateway"},
                {"title": "Redis Permission Cache", "slug": "concept/redis-cache"},
            ],
            "fromSlug": "concept/identity-auth",
            "content": "Identity validate token cho [[concept/api-gateway]] và cache [[concept/redis-cache]].",
            "expectedLinks": ["concept/api-gateway", "concept/redis-cache"],
            "forbiddenLinks": ["topic/auth0"],
        },
    },
    {
        "id": "messaging-service-chat",
        "category": "messaging",
        "md": """# Messaging Service — Chat và Groups

## Chức năng

messaging-service (:3020) quản lý conversation, message, group membership. Schema `messaging` trong PostgreSQL.

## API

- gRPC SendMessage, ListConversations
- Publish `ott.events.message.created` sau khi lưu message

## Quyền

Kiểm tra RBAC workspace/department trước khi đọc thread. Message có `securityClassification` INTERNAL hoặc CONFIDENTIAL.
""",
        "gt": {
            "expectedTopics": ["messaging-service", "gRPC", "NATS", "RBAC", "PostgreSQL"],
            "forbiddenHallucinations": ["WhatsApp API", "GraphQL", "MongoDB"],
            "passingMentions": ["Slack"],
        },
        "link": {
            "seedPages": [
                {"title": "NATS JetStream", "slug": "concept/jetstream"},
                {"title": "RBAC Model", "slug": "concept/rbac"},
            ],
            "fromSlug": "concept/messaging",
            "content": "Chat publish qua [[concept/jetstream]] với kiểm tra [[concept/rbac]].",
            "expectedLinks": ["concept/jetstream", "concept/rbac"],
            "forbiddenLinks": ["topic/slack"],
        },
    },
    {
        "id": "notification-service-push",
        "category": "notification",
        "md": """# Notification Service

## Tổng quan

notification-service (:3019) gửi email và push notification. Subscribe NATS `ott.events.notification.send`.

## Template

Email dùng Thymeleaf template. Push qua FCM với device token lưu trong schema notification.

## Retry

Consumer ack explicit, max deliver 3. Dead letter queue sau 3 lần thất bại.
""",
        "gt": {
            "expectedTopics": ["notification-service", "NATS", "FCM", "email"],
            "forbiddenHallucinations": ["Twilio", "GraphQL", "Kafka"],
            "passingMentions": ["SendGrid"],
        },
        "link": {
            "seedPages": [
                {"title": "NATS JetStream", "slug": "concept/jetstream"},
                {"title": "AI Knowledge Worker", "slug": "concept/ai-knowledge-worker"},
            ],
            "fromSlug": "concept/notification",
            "content": "Push notification từ [[concept/jetstream]] tới worker [[concept/ai-knowledge-worker]].",
            "expectedLinks": ["concept/jetstream", "concept/ai-knowledge-worker"],
            "forbiddenLinks": ["topic/sendgrid"],
        },
    },
    {
        "id": "file-service-storage",
        "category": "storage",
        "md": """# File Service — Upload và Storage

## Tổng quan

file-service (:3014) nhận upload multipart, lưu S3 hoặc Cloudinary, trả presigned URL.

## Metadata

Lưu `workspaceId`, `departmentId`, MIME type và checksum SHA-256. Publish `ott.events.document.ingested` cho ai-knowledge.

## Giới hạn

Max file 50MB. Chặn executable MIME type.
""",
        "gt": {
            "expectedTopics": ["file-service", "S3", "Cloudinary", "NATS", "presigned URL"],
            "forbiddenHallucinations": ["FTP", "GraphQL", "MinIO production"],
            "passingMentions": ["MinIO"],
        },
        "link": {
            "seedPages": [
                {"title": "Amazon S3", "slug": "concept/s3"},
                {"title": "Cloudinary CDN", "slug": "concept/cloudinary"},
            ],
            "fromSlug": "concept/file-service",
            "content": "Upload lên [[concept/s3]] hoặc [[concept/cloudinary]].",
            "expectedLinks": ["concept/s3", "concept/cloudinary"],
            "forbiddenLinks": ["topic/ftp"],
        },
    },
    {
        "id": "prisma-multi-schema",
        "category": "database",
        "md": """# Prisma Multi-Schema PostgreSQL

## Mô hình

Mỗi microservice có thư mục `prisma/` riêng, schema PostgreSQL riêng trong DB `ott_chat` chung.

## identity-service

3 schema: auth, userorg, rbac. Startup chạy `prisma db push` trong dev.

## messaging-service

Schema `messaging` dùng `prisma migrate deploy` trong production.
""",
        "gt": {
            "expectedTopics": ["Prisma", "PostgreSQL", "identity-service", "messaging-service", "schema"],
            "forbiddenHallucinations": ["MongoDB", "DynamoDB", "GraphQL"],
            "passingMentions": ["TypeORM"],
        },
        "link": {
            "seedPages": [
                {"title": "Identity Service", "slug": "concept/identity-service"},
                {"title": "Messaging Service", "slug": "concept/messaging-service"},
            ],
            "fromSlug": "concept/prisma-multi",
            "content": "Prisma schema cho [[concept/identity-service]] và [[concept/messaging-service]].",
            "expectedLinks": ["concept/identity-service", "concept/messaging-service"],
            "forbiddenLinks": ["topic/typeorm"],
        },
    },
    {
        "id": "docker-compose-stack",
        "category": "infra",
        "md": """# Docker Compose Full Stack

## Services

`docker compose up` khởi động postgres, redis, nats, api-gateway, ws-gateway, identity-service, messaging-service, file-service, notification-service, ai-knowledge.

## Healthcheck

Mỗi service expose `/healthz`. Compose `depends_on` với condition service_healthy.

## Env

File `.env.docker` override port và API_KEY cho Gemini.
""",
        "gt": {
            "expectedTopics": ["Docker Compose", "postgres", "redis", "NATS", "api-gateway"],
            "forbiddenHallucinations": ["Kubernetes Helm", "Terraform only", "GraphQL"],
            "passingMentions": ["Kubernetes"],
        },
        "link": {
            "seedPages": [
                {"title": "API Gateway", "slug": "concept/api-gateway"},
                {"title": "NATS JetStream", "slug": "concept/jetstream"},
            ],
            "fromSlug": "concept/docker-compose",
            "content": "Stack gồm [[concept/api-gateway]] và [[concept/jetstream]].",
            "expectedLinks": ["concept/api-gateway", "concept/jetstream"],
            "forbiddenLinks": ["topic/helm-only"],
        },
    },
    {
        "id": "hybrid-search-rag",
        "category": "rag",
        "md": """# Hybrid Search (Vector + Keyword)

## Tổng quan

HybridSearchService kết hợp pgvector cosine similarity và PostgreSQL full-text search (tsvector).

## RRF

Reciprocal Rank Fusion gộp hai danh sách kết quả với hằng số k=60.

## Filter

Lọc theo `workspaceId`, `departmentId` và `securityClassification` trước khi rerank.
""",
        "gt": {
            "expectedTopics": ["hybrid search", "pgvector", "full-text", "RRF", "RBAC"],
            "forbiddenHallucinations": ["Elasticsearch only", "GraphQL", "Pinecone"],
            "passingMentions": ["Elasticsearch"],
        },
        "link": {
            "seedPages": [
                {"title": "pgvector Extension", "slug": "concept/pgvector"},
                {"title": "RBAC Model", "slug": "concept/rbac"},
            ],
            "fromSlug": "concept/hybrid-search",
            "content": "Hybrid search trên [[concept/pgvector]] với filter [[concept/rbac]].",
            "expectedLinks": ["concept/pgvector", "concept/rbac"],
            "forbiddenLinks": ["topic/elasticsearch-only"],
        },
    },
    {
        "id": "rerank-cross-encoder",
        "category": "rag",
        "md": """# Rerank Service

## Mục đích

RerankService sắp xếp lại top-k chunk sau hybrid search bằng cross-encoder hoặc LLM scoring.

## Input

Nhận query và danh sách chunk với score ban đầu. Trả về top 5 chunk relevance cao nhất.

## Latency

Target p95 < 500ms cho 20 candidate chunks.
""",
        "gt": {
            "expectedTopics": ["rerank", "cross-encoder", "hybrid search", "chunk"],
            "forbiddenHallucinations": ["GraphQL", "MongoDB", "only BM25"],
            "passingMentions": ["Cohere"],
        },
        "link": {
            "seedPages": [
                {"title": "pgvector Extension", "slug": "concept/pgvector"},
                {"title": "Gemini Embedding", "slug": "concept/gemini-embedding"},
            ],
            "fromSlug": "concept/rerank",
            "content": "Rerank sau search [[concept/pgvector]] và embedding [[concept/gemini-embedding]].",
            "expectedLinks": ["concept/pgvector", "concept/gemini-embedding"],
            "forbiddenLinks": ["topic/cohere-only"],
        },
    },
    {
        "id": "docling-parser",
        "category": "etl",
        "md": """# Docling Document Parser

## Tổng quan

DoclingClient gọi service Docling để chuyển PDF/DOCX sang markdown có cấu trúc, giữ heading và bảng.

## Pipeline

DocumentExtractionService nhận file từ file-service, gọi Docling, lưu markdown vào PostgreSQL.

## Fallback

Nếu Docling timeout 60s, fallback sang plain text extraction.
""",
        "gt": {
            "expectedTopics": ["Docling", "PDF", "markdown", "DocumentExtractionService", "file-service"],
            "forbiddenHallucinations": ["Tesseract only", "GraphQL", "MongoDB"],
            "passingMentions": ["Tesseract"],
        },
        "link": {
            "seedPages": [
                {"title": "Amazon S3", "slug": "concept/s3"},
                {"title": "Prisma ORM", "slug": "concept/prisma"},
            ],
            "fromSlug": "concept/docling",
            "content": "Parser đọc file từ [[concept/s3]] lưu qua [[concept/prisma]].",
            "expectedLinks": ["concept/s3", "concept/prisma"],
            "forbiddenLinks": ["topic/tesseract-only"],
        },
    },
    {
        "id": "mrp-map-reduce",
        "category": "ai-pipeline",
        "md": """# MRP Map-Reduce Pipeline

## Map phase

MrpPipelineService gọi Gemini với JSON schema: entities, concepts, claims, contradictions, recommendations.

## Reduce phase

Gộp chunk thành wiki plan: CREATE/UPDATE action, dedup synonym, citation integrity với [Source Context:].

## Groundedness

Prompt yêu cầu mọi claim phải có source context, cấm hallucination.
""",
        "gt": {
            "expectedTopics": ["MRP", "Map", "Reduce", "Gemini", "wiki", "groundedness"],
            "forbiddenHallucinations": ["GPT-4 only", "GraphQL", "no JSON schema"],
            "passingMentions": ["GPT-4"],
        },
        "link": {
            "seedPages": [
                {"title": "Gemini Embedding", "slug": "concept/gemini-embedding"},
                {"title": "AI Knowledge Worker", "slug": "concept/ai-knowledge-worker"},
            ],
            "fromSlug": "concept/mrp-pipeline",
            "content": "MRP dùng [[concept/gemini-embedding]] và worker [[concept/ai-knowledge-worker]].",
            "expectedLinks": ["concept/gemini-embedding", "concept/ai-knowledge-worker"],
            "forbiddenLinks": ["topic/gpt-only"],
        },
    },
    {
        "id": "wiki-graph-communities",
        "category": "wiki",
        "md": """# Wiki Graph và Community Detection

## WikiGraphService

Xây đồ thị directed từ bảng wiki_link. Detect community bằng thuật toán label propagation.

## refreshLinks

WikiDraftService parse [[slug]] và implicit title mention để tạo edge.

## Health

WikiHealthService báo dangling link và orphan page.
""",
        "gt": {
            "expectedTopics": ["wiki graph", "community", "WikiLink", "WikiDraftService", "dangling"],
            "forbiddenHallucinations": ["Neo4j only", "GraphQL", "MongoDB"],
            "passingMentions": ["Neo4j"],
        },
        "link": {
            "seedPages": [
                {"title": "RBAC Model", "slug": "concept/rbac"},
                {"title": "Protocol Buffers", "slug": "concept/protobuf"},
            ],
            "fromSlug": "concept/wiki-graph",
            "content": "Graph wiki liên kết [[concept/rbac]] và node [[concept/protobuf]].",
            "expectedLinks": ["concept/rbac", "concept/protobuf"],
            "forbiddenLinks": ["topic/neo4j-only"],
        },
    },
    {
        "id": "agent-streaming",
        "category": "ai-agent",
        "md": """# Agent Service — Streaming Chat

## Tổng quan

AgentService stream response từ Gemini qua SSE. Hỗ trợ function calling với RAG retrieval tool.

## Memory

ConversationService lưu lịch sử message và token usage vào PostgreSQL.

## Security

Filter chunk theo RBAC trước khi đưa vào context window.
""",
        "gt": {
            "expectedTopics": ["AgentService", "SSE", "Gemini", "RAG", "RBAC"],
            "forbiddenHallucinations": ["Claude only", "GraphQL", "no streaming"],
            "passingMentions": ["Claude"],
        },
        "link": {
            "seedPages": [
                {"title": "pgvector Extension", "slug": "concept/pgvector"},
                {"title": "RBAC Model", "slug": "concept/rbac"},
            ],
            "fromSlug": "concept/agent-stream",
            "content": "Agent RAG trên [[concept/pgvector]] với filter [[concept/rbac]].",
            "expectedLinks": ["concept/pgvector", "concept/rbac"],
            "forbiddenLinks": ["topic/claude-only"],
        },
    },
    {
        "id": "embedding-gemini",
        "category": "rag",
        "md": """# Gemini Embedding Service

## Model

EmbeddingService dùng `text-embedding-004` qua Spring AI Google GenAI. Vector 768 chiều.

## Storage

Lưu embedding vào bảng `embeddings` schema ai với pgvector index HNSW.

## Batch

Embed theo batch 32 chunk, retry 3 lần khi rate limit.
""",
        "gt": {
            "expectedTopics": ["embedding", "Gemini", "pgvector", "HNSW", "768"],
            "forbiddenHallucinations": ["OpenAI ada only", "GraphQL", "MongoDB"],
            "passingMentions": ["OpenAI"],
        },
        "link": {
            "seedPages": [
                {"title": "pgvector Extension", "slug": "concept/pgvector"},
                {"title": "Prisma ORM", "slug": "concept/prisma"},
            ],
            "fromSlug": "concept/embedding-gemini",
            "content": "Embedding lưu [[concept/pgvector]] qua [[concept/prisma]].",
            "expectedLinks": ["concept/pgvector", "concept/prisma"],
            "forbiddenLinks": ["topic/openai-only"],
        },
    },
    {
        "id": "semantic-chunking",
        "category": "etl",
        "md": """# Semantic Markdown Chunking

## Thuật toán

SemanticMarkdownChunker tách theo heading H1-H3, giữ code block nguyên khối. Target 512 token/chunk.

## Metadata

Mỗi chunk lưu `tokenCount`, `headingPath`, `workspaceId`.

## Overlap

Chunk overlap 50 token giữa section liền kề.
""",
        "gt": {
            "expectedTopics": ["chunking", "markdown", "token", "heading", "overlap"],
            "forbiddenHallucinations": ["fixed 100 char only", "GraphQL", "no overlap"],
            "passingMentions": ["LangChain"],
        },
        "link": {
            "seedPages": [
                {"title": "Gemini Embedding", "slug": "concept/gemini-embedding"},
                {"title": "Docling Parser", "slug": "concept/docling"},
            ],
            "fromSlug": "concept/semantic-chunk",
            "content": "Chunk feed vào [[concept/gemini-embedding]] sau parser [[concept/docling]].",
            "expectedLinks": ["concept/gemini-embedding", "concept/docling"],
            "forbiddenLinks": ["topic/langchain-only"],
        },
    },
    {
        "id": "permission-utils",
        "category": "security",
        "md": """# Permission Utils và Scope Normalizer

## ScopeNormalizer

Chuẩn hóa `workspaceId` và `departmentId` sentinel `ALL` / `GLOBAL`.

## PermissionUtils

Parse JSON department roles từ JWT claim. Kiểm tra HEAD/MEMBER trên từng department.

## RAG filter

RAGService áp filter classification INTERNAL/CONFIDENTIAL theo role user.
""",
        "gt": {
            "expectedTopics": ["PermissionUtils", "ScopeNormalizer", "JWT", "RBAC", "RAG"],
            "forbiddenHallucinations": ["ACL only", "GraphQL", "no JWT"],
            "passingMentions": ["Casbin"],
        },
        "link": {
            "seedPages": [
                {"title": "RBAC Model", "slug": "concept/rbac"},
                {"title": "JWT Authentication", "slug": "concept/jwt-auth"},
            ],
            "fromSlug": "concept/permission-utils",
            "content": "Permission parse [[concept/jwt-auth]] theo [[concept/rbac]].",
            "expectedLinks": ["concept/jwt-auth", "concept/rbac"],
            "forbiddenLinks": ["topic/casbin-only"],
        },
    },
    {
        "id": "jwt-blacklist-redis",
        "category": "security",
        "md": """# JWT Blacklist trên Redis

## Cơ chế

Khi logout hoặc revoke, identity-service ghi `jti` vào Redis key `jwt:blacklist:{jti}` TTL bằng thời gian còn lại của token.

## Kiểm tra

api-gateway filter đọc Redis trước khi forward request. Miss cache gọi gRPC ValidateToken.

## Prefix

Tất cả key Redis dùng prefix `ott:` để tránh collision.
""",
        "gt": {
            "expectedTopics": ["JWT", "blacklist", "Redis", "jti", "api-gateway"],
            "forbiddenHallucinations": ["Memcached only", "GraphQL", "no revoke"],
            "passingMentions": ["Memcached"],
        },
        "link": {
            "seedPages": [
                {"title": "Redis Cache", "slug": "concept/redis"},
                {"title": "API Gateway", "slug": "concept/api-gateway"},
            ],
            "fromSlug": "concept/jwt-blacklist-redis",
            "content": "Blacklist lưu [[concept/redis]] kiểm tra tại [[concept/api-gateway]].",
            "expectedLinks": ["concept/redis", "concept/api-gateway"],
            "forbiddenLinks": ["topic/memcached-only"],
        },
    },
    {
        "id": "cors-policy",
        "category": "security",
        "md": """# CORS Policy cho API Gateway

## Cấu hình

Cho phép origin `http://localhost:3002` (web) và Expo dev origin. Method GET, POST, PUT, DELETE, OPTIONS.

## Header

Expose `Authorization`, `X-Workspace-Id`. Preflight cache 3600 giây.

## Production

Origin production whitelist qua biến môi trường `CORS_ALLOWED_ORIGINS`.
""",
        "gt": {
            "expectedTopics": ["CORS", "api-gateway", "preflight", "origin"],
            "forbiddenHallucinations": ["allow all origins prod", "GraphQL", "no preflight"],
            "passingMentions": ["nginx"],
        },
        "link": {
            "seedPages": [
                {"title": "API Gateway", "slug": "concept/api-gateway"},
                {"title": "Spring Security Filter", "slug": "concept/spring-security"},
            ],
            "fromSlug": "concept/cors-policy",
            "content": "CORS cấu hình tại [[concept/api-gateway]] với [[concept/spring-security]].",
            "expectedLinks": ["concept/api-gateway", "concept/spring-security"],
            "forbiddenLinks": ["topic/allow-all"],
        },
    },
    {
        "id": "spring-actuator-health",
        "category": "ops",
        "md": """# Spring Actuator Health

## Endpoint

Mỗi service expose `/healthz` và `/actuator/health` cho Docker healthcheck.

## Probe

Liveness: JVM up. Readiness: PostgreSQL + Redis + NATS connection OK.

## Metrics

Micrometer export Prometheus metrics tại `/actuator/prometheus` (nội bộ only).
""",
        "gt": {
            "expectedTopics": ["actuator", "healthz", "readiness", "Prometheus", "Docker"],
            "forbiddenHallucinations": ["no healthcheck", "GraphQL", "Datadog only"],
            "passingMentions": ["Datadog"],
        },
        "link": {
            "seedPages": [
                {"title": "NATS JetStream", "slug": "concept/jetstream"},
                {"title": "Redis Cache", "slug": "concept/redis"},
            ],
            "fromSlug": "concept/actuator-health",
            "content": "Readiness kiểm tra [[concept/jetstream]] và [[concept/redis]].",
            "expectedLinks": ["concept/jetstream", "concept/redis"],
            "forbiddenLinks": ["topic/no-health"],
        },
    },
    {
        "id": "virtual-threads-jvm",
        "category": "performance",
        "md": """# Java 21 Virtual Threads

## Sử dụng

ai-knowledge bật virtual threads cho I/O bound task: NATS consumer, HTTP client Docling, embedding batch.

## Benchmark

JVMConcurrencyEvaluator đo throughput xử lý 100 document song song.

## Lưu ý

Không pin carrier thread khi gọi synchronized block dài.
""",
        "gt": {
            "expectedTopics": ["virtual threads", "Java 21", "throughput", "NATS", "concurrency"],
            "forbiddenHallucinations": ["Java 8 only", "GraphQL", "no virtual threads"],
            "passingMentions": ["Kotlin coroutines"],
        },
        "link": {
            "seedPages": [
                {"title": "NATS JetStream", "slug": "concept/jetstream"},
                {"title": "AI Knowledge Worker", "slug": "concept/ai-knowledge-worker"},
            ],
            "fromSlug": "concept/virtual-threads",
            "content": "Virtual thread xử lý [[concept/jetstream]] cho [[concept/ai-knowledge-worker]].",
            "expectedLinks": ["concept/jetstream", "concept/ai-knowledge-worker"],
            "forbiddenLinks": ["topic/java8-only"],
        },
    },
    {
        "id": "cloudinary-cdn",
        "category": "storage",
        "md": """# Cloudinary Media CDN

## Use case

Ảnh avatar và thumbnail chat upload qua Cloudinary transformation `w_200,h_200,c_fill`.

## Fallback

Nếu Cloudinary unavailable, fallback S3 presigned URL gốc.

## Security

Signed upload preset TTL 10 phút, giới hạn `workspaceId` trong context.
""",
        "gt": {
            "expectedTopics": ["Cloudinary", "CDN", "S3", "thumbnail", "presigned"],
            "forbiddenHallucinations": ["Imgur", "GraphQL", "FTP"],
            "passingMentions": ["Imgur"],
        },
        "link": {
            "seedPages": [
                {"title": "Cloudinary CDN", "slug": "concept/cloudinary"},
                {"title": "Amazon S3", "slug": "concept/s3"},
            ],
            "fromSlug": "concept/cloudinary-cdn",
            "content": "Media qua [[concept/cloudinary]] fallback [[concept/s3]].",
            "expectedLinks": ["concept/cloudinary", "concept/s3"],
            "forbiddenLinks": ["topic/imgur"],
        },
    },
    {
        "id": "s3-presigned-url",
        "category": "storage",
        "md": """# S3 Presigned URL

## Flow

file-service tạo presigned PUT URL TTL 15 phút. Client upload trực tiếp lên bucket `ott-chat-files`.

## Validation

Sau upload, service verify etag và virus scan (ClamAV sidecar).

## RBAC

Chỉ MEMBER trở lên trong workspace mới được presign.
""",
        "gt": {
            "expectedTopics": ["S3", "presigned URL", "file-service", "RBAC", "etag"],
            "forbiddenHallucinations": ["public bucket", "GraphQL", "FTP"],
            "passingMentions": ["MinIO"],
        },
        "link": {
            "seedPages": [
                {"title": "Amazon S3", "slug": "concept/s3"},
                {"title": "RBAC Model", "slug": "concept/rbac"},
            ],
            "fromSlug": "concept/s3-presigned",
            "content": "Presign [[concept/s3]] kiểm tra [[concept/rbac]].",
            "expectedLinks": ["concept/s3", "concept/rbac"],
            "forbiddenLinks": ["topic/public-bucket"],
        },
    },
    {
        "id": "nats-jetstream-consumer",
        "category": "messaging",
        "md": """# NATS JetStream Consumer

## Consumer

`ai-knowledge-worker` dùng durable consumer, filter subject `ott.events.document.ingested`, ack wait 30s.

## Retry

Max deliver 3, backoff exponential 2s/4s/8s. Message fail vào stream DLQ `OTT_DLQ`.

## Scale

Horizontal scale: mỗi pod cùng consumer group, NATS phân phối round-robin.
""",
        "gt": {
            "expectedTopics": ["JetStream", "consumer", "durable", "DLQ", "ai-knowledge"],
            "forbiddenHallucinations": ["Kafka consumer", "GraphQL", "no ack"],
            "passingMentions": ["Kafka"],
        },
        "link": {
            "seedPages": [
                {"title": "NATS JetStream", "slug": "concept/jetstream"},
                {"title": "AI Knowledge Worker", "slug": "concept/ai-knowledge-worker"},
            ],
            "fromSlug": "concept/nats-consumer",
            "content": "Consumer [[concept/jetstream]] chạy trên [[concept/ai-knowledge-worker]].",
            "expectedLinks": ["concept/jetstream", "concept/ai-knowledge-worker"],
            "forbiddenLinks": ["topic/kafka-only"],
        },
    },
    {
        "id": "postgres-pgvector-index",
        "category": "database",
        "md": """# PostgreSQL pgvector HNSW Index

## Index

Bảng `embeddings.embedding` dùng index HNSW `vector_cosine_ops` với `m=16`, `ef_construction=64`.

## Query

`ORDER BY embedding <=> query_vector LIMIT 20` kết hợp filter workspace.

## Maintenance

`REINDEX` scheduled hàng tuần khi delete ratio > 20%.
""",
        "gt": {
            "expectedTopics": ["pgvector", "HNSW", "cosine", "PostgreSQL", "embeddings"],
            "forbiddenHallucinations": ["FAISS only", "GraphQL", "no index"],
            "passingMentions": ["FAISS"],
        },
        "link": {
            "seedPages": [
                {"title": "pgvector Extension", "slug": "concept/pgvector"},
                {"title": "Gemini Embedding", "slug": "concept/gemini-embedding"},
            ],
            "fromSlug": "concept/pgvector-index",
            "content": "Index [[concept/pgvector]] cho vector từ [[concept/gemini-embedding]].",
            "expectedLinks": ["concept/pgvector", "concept/gemini-embedding"],
            "forbiddenLinks": ["topic/faiss-only"],
        },
    },
]


def build_ground_truth(doc_id: str, spec: dict) -> dict:
    topics = spec["gt"]["expectedTopics"]
    entities = [{"name": topics[0], "aliases": []}] if topics else []
    concepts = [{"name": t, "keywords": [t.lower()]} for t in topics[1:3]] if len(topics) > 1 else []
    claims = [{"subject": topics[0], "keywords": topics[:3]}] if topics else []
    return {
        "documentId": doc_id,
        "expectedTopics": topics,
        "expectedEntities": entities,
        "expectedConcepts": concepts,
        "expectedClaims": claims,
        "passingMentions": spec["gt"].get("passingMentions", []),
        "forbiddenHallucinations": spec["gt"]["forbiddenHallucinations"],
        "expectedRecommendations": False,
    }


ORIGINAL_DOCS = [
    {"id": "short-note", "sourceFile": "benchmark/mrp/short-note.md",
     "groundTruthFile": "benchmark/mrp/short-note-ground-truth.json", "category": "auth"},
    {"id": "distinct-topics", "sourceFile": "benchmark/mrp/distinct-topics.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/distinct-topics.json", "category": "ai-policy"},
    {"id": "conflicting-doc", "sourceFile": "benchmark/mrp/conflicting-doc.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/conflicting-doc.json", "category": "api-policy"},
    {"id": "oauth2-flow", "sourceFile": "benchmark/evaluation/docs/oauth2-flow.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/oauth2-flow.json", "category": "auth",
     "linkEvaluation": {
         "seedPages": [
             {"title": "JWT Authentication", "slug": "concept/jwt-auth"},
             {"title": "Spring Security Filter", "slug": "concept/spring-security"},
         ],
         "fromSlug": "concept/oauth2",
         "content": "OAuth2 Authorization Code flow cần tích hợp với [[concept/jwt-auth]] và Spring Security Filter.",
         "expectedLinks": ["concept/jwt-auth", "concept/spring-security"],
         "forbiddenLinks": ["topic/react"],
     }},
    {"id": "rbac-model", "sourceFile": "benchmark/evaluation/docs/rbac-model.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/rbac-model.json", "category": "security"},
    {"id": "nats-messaging", "sourceFile": "benchmark/evaluation/docs/nats-messaging.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/nats-messaging.json", "category": "messaging"},
    {"id": "redis-caching", "sourceFile": "benchmark/evaluation/docs/redis-caching.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/redis-caching.json", "category": "infra"},
    {"id": "grpc-service", "sourceFile": "benchmark/evaluation/docs/grpc-service.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/grpc-service.json", "category": "rpc"},
    {"id": "api-gateway", "sourceFile": "benchmark/evaluation/docs/api-gateway.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/api-gateway.json", "category": "gateway",
     "linkEvaluation": {
         "seedPages": [
             {"title": "gRPC Service", "slug": "concept/grpc"},
             {"title": "Rate Limiting", "slug": "concept/rate-limit"},
         ],
         "fromSlug": "concept/api-gateway",
         "content": "API Gateway route request tới [[concept/grpc]] và áp dụng [[concept/rate-limit]].",
         "expectedLinks": ["concept/grpc", "concept/rate-limit"],
         "forbiddenLinks": [],
     }},
    {"id": "rate-limiting", "sourceFile": "benchmark/evaluation/docs/rate-limiting.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/rate-limiting.json", "category": "security"},
    {"id": "postgresql-schema", "sourceFile": "benchmark/evaluation/docs/postgresql-schema.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/postgresql-schema.json", "category": "database"},
    {"id": "vector-embedding", "sourceFile": "benchmark/evaluation/docs/vector-embedding.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/vector-embedding.json", "category": "rag"},
    {"id": "socket-io", "sourceFile": "benchmark/evaluation/docs/socket-io.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/socket-io.json", "category": "realtime"},
    {"id": "file-upload-s3", "sourceFile": "benchmark/evaluation/docs/file-upload-s3.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/file-upload-s3.json", "category": "storage"},
    {"id": "spring-csrf", "sourceFile": "benchmark/evaluation/docs/spring-csrf.md",
     "groundTruthFile": "benchmark/evaluation/ground-truth/spring-csrf.json", "category": "security"},
]


def main():
    DOCS.mkdir(parents=True, exist_ok=True)
    GT.mkdir(parents=True, exist_ok=True)

    for spec in NEW_DOCS:
        doc_id = spec["id"]
        (DOCS / f"{doc_id}.md").write_text(spec["md"].strip() + "\n", encoding="utf-8")
        gt = build_ground_truth(doc_id, spec)
        (GT / f"{doc_id}.json").write_text(
            json.dumps(gt, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )

    manifest_path = RES / "golden-dataset-manifest.json"

    original_docs = []
    for entry in ORIGINAL_DOCS:
        doc = dict(entry)
        if doc["id"] in EXISTING_LINK_EVAL:
            doc["linkEvaluation"] = EXISTING_LINK_EVAL[doc["id"]]
        elif "linkEvaluation" not in doc:
            doc["linkEvaluation"] = EXISTING_LINK_EVAL.get(doc["id"])
        original_docs.append(doc)

    new_docs = [{
        "id": spec["id"],
        "sourceFile": f"benchmark/evaluation/docs/{spec['id']}.md",
        "groundTruthFile": f"benchmark/evaluation/ground-truth/{spec['id']}.json",
        "category": spec["category"],
        "linkEvaluation": spec["link"],
    } for spec in NEW_DOCS]

    manifest = {
        "version": "2.0",
        "description": "SecWiki Evaluation Golden Dataset — 40 documents with ground truth and GERBIL link scenarios",
        "workspaceId": "ws-bench",
        "documents": original_docs + new_docs,
        "thresholds": {
            "corpusFaithfulness": 0.80,
            "corpusGroundedness": 0.80,
            "corpusExtractionF1": 0.55,
            "corpusLinkF1": 0.70,
            "crossModelJudgeAvg": 3.5,
            "maxForbiddenHallucinations": 0,
        },
        "judgeConfig": {
            "openAiModel": "gpt-4o",
            "maxClaimsPerDocument": 2,
            "fallbackProvider": "gemini",
        },
    }

    manifest_path.write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"Generated {len(NEW_DOCS)} new docs. Total documents: {len(manifest['documents'])}")


if __name__ == "__main__":
    main()