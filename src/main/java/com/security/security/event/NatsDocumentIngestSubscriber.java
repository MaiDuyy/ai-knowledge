package com.security.security.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.event.listener.DocumentProcessingListener;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NatsDocumentIngestSubscriber {

    private static final String SUBJECT = "document.ingest.requested";
    private static final String SYNC_SUBJECT = "datasource.sync.requested";

    private final Connection natsConnection;
    private final DocumentProcessingListener documentProcessingListener;
    private final ObjectMapper objectMapper;

    private Dispatcher dispatcher;

    @PostConstruct
    public synchronized void subscribe() {
        if (dispatcher != null) {
            log.info("[NATS Ingest Subscriber] Already subscribed to subject: {}", SUBJECT);
            return;
        }
        try {
            dispatcher = natsConnection.createDispatcher(msg -> {});
            
            io.nats.client.JetStream js = natsConnection.jetStream();
            
            // 1. Subscribe to document.ingest.requested
            io.nats.client.PushSubscribeOptions pushOptions = io.nats.client.PushSubscribeOptions.builder()
                    .durable("ai-knowledge-document-ingest-subscriber")
                    .build();
            js.subscribe(SUBJECT, dispatcher, this::handleMessage, false, pushOptions);
            log.info("[NATS Ingest Subscriber] Subscribed to JetStream subject: {} with durable consumer 'ai-knowledge-document-ingest-subscriber'", SUBJECT);
            
            // 2. Subscribe to datasource.sync.requested
            io.nats.client.PushSubscribeOptions syncPushOptions = io.nats.client.PushSubscribeOptions.builder()
                    .durable("ai-knowledge-datasource-sync-subscriber")
                    .build();
            js.subscribe(SYNC_SUBJECT, dispatcher, this::handleDatasourceSyncMessage, false, syncPushOptions);
            log.info("[NATS Ingest Subscriber] Subscribed to JetStream subject: {} with durable consumer 'ai-knowledge-datasource-sync-subscriber'", SYNC_SUBJECT);
            
        } catch (Exception e) {
            log.warn("[NATS Ingest Subscriber] Could not subscribe to JetStream subjects — will retry on reconnect. Cause: {}", e.getMessage());
            dispatcher = null;
        }
    }

    @org.springframework.context.event.EventListener
    public void onNatsConnected(NatsConnectedEvent event) {
        log.info("[NATS Ingest Subscriber] Received NatsConnectedEvent, triggering subscription...");
        subscribe();
    }

    @PreDestroy
    public void unsubscribe() {
        try {
            if (dispatcher != null) {
                dispatcher.unsubscribe(SUBJECT);
                dispatcher.unsubscribe(SYNC_SUBJECT);
            }
        } catch (Exception e) {
            log.warn("[NATS Ingest Subscriber] Error unsubscribing: {}", e.getMessage());
        }
    }

    private void handleMessage(Message msg) {
        Long documentId = null;
        try {
            String raw = new String(msg.getData());
            log.info("[NATS Ingest Subscriber] Received on '{}': {}", SUBJECT, raw);

            JsonNode root = objectMapper.readTree(raw);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;

            if (payload.has("documentId")) {
                documentId = payload.get("documentId").asLong();
            }

            if (documentId == null) {
                log.warn("[NATS Ingest Subscriber] Missing 'documentId' in payload, skipping and acking");
                msg.ack();
                return;
            }

            log.info("[NATS Ingest Subscriber] Processing ingestion request for documentId={}", documentId);
            msg.ack();
            
            documentProcessingListener.processDocument(documentId);

            log.info("[NATS Ingest Subscriber] Successfully processed documentId={}", documentId);
        } catch (Exception e) {
            log.error("[NATS Ingest Subscriber] Failed to process ingestion request for documentId={}: {}", documentId, e.getMessage(), e);
            // Do not call msg.ack() to allow JetStream to retry/redeliver the message
        }
    }

    private void handleDatasourceSyncMessage(Message msg) {
        Long datasourceId = null;
        try {
            String raw = new String(msg.getData());
            log.info("[NATS Ingest Subscriber] Received on '{}': {}", SYNC_SUBJECT, raw);

            JsonNode root = objectMapper.readTree(raw);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;

            if (payload.has("datasourceId")) {
                datasourceId = payload.get("datasourceId").asLong();
            }

            if (datasourceId == null) {
                log.warn("[NATS Ingest Subscriber] Missing 'datasourceId' in payload, skipping and acking");
                msg.ack();
                return;
            }

            log.info("[NATS Ingest Subscriber] Processing sync request for datasourceId={}", datasourceId);
            
            // Notion/Feishu/Yuque integrations are deferred under YAGNI, so we log and ack immediately.
            log.info("[NATS Ingest Subscriber] External connectors sync is deferred (YAGNI). Acknowledging datasourceId={}", datasourceId);
            msg.ack();
        } catch (Exception e) {
            log.error("[NATS Ingest Subscriber] Failed to process sync request for datasourceId={}: {}", datasourceId, e.getMessage(), e);
        }
    }
}
