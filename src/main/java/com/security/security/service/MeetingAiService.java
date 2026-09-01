package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.dto.MeetingAiBufferedResponse;
import com.security.security.dto.MeetingAiStreamEvent;
import com.security.security.dto.RAGResponseDTO;
import com.security.security.dtorequest.MeetingAiRequest;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.Conversation;
import com.security.security.entity.Message;
import com.security.security.entity.enumeration.MessageInputMode;
import com.security.security.entity.enumeration.MessageStatus;
import com.security.security.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * Emits a typed stream for voice-service. The buffered endpoint remains
     * unchanged for the Phase 1 fallback path.
     */
    public Flux<MeetingAiStreamEvent> answerStream(MeetingAiRequest request) {
        return Flux.defer(() -> answerStreamDeferred(request));
    }

    private Flux<MeetingAiStreamEvent> answerStreamDeferred(MeetingAiRequest request) {
        validateRequest(request);

        Conversation conversation = meetingConversationService.findOrCreate(
                request.getMeetingSessionId(),
                request.getChatId(),
                request.getWorkspaceId(),
                request.getSpeakerUserId()
        );

        Optional<Message> completed = messageRepository.findByConversationIdAndTurnIdAndRole(
                conversation.getId(), request.getTurnId(), "assistant");
        if (completed.isPresent() && completed.get().getStatus() == MessageStatus.COMPLETED) {
            return replaySavedAssistant(request, conversation, completed.get());
        }

        turnPersistenceService.saveUserIfMeetingActive(
                request.getMeetingSessionId(), userMessage(conversation.getId(), request));
        MeetingAiTurnPersistenceService.AssistantTurnClaim claim = turnPersistenceService.claimAssistantStreaming(
                request.getMeetingSessionId(), assistantStreamingMessage(conversation.getId(), request.getTurnId()));

        if (!claim.created()) {
            if (claim.message().getStatus() == MessageStatus.COMPLETED) {
                return replaySavedAssistant(request, conversation, claim.message());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Meeting AI turn is already terminal or streaming");
        }

        return generateStream(request, conversation, claim.message());
    }

    private Flux<MeetingAiStreamEvent> generateStream(MeetingAiRequest request, Conversation conversation,
                                                       Message assistant) {
        MeetingAiStreamTextExtractor.Session extractor = new MeetingAiStreamTextExtractor().open();
        StringBuilder display = new StringBuilder();
        StringBuilder speech = new StringBuilder();
        AtomicLong displaySequence = new AtomicLong();
        AtomicLong speechSequence = new AtomicLong();
        AtomicLong firstDeltaNanos = new AtomicLong(-1L);
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        long startedAtNanos = System.nanoTime();

        llmRateLimiterService.acquireRagQuery();
        RAGService.MeetingAnswerStream meetingAnswerStream;
        try {
            meetingAnswerStream = ragService.generateMeetingAnswerStream(
                    conversation.getId(),
                    request.getMessage(),
                    request.getSpeakerUserId(),
                    RAGQueryPayload.UserPermissionContext.builder()
                            .workspaceId(request.getWorkspaceId())
                            .ragScope(MEETING_SHARED_RAG_SCOPE)
                            .roles(List.of())
                            .userDepartments(List.of())
                            .build());
        } catch (RuntimeException exception) {
            llmRateLimiterService.releaseRagQuery();
            markFailed(request.getMeetingSessionId(), assistant.getId(), failed);
            return Flux.error(exception);
        }

        AtomicLong sourceSequence = new AtomicLong();
        Flux<MeetingAiStreamEvent> sourceEvents = Flux.fromIterable(meetingAnswerStream.sources())
                .map(source -> MeetingAiStreamEvent.Source.of(request.getTurnId(), sourceSequence.getAndIncrement(),
                        source.documentId(), source.title(), source.chunkId()));

        Flux<MeetingAiStreamEvent> responseEvents = meetingAnswerStream.tokens()
                .concatMap(chunk -> Flux.fromIterable(extractor.accept(chunk))
                        .flatMapIterable(fragment -> eventsForFragment(request.getTurnId(), fragment, display, speech,
                                displaySequence, speechSequence, firstDeltaNanos, startedAtNanos)))
                .concatWith(Flux.defer(() -> {
                    List<MeetingAiStreamEvent> trailing = new ArrayList<>(eventsForFragments(
                            request.getTurnId(), extractor.complete(), display, speech, displaySequence,
                            speechSequence, firstDeltaNanos, startedAtNanos));
                    long firstDeltaMs = firstDeltaNanos.get() < 0
                            ? 0L
                            : (firstDeltaNanos.get() - startedAtNanos) / 1_000_000L;
                    long totalMs = (System.nanoTime() - startedAtNanos) / 1_000_000L;
                    turnPersistenceService.completeAssistantIfMeetingActive(
                            request.getMeetingSessionId(), assistant.getId(), display.toString(), speech.toString());
                    completed.set(true);
                    trailing.add(MeetingAiStreamEvent.Done.of(request.getTurnId(), false, firstDeltaMs, totalMs));
                    return Flux.fromIterable(trailing);
                }));

        return sourceEvents.concatWith(responseEvents)
                .doOnError(error -> markFailed(request.getMeetingSessionId(), assistant.getId(), failed))
                .doOnCancel(() -> markFailed(request.getMeetingSessionId(), assistant.getId(), failed))
                .doFinally(signal -> {
                    if (!completed.get()) {
                        markFailed(request.getMeetingSessionId(), assistant.getId(), failed);
                    }
                    extractor.close();
                    llmRateLimiterService.releaseRagQuery();
                });
    }

    private List<MeetingAiStreamEvent> eventsForFragments(String turnId,
                                                           List<MeetingAiStreamTextExtractor.TextFragment> fragments,
                                                           StringBuilder display, StringBuilder speech,
                                                           AtomicLong displaySequence, AtomicLong speechSequence,
                                                           AtomicLong firstDeltaNanos, long startedAtNanos) {
        List<MeetingAiStreamEvent> events = new ArrayList<>();
        for (MeetingAiStreamTextExtractor.TextFragment fragment : fragments) {
            events.addAll(eventsForFragment(turnId, fragment, display, speech, displaySequence, speechSequence,
                    firstDeltaNanos, startedAtNanos));
        }
        return events;
    }

    private List<MeetingAiStreamEvent> eventsForFragment(String turnId,
                                                          MeetingAiStreamTextExtractor.TextFragment fragment,
                                                          StringBuilder display, StringBuilder speech,
                                                          AtomicLong displaySequence, AtomicLong speechSequence,
                                                          AtomicLong firstDeltaNanos, long startedAtNanos) {
        if (firstDeltaNanos.compareAndSet(-1L, System.nanoTime())) {
            // Timestamp is intentionally retained only as latency metadata.
        }
        display.append(fragment.displayText());
        speech.append(fragment.speechText());
        return List.of(
                MeetingAiStreamEvent.SpeechDelta.of(turnId, speechSequence.getAndIncrement(), fragment.speechText()),
                MeetingAiStreamEvent.DisplayDelta.of(turnId, displaySequence.getAndIncrement(), fragment.displayText())
        );
    }

    private Flux<MeetingAiStreamEvent> replaySavedAssistant(MeetingAiRequest request, Conversation conversation,
                                                             Message assistant) {
        MeetingAiBufferedResponse response = fromSavedAssistant(conversation, request, assistant);
        List<MeetingAiStreamEvent> events = new ArrayList<>();
        if (!response.speechText().isBlank()) {
            events.add(MeetingAiStreamEvent.SpeechDelta.of(request.getTurnId(), 0, response.speechText()));
        }
        if (!response.displayText().isBlank()) {
            events.add(MeetingAiStreamEvent.DisplayDelta.of(request.getTurnId(), 0, response.displayText()));
        }
        events.add(MeetingAiStreamEvent.Done.of(request.getTurnId(), true, 0, 0));
        return Flux.fromIterable(events);
    }

    private void markFailed(String meetingSessionId, Long assistantMessageId, AtomicBoolean failed) {
        if (!failed.compareAndSet(false, true)) {
            return;
        }
        try {
            turnPersistenceService.failAssistantIfMeetingActive(meetingSessionId, assistantMessageId);
        } catch (RuntimeException ignored) {
            // Meeting cleanup may have already removed or ended the conversation.
        }
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

    private Message assistantStreamingMessage(Long conversationId, String turnId) {
        return Message.builder()
                .conversationId(conversationId)
                .role("assistant")
                .content("")
                .turnId(turnId)
                .inputMode(MessageInputMode.VOICE)
                .displayContent("")
                .speechContent("")
                .status(MessageStatus.STREAMING)
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
