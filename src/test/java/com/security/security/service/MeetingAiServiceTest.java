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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
