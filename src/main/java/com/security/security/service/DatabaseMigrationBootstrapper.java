package com.security.security.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Slf4j
@RequiredArgsConstructor
public class DatabaseMigrationBootstrapper implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(String... args) {
        log.info("[DatabaseMigrationBootstrapper] Starting legacy permission scope sentinel value migration...");
        try {
            int docsWs = jdbcTemplate.update(
                "UPDATE documents SET workspace_id = 'ALL' " +
                "WHERE workspace_id IS NULL OR TRIM(workspace_id) = '' " +
                "OR LOWER(TRIM(workspace_id)) IN ('default-workspace', 'workspace-default', 'all', 'global')"
            );
            log.info("Migrated {} documents workspace_id to 'ALL'", docsWs);

            int docsDept = jdbcTemplate.update(
                "UPDATE documents SET department_id = 'ALL' " +
                "WHERE department_id IS NULL OR TRIM(department_id) = '' " +
                "OR LOWER(TRIM(department_id)) IN ('all', 'default', 'global')"
            );
            log.info("Migrated {} documents department_id to 'ALL'", docsDept);

            int wikiWs = jdbcTemplate.update(
                "UPDATE wiki_pages SET workspace_id = 'ALL' " +
                "WHERE workspace_id IS NULL OR TRIM(workspace_id) = '' " +
                "OR LOWER(TRIM(workspace_id)) IN ('default-workspace', 'workspace-default', 'all', 'global')"
            );
            log.info("Migrated {} wiki_pages workspace_id to 'ALL'", wikiWs);

            int wikiDept = jdbcTemplate.update(
                "UPDATE wiki_pages SET department_id = 'ALL' " +
                "WHERE department_id IS NULL OR TRIM(department_id) = '' " +
                "OR LOWER(TRIM(department_id)) IN ('all', 'default', 'global')"
            );
            log.info("Migrated {} wiki_pages department_id to 'ALL'", wikiDept);

            int draftWs = jdbcTemplate.update(
                "UPDATE wiki_page_drafts SET workspace_id = 'ALL' " +
                "WHERE workspace_id IS NULL OR TRIM(workspace_id) = '' " +
                "OR LOWER(TRIM(workspace_id)) IN ('default-workspace', 'workspace-default', 'all', 'global')"
            );
            log.info("Migrated {} wiki_page_drafts workspace_id to 'ALL'", draftWs);

            int draftDept = jdbcTemplate.update(
                "UPDATE wiki_page_drafts SET department_id = 'ALL' " +
                "WHERE department_id IS NULL OR TRIM(department_id) = '' " +
                "OR LOWER(TRIM(department_id)) IN ('all', 'default', 'global')"
            );
            log.info("Migrated {} wiki_page_drafts department_id to 'ALL'", draftDept);

            int embedWs = jdbcTemplate.update(
                "UPDATE embeddings SET workspace_id = 'ALL' " +
                "WHERE workspace_id IS NULL OR TRIM(workspace_id) = '' " +
                "OR LOWER(TRIM(workspace_id)) IN ('default-workspace', 'workspace-default', 'all', 'global')"
            );
            log.info("Migrated {} embeddings workspace_id to 'ALL'", embedWs);

            // PostgreSQL-specific vector_store JSONB metadata migration & GIN index creation
            try (java.sql.Connection conn = jdbcTemplate.getDataSource().getConnection()) {
                String dbProduct = conn.getMetaData().getDatabaseProductName();
                if (dbProduct != null && dbProduct.toLowerCase().contains("postgres")) {
                    log.info("PostgreSQL database detected. Checking vector_store table...");

                    // Check if vector_store table exists
                    boolean vsTableExists = false;
                    String schema = "ai_knowledge";
                    try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, schema, "vector_store", null)) {
                        if (rs.next()) {
                            vsTableExists = true;
                        }
                    }
                    if (!vsTableExists) {
                        try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, schema.toLowerCase(), "vector_store", null)) {
                            if (rs.next()) {
                                vsTableExists = true;
                            }
                        }
                    }
                    if (!vsTableExists) {
                        try (java.sql.ResultSet rs = conn.getMetaData().getTables(null, null, "vector_store", null)) {
                            if (rs.next()) {
                                vsTableExists = true;
                            }
                        }
                    }

                    if (vsTableExists) {
                        log.info("vector_store table exists. Altering metadata column type to jsonb and migrating...");

                        // 0. Alter column metadata type to JSONB for performance and index support
                        try {
                            jdbcTemplate.execute("ALTER TABLE vector_store ALTER COLUMN metadata TYPE jsonb USING metadata::jsonb");
                            log.info("Successfully altered vector_store metadata column to JSONB");
                        } catch (Exception e) {
                            log.info("Could not alter vector_store metadata column to JSONB (it might already be JSONB): {}", e.getMessage());
                        }

                        // 1. Migrate workspaceId in JSONB metadata
                        int vsWs = jdbcTemplate.update(
                            "UPDATE vector_store SET metadata = jsonb_set(CAST(metadata AS jsonb), '{workspaceId}', '\"ALL\"') " +
                            "WHERE metadata->>'workspaceId' IS NULL OR TRIM(metadata->>'workspaceId') = '' " +
                            "OR LOWER(TRIM(metadata->>'workspaceId')) IN ('default-workspace', 'workspace-default', 'all', 'global')"
                        );
                        log.info("Migrated {} vector_store records workspaceId to 'ALL'", vsWs);

                        // 2. Migrate departmentId in JSONB metadata
                        int vsDept = jdbcTemplate.update(
                            "UPDATE vector_store SET metadata = jsonb_set(CAST(metadata AS jsonb), '{departmentId}', '\"ALL\"') " +
                            "WHERE metadata->>'departmentId' IS NULL OR TRIM(metadata->>'departmentId') = '' " +
                            "OR LOWER(TRIM(metadata->>'departmentId')) IN ('all', 'default', 'global')"
                        );
                        log.info("Migrated {} vector_store records departmentId to 'ALL'", vsDept);

                        // 3. Migrate allowedRoles in JSONB metadata
                        int vsRoles = jdbcTemplate.update(
                            "UPDATE vector_store SET metadata = jsonb_set(CAST(metadata AS jsonb), '{allowedRoles}', '\"ALL\"') " +
                            "WHERE metadata->>'allowedRoles' IS NULL OR TRIM(metadata->>'allowedRoles') = ''"
                        );
                        log.info("Migrated {} vector_store records allowedRoles to 'ALL'", vsRoles);

                        // 4. Migrate classification / securityClassification in JSONB metadata
                        int vsClass = jdbcTemplate.update(
                            "UPDATE vector_store SET metadata = jsonb_set(jsonb_set(CAST(metadata AS jsonb), '{classification}', '\"INTERNAL\"'), '{securityClassification}', '\"INTERNAL\"') " +
                            "WHERE metadata->>'classification' IS NULL OR metadata->>'securityClassification' IS NULL " +
                            "OR TRIM(metadata->>'classification') = '' OR TRIM(metadata->>'securityClassification') = ''"
                        );
                        log.info("Migrated {} vector_store records classification/securityClassification to 'INTERNAL'", vsClass);

                        // 5. Create GIN index on metadata column
                        try {
                            jdbcTemplate.execute(
                                "CREATE INDEX IF NOT EXISTS vector_store_metadata_gin_idx ON vector_store USING gin (metadata)"
                            );
                            log.info("GIN index verified/created on vector_store(metadata)");
                        } catch (Exception e) {
                            log.warn("Failed to create GIN index directly on metadata (trying expression GIN index): {}", e.getMessage());
                            try {
                                jdbcTemplate.execute(
                                    "CREATE INDEX IF NOT EXISTS vector_store_metadata_gin_expr_idx ON vector_store USING gin (CAST(metadata AS jsonb))"
                                );
                                log.info("Expression GIN index created successfully on CAST(metadata AS jsonb)");
                            } catch (Exception ex) {
                                log.error("Failed to create expression GIN index: {}", ex.getMessage());
                            }
                        }
                    } else {
                        log.info("vector_store table does not exist yet. Skipping pgvector metadata migration.");
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to migrate vector_store metadata or create GIN index (ignoring for non-PostgreSQL): {}", e.getMessage());
            }

            log.info("[DatabaseMigrationBootstrapper] Legacy permission scope migration completed successfully.");
        } catch (Exception e) {
            log.error("[DatabaseMigrationBootstrapper] Failed to execute database migration for sentinel values", e);
        }
    }
}
