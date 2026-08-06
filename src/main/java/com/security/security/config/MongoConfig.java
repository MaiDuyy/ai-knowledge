package com.security.security.config;

import com.security.security.entity.mongo.MongoDocument;
import com.security.security.entity.mongo.MongoFlatChunk;
import com.security.security.entity.mongo.MongoWikiPage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Conditional MongoDB configuration for experimental storage engine.
 * Active only under {@code mongodb} / {@code mongodb-benchmark} profiles.
 */
@Configuration
@Profile({"mongodb", "mongodb-benchmark"})
@EnableMongoRepositories(basePackages = "com.security.security.repository.mongo")
@EnableMongoAuditing
@RequiredArgsConstructor
@Slf4j
public class MongoConfig {

    private final MongoTemplate mongoTemplate;

    @Value("${mongo.use-nested-chunks:true}")
    private boolean useNestedChunks;

    @Value("${mongo.vector.mode:brute-force}")
    private String vectorMode;

    @PostConstruct
    public void ensureIndexes() {
        log.info("[MongoConfig] Bootstrapping indexes (nestedChunks={}, vectorMode={})",
                useNestedChunks, vectorMode);

        mongoTemplate.indexOps(MongoDocument.class)
                .createIndex(new Index().on("workspaceId", Sort.Direction.ASC));
        mongoTemplate.indexOps(MongoDocument.class)
                .createIndex(new Index().on("departmentId", Sort.Direction.ASC));
        mongoTemplate.indexOps(MongoDocument.class)
                .createIndex(new Index().on("allowedRoles", Sort.Direction.ASC));
        mongoTemplate.indexOps(MongoDocument.class)
                .createIndex(new Index().on("postgresDocumentId", Sort.Direction.ASC));

        mongoTemplate.indexOps(MongoFlatChunk.class)
                .createIndex(new Index().on("documentId", Sort.Direction.ASC));
        mongoTemplate.indexOps(MongoFlatChunk.class)
                .createIndex(new Index().on("workspaceId", Sort.Direction.ASC));
        mongoTemplate.indexOps(MongoFlatChunk.class)
                .createIndex(new Index().on("postgresDocumentId", Sort.Direction.ASC));

        mongoTemplate.indexOps(MongoWikiPage.class)
                .createIndex(new Index().on("slug", Sort.Direction.ASC).unique());
        mongoTemplate.indexOps(MongoWikiPage.class)
                .createIndex(new Index().on("workspaceId", Sort.Direction.ASC));
        mongoTemplate.indexOps(MongoWikiPage.class)
                .createIndex(new Index().on("outboundSlugs", Sort.Direction.ASC));

        log.info("[MongoConfig] Indexes ensured on documents / mongo_flat_chunks / wiki_pages");
    }
}
