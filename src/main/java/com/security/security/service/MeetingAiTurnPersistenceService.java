package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.Message;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.repository.ConversationRepository;
import com.security.security.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingAiTurnPersistenceService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @Transactional
    public Message saveUserIfMeetingActive(String meetingSessionId, Message message) {
        return saveIfMeetingActive(meetingSessionId, message);
    }

    @Transactional
    public Message saveAssistantIfMeetingActive(String meetingSessionId, Message message) {
        return saveIfMeetingActive(meetingSessionId, message);
    }

    private Message saveIfMeetingActive(String meetingSessionId, Message message) {
        Conversation conversation = conversationRepository.findByMeetingSessionIdForUpdate(meetingSessionId)
                .orElseThrow(() -> new IllegalStateException("Meeting conversation no longer exists"));
        if (conversation.getScope() != ConversationScope.MEETING
                || conversation.getStatus() != ConversationStatus.ACTIVE
                || !conversation.getId().equals(message.getConversationId())) {
            throw new IllegalStateException("Meeting conversation is no longer active");
        }

        try {
            return messageRepository.saveAndFlush(message);
        } catch (DataIntegrityViolationException duplicate) {
            return messageRepository.findByConversationIdAndTurnIdAndRole(
                            message.getConversationId(), message.getTurnId(), message.getRole())
                    .orElseThrow(() -> duplicate);
        }
    }
}
