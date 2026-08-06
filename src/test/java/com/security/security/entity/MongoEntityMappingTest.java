package com.security.security.entity;

import com.security.security.entity.mongo.MongoChunk;
import com.security.security.entity.mongo.MongoDocument;
import com.security.security.entity.mongo.MongoFlatChunk;
import com.security.security.entity.mongo.MongoWikiPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MongoEntityMappingTest {

    @Test
    @DisplayName("MongoDocument & MongoChunk nested model mapping test")
    void testMongoDocumentNestedMapping() {
        MongoChunk chunk = MongoChunk.builder()
                .chunkIndex(0)
                .chunkText("Test chunk text")
                .embedding(List.of(0.1, 0.2, 0.3))
                .tokenCount(10)
                .build();

        MongoDocument doc = MongoDocument.builder()
                .id("mongo-doc-1")
                .postgresDocumentId(101L)
                .workspaceId("ws-1")
                .departmentId("dept-A")
                .securityClassification("CONFIDENTIAL")
                .chunks(List.of(chunk))
                .createdAt(Instant.now())
                .build();

        assertEquals("mongo-doc-1", doc.getId());
        assertEquals(101L, doc.getPostgresDocumentId());
        assertEquals("ws-1", doc.getWorkspaceId());
        assertEquals("dept-A", doc.getDepartmentId());
        assertEquals(1, doc.getChunks().size());
        assertEquals("Test chunk text", doc.getChunks().get(0).getChunkText());
        assertEquals(3, doc.getChunks().get(0).getEmbedding().size());
    }

    @Test
    @DisplayName("MongoFlatChunk collection mapping test")
    void testMongoFlatChunkMapping() {
        MongoFlatChunk flatChunk = MongoFlatChunk.builder()
                .id("flat-1")
                .documentId("mongo-doc-1")
                .postgresDocumentId(101L)
                .chunkIndex(0)
                .chunkText("Flat chunk content")
                .embedding(List.of(0.5, 0.6))
                .workspaceId("ws-1")
                .departmentId("dept-A")
                .securityClassification("SECRET")
                .build();

        assertEquals("flat-1", flatChunk.getId());
        assertEquals("mongo-doc-1", flatChunk.getDocumentId());
        assertEquals(101L, flatChunk.getPostgresDocumentId());
        assertEquals("SECRET", flatChunk.getSecurityClassification());
        assertEquals(2, flatChunk.getEmbedding().size());
    }

    @Test
    @DisplayName("MongoWikiPage graph outboundSlugs mapping test")
    void testMongoWikiPageMapping() {
        MongoWikiPage wikiPage = MongoWikiPage.builder()
                .id("wiki-1")
                .postgresWikiPageId(201L)
                .title("Architecture Guide")
                .slug("architecture-guide")
                .workspaceId("ws-1")
                .outboundSlugs(List.of("security-spec", "database-design"))
                .build();

        assertEquals("wiki-1", wikiPage.getId());
        assertEquals("architecture-guide", wikiPage.getSlug());
        assertEquals(2, wikiPage.getOutboundSlugs().size());
        assertTrue(wikiPage.getOutboundSlugs().contains("security-spec"));
        assertTrue(wikiPage.getOutboundSlugs().contains("database-design"));
    }
}
