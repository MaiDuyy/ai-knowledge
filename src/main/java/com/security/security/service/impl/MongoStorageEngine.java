package com.security.security.service.impl;

import com.security.security.dto.StorageSearchHit;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.mongo.MongoChunk;
import com.security.security.entity.mongo.MongoDocument;
import com.security.security.entity.mongo.MongoFlatChunk;
import com.security.security.entity.mongo.MongoWikiPage;
import com.security.security.repository.mongo.MongoDocumentRepository;
import com.security.security.repository.mongo.MongoFlatChunkRepository;
import com.security.security.repository.mongo.MongoWikiPageRepository;
import com.security.security.service.KnowledgeStorageEngine;
import com.security.security.service.MongoRbacFilterBuilder;
import com.security.security.service.PermissionUtils;
import com.security.security.service.ScopeNormalizer;
import com.security.security.service.StorageEngineType;
import com.security.security.service.VectorMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.GraphLookupOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Experimental MongoDB storage engine: nested or flat chunks, RBAC Criteria pre-filter,
 * brute-force cosine (local) or Atlas path (optional), and $graphLookup for wiki edges.
 */
@Service
@Profile({"mongodb", "mongodb-benchmark"})
@RequiredArgsConstructor
@Slf4j
public class MongoStorageEngine implements KnowledgeStorageEngine {

    private final MongoDocumentRepository mongoDocumentRepository;
    private final MongoFlatChunkRepository mongoFlatChunkRepository;
    private final MongoWikiPageRepository mongoWikiPageRepository;
    private final MongoTemplate mongoTemplate;
    private final MongoRbacFilterBuilder rbacFilterBuilder;

    @Value("${mongo.use-nested-chunks:true}")
    private boolean useNestedChunks;

    @Value("${mongo.vector.dimensions:768}")
    private int vectorDimensions;

    @Override
    public StorageEngineType getEngineType() {
        return StorageEngineType.MONGODB;
    }

    @Override
    public void storeDocument(Document document, List<Embedding> chunks) {
        Instant now = Instant.now();
        String ws = ScopeNormalizer.normalizeWorkspace(document.getWorkspaceId());
        String dept = ScopeNormalizer.normalizeDepartment(document.getDepartmentId());
        String roles = document.getAllowedRoles() != null ? document.getAllowedRoles() : "ALL";
        String classification = document.getSecurityClassification() != null
                ? document.getSecurityClassification().name()
                : SecurityClassification.INTERNAL.name();

        List<MongoChunk> nestedChunks = new ArrayList<>();
        if (useNestedChunks && chunks != null) {
            for (Embedding e : chunks) {
                nestedChunks.add(toMongoChunk(e));
            }
        }

        String existingId = null;
        if (document.getId() != null) {
            existingId = mongoDocumentRepository.findByPostgresDocumentId(document.getId())
                    .map(MongoDocument::getId)
                    .orElse(null);
        }

        MongoDocument mongoDoc = MongoDocument.builder()
                .id(existingId)
                .postgresDocumentId(document.getId())
                .userId(document.getUserId())
                .workspaceId(ws)
                .fileName(document.getFileName())
                .fileSize(document.getFileSize() != null ? document.getFileSize().longValue() : null)
                .filePath(document.getFilePath())
                .fileUrl(document.getFileUrl())
                .documentType(document.getDocumentType() != null ? document.getDocumentType().name() : null)
                .status(document.getStatus() != null ? document.getStatus().name() : null)
                .parserMethod(document.getParserMethod())
                .markdownContent(document.getMarkdownContent())
                .chunkCount(chunks != null ? chunks.size() : 0)
                .securityClassification(classification)
                .departmentId(dept)
                .allowedRoles(roles)
                .tags(document.getTags() != null ? new ArrayList<>(document.getTags()) : new ArrayList<>())
                .createdAt(now)
                .updatedAt(now)
                .chunks(useNestedChunks ? nestedChunks : new ArrayList<>())
                .build();

        MongoDocument saved = mongoDocumentRepository.save(mongoDoc);

        if (!useNestedChunks) {
            if (document.getId() != null) {
                mongoFlatChunkRepository.deleteByPostgresDocumentId(document.getId());
            }
            if (saved.getId() != null) {
                mongoFlatChunkRepository.deleteByDocumentId(saved.getId());
            }

            if (chunks != null) {
                List<MongoFlatChunk> flat = new ArrayList<>();
                for (Embedding e : chunks) {
                    flat.add(MongoFlatChunk.builder()
                            .documentId(saved.getId())
                            .postgresDocumentId(document.getId())
                            .chunkIndex(e.getChunkIndex())
                            .chunkText(e.getChunkText())
                            .contextHeader(e.getContextHeader())
                            .sectionPath(e.getSectionPath())
                            .chunkType(e.getChunkType() != null ? e.getChunkType().name() : "TEXT")
                            .embedding(resolveVector(e))
                            .tokenCount(e.getTokenCount())
                            .charCount(e.getCharCount())
                            .chunkTitle(e.getChunkTitle())
                            .workspaceId(ws)
                            .departmentId(dept)
                            .allowedRoles(roles)
                            .securityClassification(classification)
                            .createdAt(now)
                            .build());
                }
                mongoFlatChunkRepository.saveAll(flat);
            }
        }
    }

