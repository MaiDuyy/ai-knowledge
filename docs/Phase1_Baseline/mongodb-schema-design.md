# Thiết kế Schema MongoDB cho Phân quyền và Metadata

Tài liệu này định nghĩa cấu trúc dữ liệu trên MongoDB (Giai đoạn 2) nhằm giải quyết bài toán Multi-Tenancy và RBAC (Role-Based Access Control) cho hệ thống RAG. PostgreSQL/PgVector sẽ chỉ đóng vai trò chứa Vector và `chunk_id` tương ứng.

## 1. Collection `workspaces` (Không gian làm việc)
Đại diện cho một tổ chức, phòng ban hoặc dự án. Giúp cô lập dữ liệu (Data Isolation) ở cấp độ cao nhất.

```json
{
  "_id": "ObjectId('64f1a2b3...')",
  "name": "Khối Công Nghệ Thông Tin",
  "owner_id": "user_123",
  "created_at": "ISODate('2026-08-01T00:00:00Z')",
  "status": "ACTIVE",
  "settings": {
    "max_storage_mb": 5000,
    "allowed_file_types": ["pdf", "docx", "txt"]
  }
}
```

## 2. Collection `documents` (Tài liệu gốc)
Lưu trữ thông tin metadata của file được upload. Chứa cấu hình phân quyền ở cấp độ file.

```json
{
  "_id": "ObjectId('doc_888...')",
  "workspace_id": "ObjectId('64f1a2b3...')",
  "title": "Báo cáo tài chính Q3-2026.pdf",
  "file_path": "/uploads/finance/q3-2026.pdf",
  "owner_id": "user_456",
  "status": "COMPLETED", // PROCESSING, COMPLETED, FAILED
  "file_metadata": {
    "size_bytes": 1048576,
    "page_count": 45,
    "author": "Phòng Kế Toán"
  },
  // Khóa bảo mật: Chỉ những role này mới được quyền đọc file này
  "allowed_roles": ["ROLE_ADMIN", "ROLE_FINANCE_MANAGER"],
  "created_at": "ISODate('2026-08-10T10:00:00Z')"
}
```

## 3. Collection `document_chunks` (Phân mảnh tài liệu)
Khi IBM Docling bóc tách tài liệu, mỗi chunk sẽ được ánh xạ vào đây. Đây là nơi kiểm soát quyền truy cập chi tiết đến từng đoạn văn (Granular Access Control).

```json
{
  "_id": "ObjectId('chunk_999...')", // ID này sẽ khớp 1-1 với ID trong PgVector
  "document_id": "ObjectId('doc_888...')",
  "workspace_id": "ObjectId('64f1a2b3...')",
  "chunk_index": 12,
  "chunk_type": "TABLE", // TEXT, TABLE, IMAGE_CAPTION
  "content_summary": "Bảng tổng hợp doanh thu tháng 9", // Không chứa vector
  
  // Được kế thừa từ Document, nhưng có thể ghi đè (override) 
  // Ví dụ: Cả báo cáo cho phép ROLE_EMPLOYEE đọc, nhưng chunk chứa bảng lương thì chỉ ROLE_MANAGER
  "allowed_roles": ["ROLE_ADMIN", "ROLE_FINANCE_MANAGER"],
  
  "metadata": {
    "page_number": 5,
    "bounding_box": [100, 200, 400, 500] // Tọa độ trong PDF gốc
  },
  "created_at": "ISODate('2026-08-10T10:05:00Z')"
}
```

## 4. Chiến lược Đánh Index (Indexing Strategy) để Tối ưu Truy vấn

Vì hệ thống dùng MongoDB làm "Cổng gác" (Gatekeeper) để filter ra danh sách `chunk_id` trước khi đưa sang PostgreSQL, tốc độ truy vấn ở MongoDB phải ở mức **mili-giây (ms)**.

Các Index cần tạo:

1. **Index cho Truy vấn Phân quyền (Filter Context):**
   ```javascript
   // Tìm kiếm nhanh các chunk thuộc workspace và role cụ thể
   db.document_chunks.createIndex({ workspace_id: 1, allowed_roles: 1 })
   ```

2. **Index cho Quản lý Document:**
   ```javascript
   // Lấy danh sách chunk của một document (khi muốn xóa/update file)
   db.document_chunks.createIndex({ document_id: 1, chunk_index: 1 })
   ```

## 5. Ví dụ Query (Cách hệ thống hoạt động thực tế)

Khi User có `ROLE_EMPLOYEE` thuộc `workspace X` đặt câu hỏi, Backend sẽ query MongoDB:

```javascript
// Query lấy danh sách ID hợp lệ
const validChunks = db.document_chunks.find(
  {
    workspace_id: ObjectId("X"),
    allowed_roles: { $in: ["ROLE_EMPLOYEE"] }
  },
  { _id: 1 } // Chỉ lấy cột _id để làm mảng pre-filter cho PgVector
).toArray();

const allowedIds = validChunks.map(c => c._id.toString());
// Trả allowedIds về cho Spring Boot để truyền tiếp sang PgVector
```
