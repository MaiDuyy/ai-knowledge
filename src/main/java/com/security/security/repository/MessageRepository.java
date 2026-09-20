package com.security.security.repository;

import com.security.security.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findByConversationIdOrderByCreatedAt(Long conversationId);
    Page<Message> findByConversationId(Long conversationId, Pageable pageable);

    @Query(value = "SELECT * FROM messages WHERE conversation_id = ?1 ORDER BY created_at DESC LIMIT ?2", nativeQuery = true)
    List<Message> findRecentMessages(Long conversationId, int limit);

    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId AND m.turnId = :turnId AND m.role = :role")
    java.util.Optional<Message> findByConversationIdAndTurnIdAndRole(
            @Param("conversationId") Long conversationId,
            @Param("turnId") String turnId,
            @Param("role") String role
    );

    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversationId = :conversationId")
    int deleteByConversationId(@Param("conversationId") Long conversationId);
}