    @Override
    public List<StorageSearchHit> similaritySearch(
            String queryText,
            List<Double> queryEmbedding,
            int limit,
            RAGQueryPayload.UserPermissionContext permissionContext) {

        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.warn("[MongoStorageEngine] Empty query embedding — returning empty results");
            return List.of();
        }

        Criteria rbac = useNestedChunks
                ? rbacFilterBuilder.buildDocumentCriteria(permissionContext)
                : rbacFilterBuilder.buildChunkCriteria(permissionContext);

        List<StorageSearchHit> hits = useNestedChunks
                ? searchNested(queryEmbedding, rbac, permissionContext)
                : searchFlat(queryEmbedding, rbac, permissionContext);

        return hits.stream()
                .sorted(Comparator.comparingDouble(StorageSearchHit::getScore).reversed())
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    private List<StorageSearchHit> searchNested(
            List<Double> queryEmbedding,
            Criteria rbac,
            RAGQueryPayload.UserPermissionContext permissionContext) {

        List<MongoDocument> docs = mongoTemplate.find(Query.query(rbac), MongoDocument.class);
        List<StorageSearchHit> hits = new ArrayList<>();

        for (MongoDocument doc : docs) {
            SecurityClassification sc = parseClassification(doc.getSecurityClassification());
            if (!PermissionUtils.isResourceAccessible(
                    doc.getWorkspaceId(), doc.getDepartmentId(), doc.getAllowedRoles(), sc, permissionContext)) {
                continue;
            }
            if (doc.getChunks() == null) {
                continue;
            }
            for (MongoChunk chunk : doc.getChunks()) {
                double score = VectorMath.cosineSimilarity(queryEmbedding, chunk.getEmbedding());
                Map<String, Object> meta = new HashMap<>();
                meta.put("workspaceId", doc.getWorkspaceId());
                meta.put("departmentId", doc.getDepartmentId());
                meta.put("allowedRoles", doc.getAllowedRoles());
                meta.put("engine", "MONGODB");
                meta.put("mode", "nested");
                hits.add(StorageSearchHit.builder()
                        .documentId(doc.getPostgresDocumentId())
                        .documentMongoId(doc.getId())
                        .chunkIndex(chunk.getChunkIndex())
                        .chunkTitle(chunk.getChunkTitle())
                        .text(chunk.getChunkText())
                        .score(score)
                        .tokenCount(chunk.getTokenCount())
                        .metadata(meta)
                        .build());
            }
        }
        return hits;
    }

    private List<StorageSearchHit> searchFlat(
            List<Double> queryEmbedding,
            Criteria rbac,
            RAGQueryPayload.UserPermissionContext permissionContext) {

        List<MongoFlatChunk> chunks = mongoTemplate.find(Query.query(rbac), MongoFlatChunk.class);
        List<StorageSearchHit> hits = new ArrayList<>();

        for (MongoFlatChunk chunk : chunks) {
            SecurityClassification sc = parseClassification(chunk.getSecurityClassification());
            if (!PermissionUtils.isResourceAccessible(
                    chunk.getWorkspaceId(), chunk.getDepartmentId(), chunk.getAllowedRoles(), sc, permissionContext)) {
                continue;
            }
            double score = VectorMath.cosineSimilarity(queryEmbedding, chunk.getEmbedding());
            Map<String, Object> meta = new HashMap<>();
            meta.put("workspaceId", chunk.getWorkspaceId());
            meta.put("departmentId", chunk.getDepartmentId());
            meta.put("allowedRoles", chunk.getAllowedRoles());
            meta.put("engine", "MONGODB");
            meta.put("mode", "flat");
            hits.add(StorageSearchHit.builder()
                    .documentId(chunk.getPostgresDocumentId())
                    .documentMongoId(chunk.getDocumentId())
                    .chunkIndex(chunk.getChunkIndex())
                    .chunkTitle(chunk.getChunkTitle())
                    .text(chunk.getChunkText())
                    .score(score)
                    .tokenCount(chunk.getTokenCount())
                    .metadata(meta)
                    .build());
        }
        return hits;
    }

