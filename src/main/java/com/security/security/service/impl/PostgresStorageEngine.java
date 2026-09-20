package com.security.security.service.impl;

import com.security.security.dto.StorageSearchHit;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.EmbeddingRepository;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;
import com.security.security.service.KnowledgeStorageEngine;
import com.security.security.service.PermissionUtils;
import com.security.security.service.ScopeNormalizer;
import com.security.security.service.StorageEngineType;
import com.security.security.service.VectorMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL baseline storage engine for trade-off benchmarks.
 * Uses JPA tables + in-app cosine over stored embedding JSON (portable without pgvector ANN in tests).
 */
@Service
@Profile({"postgres", "mongodb-benchmark", "default", "dev", "test"})
@RequiredArgsConstructor
@Slf4j
public class PostgresStorageEngine implements KnowledgeStorageEngine {

    private final DocumentRepository documentRepository;
    private final EmbeddingRepository embeddingRepository;
    private final WikiPageRepository wikiPageRepository;
    private final WikiLinkRepository wikiLinkRepository;

    @Override
    public StorageEngineType getEngineType() {
        return StorageEngineType.POSTGRES;
    }

    @Override
    @Transactional
    public void storeDocument(Document document, List<Embedding> chunks) {
        // IDENTITY strategy: let PostgreSQL assign PKs (benchmark may pass synthetic ids)
        Long correlationId = document.getId();
        document.setId(null);
        Document saved = documentRepository.save(document);
        if (correlationId != null) {
            // keep original id available for dual-engine correlation logs
            log.debug("[PostgresStorageEngine] stored document id={} correlationId={}", saved.getId(), correlationId);
        }
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (Embedding chunk : chunks) {
            chunk.setId(null);
            chunk.setDocumentId(saved.getId());
            if (chunk.getWorkspaceId() == null) {
                chunk.setWorkspaceId(ScopeNormalizer.normalizeWorkspace(saved.getWorkspaceId()));
            }
        }
        embeddingRepository.saveAll(chunks);
        saved.setChunkCount(chunks.size());
        documentRepository.save(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StorageSearchHit> similaritySearch(
            String queryText,
            List<Double> queryEmbedding,
            int limit,
            RAGQueryPayload.UserPermissionContext permissionContext) {

        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("[PostgresStorageEngine] Empty query embedding — returning empty results");
            return List.of();
        }

        // Load candidate documents then embeddings (relational join path)
        List<Document> docs = documentRepository.findAll();
        Map<Long, Document> accessibleDocs = new HashMap<>();
        for (Document doc : docs) {
            SecurityClassification sc = doc.getSecurityClassification() != null
                    ? doc.getSecurityClassification()
                    : SecurityClassification.INTERNAL;
            if (PermissionUtils.isResourceAccessible(
                    doc.getWorkspaceId(),
                    doc.getDepartmentId(),
                    doc.getAllowedRoles(),
                    sc,
                    permissionContext)) {
                accessibleDocs.put(doc.getId(), doc);
            }
        }

        if (accessibleDocs.isEmpty()) {
            return List.of();
        }

        List<StorageSearchHit> hits = new ArrayList<>();
        for (Long docId : accessibleDocs.keySet()) {
            List<Embedding> embeddings = embeddingRepository.findByDocumentId(docId);
            Document doc = accessibleDocs.get(docId);
            for (Embedding emb : embeddings) {
                List<Double> vector = VectorMath.parseEmbeddingJson(emb.getEmbedding());
                double score = VectorMath.cosineSimilarity(queryEmbedding, vector);
                hits.add(StorageSearchHit.builder()
                        .documentId(docId)
                        .chunkIndex(emb.getChunkIndex())
                        .chunkTitle(emb.getChunkTitle())
                        .text(emb.getChunkText())
                        .score(score)
                        .tokenCount(emb.getTokenCount())
                        .metadata(Map.of(
                                "workspaceId", ScopeNormalizer.normalizeWorkspace(doc.getWorkspaceId()),
                                "departmentId", ScopeNormalizer.normalizeDepartment(doc.getDepartmentId()),
                                "allowedRoles", doc.getAllowedRoles() != null ? doc.getAllowedRoles() : "ALL",
                                "engine", "POSTGRES"
                        ))
                        .build());
            }
        }

        return hits.stream()
                .sorted(Comparator.comparingDouble(StorageSearchHit::getScore).reversed())
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void storeWikiPage(WikiPage page, List<String> outboundSlugs) {
        page.setId(null);
        WikiPage saved = wikiPageRepository.save(page);
        if (outboundSlugs == null || outboundSlugs.isEmpty()) {
            return;
        }
        List<WikiLink> links = outboundSlugs.stream()
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .map(slug -> WikiLink.builder()
                        .fromPageId(saved.getId())
                        .toSlug(slug.trim())
                        .build())
                .toList();
        wikiLinkRepository.saveAll(links);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> graphReachable(String startSlug, int maxDepth) {
        List<WikiPage> pages = wikiPageRepository.findAll();
        Map<String, Long> slugToId = new HashMap<>();
        Map<Long, String> idToSlug = new HashMap<>();
        for (WikiPage p : pages) {
            slugToId.put(p.getSlug(), p.getId());
            idToSlug.put(p.getId(), p.getSlug());
        }
        Long startId = slugToId.get(startSlug);
        if (startId == null) {
            return List.of();
        }

        Map<Long, List<Long>> adj = new HashMap<>();
        List<WikiLink> links = wikiLinkRepository.findAll();
        for (WikiLink link : links) {
            Long toId = slugToId.get(link.getToSlug());
            if (toId != null) {
                adj.computeIfAbsent(link.getFromPageId(), k -> new ArrayList<>()).add(toId);
            }
        }

        Set<String> reachable = new HashSet<>();
        // BFS in-memory (mirrors JGraphT load-and-traverse complexity class)
        List<Long> frontier = new ArrayList<>();
        frontier.add(startId);
        Set<Long> visited = new HashSet<>();
        visited.add(startId);
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<Long> next = new ArrayList<>();
            for (Long node : frontier) {
                for (Long neigh : adj.getOrDefault(node, List.of())) {
                    if (visited.add(neigh)) {
                        reachable.add(idToSlug.get(neigh));
                        next.add(neigh);
                    }
                }
            }
            frontier = next;
        }
        return new ArrayList<>(reachable);
    }
}
