package com.security.security.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SearchIndexInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void createSearchIndexes() {
        try {
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

            log.info("[SearchIndexInitializer] Full-text search and pipeline indexes created/verified");
        } catch (Exception e) {
            log.warn("[SearchIndexInitializer] Could not create indexes (table may not exist yet): {}", e.getMessage());
        }
    }
}