    @Override
    public void storeWikiPage(WikiPage page, List<String> outboundSlugs) {
        Instant now = Instant.now();
        String existingId = null;
        if (page.getId() != null) {
            existingId = mongoWikiPageRepository.findByPostgresWikiPageId(page.getId())
                    .map(MongoWikiPage::getId)
                    .orElse(null);
        }
        if (existingId == null && page.getSlug() != null) {
            existingId = mongoWikiPageRepository.findBySlug(page.getSlug())
                    .map(MongoWikiPage::getId)
                    .orElse(null);
        }

        MongoWikiPage mongoPage = MongoWikiPage.builder()
                .id(existingId)
                .postgresWikiPageId(page.getId())
                .title(page.getTitle())
                .slug(page.getSlug())
                .content(page.getContent())
                .workspaceId(ScopeNormalizer.normalizeWorkspace(page.getWorkspaceId()))
                .departmentId(ScopeNormalizer.normalizeDepartment(page.getDepartmentId()))
                .allowedRoles(page.getAllowedRoles() != null ? page.getAllowedRoles() : "ALL")
                .securityClassification(page.getSecurityClassification() != null
                        ? page.getSecurityClassification().name()
                        : SecurityClassification.INTERNAL.name())
                .tags(page.getTags())
                .pageType(page.getPageType() != null ? page.getPageType().name() : null)
                .summary(page.getSummary())
                .sourceDocumentId(page.getSourceDocumentId())
                .version(page.getVersion() != null ? page.getVersion() : 0)
                .outboundSlugs(outboundSlugs != null ? new ArrayList<>(outboundSlugs) : new ArrayList<>())
                .createdAt(now)
                .updatedAt(now)
                .build();
        mongoWikiPageRepository.save(mongoPage);
    }

    @Override
    public List<String> graphReachable(String startSlug, int maxDepth) {
        // GraphLookupOperation graphLookup = GraphLookupOperation.builder()
        //         .from("wiki_pages")
        //         .startWith("outboundSlugs")
        //         .connectFrom("outboundSlugs")
        //         .connectTo("slug")
        //         .maxDepth(Math.max(0, maxDepth - 1))
        //         .depthField("hopCount")
        //         .as("reachablePages");
        GraphLookupOperation graphLookupStage = GraphLookupOperation.builder()
        .from("wiki_pages")
        .startWith("metadata.slug")
        .connectFrom("outboundSlugs")
        .connectTo("slug")
        .restrictSearchWithMatch(Criteria.where("workspaceId").is(currentWorkspaceId))
        .maxDepth(1)
        .as("relatedPages");


        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("slug").is(startSlug)),
                graphLookup
        );

        List<org.bson.Document> results = mongoTemplate.aggregate(
                aggregation, "wiki_pages", org.bson.Document.class).getMappedResults();

        List<String> slugs = new ArrayList<>();
        for (org.bson.Document doc : results) {
            Object reachable = doc.get("reachablePages");
            if (reachable instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof org.bson.Document pageDoc) {
                        String slug = pageDoc.getString("slug");
                        if (slug != null) {
                            slugs.add(slug);
                        }
                    } else if (item instanceof MongoWikiPage mwp && mwp.getSlug() != null) {
                        slugs.add(mwp.getSlug());
                    }
                }
            }
        }
        return slugs.stream().distinct().collect(Collectors.toList());
    }

    private MongoChunk toMongoChunk(Embedding e) {
        return MongoChunk.builder()
                .chunkIndex(e.getChunkIndex())
                .chunkText(e.getChunkText())
                .contextHeader(e.getContextHeader())
                .sectionPath(e.getSectionPath())
                .chunkType(e.getChunkType() != null ? e.getChunkType().name() : "TEXT")
                .embedding(resolveVector(e))
                .tokenCount(e.getTokenCount())
                .charCount(e.getCharCount())
                .chunkTitle(e.getChunkTitle())
                .build();
    }

    private List<Double> resolveVector(Embedding e) {
        List<Double> parsed = VectorMath.parseEmbeddingJson(e.getEmbedding());
        if (!parsed.isEmpty()) {
            return parsed;
        }
        return VectorMath.randomUnitVector(vectorDimensions, e.getChunkIndex() != null ? e.getChunkIndex() : 0L);
    }

    private static SecurityClassification parseClassification(String value) {
        if (value == null || value.isBlank()) {
            return SecurityClassification.INTERNAL;
        }
        try {
            return SecurityClassification.valueOf(value);
        } catch (Exception e) {
            return SecurityClassification.INTERNAL;
        }
    }
}
