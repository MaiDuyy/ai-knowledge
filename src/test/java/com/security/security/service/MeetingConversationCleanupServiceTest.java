package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.repository.ConversationRepository;
import com.security.security.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingConversationCleanupServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private MeetingConversationCleanupService cleanupService;

    @Captor
    private ArgumentCaptor<Conversation> conversationCaptor;

    @Test
    void endMeetingConversationMovesActiveConversationToEnding() {
        Conversation conversation = meetingConversation(10L, ConversationStatus.ACTIVE);
        when(conversationRepository.findByMeetingSessionIdForUpdate("meeting-1"))
                .thenReturn(Optional.of(conversation));

        boolean found = cleanupService.endMeetingConversation("meeting-1");

        assertThat(found).isTrue();
        verify(conversationRepository).save(conversationCaptor.capture());
        assertThat(conversationCaptor.getValue().getStatus()).isEqualTo(ConversationStatus.ENDING);
        assertThat(conversationCaptor.getValue().getEndedAt()).isNotNull();
    }

    @Test
    void purgeMeetingConversationDeletesMessagesBeforeConversation() {
        Conversation conversation = meetingConversation(10L, ConversationStatus.ENDED);
        when(conversationRepository.findByMeetingSessionIdForUpdate("meeting-1"))
                .thenReturn(Optional.of(conversation));

        boolean purged = cleanupService.purgeMeetingConversation("meeting-1");

        assertThat(purged).isTrue();
        InOrder order = inOrder(messageRepository, conversationRepository);
        order.verify(messageRepository).deleteByConversationId(10L);
        order.verify(conversationRepository).delete(conversation);
    }

    @Test
    void cleanupRejectsNonMeetingConversation() {
        Conversation conversation = meetingConversation(10L, ConversationStatus.ACTIVE);
        conversation.setScope(ConversationScope.PERSONAL);
        when(conversationRepository.findByMeetingSessionIdForUpdate("meeting-1"))
                .thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> cleanupService.purgeMeetingConversation("meeting-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Conversation is not a meeting conversation");
        verify(messageRepository, never()).deleteByConversationId(10L);
    }

    @Test
    void missingMeetingCleanupIsIdempotent() {
        when(conversationRepository.findByMeetingSessionIdForUpdate("meeting-1"))
                .thenReturn(Optional.empty());

        boolean purged = cleanupService.purgeMeetingConversation("meeting-1");

        assertThat(purged).isFalse();
        verify(messageRepository, never()).deleteByConversationId(10L);
    }

    @Test
    void completeCleanupDeletesMessagesAndLeavesEndedTombstone() {
        Conversation conversation = meetingConversation(10L, ConversationStatus.ENDING);
        when(conversationRepository.findByMeetingSessionIdForUpdate("meeting-1"))
                .thenReturn(Optional.of(conversation));

        boolean completed = cleanupService.completeMeetingConversationCleanup("meeting-1");

        assertThat(completed).isTrue();
        InOrder order = inOrder(messageRepository, conversationRepository);
        order.verify(messageRepository).deleteByConversationId(10L);
        order.verify(conversationRepository).save(conversation);
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ENDED);
        assertThat(conversation.getExpiresAt()).isNotNull();
    }

    private Conversation meetingConversation(Long id, ConversationStatus status) {
        return Conversation.builder()
                .id(id)
                .meetingSessionId("meeting-1")
                .chatId("chat-1")
                .createdBy("user-1")
                .scope(ConversationScope.MEETING)
                .status(status)
                .build();
    }
}
