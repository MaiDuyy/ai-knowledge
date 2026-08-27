package com.security.security.repository;

import com.security.security.entity.Conversation;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Conversation> findByIdAndUserId(Long id, String userId);

    Optional<Conversation> findByChatIdAndUserId(String chatId, String userId);

    Optional<Conversation> findByMeetingSessionId(String meetingSessionId);

    List<Conversation> findByScopeAndStatusAndExpiresAtBefore(
            ConversationScope scope,
            ConversationStatus status,
            LocalDateTime expiresAt
    );
}
