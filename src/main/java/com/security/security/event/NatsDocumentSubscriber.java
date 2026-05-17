package com.security.security.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.entity.Document;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import com.security.security.repository.DocumentRepository;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Subscribes to NATS subject "file.document.uploaded" published by file-service.
 *
 * Payload structure (from file-service EventSubjects.DOCUMENT_UPLOADED):
 * {
 *   "subject": "file.document.uploaded",
 *   "payload": {
 *     "fileId": "uuid",
 *     "userId": "string",
 *     "url": "https://s3.../doc_...",
 *     "mimeType": "application/pdf",
 *     "originalName": "report.pdf",
 *     "classification": "INTERNAL",
 *     "collectionId": "optional-string"
 *   },
 *   "timestamp": "ISO-8601"
 * }
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NatsDocumentSubscriber {

    // Subject must match file-service EventSubjects.DOCUMENT_UPLOADED
    private static final String SUBJECT = "file.document.uploaded";

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown"
    );

    private final Connection natsConnection;
    private final DocumentRepository documentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private Dispatcher dispatcher;

    @PostConstruct
    public void subscribe() {
        try {
            dispatcher = natsConnection.createDispatcher(this::handleMessage);
            dispatcher.subscribe(SUBJECT);
            log.info("[NATS] Subscribed to subject: {}", SUBJECT);
        } catch (Exception e) {
            log.warn("[NATS] Could not subscribe to '{}' — will retry on reconnect. Cause: {}", SUBJECT, e.getMessage());
        }
    }

    @PreDestroy
    public void unsubscribe() {
        try {
            if (dispatcher != null) {
                dispatcher.unsubscribe(SUBJECT);
            }
        } catch (Exception e) {
            log.warn("[NATS] Error unsubscribing: {}", e.getMessage());
        }
    }

    private void handleMessage(Message msg) {
        try {
            String raw = new String(msg.getData());
            log.debug("[NATS] Received on '{}': {}", SUBJECT, raw);

            // Unwrap outer envelope: { subject, payload, timestamp }
            JsonNode root = objectMapper.readTree(raw);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;

            String fileId       = getTextField(payload, "fileId");
            String userId       = getTextField(payload, "userId");
            String url          = getTextField(payload, "url");
            String mimeType     = getTextField(payload, "mimeType");
            String originalName = getTextField(payload, "originalName");

            if (url == null || url.isBlank()) {
                log.warn("[NATS] Missing 'url' in payload, skipping");
                return;
            }

            if (!SUPPORTED_MIME_TYPES.contains(mimeType)) {
                log.info("[NATS] Skipping unsupported mimeType: {}", mimeType);
                return;
            }

            log.info("[NATS] Processing document from file-service | fileId={} userId={} mime={}", fileId, userId, mimeType);

            DocType docType = resolveDocType(mimeType, originalName);

            Document document = Document.builder()
                    .userId(userId != null ? userId : "system")
                    .fileName(originalName != null ? originalName : "document")
                    .fileSize(0)            // size not available at this point
                    .filePath(null)         // no local path — using URL
                    .fileUrl(url)
                    .documentType(docType)
                    .status(DocStatus.PENDING)
                    .chunkCount(0)
                    .build();

            Document saved = documentRepository.save(document);
            log.info("[NATS] Document record created: id={}", saved.getId());

            // Trigger async RAG pipeline (same as direct upload)
            eventPublisher.publishEvent(new DocumentUploadedEvent(this, saved));

        } catch (Exception e) {
            log.error("[NATS] Error processing message: {}", e.getMessage(), e);
        }
    }

    private String getTextField(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull()) ? n.asText() : null;
    }

    private DocType resolveDocType(String mimeType, String fileName) {
        if (mimeType != null) {
            if (mimeType.contains("pdf"))  return DocType.pdf;
            if (mimeType.contains("word") || mimeType.contains("openxmlformats")) return DocType.docx;
            if (mimeType.contains("text")) return DocType.txt;
        }
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".pdf"))  return DocType.pdf;
            if (lower.endsWith(".docx") || lower.endsWith(".doc")) return DocType.docx;
            if (lower.endsWith(".txt") || lower.endsWith(".md"))   return DocType.txt;
        }
        return DocType.pdf; // default
    }
}
