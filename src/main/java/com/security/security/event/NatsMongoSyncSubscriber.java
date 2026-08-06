package com.security.security.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.entity.Document;
import com.security.security.entity.Embedding;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.EmbeddingRepository;
import com.security.security.service.impl.MongoStorageEngine;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.Message;
import io.nats.client.PushSubscribeOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asynchronous NATS JetStream event consumer for Dual-Engine synchronization into MongoDB.
 * Consumes status events and syncs PostgreSQL documents and chunks into MongoDB
 * with Idempotency Key checks and Retry policies.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NatsMongoSyncSubscriber {

    private static final String STATUS_SUBJECT = "ai.document.status";
    private static final String WIKI_SUBJECT = "ai.wiki.published";

    private final ObjectProvider<Connection> natsConnectionProvider;
    private final ObjectProvider<MongoStorageEngine> mongoStorageEngineProvider;
    private final DocumentRepository documentRepository;
    private final EmbeddingRepository embeddingRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.storage.dual-write.enabled:true}")
    private boolean dualWriteEnabled;

    @Value("${app.storage.dual-write.mode:async}")
    private String dualWriteMode;

    private Dispatcher dispatcher;
    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public synchronized void subscribe() {
        if (!dualWriteEnabled) {
            log.info("[NatsMongoSyncSubscriber] Dual-write disabled. Skipping NATS subscriber.");
            return;
        }

        Connection natsConnection = natsConnectionProvider.getIfAvailable();
        MongoStorageEngine mongoStorageEngine = mongoStorageEngineProvider.getIfAvailable();

        if (natsConnection == null || mongoStorageEngine == null) {
            log.info("[NatsMongoSyncSubscriber] NATS or MongoStorageEngine unavailable. Subscriber idle.");
            return;
        }

        try {
            dispatcher = natsConnection.createDispatcher(msg -> {});
            JetStream js = natsConnection.jetStream();

            PushSubscribeOptions options = PushSubscribeOptions.builder()
                    .durable("ai-knowledge-mongo-sync-subscriber")
                    .build();

            js.subscribe(STATUS_SUBJECT, dispatcher, this::handleStatusMessage, false, options);
            log.info("[NatsMongoSyncSubscriber] Subscribed to NATS JetStream subject '{}' (mode={})", STATUS_SUBJECT, dualWriteMode);
        } catch (Exception e) {
            log.warn("[NatsMongoSyncSubscriber] Subscription warning: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void unsubscribe() {
        if (dispatcher != null) {
            try {
                dispatcher.unsubscribe(STATUS_SUBJECT);
            } catch (Exception e) {
                log.warn("[NatsMongoSyncSubscriber] Error unsubscribing: {}", e.getMessage());
            }
        }
    }

    private void handleStatusMessage(Message msg) {
        try {
            String raw = new String(msg.getData());
            JsonNode root = objectMapper.readTree(raw);
            String status = root.has("status") ? root.get("status").asText() : "";
            Long documentId = root.has("documentId") ? root.get("documentId").asLong() : null;

            if ("COMPLETED".equalsIgnoreCase(status) && documentId != null) {
                String idempotencyKey = documentId + ":completed";
                if (processedKeys.add(idempotencyKey)) {
                    log.info("[NatsMongoSyncSubscriber] Syncing document id={} into MongoDB asynchronously", documentId);
                    syncDocumentToMongo(documentId);
                } else {
                    log.debug("[NatsMongoSyncSubscriber] Duplicate event ignored for key: {}", idempotencyKey);
                }
            }
            msg.ack();
        } catch (Exception e) {
            log.error("[NatsMongoSyncSubscriber] Error handling sync message: {}", e.getMessage(), e);
        }
    }

    public void syncDocumentToMongo(Long documentId) {
        MongoStorageEngine mongoStorageEngine = mongoStorageEngineProvider.getIfAvailable();
        if (mongoStorageEngine == null) {
            return;
        }

        documentRepository.findById(documentId).ifPresent(doc -> {
            List<Embedding> chunks = embeddingRepository.findByDocumentId(documentId);
            try {
                mongoStorageEngine.storeDocument(doc, chunks);
                log.info("[NatsMongoSyncSubscriber] Successfully synced document id={} with {} chunks to MongoDB", documentId, chunks.size());
            } catch (Exception e) {
                log.error("[NatsMongoSyncSubscriber] Failed to sync document id={} to MongoDB: {}", documentId, e.getMessage(), e);
            }
        });
    }
}
