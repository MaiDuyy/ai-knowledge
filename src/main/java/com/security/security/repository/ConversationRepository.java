package com.security.security.repository;

import com.security.security.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findByUserIdOrderByCreatedAtDesc(String userId);

    Optional<Conversation> findByIdAndUserId(Long id, String userId);

    Optional<Conversation> findByChatIdAndUserId(String chatId, String userId);
}
