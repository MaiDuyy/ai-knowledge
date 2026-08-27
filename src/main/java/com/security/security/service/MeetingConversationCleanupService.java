package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.repository.ConversationRepository;
import com.security.security.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class MeetingConversationCleanupService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @Transactional
    public boolean endMeetingConversation(String meetingSessionId) {
        Conversation conversation = findMeetingConversation(meetingSessionId);
        if (conversation == null) {
            return false;
        }

        if (conversation.getStatus() == ConversationStatus.ACTIVE) {
            conversation.setStatus(ConversationStatus.ENDING);
            conversation.setEndedAt(LocalDateTime.now());
            conversationRepository.save(conversation);
        }

        return true;
    }

    @Transactional
    public boolean markMeetingConversationEnded(String meetingSessionId) {
        Conversation conversation = findMeetingConversation(meetingSessionId);
        if (conversation == null) {
            return false;
        }

        if (conversation.getStatus() != ConversationStatus.ENDED) {
            conversation.setStatus(ConversationStatus.ENDED);
            if (conversation.getEndedAt() == null) {
                conversation.setEndedAt(LocalDateTime.now());
            }
            conversationRepository.save(conversation);
        }

        return true;
    }

    @Transactional
    public boolean purgeMeetingConversation(String meetingSessionId) {
        Conversation conversation = findMeetingConversation(meetingSessionId);
        if (conversation == null) {
            return false;
        }

        messageRepository.deleteByConversationId(conversation.getId());
        conversationRepository.delete(conversation);
        log.info("Purged meeting conversation: meetingSessionId={}, conversationId={}",
                meetingSessionId, conversation.getId());
        return true;
    }

    private Conversation findMeetingConversation(String meetingSessionId) {
        if (meetingSessionId == null || meetingSessionId.isBlank()) {
            throw new IllegalArgumentException("meetingSessionId must not be blank");
        }

        Conversation conversation = conversationRepository.findByMeetingSessionId(meetingSessionId).orElse(null);
        if (conversation != null && conversation.getScope() != ConversationScope.MEETING) {
            throw new IllegalStateException("Conversation is not a meeting conversation");
        }

        return conversation;
    }
}
