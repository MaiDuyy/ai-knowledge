package com.security.security.service;

import com.security.security.entity.Conversation;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import com.security.security.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MeetingConversationService {

    private static final String DEFAULT_TITLE = "Meeting AI";

    private final ConversationRepository conversationRepository;

    /**
     * A meeting session owns one shared conversation. The database partial unique
     * index is the final concurrency guard; a competing insert is re-read here.
     */
    public Conversation findOrCreate(
            String meetingSessionId,
            String chatId,
            String workspaceId,
            String createdBy
    ) {
        return conversationRepository.findByMeetingSessionId(meetingSessionId)
                .map(conversation -> validateExisting(conversation, chatId, workspaceId))
                .orElseGet(() -> createOrReadExisting(meetingSessionId, chatId, workspaceId, createdBy));
    }

    private Conversation createOrReadExisting(
            String meetingSessionId,
            String chatId,
            String workspaceId,
            String createdBy
    ) {
        Conversation conversation = Conversation.builder()
                .chatId(chatId)
                .meetingSessionId(meetingSessionId)
                .workspaceId(workspaceId)
                .createdBy(createdBy)
                .title(DEFAULT_TITLE)
                .scope(ConversationScope.MEETING)
                .status(ConversationStatus.ACTIVE)
                .build();

        try {
            return conversationRepository.saveAndFlush(conversation);
        } catch (DataIntegrityViolationException duplicate) {
            return conversationRepository.findByMeetingSessionId(meetingSessionId)
                    .map(existing -> validateExisting(existing, chatId, workspaceId))
                    .orElseThrow(() -> duplicate);
        }
    }

    private Conversation validateExisting(Conversation conversation, String chatId, String workspaceId) {
        if (conversation.getScope() != ConversationScope.MEETING) {
            throw new IllegalStateException("Conversation is not a meeting conversation");
        }
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw new IllegalStateException("Meeting conversation is no longer active");
        }
        if (!Objects.equals(conversation.getChatId(), chatId)) {
            throw new IllegalArgumentException("chatId does not match the meeting conversation");
        }
        if (!Objects.equals(conversation.getWorkspaceId(), workspaceId)) {
            throw new IllegalArgumentException("workspaceId does not match the meeting conversation");
        }
        return conversation;
    }
}
