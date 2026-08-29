package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.Message;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.repository.ConversationRepository;
import com.security.security.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingAiTurnPersistenceServiceTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MessageRepository messageRepository;
    @InjectMocks
    private MeetingAiTurnPersistenceService persistenceService;

    @Test
    void rejectsLateAssistantAfterMeetingEnteredEndingState() {
        Conversation conversation = Conversation.builder()
                .id(10L)
                .meetingSessionId("meeting-1")
                .scope(ConversationScope.MEETING)
                .status(ConversationStatus.ENDING)
                .build();
        Message assistant = Message.builder().conversationId(10L).turnId("turn-1").role("assistant").build();
        when(conversationRepository.findByMeetingSessionIdForUpdate("meeting-1"))
                .thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> persistenceService.saveAssistantIfMeetingActive("meeting-1", assistant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting conversation is no longer active");
        verify(messageRepository, never()).saveAndFlush(assistant);
    }

    @Test
    void rejectsLateUserTranscriptAfterMeetingEnteredEndingState() {
        Conversation conversation = Conversation.builder()
                .id(10L)
                .meetingSessionId("meeting-1")
                .scope(ConversationScope.MEETING)
                .status(ConversationStatus.ENDING)
                .build();
        Message transcript = Message.builder().conversationId(10L).turnId("turn-1").role("user").build();
        when(conversationRepository.findByMeetingSessionIdForUpdate("meeting-1"))
                .thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> persistenceService.saveUserIfMeetingActive("meeting-1", transcript))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting conversation is no longer active");
        verify(messageRepository, never()).saveAndFlush(transcript);
    }
}
