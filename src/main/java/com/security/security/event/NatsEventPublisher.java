package com.security.security.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class NatsEventPublisher {

    private final Connection natsConnection;
    private final ObjectMapper objectMapper;

    public void publishEvent(String subject, Map<String, Object> payload) {
        if (natsConnection == null || natsConnection.getStatus() != Connection.Status.CONNECTED) {
            log.warn("[NATS Publisher] Skipping publish to subject '{}' because NATS is not connected", subject);
            return;
        }

        try {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("subject", subject);
            envelope.put("payload", payload);
            envelope.put("timestamp", Instant.now().toString());

            byte[] jsonBytes = objectMapper.writeValueAsBytes(envelope);
            
            // Publish via JetStream API
            io.nats.client.JetStream js = natsConnection.jetStream();
            js.publish(subject, jsonBytes);
            
            log.info("[NATS Publisher] Published JetStream event to subject '{}'", subject);
            log.debug("[NATS Publisher] Payload: {}", payload);
        } catch (Exception e) {
            log.error("[NATS Publisher] Failed to publish JetStream event to subject '{}': {}", subject, e.getMessage(), e);
        }
    }

    /**
     * Publish document status update event
     */
    public void publishDocumentStatus(Long documentId, String userId, String workspaceId, String status) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("documentId", documentId);
        payload.put("userId", userId != null ? userId : "system-user");
        payload.put("workspaceId", workspaceId != null ? workspaceId : "default-workspace");
        payload.put("status", status);

        publishEvent("document.status.updated", payload);
    }

    /**
     * Publish compilation plan update event
     */
    public void publishCompilationPlanUpdated(Long planId, Long documentId, String workspaceId, String status, String userId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("planId", planId);
        payload.put("sourceDocumentId", documentId);
        payload.put("workspaceId", workspaceId != null ? workspaceId : "default-workspace");
        payload.put("status", status);
        payload.put("userId", userId != null ? userId : "system-user");

        publishEvent("compilation.plan.updated", payload);
    }

    /**
     * Publish wiki page draft update event
     */
    public void publishWikiDraftUpdated(Long draftId, String title, String slug, String workspaceId, String status, String userId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("draftId", draftId);
        payload.put("title", title);
        payload.put("slug", slug);
        payload.put("workspaceId", workspaceId != null ? workspaceId : "default-workspace");
        payload.put("status", status);
        payload.put("userId", userId != null ? userId : "system-user");

        publishEvent("wiki.draft.updated", payload);
    }
}
