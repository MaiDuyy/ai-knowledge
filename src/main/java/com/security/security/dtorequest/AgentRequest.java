package com.security.security.dtorequest;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 2: Agent request DTO.
 * Extends the basic chat request with context fields so the
 * agent can call tools that operate on a specific chat/workspace.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {
    /** Existing conversation (from Phase 1). Null = create new. */
    private Long conversationId;
    /** The user's natural-language query. */
    private String message;
    /** Current chat room context — lets tools like summarize_chat & create_task work. */
    private String chatId;
    /** Optional workspace context. */
    private String workspaceId;
    /** Optional LLM provider (gemini, openai, anthropic). Default is gemini. */
    private String provider;
    /** Optional AgentSkill ID to use custom system prompt instead of default. */
    private Long skillId;
}
