package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingConversationPurgeSchedulerTest {

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MeetingConversationCleanupService cleanupService;
    @InjectMocks
    private MeetingConversationPurgeScheduler scheduler;

    @Test
    void purgesOnlyExpiredEndedMeetingsAndContinuesAfterFailure() {
        Conversation first = Conversation.builder().meetingSessionId("meeting-1").build();
        Conversation second = Conversation.builder().meetingSessionId("meeting-2").build();
        when(conversationRepository.findByScopeAndStatusAndExpiresAtBefore(
                org.mockito.ArgumentMatchers.eq(ConversationScope.MEETING),
                org.mockito.ArgumentMatchers.eq(ConversationStatus.ENDED),
                any(LocalDateTime.class)))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("temporary failure"))
                .when(cleanupService).purgeMeetingConversation("meeting-1");

        scheduler.purgeExpiredConversations();

        verify(cleanupService).purgeMeetingConversation("meeting-1");
        verify(cleanupService).purgeMeetingConversation("meeting-2");
    }
}
