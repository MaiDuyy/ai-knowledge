# Sơ đồ Kiến trúc Pipeline ETL & Truy xuất (Dual-Database)

Tài liệu này mô tả luồng xử lý dữ liệu từ dạng thô (PDF, Word) cho đến khi được lưu trữ vào hệ thống cơ sở dữ liệu kép (MongoDB + PostgreSQL) và luồng truy xuất thông tin (RAG Retrieval) có kiểm soát quyền truy cập (RBAC).

## 1. Pipeline Ingest (Xử lý và Lưu trữ Dữ liệu)

Đường ống này mô tả quá trình tài liệu nội bộ được đưa vào hệ thống. Điểm nhấn của kiến trúc là việc tách bạch lưu trữ: **MongoDB** lo Metadata và Phân quyền, trong khi **PgVector** lo tìm kiếm ngữ nghĩa.

```mermaid
sequenceDiagram
    autonumber
    actor Admin as User/Admin
    participant API as Ingest API (Spring Boot)
    participant Docling as IBM Docling (Parser)
    participant Embedding as Local ONNX Model<br/>(multilingual-e5-base)
    participant Mongo as MongoDB<br/>(Metadata & ACL)
    participant PgVector as PostgreSQL<br/>(Vector Store)

    Admin->>API: 1. Upload File (PDF/Word) + WorkspaceID + Access Roles
    activate API
    
    API->>Mongo: 2. Khởi tạo Document Record (Status: PROCESSING)
    
    API->>Docling: 3. Gửi File để Extract & Chunking
    activate Docling
    Docling-->>API: 4. Trả về Layout & List of Chunks
    deactivate Docling

    loop Cho mỗi Chunk
        API->>Embedding: 5. Tạo Vector Embedding cho Chunk content
        activate Embedding
        Embedding-->>API: 6. Trả về Vector (768 dimensions)
        deactivate Embedding
        
        API->>PgVector: 7. Lưu [chunk_id, vector, content]
        API->>Mongo: 8. Lưu [chunk_id, document_id, allowed_roles] vào document_chunks
    end

    API->>Mongo: 9. Cập nhật Document Record (Status: COMPLETED)
    API-->>Admin: 10. Trả về thông báo thành công
    deactivate API
```

## 2. Pipeline Retrieval (Truy xuất Thông tin an toàn)

Đường ống này đảm bảo rằng người dùng chỉ có thể lấy được context từ những chunk mà họ có quyền xem (RBAC - Role Based Access Control) trước khi đưa cho LLM tổng hợp câu trả lời.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant ChatAPI as Chat API (Spring Boot)
    participant Mongo as MongoDB<br/>(Policy Decision)
    participant Embedding as Local ONNX Model
    participant PgVector as PostgreSQL<br/>(Vector Search)
    participant LLM as LLM (Gemini/Local)

    User->>ChatAPI: 1. Đặt câu hỏi ("Báo cáo tài chính quý 3?") + User Token
    activate ChatAPI

    ChatAPI->>ChatAPI: 2. Decode Token -> Lấy [user_id, roles, workspace_id]

    ChatAPI->>Embedding: 3. Embed câu hỏi
    activate Embedding
    Embedding-->>ChatAPI: 4. Vector câu hỏi (768 dims)
    deactivate Embedding

    ChatAPI->>Mongo: 5. Query các chunk_id user được phép đọc <br/>(filter theo workspace_id & roles)
    activate Mongo
    Mongo-->>ChatAPI: 6. Trả về List<chunk_id> hợp lệ (Allowed IDs)
    deactivate Mongo

    ChatAPI->>PgVector: 7. Vector Search (Vector câu hỏi) <br/>KÈM PRE-FILTER: id IN (Allowed IDs)
    activate PgVector
    PgVector-->>ChatAPI: 8. Trả về Top K Chunks (Context)
    deactivate PgVector

    ChatAPI->>LLM: 9. Prompt: "Dựa vào Context sau, trả lời câu hỏi..."
    activate LLM
    LLM-->>ChatAPI: 10. Trả lời (Response)
    deactivate LLM

    ChatAPI-->>User: 11. Hiển thị câu trả lời
    deactivate ChatAPI
```

## 3. Điểm mạnh của kiến trúc
1. **Security-First (Bảo mật tuyệt đối):** Việc truy vấn MongoDB lấy danh sách `chunk_id` được phép đọc giúp hệ thống loại bỏ hoàn toàn khả năng Data Leakage. LLM sẽ không bao giờ nhìn thấy context thuộc về phòng ban khác.
2. **Hiệu năng cao:** Dù gọi 2 Database, nhưng MongoDB đọc Index cực nhanh, và việc truyền mảng `Allowed IDs` vào PostgreSQL làm bộ lọc (Pre-filter) giúp Vector DB giảm thiểu không gian tìm kiếm, tăng tốc độ tính toán khoảng cách Cosine.
3. **Mở rộng linh hoạt:** Sẵn sàng cho Phase 3 (GraphRAG) vì MongoDB hỗ trợ tốt dữ liệu dạng JSON lồng nhau và Aggregation `$graphLookup`.
