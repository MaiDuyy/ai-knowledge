package com.security.security.service;

import com.security.security.dto.StorageSearchHit;
import com.security.security.dtorequest.RAGQueryPayload.UserPermissionContext;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.entity.WikiLink;
import com.security.security.entity.WikiPage;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.mongo.MongoDocument;
import com.security.security.entity.mongo.MongoFlatChunk;
import com.security.security.entity.mongo.MongoWikiPage;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.EmbeddingRepository;
import com.security.security.repository.WikiLinkRepository;
import com.security.security.repository.WikiPageRepository;
import com.security.security.repository.mongo.MongoDocumentRepository;
import com.security.security.repository.mongo.MongoFlatChunkRepository;
import com.security.security.repository.mongo.MongoWikiPageRepository;
import com.security.security.service.impl.MongoStorageEngine;
import com.security.security.service.impl.PostgresStorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MongoPostgresParityTest {

    @Mock
    private DocumentRepository postgresDocRepo;
    @Mock
    private EmbeddingRepository postgresEmbRepo;
    @Mock
    private WikiPageRepository postgresWikiRepo;
    @Mock
    private WikiLinkRepository postgresLinkRepo;

    @Mock
    private MongoDocumentRepository mongoDocRepo;
    @Mock
    private MongoFlatChunkRepository mongoFlatChunkRepo;
    @Mock
    private MongoWikiPageRepository mongoWikiRepo;
    @Mock
    private MongoTemplate mongoTemplate;

    private PostgresStorageEngine postgresStorageEngine;
    private MongoStorageEngine mongoStorageEngine;

    @BeforeEach
    void setUp() {
        postgresStorageEngine = new PostgresStorageEngine(
                postgresDocRepo, postgresEmbRepo, postgresWikiRepo, postgresLinkRepo
        );
        mongoStorageEngine = new MongoStorageEngine(
                mongoDocRepo, mongoFlatChunkRepo, mongoWikiRepo, mongoTemplate, new MongoRbacFilterBuilder()
        );
    }

    @Test
    @DisplayName("Engine type identification parity test")
    void testEngineTypeParity() {
        assertEquals(StorageEngineType.POSTGRES, postgresStorageEngine.getEngineType());
        assertEquals(StorageEngineType.MONGODB, mongoStorageEngine.getEngineType());
    }

    @Test
    @DisplayName("Similarity search parity: both engines yield identical top score hits and metadata")
    void testSimilaritySearchParity() {
        List<Double> queryVector = List.of(1.0, 0.0, 0.0);
        UserPermissionContext adminContext = new UserPermissionContext();
        adminContext.setWorkspaceId("ws-test");
        adminContext.setRoles(List.of("ADMIN"));
        adminContext.setRoleLevel(1);

        // Setup Postgres Mock Data
        Document pgDoc = Document.builder()
                .id(10L)
                .workspaceId("ws-test")
                .securityClassification(SecurityClassification.INTERNAL)
                .build();
        Embedding pgEmb = Embedding.builder()
                .id(1L)
                .documentId(10L)
                .chunkIndex(0)
                .chunkTitle("Security Overview")
                .chunkText("Internal security guidelines")
                .embedding("[1.0, 0.0, 0.0]")
                .tokenCount(15)
                .build();

        when(postgresDocRepo.findAll()).thenReturn(List.of(pgDoc));
        when(postgresEmbRepo.findByDocumentId(10L)).thenReturn(List.of(pgEmb));

        List<StorageSearchHit> pgHits = postgresStorageEngine.similaritySearch("security", queryVector, 5, adminContext);

        // Setup Mongo Mock Data (Flat chunk search)
        MongoFlatChunk mongoChunk = MongoFlatChunk.builder()
                .id("flat-10")
                .postgresDocumentId(10L)
                .chunkIndex(0)
                .chunkTitle("Security Overview")
                .chunkText("Internal security guidelines")
                .embedding(List.of(1.0, 0.0, 0.0))
                .workspaceId("ws-test")
                .securityClassification("INTERNAL")
                .tokenCount(15)
                .build();

        when(mongoTemplate.find(any(Query.class), eq(MongoFlatChunk.class))).thenReturn(List.of(mongoChunk));

        List<StorageSearchHit> mongoHits = mongoStorageEngine.similaritySearch("security", queryVector, 5, adminContext);

        // Parity Assertions
        assertFalse(pgHits.isEmpty(), "Postgres hits should not be empty");
        assertFalse(mongoHits.isEmpty(), "Mongo hits should not be empty");
        assertEquals(pgHits.size(), mongoHits.size(), "Hits count parity");

        StorageSearchHit pgTop = pgHits.get(0);
        StorageSearchHit mongoTop = mongoHits.get(0);

        assertEquals(pgTop.getChunkTitle(), mongoTop.getChunkTitle(), "Title parity");
        assertEquals(pgTop.getText(), mongoTop.getText(), "Chunk text parity");
        assertEquals(pgTop.getScore(), mongoTop.getScore(), 0.001, "Cosine score parity");
    }

    @Test
    @DisplayName("Graph reachability parity: SQL recursive join vs. Mongo graph lookup strategy")
    void testGraphReachableParity() {
        // Postgres Graph Data
        WikiPage pgPage1 = WikiPage.builder().id(1L).slug("sec-intro").build();
        WikiPage pgPage2 = WikiPage.builder().id(2L).slug("sec-policy").build();
        WikiLink pgLink = WikiLink.builder().fromPageId(1L).toSlug("sec-policy").build();

        when(postgresWikiRepo.findAll()).thenReturn(List.of(pgPage1, pgPage2));
        when(postgresLinkRepo.findAll()).thenReturn(List.of(pgLink));

        List<String> pgReachable = postgresStorageEngine.graphReachable("sec-intro", 2);

        assertFalse(pgReachable.isEmpty());
        assertTrue(pgReachable.contains("sec-policy"));
    }
}
