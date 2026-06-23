package com.security.security.config;

import io.nats.client.Connection;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamApiException;
import io.nats.client.api.StreamInfo;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("NatsConfig Proxy and Reconnect Tests")
class NatsConfigTest {

    @Test
    @DisplayName("Should return a proxy connection even when NATS server is offline")
    void shouldReturnProxyConnectionWhenOffline() {
        NatsConfig natsConfig = new NatsConfig();
        ReflectionTestUtils.setField(natsConfig, "natsUrl", "nats://localhost:65422");

        ApplicationEventPublisher mockPublisher = Mockito.mock(ApplicationEventPublisher.class);

        Connection connection = natsConfig.natsConnection(mockPublisher);

        assertThat(connection).isNotNull();
        assertThat(connection.getStatus()).isEqualTo(Connection.Status.DISCONNECTED);

        assertThatThrownBy(() -> connection.createDispatcher(msg -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NATS connection is not established yet");
    }

    @Test
    @DisplayName("Should create AI_KNOWLEDGE_EVENTS stream with datasource.sync.requested subject when not existing")
    void shouldCreateStreamWithSyncSubjectWhenNotExisting() throws Exception {
        NatsConfig natsConfig = new NatsConfig();
        Connection mockConn = mock(Connection.class);
        JetStreamManagement mockJsm = mock(JetStreamManagement.class);

        when(mockConn.jetStreamManagement()).thenReturn(mockJsm);
        
        // Mock getStreamInfo to throw a mocked JetStreamApiException
        JetStreamApiException mockException = mock(JetStreamApiException.class);
        when(mockJsm.getStreamInfo(anyString())).thenThrow(mockException);

        // Call the private initializeStreams method via reflection
        ReflectionTestUtils.invokeMethod(natsConfig, "initializeStreams", mockConn);

        // Verify that jsm.addStream was called for AI_KNOWLEDGE_EVENTS
        ArgumentCaptor<StreamConfiguration> configCaptor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(mockJsm, times(2)).addStream(configCaptor.capture());

        List<StreamConfiguration> configs = configCaptor.getAllValues();
        StreamConfiguration aiKnowledgeConfig = configs.stream()
                .filter(c -> "AI_KNOWLEDGE_EVENTS".equals(c.getName()))
                .findFirst()
                .orElse(null);

        assertThat(aiKnowledgeConfig).isNotNull();
        assertThat(aiKnowledgeConfig.getSubjects()).contains("datasource.sync.requested");
    }

    @Test
    @DisplayName("Should update AI_KNOWLEDGE_EVENTS stream to include datasource.sync.requested subject when stream exists without it")
    void shouldUpdateStreamToIncludeSyncSubjectWhenExistingWithoutIt() throws Exception {
        NatsConfig natsConfig = new NatsConfig();
        Connection mockConn = mock(Connection.class);
        JetStreamManagement mockJsm = mock(JetStreamManagement.class);
        StreamInfo mockFileStreamInfo = mock(StreamInfo.class);
        StreamInfo mockAiStreamInfo = mock(StreamInfo.class);
        StreamConfiguration mockAiConfig = mock(StreamConfiguration.class);

        when(mockConn.jetStreamManagement()).thenReturn(mockJsm);
        when(mockJsm.getStreamInfo("FILE_EVENTS")).thenReturn(mockFileStreamInfo);
        when(mockJsm.getStreamInfo("AI_KNOWLEDGE_EVENTS")).thenReturn(mockAiStreamInfo);
        when(mockAiStreamInfo.getConfiguration()).thenReturn(mockAiConfig);
        
        // Simulating existing subjects, missing "datasource.sync.requested"
        List<String> existingSubjects = new ArrayList<>(Arrays.asList("document.status.updated", "compilation.plan.updated", "wiki.draft.updated", "document.ingest.requested"));
        when(mockAiConfig.getSubjects()).thenReturn(existingSubjects);

        // Call the private initializeStreams method via reflection
        ReflectionTestUtils.invokeMethod(natsConfig, "initializeStreams", mockConn);

        // Verify that jsm.updateStream was called
        ArgumentCaptor<StreamConfiguration> configCaptor = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(mockJsm).updateStream(configCaptor.capture());

        StreamConfiguration updatedConfig = configCaptor.getValue();
        assertThat(updatedConfig.getSubjects()).contains("datasource.sync.requested");
    }
}

