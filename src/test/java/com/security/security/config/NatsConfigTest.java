package com.security.security.config;

import io.nats.client.Connection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NatsConfig Proxy and Reconnect Tests")
class NatsConfigTest {

    @Test
    @DisplayName("Should return a proxy connection even when NATS server is offline")
    void shouldReturnProxyConnectionWhenOffline() {
        NatsConfig natsConfig = new NatsConfig();
        // Set an invalid port/url so that it is guaranteed to be offline and fail quickly
        ReflectionTestUtils.setField(natsConfig, "natsUrl", "nats://localhost:65422");

        ApplicationEventPublisher mockPublisher = Mockito.mock(ApplicationEventPublisher.class);

        // This call should not throw an exception even if NATS is offline
        Connection connection = natsConfig.natsConnection(mockPublisher);

        assertThat(connection).isNotNull();
        // Status should be DISCONNECTED since NATS is offline
        assertThat(connection.getStatus()).isEqualTo(Connection.Status.DISCONNECTED);

        // Calling other connection methods should throw IllegalStateException when not connected
        assertThatThrownBy(() -> connection.createDispatcher(msg -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NATS connection is not established yet");
    }
}
