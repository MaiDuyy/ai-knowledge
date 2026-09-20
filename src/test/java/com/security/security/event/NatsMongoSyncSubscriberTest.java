package com.security.security.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.EmbeddingRepository;
import com.security.security.service.impl.MongoStorageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NatsMongoSyncSubscriberTest {

    @Mock
    private ObjectProvider<io.nats.client.Connection> natsConnectionProvider;

    @Mock
    private ObjectProvider<MongoStorageEngine> mongoStorageEngineProvider;

    @Mock
    private MongoStorageEngine mongoStorageEngine;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private EmbeddingRepository embeddingRepository;

    private NatsMongoSyncSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new NatsMongoSyncSubscriber(
                natsConnectionProvider,
                mongoStorageEngineProvider,
                documentRepository,
                embeddingRepository,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("syncDocumentToMongo syncs document and chunks when engine is available")
    void testSyncDocumentToMongo() {
        Long docId = 42L;
        Document document = Document.builder().id(docId).fileName("test.pdf").build();
        Embedding chunk = Embedding.builder().id(100L).documentId(docId).chunkText("Sample text").build();

        when(mongoStorageEngineProvider.getIfAvailable()).thenReturn(mongoStorageEngine);
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(embeddingRepository.findByDocumentId(docId)).thenReturn(List.of(chunk));

        subscriber.syncDocumentToMongo(docId);

        verify(mongoStorageEngine, times(1)).storeDocument(document, List.of(chunk));
    }
}
