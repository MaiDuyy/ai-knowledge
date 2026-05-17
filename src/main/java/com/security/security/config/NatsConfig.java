package com.security.security.config;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Duration;

@Configuration
@Slf4j
public class NatsConfig {

    @Value("${nats.url:nats://localhost:4222}")
    private String natsUrl;

    @Bean
    public Connection natsConnection() throws IOException, InterruptedException {
        Options options = new Options.Builder()
                .server(natsUrl)
                .connectionName("ai-knowledge-service")
                .maxReconnects(-1)                          // reconnect indefinitely
                .reconnectWait(Duration.ofSeconds(2))
                .connectionTimeout(Duration.ofSeconds(10))
                .errorListener(new io.nats.client.ErrorListener() {
                    @Override
                    public void exceptionOccurred(Connection conn, Exception exp) {
                        log.error("[NATS] Exception: {}", exp.getMessage());
                    }

                    @Override
                    public void slowConsumerDetected(Connection conn, io.nats.client.Consumer consumer) {
                        log.warn("[NATS] Slow consumer detected");
                    }
                })
                .connectionListener((conn, type) ->
                        log.info("[NATS] Connection event: {} | status: {}", type, conn.getStatus()))
                .build();

        try {
            Connection conn = Nats.connect(options);
            log.info("[NATS] Connected to {}", natsUrl);
            return conn;
        } catch (Exception e) {
            log.warn("[NATS] Could not connect to {} — document events will not be received. Cause: {}",
                    natsUrl, e.getMessage());
            // Return a disconnected connection instead of failing startup
            // ai-knowledge can still serve requests without NATS
            return Nats.connect(new Options.Builder()
                    .server(natsUrl)
                    .maxReconnects(-1)
                    .reconnectWait(Duration.ofSeconds(5))
                    .build());
        }
    }
}
