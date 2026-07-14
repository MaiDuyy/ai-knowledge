package com.security.security.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class SearchIndexInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void createSearchIndexes() {
        CompletableFuture.runAsync(() -> {
            try {
                // Sleep for 5 seconds to allow Hibernate startup schema updates and transactions to complete
                Thread.sleep(5000);
                log.info("[SearchIndexInitializer] Creating/verifying indexes in background...");

                jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_embeddings_chunk_text_fts
                        ON embeddings USING GIN (to_tsvector('simple', chunk_text))
                """);

                jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_embeddings_ws_chunk_type
                        ON embeddings (workspace_id, chunk_type)
                """);

                jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_documents_processing_stage
                        ON documents (processing_stage) WHERE processing_stage != 'IDLE'
                """);

                log.info("[SearchIndexInitializer] Full-text search and pipeline indexes created/verified in background");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[SearchIndexInitializer] Background index creation interrupted");
            } catch (Exception e) {
                log.warn("[SearchIndexInitializer] Could not create indexes in background (table may not exist yet): {}", e.getMessage());
            }
        });
    }
}
