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
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/html",
            "application/xhtml+xml",
            "audio/wav",
            "audio/x-wav",
            "audio/mpeg",
            "audio/mp3",
            "text/vtt",
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/tiff",
            "image/gif",
            "image/bmp",
            "image/webp",
            "application/x-latex",
            "text/x-tex",
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
            String m = mimeType.toLowerCase();
            if (m.contains("pdf"))  return DocType.pdf;
            if (m.contains("word") || m.contains("openxmlformats-officedocument.wordprocessingml")) return DocType.docx;
            if (m.contains("powerpoint") || m.contains("presentation")) return DocType.pptx;
            if (m.contains("excel") || m.contains("sheet")) return DocType.xlsx;
            if (m.contains("html") || m.contains("xhtml")) return DocType.html;
            if (m.contains("wav")) return DocType.wav;
            if (m.contains("mpeg") || m.contains("mp3")) return DocType.mp3;
            if (m.contains("vtt")) return DocType.vtt;
            if (m.contains("png")) return DocType.png;
            if (m.contains("tiff")) return DocType.tiff;
            if (m.contains("jpeg") || m.contains("jpg")) return DocType.jpeg;
            if (m.contains("latex") || m.contains("tex")) return DocType.latex;
            if (m.contains("markdown") || m.contains("md")) return DocType.md;
            if (m.contains("text")) return DocType.txt;
        }
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".pdf"))  return DocType.pdf;
            if (lower.endsWith(".docx") || lower.endsWith(".doc")) return DocType.docx;
            if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) return DocType.pptx;
            if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return DocType.xlsx;
            if (lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtml")) return DocType.html;
            if (lower.endsWith(".wav")) return DocType.wav;
            if (lower.endsWith(".mp3")) return DocType.mp3;
            if (lower.endsWith(".vtt")) return DocType.vtt;
            if (lower.endsWith(".png")) return DocType.png;
            if (lower.endsWith(".tiff") || lower.endsWith(".tif")) return DocType.tiff;
            if (lower.endsWith(".jpeg") || lower.endsWith(".jpg")) return DocType.jpeg;
            if (lower.endsWith(".tex") || lower.endsWith(".latex")) return DocType.latex;
            if (lower.endsWith(".txt")) return DocType.txt;
            if (lower.endsWith(".md")) return DocType.md;
        }
        return DocType.pdf; // default
    }
}
