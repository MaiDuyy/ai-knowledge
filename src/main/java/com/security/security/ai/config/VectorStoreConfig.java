package com.security.security.ai.config;

import org.springframework.context.annotation.Configuration;

/**
 * VectorStore Configuration
 * 
 * MariaDB VectorStore is auto-configured by spring-ai-starter-vector-store-mariadb
 * Configuration is done in application.properties:
 * - spring.ai.vectorstore.mariadb.initialize-schema=true
 * - spring.ai.vectorstore.mariadb.distance-type=cosine
 * - spring.ai.vectorstore.mariadb.dimensions=768
 */
@Configuration
public class VectorStoreConfig {
    // MariaDB VectorStore is auto-configured by Spring AI starter
    // Custom configuration for user-specific filtering can be added here if needed
}
