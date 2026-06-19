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

            log.info("[DatabaseMigrationBootstrapper] Legacy permission scope migration completed successfully.");
        } catch (Exception e) {
            log.error("[DatabaseMigrationBootstrapper] Failed to execute database migration for sentinel values", e);
        }
    }
}
