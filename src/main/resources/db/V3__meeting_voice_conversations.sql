-- Run this migration against the ai_knowledge PostgreSQL schema before deploying
-- the Meeting AI Voice service version that uses the new JPA fields.

ALTER TABLE ai_knowledge.conversations
    ADD COLUMN IF NOT EXISTS meeting_session_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS scope VARCHAR(20),
    ADD COLUMN IF NOT EXISTS status VARCHAR(20),
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(255),
    ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;

UPDATE ai_knowledge.conversations
SET scope = 'PERSONAL'
WHERE scope IS NULL;

UPDATE ai_knowledge.conversations
SET status = 'ACTIVE'
WHERE status IS NULL;

UPDATE ai_knowledge.conversations
SET created_by = user_id
WHERE created_by IS NULL;

ALTER TABLE ai_knowledge.conversations
    ALTER COLUMN user_id DROP NOT NULL,
    ALTER COLUMN scope SET DEFAULT 'PERSONAL',
    ALTER COLUMN scope SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'ACTIVE',
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN created_by SET NOT NULL;

ALTER TABLE ai_knowledge.messages
    ADD COLUMN IF NOT EXISTS turn_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS speaker_user_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS speaker_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS input_mode VARCHAR(20),
    ADD COLUMN IF NOT EXISTS display_content TEXT,
    ADD COLUMN IF NOT EXISTS speech_content TEXT,
    ADD COLUMN IF NOT EXISTS status VARCHAR(20);

UPDATE ai_knowledge.messages
SET input_mode = 'TEXT'
WHERE input_mode IS NULL;

UPDATE ai_knowledge.messages
SET display_content = content
WHERE display_content IS NULL;

UPDATE ai_knowledge.messages
SET status = 'COMPLETED'
WHERE status IS NULL;

ALTER TABLE ai_knowledge.messages
    ALTER COLUMN input_mode SET DEFAULT 'TEXT',
    ALTER COLUMN input_mode SET NOT NULL,
    ALTER COLUMN display_content SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'COMPLETED',
    ALTER COLUMN status SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_conversations_meeting_session_id
    ON ai_knowledge.conversations (meeting_session_id)
    WHERE scope = 'MEETING' AND meeting_session_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_meeting_cleanup
    ON ai_knowledge.conversations (scope, status, expires_at)
    WHERE scope = 'MEETING';

CREATE UNIQUE INDEX IF NOT EXISTS ux_messages_conversation_turn_role
    ON ai_knowledge.messages (conversation_id, turn_id, role)
    WHERE turn_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_messages_conversation_created_at
    ON ai_knowledge.messages (conversation_id, created_at);
