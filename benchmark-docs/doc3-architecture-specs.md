# Đặc tả Kiến trúc Hạ tầng (Architecture Specs)

## 1. Thông số Kỹ thuật
Kiến trúc Dual-DB được ứng dụng trong công ty là sự kết hợp giữa:
- **MongoDB Community 7.0:** Quản lý siêu dữ liệu (Metadata) và Access Control List (ACL).
- **PostgreSQL 15 (PgVector):** Lưu trữ không gian vector 768 chiều.

## 2. Thông số Hoạt động
Hệ thống sử dụng một server chuyên dụng có GPU để chạy nhúng vector. 
Chi phí để duy trì toàn bộ hạ tầng Dual-DB này trên môi trường Cloud được tính toán cứng là **$500/tháng**. Chi phí này chưa bao gồm phí bảo trì phần mềm và các tool theo dõi.
