package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.Message;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.entity.enumeration.MessageInputMode;
import com.security.security.entity.enumeration.MessageStatus;
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
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceMeetingCompatibilityTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ChatClient chatClient;

    @InjectMocks
    private ConversationService conversationService;

    @Captor
    private ArgumentCaptor<Conversation> conversationCaptor;

    @Captor
    private ArgumentCaptor<Message> messageCaptor;

    @Test
    void personalConversationHasMeetingSafeDefaults() {
        when(conversationRepository.save(any(Conversation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        conversationService.createConversation("user-1", "Personal chat", "chat-1");

        verify(conversationRepository).save(conversationCaptor.capture());
        Conversation saved = conversationCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getCreatedBy()).isEqualTo("user-1");
        assertThat(saved.getScope()).isEqualTo(ConversationScope.PERSONAL);
        assertThat(saved.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
    }

    @Test
    void personalMessageRetainsTextAndCompletedDefaults() {
        when(messageRepository.save(any(Message.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        conversationService.saveMessage(10L, "user", "Question", null, null);

        verify(messageRepository).save(messageCaptor.capture());
        Message saved = messageCaptor.getValue();
        assertThat(saved.getContent()).isEqualTo("Question");
        assertThat(saved.getDisplayContent()).isEqualTo("Question");
        assertThat(saved.getInputMode()).isEqualTo(MessageInputMode.TEXT);
        assertThat(saved.getStatus()).isEqualTo(MessageStatus.COMPLETED);
    }

    @Test
    void personalDeletePurgesMessagesBeforeConversation() {
        Conversation conversation = Conversation.builder()
                .id(10L)
                .userId("user-1")
                .createdBy("user-1")
                .build();
        when(conversationRepository.findByIdAndUserId(10L, "user-1"))
                .thenReturn(Optional.of(conversation));

        conversationService.deleteConversation(10L, "user-1");

        InOrder order = inOrder(messageRepository, conversationRepository);
        order.verify(messageRepository).deleteByConversationId(10L);
        order.verify(conversationRepository).delete(conversation);
    }
}
