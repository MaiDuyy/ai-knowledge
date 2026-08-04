# Báo cáo Tổng kết Giai đoạn 1 (Phase 1: Baseline & Kiến trúc)

**Dự án:** Thiết kế và hiện thực đường ống pipeline xử lý tài liệu đa tầng kết hợp truy xuất thông tin phân quyền dựa trên RAG.
**Mục tiêu Giai đoạn 1:** Xây dựng nền tảng kiến trúc hạ tầng, thiết kế Database phục vụ cho cơ chế Dual-DB (MongoDB + PgVector) và chuẩn bị Framework đánh giá (Benchmark).

---

## 1. Kết quả đạt được

Trong Giai đoạn 1 (kéo dài 1 tuần), nhóm đã hoàn thành xuất sắc 100% các hạng mục đề ra:

### 1.1. Về mặt Thiết kế Kiến trúc (Architecture & Design)
- **Sơ đồ Pipeline ETL & Truy xuất:** Đã hoàn thiện thiết kế luồng xử lý tài liệu đa tầng. Luồng này mô tả chi tiết cách hệ thống Ingest (sử dụng IBM Docling và mô hình ONNX `multilingual-e5-base` chạy local) và cách hệ thống Retrieval hoạt động song song giữa MongoDB (để lọc quyền) và PgVector (để tìm kiếm ngữ nghĩa). [Xem tài liệu đính kèm](file:///D:/CNM_Mobile/docs/Phase1_Baseline/current-etl-pipeline.md)
- **Thiết kế Schema MongoDB:** Đã định nghĩa chuẩn xác mô hình dữ liệu cho cơ chế RBAC (Role-Based Access Control) bao gồm các collections: `workspaces`, `documents`, và `document_chunks`. Kiến trúc này đảm bảo loại bỏ hoàn toàn nguy cơ rò rỉ dữ liệu chéo giữa các phòng ban. [Xem tài liệu đính kèm](file:///D:/CNM_Mobile/docs/Phase1_Baseline/mongodb-schema-design.md)

### 1.2. Về mặt Hạ tầng Kỹ thuật (Infrastructure)
- **Docker Compose:** Tích hợp thành công MongoDB Community 7.0 vào file `docker-compose.yml` để khởi chạy cùng Redis, NATS và PgVector.
- **Spring Boot Config:** Thiết lập kết nối thành công từ Backend (`ai-knowledge` - Java 21) đến MongoDB thông qua `spring-boot-starter-data-mongodb` và class tự động test kết nối `MongoConfig.java`.

### 1.3. Về mặt Đánh giá Chất lượng (Benchmark)
- **Benchmark Design:** Xây dựng thành công 20 câu hỏi truy vấn bao phủ 4 nhóm chính: Truy vấn thực tế, Truy vấn suy luận, Truy vấn ảo giác (Negative Test), và Truy vấn bảo mật. Các tiêu chí đánh giá khắt khe được đưa ra như Context Precision (>80%), Faithfulness (>95%), và Security Isolation (100%). [Xem bộ câu hỏi](file:///D:/CNM_Mobile/docs/Phase1_Baseline/benchmark-design.md)
- **Công cụ đo lường:** Đã phát triển class `BenchmarkLogger.java` để theo dõi và ghi log số liệu Latency cũng như độ chính xác (Top-K) của từng truy vấn.
- **Dataset mẫu:** Tạo bộ dữ liệu mẫu trong `benchmark-docs/` để phục vụ cho quá trình test.

---

## 2. Quản lý Phiên bản (Version Control - Git)

Việc phát triển trong Phase 1 đã tuân thủ nghiêm ngặt quy trình làm việc nhóm 2 người (Git Flow):
- **Dev 1 (Nền tảng):** Thực hiện commit hạ tầng cấu hình MongoDB và Spring Data. Đã merge từ nhánh `feature/mongodb-setup`.
- **Dev 2 (Kiểm thử):** Thực hiện commit bộ dữ liệu Benchmark và các thiết kế Pipeline. Đã merge từ nhánh `feature/benchmark-setup`.
*(Các thay đổi đã được Push thành công lên repository GitHub của dự án).*

---

## 3. Khó khăn và Hướng giải quyết
- **Khó khăn:** Quản lý file nhúng mô hình ONNX (`multilingual-e5-base`) có dung lượng lớn (>1GB) gặp vấn đề khi Push lên GitHub.
- **Giải quyết:** Nhóm đã linh hoạt áp dụng kỹ thuật loại trừ `.gitignore` đối với các tệp nhị phân `.onnx` và `.bin`, bảo đảm mã nguồn vẫn nhẹ nhàng và gọn gàng, đồng thời mô hình sẽ được tải trực tiếp khi Backend khởi động thông qua DJL library.

---

## 4. Kết luận & Hướng đi Giai đoạn 2
Giai đoạn 1 đã cung cấp một bản lề vững chắc cho toàn bộ dự án Khóa luận. Cấu trúc Dual-DB đã được chứng minh khả năng tồn tại qua các kết nối kiểm thử. 

Ở **Giai đoạn 2 (Tích hợp MongoDB & ACL)**, nhóm sẽ hiện thực hóa (coding) các thiết kế trên giấy này vào Spring Boot:
1. Ánh xạ (Map) thiết kế MongoDB Schema thành các `Entity` trong Java.
2. Viết các `Repository` và `Service` thao tác với MongoDB.
3. Chạy `Data Seeder` để bơm dữ liệu mẫu và chuẩn bị sẵn sàng cho Giai đoạn 3 (Truy xuất AI thực tế).
