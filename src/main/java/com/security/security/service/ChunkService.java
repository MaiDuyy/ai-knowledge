package com.security.security.service;

import com.security.security.dto.ChunkDTO;
import com.security.security.dto.ChunkSearchResponse;
import com.security.security.dto.DocumentChunksResponse;
import com.security.security.dto.DocumentStatsDTO;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.exception.ApiException;
import com.security.security.repository.EmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ChunkService — LangChain Spring AI pattern:
 *
 *  ┌─────────────────────────────────────────────────────────────┐
 *  │  getDocumentChunks / getChunk / getDocumentStats            │
 *  │    → Embedding table (JPA/DB)                               │
 *  │    Lý do: DB là nguồn sự thật cho CRUD, có thứ tự, đầy đủ  │
 *  ├─────────────────────────────────────────────────────────────┤
 *  │  searchChunks                                               │
 *  │    → VectorStore.similaritySearch()                         │
 *  │    Lý do: ANN index chỉ dùng cho semantic retrieval         │
 *  └─────────────────────────────────────────────────────────────┘
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChunkService {

    private final VectorStore vectorStore;
    private final DocumentService documentService;
    private final EmbeddingRepository embeddingRepository;

    // ─────────────────────────────────────────────────────────────
    // 1. Lấy tất cả chunks của document  →  DB
    // ─────────────────────────────────────────────────────────────

    public DocumentChunksResponse getDocumentChunks(Long documentId, String userId) {
        Document document = documentService.getDocument(documentId, userId);

        List<ChunkDTO> chunks = embeddingRepository
                .findByDocumentIdOrderByChunkIndex(documentId)
                .stream()
                .map(this::toChunkDTO)
                .collect(Collectors.toList());

        log.info("Loaded {} chunks from DB for document={}", chunks.size(), documentId);

        return DocumentChunksResponse.builder()
                .documentId(documentId)
                .fileName(document.getFileName())
                .totalChunks(chunks.size())
                .chunks(chunks)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Lấy chunk theo index  →  DB
    // ─────────────────────────────────────────────────────────────

    public ChunkDTO getChunk(Long documentId, Integer chunkIndex, String userId) {
        documentService.getDocument(documentId, userId);

        return embeddingRepository
                .findByDocumentIdOrderByChunkIndex(documentId)
                .stream()
                .filter(e -> chunkIndex.equals(e.getChunkIndex()))
                .findFirst()
                .map(this::toChunkDTO)
                .orElseThrow(() -> new ApiException("Chunk not found: index=" + chunkIndex));
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Thống kê document  →  DB
    // ─────────────────────────────────────────────────────────────

    public DocumentStatsDTO getDocumentStats(Long documentId, String userId) {
        Document document = documentService.getDocument(documentId, userId);

        List<Embedding> embeddings = embeddingRepository.findByDocumentId(documentId);

        int totalTokens = embeddings.stream()
                .mapToInt(e -> e.getTokenCount() != null ? e.getTokenCount() : 0)
                .sum();
        int totalChars = embeddings.stream()
                .mapToInt(e -> e.getCharCount() != null ? e.getCharCount() : 0)
                .sum();
        int count = embeddings.size();

        return DocumentStatsDTO.builder()
                .documentId(documentId)
                .fileName(document.getFileName())
                .status(String.valueOf(document.getStatus()))
                .totalChunks(count)
                .totalTokens(totalTokens)
                .totalCharacters(totalChars)
                .avgTokensPerChunk(count > 0 ? totalTokens / count : 0)
                .avgCharsPerChunk(count > 0 ? totalChars / count : 0)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // 4. Semantic search  →  VectorStore (đúng mục đích ANN)
    // ─────────────────────────────────────────────────────────────

    public ChunkSearchResponse searchChunks(String query, Integer topK, Double minSimilarity, String workspaceId, String userId) {
        log.info("Semantic search: query='{}', topK={}, threshold={}, workspaceId={}", query, topK, minSimilarity, workspaceId);

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(minSimilarity);

        if (workspaceId != null && !workspaceId.isBlank() && !"default-workspace".equals(workspaceId)) {
            builder.filterExpression("workspaceId == '" + workspaceId + "'");
        }

        SearchRequest request = builder.build();

        List<ChunkSearchResponse.ChunkSearchResult> results = vectorStore
                .similaritySearch(request)
                .stream()
                .map(doc -> ChunkSearchResponse.ChunkSearchResult.builder()
                        .documentId(parseLong(doc.getMetadata().get("documentId")))
                        .fileName(getString(doc.getMetadata().get("fileName"), "Unknown"))
                        .chunkIndex(parseInt(doc.getMetadata().get("chunkIndex")))
                        .chunkTitle(getString(doc.getMetadata().get("chunkTitle"), "General"))
                        .text(doc.getText())
                        .similarity(doc.getScore() != null ? doc.getScore() : 0.0)
                        .tokenCount(parseInt(doc.getMetadata().get("tokenCount")))
                        .build())
                .collect(Collectors.toList());

        log.info("Found {} results for query='{}'", results.size(), query);

        return ChunkSearchResponse.builder()
                .query(query)
                .totalResults(results.size())
                .chunks(results)
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private ChunkDTO toChunkDTO(Embedding e) {
        return ChunkDTO.builder()
                .id(e.getId())
                .chunkIndex(e.getChunkIndex())
                .chunkTitle(e.getChunkTitle() != null ? e.getChunkTitle() : "Chunk #" + e.getChunkIndex())
                .text(e.getChunkText())
                .tokenCount(e.getTokenCount())
                .charCount(e.getCharCount())
                .similarity(null)
                .createdAt(e.getCreatedAt())
                .build();
    }

    private long parseLong(Object val) {
        try { return val != null ? Long.parseLong(val.toString()) : 0L; }
        catch (NumberFormatException ex) { return 0L; }
    }

    private int parseInt(Object val) {
        try { return val != null ? Integer.parseInt(val.toString()) : 0; }
        catch (NumberFormatException ex) { return 0; }
    }

    private String getString(Object val, String fallback) {
        return val != null ? val.toString() : fallback;
    }
}
