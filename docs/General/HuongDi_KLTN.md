**Tên đề tài (đã chốt)**: Thiết kế và hiện thực đường ống pipline xử lý tài liệu đa tầng kết hợp truy xuất thông tin phân quyền dựa trên RAG trong quản lý tri thức doanh nghiệp

**Góp ý giáo viên** :Thầy hiểu về việc em chọn PostgreSQL ngay từ đầu để tận dụng hệ sinh thái Spring AI và sự ổn định của RDBMS. Đó là cách tiếp cận an toàn, thầy chỉ muốn các em đào sâu hơn ở góc độ kiến trúc thay vì chỉ chọn cái gì 'có sẵn':

Việc em giữ lại PostgreSQL làm baseline là cho phép các em làm một bài toán Trade-off Analysis có giá trị cho khóa luận. Thay vì phải chật vật tìm benchmark không phù hợp với ngữ cảnh dữ liệu riêng biệt của em, chính việc so sánh hiệu năng giữa hai cách tiếp cận này sẽ là benchmark thuyết phục nhất. Để làm điều đó, các em cần chú ý hai điểm này:

1- Về tư duy mô hình hóa dữ liệu: NoSQL chỉ là nơi chứa 'document rời'. Hãy nhìn vào cấu trúc phân cấp (Document -> Chunks -> Wiki) của em: đây chính là 'đất diễn' cho Document Model. Việc thiết kế MongoDB theo hướng Embedding (nhúng tài liệu con vào tài liệu cha) hoặc Referencing sẽ loại bỏ hoàn toàn các phép JOIN phức tạp vốn là điểm yếu của SQL khi dữ liệu đồ thị phình to.
2- Về khả năng truy vấn và phân quyền: thử tìm hiểu sâu hơn về kiến trúc Vector Search trên MongoDB. Các em sẽ thấy việc lọc metadata theo workspace hay quyền hạn trên cấu trúc JSON lồng nhau (nested JSON) linh hoạt và mạnh hơn rất nhiều so với việc ép kiểu trong các bảng SQL.

Việc build thêm module MongoDB chính là điểm sáng như một bài thực nghiệm đối chứng trong khóa luận của mình (hoàn toàn có thể viết báo):
- Phần SQL (pgvector) làm baseline về tính nhất quán.
- Phần NoSQL (MongoDB Vector Search + $graphLookup) làm điểm nhấn về khả năng mở rộng (Scalability) cho tri thức đồ thị.

Kết quả so sánh giữa hai cách tiếp cận này sẽ là minh chứng rõ nhất cho việc tại sao các em chọn công nghệ, chứ không phải vì 'framework có hỗ trợ'. Đó chính là tư duy mới.

**Tài liệu về mongodb**
MongoDB (Vector Search + RAG)	MongoDB Vector Search Overview	Tổng quan $vectorSearch, ANN/ENN, filter metadata, hybrid search
	Retrieval-Augmented Generation (RAG) with MongoDB	Hướng dẫn đầy đủ pipeline RAG (ingest → retrieve → generate) dùng Atlas Vector Search
	Build a Local RAG Implementation	Tutorial chạy RAG local (không cần API key), dùng embedding local + Vector Search
	Run Vector Search Queries	Cú pháp $vectorSearch, pre-filter, numCandidates, score
	Multi-Tenant Architecture for Vector Search	Pre-filter theo tenant_id, Flat Indexes cho multi-tenant, view-based isolation
	Vector Search over Nested Embeddings (Public Preview 2026)	Tìm vector trong nested array/subdocument (rất phù hợp chunk bên trong document)
		
GraphRAG & $graphLookup (Knowledge Graph)	Tài liệu	Nội dung chính
	GraphRAG with MongoDB and LangChain	Official tutorial dùng MongoDBGraphStore + $graphLookup để làm GraphRAG
	$graphLookup (aggregation)	Tài liệu gốc về recursive graph traversal, maxDepth, restrictSearchWithMatch
	Depth-first Hybrid Search for GraphRAG	Kết hợp vector search + $graphLookup depth-first để tạo context cho LLM
	Knowledge Graph RAG Using MongoDB (Medium – MongoDB)	Schema entity + relationship, pipeline $graphLookup thực tế
	GraphRAG with MongoDB Atlas: Integrating Knowledge Graphs with LLMs	Blog chính thức về LangChain integration cho GraphRAG
		
	Tài liệu	Nội dung chính
Spring AI + MongoDB	Building a RAG Application with Spring Boot, Spring AI, MongoDB Atlas Vector Search, and OpenAI (InfoQ)	Hướng dẫn đầy đủ Spring Boot + Spring AI + MongoDB Vector Search