-- WeKnora-inspired Pipeline Enhancements Migration
-- Hibernate ddl-auto=update handles column creation automatically.
-- This script adds performance indexes that Hibernate cannot create.

-- GIN index for PostgreSQL full-text search (tsvector) on embeddings.chunk_text
-- Enables fast BM25-equivalent keyword search via to_tsvector/plainto_tsquery
CREATE INDEX IF NOT EXISTS idx_embeddings_chunk_text_fts
    ON embeddings USING GIN (to_tsvector('simple', chunk_text));

-- Composite index for keyword search filtered by workspace
CREATE INDEX IF NOT EXISTS idx_embeddings_ws_chunk_type
    ON embeddings (workspace_id, chunk_type);

-- Index for fast document pending_subtasks lookup during post-processing
CREATE INDEX IF NOT EXISTS idx_documents_processing_stage
    ON documents (processing_stage) WHERE processing_stage != 'IDLE';

-- Index for context header lookups
CREATE INDEX IF NOT EXISTS idx_embeddings_context_header
    ON embeddings (document_id, context_header) WHERE context_header IS NOT NULL;
