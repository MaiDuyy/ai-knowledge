# Báo cáo Tổng kết Giai đoạn 2: Tích hợp MongoDB và Xây dựng kiến trúc Dual-DB

## 1. Mục tiêu của Giai đoạn 2
Mục tiêu cốt lõi của Giai đoạn 2 là thiết lập hệ thống cơ sở dữ liệu MongoDB đóng vai trò làm "Gatekeeper" (Bộ lọc phân quyền - RBAC) hoạt động song song với cơ sở dữ liệu Vector (PgVector) hiện có. Cụ thể:
- Chuyển đổi thiết kế Schema từ Giai đoạn 1 thành mã nguồn Java thực tế.
- Bơm dữ liệu giả lập (Mock Data) để phục vụ quá trình Benchmark.
- Tích hợp MongoDB vào luồng Ingest (Upload và xử lý tài liệu) để mỗi khi có tài liệu mới, siêu dữ liệu phân quyền sẽ được lưu vào MongoDB song song với vector được lưu ở Postgres.

## 2. Các công việc đã thực hiện

### 2.1. Xây dựng Data Models (Entities)
Tạo thành công 3 Entity ánh xạ với MongoDB tại package `com.security.security.model.mongo`:
- `Workspace`: Quản lý không gian làm việc.
- `DocumentMeta`: Lưu trữ siêu dữ liệu của toàn bộ tài liệu (Metadata, Owner, Role).
- `DocumentChunk`: Lưu trữ từng mảnh cắt (Chunk) của tài liệu kèm theo quyền truy cập (`allowedRoles`).
  - Đã tích hợp `@CompoundIndex` cho `WorkspaceId` và `allowedRoles` để tối ưu hóa tốc độ truy vấn phân quyền sau này.

### 2.2. Xây dựng Data Access Layer (Repositories)
Thiết lập các interface kế thừa từ `MongoRepository` tại `com.security.security.repository.mongo`:
- `WorkspaceRepository`
- `DocumentMetaRepository`
- `DocumentChunkRepository` (Đã bổ sung các hàm tùy chỉnh như `findByWorkspaceIdAndAllowedRolesIn` và `deleteByDocumentId`).

### 2.3. Bơm dữ liệu (Data Seeding)
Viết lớp `MongoBenchmarkDataSeeder` tự động chạy khi khởi động ứng dụng để nạp các dữ liệu mẫu phức tạp vào MongoDB:
- Tạo 4 tài liệu Benchmark: *Project Nexus*, *HR Directory*, *Architecture Specs*, và *Financial Policy*.
- Mô phỏng cấu trúc phân quyền đa dạng (e.g. Tài liệu tài chính chỉ dành cho `ROLE_FINANCE_MANAGER`).

### 2.4. Tích hợp luồng Ingest (Dual-DB Architecture)
Can thiệp sâu vào luồng xử lý tài liệu gốc của hệ thống (PostgreSQL base) để biến nó thành luồng Dual-DB:
- **Tại `DocumentService`**: Mỗi khi lưu một tài liệu mới (Upload), hệ thống sẽ khởi tạo một bản sao siêu dữ liệu (`DocumentMeta`) và đẩy sang MongoDB. Khi xóa tài liệu, bản ghi bên MongoDB cũng bị xóa đồng bộ.
- **Tại `EmbeddingService`**: Trong quá trình cắt văn bản (Semantic Chunking) và tạo Vector, thay vì chỉ lưu vào PgVector, hệ thống giờ đây chạy một tiến trình song song lưu các `DocumentChunk` sang MongoDB. Mảng quyền `allowedRoles` được parse cẩn thận từ chuỗi sang `List<String>` để Indexing trên MongoDB hoạt động hiệu quả nhất.

## 3. Kết quả đạt được
- Hệ thống đã chính thức trở thành **kiến trúc Dual-DB**:
  - **PostgreSQL / PgVector**: Chịu trách nhiệm lưu trữ Vector thuần túy và nội dung thô để tính toán độ tương đồng (Cosine Similarity).
  - **MongoDB**: Chịu trách nhiệm quản lý cây phân quyền (RBAC) linh hoạt, metadata phức tạp ở tốc độ cao (nhờ Compound Index).
- Code đã được biên dịch thành công (`mvn clean compile` PASS), không gây xung đột giữa cấu hình kết nối JPA và Spring Data MongoDB.
- Frontend đã có thể tương tác trực tiếp với luồng mới mà không gặp rào cản kỹ thuật nào, dữ liệu được ghi nhận đồng thời ở cả hai Database một cách trơn tru.

## 4. Đề xuất cho Giai đoạn tiếp theo (Phase 3)
- Hiện tại, quá trình Ingest (Lưu dữ liệu) đã hoàn hảo ở cả hai phía (Postgres & Mongo). Tuy nhiên, hàm truy vấn (Search/Query) vẫn đang gọi thẳng vào PgVector.
- Trọng tâm của Phase 3 sẽ là: **Sửa đổi API `/search`**. Thay vì ném thẳng câu hỏi vào PgVector, Backend sẽ phải đi qua MongoDB trước (Gatekeeper) để lấy ra `danh sách các chunk_id hợp lệ mà user được phép xem`, sau đó mới mang danh sách này sang PgVector để tìm kiếm Vector. 
- Xây dựng cơ chế A/B Testing để đo lường độ trễ (Latency) giữa cách search cũ và cách search qua Gatekeeper MongoDB.
