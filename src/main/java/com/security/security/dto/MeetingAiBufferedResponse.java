package com.security.security.dto;

/**
 * Buffered response used by the Phase 1 voice-service integration.
 * Typed SSE events replace this contract in the streaming phase.
 */
public record MeetingAiBufferedResponse(
        Long conversationId,
        String meetingSessionId,
        String turnId,
        String displayText,
        String speechText,
        boolean replayed
) {
}
