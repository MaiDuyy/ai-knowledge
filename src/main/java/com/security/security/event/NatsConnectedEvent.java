package com.security.security.event;

import io.nats.client.Connection;
import org.springframework.context.ApplicationEvent;

public class NatsConnectedEvent extends ApplicationEvent {
    private final Connection connection;

    public NatsConnectedEvent(Object source, Connection connection) {
        super(source);
        this.connection = connection;
    }

    public Connection getConnection() {
        return connection;
    }
}
