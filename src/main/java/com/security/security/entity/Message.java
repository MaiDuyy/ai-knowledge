package com.security.security.entity;

import com.security.security.entity.enumeration.MessageInputMode;
import com.security.security.entity.enumeration.MessageStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(nullable = false)
    private String role;  // user, assistant

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "turn_id")
    private String turnId;

    @Column(name = "speaker_user_id")
    private String speakerUserId;

    @Column(name = "speaker_name")
    private String speakerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_mode", nullable = false, length = 20)
    @Builder.Default
    private MessageInputMode inputMode = MessageInputMode.TEXT;

    @Column(name = "display_content", nullable = false, columnDefinition = "TEXT")
    private String displayContent;

    @Column(name = "speech_content", columnDefinition = "TEXT")
    private String speechContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MessageStatus status = MessageStatus.COMPLETED;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "response_time_ms")
    private Integer responseTimeMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
