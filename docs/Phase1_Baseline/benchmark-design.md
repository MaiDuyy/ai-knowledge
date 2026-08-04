# Kế Hoạch Benchmark Hệ Thống RAG (20 Câu Hỏi)

Tài liệu này liệt kê 20 câu hỏi kỹ thuật dùng để chạy bài test Benchmark trên hệ thống RAG nhằm đánh giá hiệu năng và độ chính xác của Vector Search.

## Nhóm 1: Truy vấn thực tế (Fact-based Queries)
1. "Phiên bản Java nào được sử dụng trong hệ thống backend KTMP Nexus?"
2. "Spring Boot framework đang dùng version bao nhiêu?"
3. "Database nào đóng vai trò quản lý phân quyền (RBAC) trong kiến trúc Dual-DB?"
4. "Mô hình embedding nào đang được sử dụng để chuyển đổi vector?"
5. "Cổng giao tiếp gRPC giữa các service chạy ở port nào?"

## Nhóm 2: Truy vấn suy luận (Reasoning Queries)
6. "Tại sao lại cần phải kết hợp MongoDB và PostgreSQL trong cùng một luồng RAG?"
7. "Điều gì xảy ra nếu user không có quyền đọc (role mismatch) ở một tài liệu cụ thể?"
8. "Quy trình xử lý file PDF của IBM Docling diễn ra như thế nào?"
9. "Làm thế nào để hệ thống ngăn chặn data leak giữa các phòng ban khác nhau?"
10. "Tại sao không lưu Vector thẳng vào MongoDB mà phải tách sang PgVector?"

## Nhóm 3: Truy vấn không có thông tin (Negative Queries - Test Ảo giác)
11. "Công ty có hỗ trợ nghỉ phép thứ Bảy, Chủ nhật không?" (Không có trong doc)
12. "Lương cơ bản của vị trí Senior Developer là bao nhiêu?" (Không có trong doc)
13. "Ai là người sáng lập ra công ty?" (Không có trong doc)
14. "Quy trình xin nghỉ thai sản như thế nào?" (Không có trong doc)
15. "Sử dụng RabbitMQ thay cho NATS có được không?" (Hỏi về kiến trúc không được mô tả)

## Nhóm 4: Truy vấn so sánh và bảo mật (Security & Relational)
16. "User có `ROLE_EMPLOYEE` có được xem Báo cáo tài chính quý 3 không?"
17. "So sánh ưu và nhược điểm của việc sử dụng ONNX model local so với gọi API OpenAI?"
18. "Chỉ số độ trễ P99 Latency của hệ thống hiện tại là bao nhiêu?"
19. "Có bao nhiêu bộ sưu tập (collections) được khởi tạo trong MongoDB?"
20. "Nếu server NATS bị sập, pipeline ingest file có tiếp tục hoạt động được không?"

---
**Tiêu chí đánh giá (Metrics):**
- **Context Precision:** 80%+ (Top-K trả về phải chứa đáp án đúng).
- **Faithfulness:** 95%+ (Câu trả lời của LLM không tự bịa thông tin ngoài Context).
- **Security Isolation:** 100% (Phải chặn tuyệt đối nếu User không có Role).
