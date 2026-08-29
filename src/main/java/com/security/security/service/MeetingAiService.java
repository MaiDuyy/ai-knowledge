package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.dto.MeetingAiBufferedResponse;
import com.security.security.dto.RAGResponseDTO;
import com.security.security.dtorequest.MeetingAiRequest;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.Conversation;
import com.security.security.entity.Message;
import com.security.security.entity.enumeration.MessageInputMode;
import com.security.security.entity.enumeration.MessageStatus;
import com.security.security.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MeetingAiService {

    public static final String MEETING_SHARED_RAG_SCOPE = "MEETING_SHARED";

    private final MeetingConversationService meetingConversationService;
    private final MessageRepository messageRepository;
    private final RAGService ragService;
    private final LlmRateLimiterService llmRateLimiterService;
    private final ObjectMapper objectMapper;
    private final MeetingAiTurnPersistenceService turnPersistenceService;

    public MeetingAiBufferedResponse answer(MeetingAiRequest request) {
        validateRequest(request);

        Conversation conversation = meetingConversationService.findOrCreate(
                request.getMeetingSessionId(),
                request.getChatId(),
                request.getWorkspaceId(),
                request.getSpeakerUserId()
        );

        Optional<Message> existingAssistant = messageRepository.findByConversationIdAndTurnIdAndRole(
                conversation.getId(), request.getTurnId(), "assistant");
        if (existingAssistant.isPresent() && existingAssistant.get().getStatus() == MessageStatus.COMPLETED) {
            return fromSavedAssistant(conversation, request, existingAssistant.get());
        }

        // Persist the final transcript before invoking RAG. A retry reuses this row by turnId.
        turnPersistenceService.saveUserIfMeetingActive(
                request.getMeetingSessionId(),
                userMessage(conversation.getId(), request));

        RAGResponseDTO ragResponse;
        llmRateLimiterService.acquireRagQuery();
        try {
            ragResponse = ragService.performRAGQuery(RAGQueryPayload.builder()
                    .query(request.getMessage())
                    .userId(request.getSpeakerUserId())
                    .conversationId(conversation.getId())
                    .userPermissions(RAGQueryPayload.UserPermissionContext.builder()
                            .workspaceId(request.getWorkspaceId())
                            .ragScope(MEETING_SHARED_RAG_SCOPE)
                            .roles(List.of())
                            .userDepartments(List.of())
                            .build())
                    .build());
        } finally {
            llmRateLimiterService.releaseRagQuery();
        }

        AnswerText answerText = extractAnswerText(ragResponse.getAnswer());
        Message assistant = turnPersistenceService.saveAssistantIfMeetingActive(
                request.getMeetingSessionId(),
                assistantMessage(conversation.getId(), request.getTurnId(), ragResponse.getAnswer(), answerText));

        return new MeetingAiBufferedResponse(
                conversation.getId(),
                request.getMeetingSessionId(),
                request.getTurnId(),
                assistant.getDisplayContent(),
                assistant.getSpeechContent(),
                false
        );
    }

    private void validateRequest(MeetingAiRequest request) {
        if (request.getParticipantIds() == null || !request.getParticipantIds().contains(request.getSpeakerUserId())) {
            throw new IllegalArgumentException("speakerUserId must be an active meeting participant");
        }
        if ("ALL".equals(ScopeNormalizer.normalizeWorkspace(request.getWorkspaceId()))) {
            throw new IllegalArgumentException("Meeting AI requires a concrete workspaceId");
        }
    }

    private MeetingAiBufferedResponse fromSavedAssistant(
            Conversation conversation,
            MeetingAiRequest request,
            Message assistant
    ) {
        String display = assistant.getDisplayContent();
        String speech = assistant.getSpeechContent();
        if (display == null || display.isBlank() || speech == null || speech.isBlank()) {
            AnswerText answerText = extractAnswerText(assistant.getContent());
            display = answerText.displayText();
            speech = answerText.speechText();
        }
        return new MeetingAiBufferedResponse(
                conversation.getId(), request.getMeetingSessionId(), request.getTurnId(), display, speech, true);
    }

    private Message userMessage(Long conversationId, MeetingAiRequest request) {
        return Message.builder()
                .conversationId(conversationId)
                .role("user")
                .content(request.getMessage())
                .turnId(request.getTurnId())
                .speakerUserId(request.getSpeakerUserId())
                .speakerName(request.getSpeakerName())
                .inputMode(MessageInputMode.VOICE)
                .displayContent(request.getMessage())
                .status(MessageStatus.COMPLETED)
                .build();
    }

    private Message assistantMessage(Long conversationId, String turnId, String rawAnswer, AnswerText answerText) {
        return Message.builder()
                .conversationId(conversationId)
                .role("assistant")
                .content(rawAnswer)
                .turnId(turnId)
                .inputMode(MessageInputMode.VOICE)
                .displayContent(answerText.displayText())
                .speechContent(answerText.speechText())
                .status(MessageStatus.COMPLETED)
                .build();
    }

    private AnswerText extractAnswerText(String rawAnswer) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return new AnswerText("AI did not return an answer.", "AI did not return an answer.");
        }

        try {
            JsonNode root = objectMapper.readTree(rawAnswer);
            List<String> fragments = new ArrayList<>();
            addText(fragments, root.path("summary"));
            JsonNode details = root.path("details");
            if (details.isArray()) {
                details.forEach(detail -> addText(fragments, detail));
            }

            if (!fragments.isEmpty()) {
                String display = String.join("\n", fragments);
                return new AnswerText(display, speechSafe(display));
            }
        } catch (Exception ignored) {
            // The display fallback is preserved for diagnostics; it is never passed to TTS.
        }

        return new AnswerText(rawAnswer, "The AI response is not available in a speech-safe format.");
    }

    private void addText(List<String> fragments, JsonNode node) {
        if (node.isTextual() && !node.asText().isBlank()) {
            fragments.add(node.asText().trim());
        }
    }

    private String speechSafe(String display) {
        String speech = display
                .replaceAll("https?://\\S+", "")
                .replaceAll("\\[[^\\]]*]\\([^)]*\\)", "")
                .replaceAll("[`*_#>]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return speech.isBlank() ? "The AI response is not available in a speech-safe format." : speech;
    }

    private record AnswerText(String displayText, String speechText) {
    }
}
