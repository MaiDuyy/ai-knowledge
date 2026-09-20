package com.security.security.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.event.listener.DocumentProcessingListener;
import io.nats.client.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NatsDocumentIngestSubscriber Tests")
class NatsDocumentIngestSubscriberTest {

    @Mock
    private Connection natsConnection;

    @Mock
    private JetStream jetStream;

    @Mock
    private Dispatcher dispatcher;

    @Mock
    private DocumentProcessingListener documentProcessingListener;

    private ObjectMapper objectMapper = new ObjectMapper();

    private NatsDocumentIngestSubscriber subscriber;

    @BeforeEach
    void setUp() {
        subscriber = new NatsDocumentIngestSubscriber(natsConnection, documentProcessingListener, objectMapper);
    }

    @Test
    @DisplayName("Should subscribe to both document.ingest.requested and datasource.sync.requested")
    void subscribe_subscribesToBothSubjects() throws Exception {
        when(natsConnection.jetStream()).thenReturn(jetStream);
        when(natsConnection.createDispatcher(any())).thenReturn(dispatcher);

        subscriber.subscribe();

        // Verify subscriptions
        verify(jetStream).subscribe(
                eq("document.ingest.requested"),
                eq(dispatcher),
                any(MessageHandler.class),
                eq(false),
                any(PushSubscribeOptions.class)
        );

        verify(jetStream).subscribe(
                eq("datasource.sync.requested"),
                eq(dispatcher),
                any(MessageHandler.class),
                eq(false),
                any(PushSubscribeOptions.class)
        );
    }
}
