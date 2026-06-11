package com.security.security.config;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.security.security.event.NatsConnectedEvent;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;

@Configuration
@Slf4j
public class NatsConfig {

    @Value("${nats.url:nats://localhost:4222}")
    private String natsUrl;

    private volatile Connection delegate = null;

    @Bean
    public Connection natsConnection(ApplicationEventPublisher eventPublisher) {
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
            delegate = conn;
        } catch (Exception e) {
            log.warn("[NATS] Could not connect to {} on startup — will retry in background. Cause: {}",
                    natsUrl, e.getMessage());
            startConnectionRetry(options, eventPublisher);
        }

        return (Connection) Proxy.newProxyInstance(
                NatsConfig.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getName().equals("getStatus")) {
                            return delegate != null ? delegate.getStatus() : Connection.Status.DISCONNECTED;
                        }
                        if (method.getName().equals("close")) {
                            if (delegate != null) {
                                delegate.close();
                            }
                            return null;
                        }
                        if (method.getName().equals("equals")) {
                            return proxy == args[0];
                        }
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (method.getName().equals("toString")) {
                            return "NatsConnectionProxy[delegate=" + (delegate != null ? delegate.toString() : "null") + "]";
                        }
                        if (delegate == null) {
                            throw new IllegalStateException("NATS connection is not established yet");
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    }
                }
        );
    }

    private void startConnectionRetry(Options options, ApplicationEventPublisher eventPublisher) {
        Thread thread = new Thread(() -> {
            while (delegate == null) {
                try {
                    log.info("[NATS] Attempting to connect to NATS in background...");
                    Connection conn = Nats.connect(options);
                    delegate = conn;
                    log.info("[NATS] Successfully connected to NATS at {}", natsUrl);
                    eventPublisher.publishEvent(new NatsConnectedEvent(this, conn));
                } catch (Exception e) {
                    log.debug("[NATS] Background connection attempt failed. Retrying in 5 seconds...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        thread.setName("nats-connection-retry");
        thread.setDaemon(true);
        thread.start();
    }
}

