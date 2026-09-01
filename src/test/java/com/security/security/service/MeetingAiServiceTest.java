package com.security.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.dto.MeetingAiBufferedResponse;
import com.security.security.dto.RAGResponseDTO;
import com.security.security.dtorequest.MeetingAiRequest;
import com.security.security.dtorequest.RAGQueryPayload;
import com.security.security.entity.Conversation;
import com.security.security.entity.Message;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.entity.enumeration.MessageInputMode;
import com.security.security.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingAiServiceTest {

    @Mock
    private MeetingConversationService meetingConversationService;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private RAGService ragService;
    @Mock
    private LlmRateLimiterService llmRateLimiterService;
    @Mock
    private MeetingAiTurnPersistenceService turnPersistenceService;

    private MeetingAiService meetingAiService;

    @BeforeEach
    void setUp() {
        meetingAiService = new MeetingAiService(
                meetingConversationService,
                messageRepository,
                ragService,
                llmRateLimiterService,
                new ObjectMapper(),
                turnPersistenceService
        );
    }

    @Test
    void persistsVoiceTranscriptAndBufferedAssistantResponse() {
        Conversation conversation = activeConversation();
        when(meetingConversationService.findOrCreate("meeting-1", "chat-1", "workspace-1", "user-1"))
                .thenReturn(conversation);
        when(messageRepository.findByConversationIdAndTurnIdAndRole(10L, "turn-1", "assistant"))
                .thenReturn(Optional.empty());
        when(ragService.performRAGQuery(any(RAGQueryPayload.class))).thenReturn(RAGResponseDTO.builder()
                .answer("{\"summary\":\"Short answer\",\"details\":[\"First detail\",\"Second detail\"]}")
                .build());
        when(turnPersistenceService.saveUserIfMeetingActive(any(), any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(turnPersistenceService.saveAssistantIfMeetingActive(any(), any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        MeetingAiBufferedResponse response = meetingAiService.answer(request());

        ArgumentCaptor<Message> messages = ArgumentCaptor.forClass(Message.class);
        verify(turnPersistenceService).saveUserIfMeetingActive(
                org.mockito.ArgumentMatchers.eq("meeting-1"), messages.capture());
        Message transcript = messages.getValue();
        ArgumentCaptor<Message> answerCaptor = ArgumentCaptor.forClass(Message.class);
        verify(turnPersistenceService).saveAssistantIfMeetingActive(
                org.mockito.ArgumentMatchers.eq("meeting-1"), answerCaptor.capture());
        Message answer = answerCaptor.getValue();
        assertThat(transcript.getRole()).isEqualTo("user");
        assertThat(transcript.getInputMode()).isEqualTo(MessageInputMode.VOICE);
        assertThat(transcript.getSpeakerUserId()).isEqualTo("user-1");
        assertThat(answer.getRole()).isEqualTo("assistant");
        assertThat(answer.getTurnId()).isEqualTo("turn-1");
        assertThat(answer.getDisplayContent()).isEqualTo("Short answer\nFirst detail\nSecond detail");
        assertThat(answer.getSpeechContent()).isEqualTo("Short answer First detail Second detail");
        assertThat(response.replayed()).isFalse();
        assertThat(response.speechText()).isEqualTo("Short answer First detail Second detail");
        verify(llmRateLimiterService).acquireRagQuery();
        verify(llmRateLimiterService).releaseRagQuery();
    }

    @Test
    void completedAssistantTurnIsReturnedWithoutRunningRagAgain() {
        Conversation conversation = activeConversation();
        Message existing = Message.builder()
                .conversationId(10L)
                .role("assistant")
                .turnId("turn-1")
                .displayContent("Stored display")
                .speechContent("Stored speech")
                .status(com.security.security.entity.enumeration.MessageStatus.COMPLETED)
                .build();
        when(meetingConversationService.findOrCreate("meeting-1", "chat-1", "workspace-1", "user-1"))
                .thenReturn(conversation);
        when(messageRepository.findByConversationIdAndTurnIdAndRole(10L, "turn-1", "assistant"))
                .thenReturn(Optional.of(existing));

        MeetingAiBufferedResponse response = meetingAiService.answer(request());

        assertThat(response.replayed()).isTrue();
        assertThat(response.displayText()).isEqualTo("Stored display");
        verify(ragService, never()).performRAGQuery(any());
        verify(messageRepository, never()).saveAndFlush(any());
        verify(turnPersistenceService, never()).saveUserIfMeetingActive(any(), any());
        verify(turnPersistenceService, never()).saveAssistantIfMeetingActive(any(), any());
        verify(llmRateLimiterService, never()).acquireRagQuery();
    }

    @Test
    void streamsOnlySpeechSafeSummaryAndDetailsThenCompletesAssistantOnce() {
        Conversation conversation = activeConversation();
        Message streaming = Message.builder()
                .id(21L)
                .conversationId(10L)
                .role("assistant")
                .turnId("turn-1")
                .content("")
                .displayContent("")
                .speechContent("")
                .status(com.security.security.entity.enumeration.MessageStatus.STREAMING)
                .build();
        when(meetingConversationService.findOrCreate("meeting-1", "chat-1", "workspace-1", "user-1"))
                .thenReturn(conversation);
        when(messageRepository.findByConversationIdAndTurnIdAndRole(10L, "turn-1", "assistant"))
                .thenReturn(Optional.empty());
        when(turnPersistenceService.saveUserIfMeetingActive(any(), any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(turnPersistenceService.claimAssistantStreaming(any(), any(Message.class)))
                .thenReturn(new MeetingAiTurnPersistenceService.AssistantTurnClaim(streaming, true));
        when(ragService.generateMeetingAnswerStream(any(), any(), any(), any()))
                .thenReturn(new RAGService.MeetingAnswerStream(Flux.just(
                        "{\"summary\":\"Tóm tắt\",\"details\":[\"[1] Chi tiết\",",
                        "\"Nội dung [tài liệu](https://example.test)\"],\"sources\":[]}"),
                        List.of(new RAGService.MeetingSource("document-1", "Policy", "chunk-1"))));
        when(turnPersistenceService.completeAssistantIfMeetingActive(any(), any(), any(), any()))
                .thenAnswer(invocation -> streaming);

        var events = meetingAiService.answerStream(request()).collectList().block();

        assertThat(events).extracting(event -> event.type())
                .containsExactly("source", "speech.delta", "display.delta", "speech.delta", "display.delta", "speech.delta", "display.delta", "done");
        assertThat(events.get(0)).isInstanceOf(com.security.security.dto.MeetingAiStreamEvent.Source.class);
        assertThat(events.get(1)).isInstanceOf(com.security.security.dto.MeetingAiStreamEvent.SpeechDelta.class);
        var speech = (com.security.security.dto.MeetingAiStreamEvent.SpeechDelta) events.get(3);
        assertThat(speech.text()).doesNotContain("[1]", "https://", "[");
        verify(turnPersistenceService).completeAssistantIfMeetingActive(
                org.mockito.ArgumentMatchers.eq("meeting-1"), org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.eq("Tóm tắt\n[1] Chi tiết\nNội dung [tài liệu](https://example.test)"),
                org.mockito.ArgumentMatchers.eq("Tóm tắt Chi tiết Nội dung"));
        verify(llmRateLimiterService).acquireRagQuery();
        verify(llmRateLimiterService).releaseRagQuery();
    }

    @Test
    void marksStreamingAssistantFailedWhenProviderErrorsBeforeDone() {
        Conversation conversation = activeConversation();
        Message streaming = Message.builder()
                .id(21L).conversationId(10L).role("assistant").turnId("turn-1")
                .content("").displayContent("").speechContent("")
                .status(com.security.security.entity.enumeration.MessageStatus.STREAMING).build();
        when(meetingConversationService.findOrCreate("meeting-1", "chat-1", "workspace-1", "user-1"))
                .thenReturn(conversation);
        when(messageRepository.findByConversationIdAndTurnIdAndRole(10L, "turn-1", "assistant"))
                .thenReturn(Optional.empty());
        when(turnPersistenceService.saveUserIfMeetingActive(any(), any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(turnPersistenceService.claimAssistantStreaming(any(), any(Message.class)))
                .thenReturn(new MeetingAiTurnPersistenceService.AssistantTurnClaim(streaming, true));
        when(ragService.generateMeetingAnswerStream(any(), any(), any(), any()))
                .thenReturn(new RAGService.MeetingAnswerStream(
                        Flux.error(new IllegalStateException("provider failed")), List.of()));

        assertThatThrownBy(() -> meetingAiService.answerStream(request()).blockLast())
                .hasMessage("provider failed");

        verify(turnPersistenceService, times(1)).failAssistantIfMeetingActive("meeting-1", 21L);
        verify(turnPersistenceService, never()).completeAssistantIfMeetingActive(any(), any(), any(), any());
        verify(llmRateLimiterService).releaseRagQuery();
    }

    private MeetingAiRequest request() {
        return new MeetingAiRequest(
                "meeting-1",
                "chat-1",
                "workspace-1",
                "turn-1",
                "user-1",
                "User One",
                List.of("user-1", "user-2"),
                "What is the policy?"
        );
    }

    private Conversation activeConversation() {
        return Conversation.builder()
                .id(10L)
                .meetingSessionId("meeting-1")
                .chatId("chat-1")
                .workspaceId("workspace-1")
                .createdBy("user-1")
                .scope(ConversationScope.MEETING)
                .status(ConversationStatus.ACTIVE)
                .build();
    }
}
