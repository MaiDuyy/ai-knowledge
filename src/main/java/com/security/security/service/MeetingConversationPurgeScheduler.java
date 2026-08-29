package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class MeetingConversationPurgeScheduler {

    private final ConversationRepository conversationRepository;
    private final MeetingConversationCleanupService cleanupService;

    @Scheduled(fixedDelayString = "${meeting.ai.cleanup.scan-interval-ms:30000}")
    public void purgeExpiredConversations() {
        for (Conversation conversation : conversationRepository.findByScopeAndStatusAndExpiresAtBefore(
                ConversationScope.MEETING,
                ConversationStatus.ENDED,
                LocalDateTime.now())) {
            try {
                cleanupService.purgeMeetingConversation(conversation.getMeetingSessionId());
            } catch (RuntimeException error) {
                log.warn("Meeting conversation purge will retry: meetingSessionId={}, reason={}",
                        conversation.getMeetingSessionId(), error.getClass().getSimpleName());
            }
        }
    }
}
