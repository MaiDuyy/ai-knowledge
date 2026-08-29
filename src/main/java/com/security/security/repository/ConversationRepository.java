package com.security.security.repository;

import com.security.security.entity.Conversation;
import com.security.security.entity.enumeration.ConversationScope;
import com.security.security.entity.enumeration.ConversationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import jakarta.persistence.LockModeType;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Conversation> findByIdAndUserId(Long id, String userId);

    Optional<Conversation> findByChatIdAndUserId(String chatId, String userId);

    Optional<Conversation> findByMeetingSessionId(String meetingSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.meetingSessionId = :meetingSessionId")
    Optional<Conversation> findByMeetingSessionIdForUpdate(@Param("meetingSessionId") String meetingSessionId);

    List<Conversation> findByScopeAndStatusAndExpiresAtBefore(
            ConversationScope scope,
            ConversationStatus status,
            LocalDateTime expiresAt
    );
}
