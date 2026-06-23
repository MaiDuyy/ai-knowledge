# BÁO CÁO KẾT QUẢ KIỂM THỬ BENCHMARK (SECWIKI-BENCH)

Tài liệu này tổng hợp kết quả chạy thử nghiệm Benchmark tự động cho dịch vụ **ai-knowledge**, bao gồm kiểm thử bảo mật phân quyền RAG, độ phủ tìm kiếm mở rộng đồ thị (Graph Hop Recall), hiệu năng luồng ảo Java 21 (Virtual Threads), và độ chính xác của đường ống biên dịch tri thức (ETL & Wiki Graph Accuracy).

Chạy kiểm thử ngày: Tue Jun 23 12:47:32 ICT 2026

---

## 1. Kiểm thử Bảo mật Phân quyền RAG (Access Control Evaluation)

Hệ thống đo lường độ rò rỉ dữ liệu **SLR (Security Leakage Rate)** và độ phủ quyền truy cập hợp lệ **AR (Authorization Recall)** qua các ngữ cảnh bảo mật người dùng khác nhau.

| Ngữ cảnh người dùng (Context) | Security Leakage Rate (SLR) | Authorization Recall (AR) | Trạng thái (Status) |
| :--- | :---: | :---: | :---: |
| IT Member User (`user-member-it`) | 0.00% | 100.00% | ĐẠT (SLR = 0%, AR = 100%) |
| IT Head User (`user-head-it`) | 0.00% | 100.00% | ĐẠT (SLR = 0%, AR = 100%) |
| External Guest User (`user-guest`) | 0.00% | 100.00% | ĐẠT (SLR = 0%, AR = 100%) |

- **Tiêu chuẩn nghiệm thu**: SLR phải đạt đúng **0.0%** (không rò rỉ thông tin phòng ban khác/cấp cao hơn) và AR đạt **100.0%** (cho phép truy cập đầy đủ tài liệu được phân quyền).

---

## 2. Kiểm thử Mở rộng Đồ thị Wiki (Graph Hop Recall - GHR)

Đo lường hiệu quả tìm kiếm thông tin liên kết đa bước nhảy giữa **RAG mở rộng đồ thị** (Wiki Graph Context Expansion) và **Vector Search phẳng** thông thường.

| Kịch bản truy vấn (Scenario) | Flat Vector Search GHR | Graph Expanded Search GHR | Trạng thái (Status) |
| :--- | :---: | :---: | :---: |
| IT Onboarding Query (Hop 2) | 0.00% | 100.00% | ĐẠT (>85% vs <20%) |
| IT Multihop Query (Hop 3) | 0.00% | 100.00% | ĐẠT (>85% vs <20%) |
| HR Hiring Query (Hop 2) | 0.00% | 100.00% | ĐẠT (>85% vs <20%) |

- **Nhận xét**: Flat Vector Search thất bại hoàn toàn trong việc tìm kiếm các thông tin liên kết sâu hơn (Hop 2 & 3) do độ tương đồng cosine phẳng giảm mạnh khi chủ đề chuyển hướng. RAG mở rộng đồ thị giải quyết triệt để vấn đề này, đạt độ phủ thu hồi **100.0%**.

---

## 3. Kiểm thử Concurrency & Hiệu năng (Performance Benchmarking)

So sánh thông lượng, độ trễ và dung lượng bộ nhớ tiêu thụ giữa luồng truyền thống **Platform Threads** và luồng ảo **Virtual Threads (Java 21)** dưới tải đồng thời **500 requests**.

| Chỉ số đo lường (Metric) | Platform Threads (Pool=50) | Virtual Threads (Java 21) | Tỷ lệ cải thiện |
| :--- | :---: | :---: | :---: |
| Tổng thời gian xử lý | 1.5806 s | 1.0823 s | **Giảm 31.53%** |
| Thông lượng trung bình (Throughput) | 316.34 RPS | 461.98 RPS | **Tăng 46.04%** |
| Platform Threads khởi tạo | 51 threads | 9 threads | **Giảm 82.35%** |
| Ước tính bộ nhớ tiêu thụ (RAM) | 51.00 MB | 9.98 MB | **Giảm 80.44%** |

### Biểu đồ so sánh trực quan (Text chart):

**1. Throughput (Requests Per Second - RPS - Càng cao càng tốt):**
```
Platform Threads: [███████████████████████████             ] 316.34 RPS
Virtual Threads : [████████████████████████████████████████] 461.98 RPS (+46.04%)
```

**2. Memory Consumption (MB - Càng thấp càng tốt):**
```
Platform Threads: [████████████████████████████████████████] 51.00 MB
Virtual Threads : [████████                                ] 9.98 MB (-80.44%)
```

---

## 4. Đánh giá Độ chính xác của Biên dịch Tri thức (ETL & Wiki Graph Accuracy)

Đo lường khả năng trích xuất cấu trúc văn bản thô (PDF/DOCX) sang định dạng máy đọc và biên dịch liên kết đồ thị tri thức.

*   **Table Cell Retention Rate (TCRR - Độ bảo toàn cấu trúc bảng biểu)**:
    *   **Docling (Layout-Aware AI)**: **100.00%** (Nhận diện chính xác 20/20 ô bảng lưới phức tạp).
    *   **Apache Tika (Plain OCR/Text)**: **0.00%** (Làm vỡ dòng, gộp cột khiến dữ liệu mất cấu trúc).
*   **WikiLinks Compiler (Độ chính xác bộ biên dịch liên kết tri thức)**:
    *   **Precision (Độ chính xác)**: **100.00%** (100% liên kết được sinh khớp chuẩn tài liệu).
    *   **Recall (Độ phủ)**: **100.00%** (Trích xuất đầy đủ 100% các liên kết do tác giả chỉ định).

- **Kết luận**: Sử dụng Docling kết hợp thuật toán biên dịch WikiLinks bằng Regex & toán học đồ thị JGraphT giúp hệ thống biên dịch tri thức hoàn toàn chính xác cấu trúc tài liệu gốc và tự động phát hiện quan hệ liên kết sâu, làm nền tảng vững chắc cho RAG.
