package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.Message;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.entity.enumeration.MessageStatus;
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

    /**
     * Claims a turn while the meeting row is locked. A competing request can
     * observe the already-created assistant row but cannot start a second model
     * stream for the same turn.
     */
    @Transactional
    public AssistantTurnClaim claimAssistantStreaming(String meetingSessionId, Message candidate) {
        Conversation conversation = activeConversationForUpdate(meetingSessionId, candidate.getConversationId());
        Message existing = messageRepository.findByConversationIdAndTurnIdAndRole(
                        conversation.getId(), candidate.getTurnId(), "assistant")
                .orElse(null);
        if (existing != null) {
            return new AssistantTurnClaim(existing, false);
        }
        return new AssistantTurnClaim(messageRepository.saveAndFlush(candidate), true);
    }

    @Transactional
    public Message completeAssistantIfMeetingActive(String meetingSessionId, Long assistantMessageId,
                                                     String displayContent, String speechContent) {
        Message message = assistantForActiveMeeting(meetingSessionId, assistantMessageId);
        if (message.getStatus() != MessageStatus.STREAMING) {
            throw new IllegalStateException("Meeting assistant turn is not streaming");
        }
        message.setContent(displayContent);
        message.setDisplayContent(displayContent);
        message.setSpeechContent(speechContent);
        message.setStatus(MessageStatus.COMPLETED);
        return messageRepository.saveAndFlush(message);
    }

    @Transactional
    public void failAssistantIfMeetingActive(String meetingSessionId, Long assistantMessageId) {
        Message message = assistantForActiveMeeting(meetingSessionId, assistantMessageId);
        if (message.getStatus() == MessageStatus.STREAMING) {
            message.setStatus(MessageStatus.FAILED);
            messageRepository.saveAndFlush(message);
        }
    }

    private Message saveIfMeetingActive(String meetingSessionId, Message message) {
        activeConversationForUpdate(meetingSessionId, message.getConversationId());

        try {
            return messageRepository.saveAndFlush(message);
        } catch (DataIntegrityViolationException duplicate) {
            return messageRepository.findByConversationIdAndTurnIdAndRole(
                            message.getConversationId(), message.getTurnId(), message.getRole())
                    .orElseThrow(() -> duplicate);
        }
    }

    private Conversation activeConversationForUpdate(String meetingSessionId, Long conversationId) {
        Conversation conversation = conversationRepository.findByMeetingSessionIdForUpdate(meetingSessionId)
                .orElseThrow(() -> new IllegalStateException("Meeting conversation no longer exists"));
        if (conversation.getScope() != ConversationScope.MEETING
                || conversation.getStatus() != ConversationStatus.ACTIVE
                || !conversation.getId().equals(conversationId)) {
            throw new IllegalStateException("Meeting conversation is no longer active");
        }
        return conversation;
    }

    private Message assistantForActiveMeeting(String meetingSessionId, Long assistantMessageId) {
        Message message = messageRepository.findById(assistantMessageId)
                .orElseThrow(() -> new IllegalStateException("Meeting assistant turn no longer exists"));
        activeConversationForUpdate(meetingSessionId, message.getConversationId());
        if (!"assistant".equals(message.getRole())) {
            throw new IllegalStateException("Meeting message is not an assistant turn");
        }
        return message;
    }

    public record AssistantTurnClaim(Message message, boolean created) {
    }
}
