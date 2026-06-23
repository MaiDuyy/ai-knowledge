# BÁO CÁO KẾT QUẢ KIỂM THỬ BENCHMARK (SECWIKI-BENCH)

Tài liệu này tổng hợp kết quả chạy thử nghiệm Benchmark tự động cho dịch vụ **ai-knowledge**, bao gồm kiểm thử bảo mật phân quyền RAG, độ phủ tìm kiếm mở rộng đồ thị (Graph Hop Recall), và hiệu năng luồng ảo Java 21 (Virtual Threads).

Chạy kiểm thử ngày: Tue Jun 23 11:56:56 ICT 2026

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
| Tổng thời gian xử lý | 1.5534 s | 0.9433 s | **Giảm 39.27%** |
| Thông lượng trung bình (Throughput) | 321.87 RPS | 530.04 RPS | **Tăng 64.67%** |
| Platform Threads khởi tạo | 51 threads | 9 threads | **Giảm 82.35%** |
| Ước tính bộ nhớ tiêu thụ (RAM) | 51.00 MB | 9.98 MB | **Giảm 80.44%** |

### Biểu đồ so sánh trực quan (Text chart):

**1. Throughput (Requests Per Second - RPS - Càng cao càng tốt):**
```
Platform Threads: [████████████████████████                ] 321.87 RPS
Virtual Threads : [████████████████████████████████████████] 530.04 RPS (+64.67%)
```

**2. Memory Consumption (MB - Càng thấp càng tốt):**
```
Platform Threads: [████████████████████████████████████████] 51.00 MB
Virtual Threads : [████████                                ] 9.98 MB (-80.44%)
```

- **Kết luận**: Sử dụng Virtual Threads trên Java 21 giúp hệ thống cải thiện đáng kể khả năng phục vụ truy cập đồng thời lớn, giảm thiểu rủi ro cạn kiệt tài nguyên RAM và cải thiện độ trễ phản hồi tổng thể của RAG pipeline.
