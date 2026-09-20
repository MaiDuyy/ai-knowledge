package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingConversationServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @InjectMocks
    private MeetingConversationService meetingConversationService;

    @Captor
    private ArgumentCaptor<Conversation> conversationCaptor;

    @Test
    void createsActiveMeetingConversationWhenSessionDoesNotExist() {
        when(conversationRepository.findByMeetingSessionId("meeting-1")).thenReturn(Optional.empty());
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Conversation conversation = meetingConversationService.findOrCreate(
                "meeting-1", "chat-1", "workspace-1", "user-1");

        verify(conversationRepository).saveAndFlush(conversationCaptor.capture());
        assertThat(conversation).isSameAs(conversationCaptor.getValue());
        assertThat(conversation.getUserId()).isNull();
        assertThat(conversation.getScope()).isEqualTo(ConversationScope.MEETING);
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(conversation.getCreatedBy()).isEqualTo("user-1");
    }

    @Test
    void reusesExistingActiveMeetingConversation() {
        Conversation existing = meetingConversation("meeting-1", "chat-1", "workspace-1", ConversationStatus.ACTIVE);
        when(conversationRepository.findByMeetingSessionId("meeting-1")).thenReturn(Optional.of(existing));

        Conversation result = meetingConversationService.findOrCreate(
                "meeting-1", "chat-1", "workspace-1", "user-2");

        assertThat(result).isSameAs(existing);
    }

    @Test
    void competingCreateReadsTheConversationCreatedByAnotherRequest() {
        Conversation existing = meetingConversation("meeting-1", "chat-1", "workspace-1", ConversationStatus.ACTIVE);
        when(conversationRepository.findByMeetingSessionId("meeting-1"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(conversationRepository.saveAndFlush(any(Conversation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate meeting"));

        Conversation result = meetingConversationService.findOrCreate(
                "meeting-1", "chat-1", "workspace-1", "user-1");

        assertThat(result).isSameAs(existing);
    }

    @Test
    void rejectsAnEndingMeetingConversation() {
        Conversation existing = meetingConversation("meeting-1", "chat-1", "workspace-1", ConversationStatus.ENDING);
        when(conversationRepository.findByMeetingSessionId("meeting-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> meetingConversationService.findOrCreate(
                "meeting-1", "chat-1", "workspace-1", "user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Meeting conversation is no longer active");
    }

    private Conversation meetingConversation(
            String meetingSessionId,
            String chatId,
            String workspaceId,
            ConversationStatus status
    ) {
        return Conversation.builder()
                .id(10L)
                .meetingSessionId(meetingSessionId)
                .chatId(chatId)
                .workspaceId(workspaceId)
                .createdBy("user-1")
                .scope(ConversationScope.MEETING)
                .status(status)
                .build();
    }
}
