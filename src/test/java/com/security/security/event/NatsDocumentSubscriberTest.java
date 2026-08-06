package com.security.security.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.entity.Document;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.repository.DocumentRepository;
import io.nats.client.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NatsDocumentSubscriber Tests")
class NatsDocumentSubscriberTest {

    @Mock
    private Connection natsConnection;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private NatsEventPublisher natsEventPublisher;

    private ObjectMapper objectMapper = new ObjectMapper();

    private NatsDocumentSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new NatsDocumentSubscriber(natsConnection, documentRepository, natsEventPublisher, objectMapper);
    }

    @Test
    @DisplayName("Should extract and normalize metadata from message payload")
    void handleMessage_extractsAndNormalizesMetadata() throws Exception {
        // Given
        String rawMessage = """
        {
          "subject": "file.document.uploaded",
          "payload": {
            "fileId": "file-123",
            "userId": "user-456",
            "url": "http://s3.example.com/file.pdf",
            "mimeType": "application/pdf",
            "originalName": "test-doc.pdf",
            "workspaceId": "workspace-abc",
            "departmentId": "dept-xyz",
            "allowedRoles": "MEMBER",
            "classification": "confidential"
          }
        }
        """;

        Message msg = mock(Message.class);
        when(msg.getData()).thenReturn(rawMessage.getBytes());

        Document savedDoc = Document.builder()
                .id(1L)
                .userId("user-456")
                .fileName("test-doc.pdf")
                .status(DocStatus.PROCESSING)
                .build();
        when(documentRepository.save(any(Document.class))).thenReturn(savedDoc);

        // When
        ReflectionTestUtils.invokeMethod(subscriber, "handleMessage", msg);

        // Then
        ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(docCaptor.capture());
        Document captured = docCaptor.getValue();

        assertThat(captured.getWorkspaceId()).isEqualTo("workspace-abc");
        assertThat(captured.getDepartmentId()).isEqualTo("dept-xyz");
        assertThat(captured.getAllowedRoles()).isEqualTo("MEMBER");
        assertThat(captured.getSecurityClassification()).isEqualTo(SecurityClassification.CONFIDENTIAL);

        verify(natsEventPublisher).publishDocumentIngestRequested(eq(1L), eq("user-456"));
        verify(msg).ack();
    }
}
