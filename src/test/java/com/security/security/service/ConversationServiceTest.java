//package com.security.security.service;
//
//import com.security.security.entity.Conversation;
//import com.security.security.entity.Message;
//import com.security.security.repository.ConversationRepository;
//import com.security.security.repository.MessageRepository;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.time.LocalDateTime;
//import java.util.List;
//import java.util.Optional;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.assertj.core.api.Assertions.assertThatThrownBy;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//@DisplayName("ConversationService Tests")
//class ConversationServiceTest {
//
//    @Mock
//    private ConversationRepository conversationRepository;
//
//    @Mock
//    private MessageRepository messageRepository;
//
//    @InjectMocks
//    private ConversationService conversationService;
//
//    private Conversation testConversation;
//    private Message testMessage;
//
//    @BeforeEach
//    void setUp() {
//        testConversation = Conversation.builder()
//                .id(1L)
//                .userId(100L)
//                .title("Test Conversation")
//                .createdAt(LocalDateTime.now())
//                .build();
//
//        testMessage = Message.builder()
//                .id(1L)
//                .conversationId(1L)
//                .role("user")
//                .content("Test message")
//                .createdAt(LocalDateTime.now())
//                .build();
//    }
//
//    @Test
//    @DisplayName("Should create conversation successfully")
//    void createConversation_ValidInput_ReturnsConversation() {
//        when(conversationRepository.save(any(Conversation.class))).thenReturn(testConversation);
//
//        Conversation result = conversationService.createConversation(100L, "Test Conversation");
//
//        assertThat(result).isNotNull();
//        assertThat(result.getTitle()).isEqualTo("Test Conversation");
//        verify(conversationRepository).save(any(Conversation.class));
//    }
//
//    @Test
//    @DisplayName("Should create conversation with default title if null")
//    void createConversation_NullTitle_UsesDefaultTitle() {
//        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
//            Conversation conv = invocation.getArgument(0);
//            assertThat(conv.getTitle()).isEqualTo("New Conversation");
//            return conv;
//        });
//
//        conversationService.createConversation(100L, null);
//
//        verify(conversationRepository).save(any(Conversation.class));
//    }
//
//    @Test
//    @DisplayName("Should get user conversations ordered by date")
//    void getUserConversations_ValidUserId_ReturnsConversations() {
//        when(conversationRepository.findByUserIdOrderByCreatedAtDesc(100L))
//                .thenReturn(List.of(testConversation));
//
//        List<Conversation> result = conversationService.getUserConversations(100L);
//
//        assertThat(result).hasSize(1);
//        assertThat(result.get(0).getUserId()).isEqualTo(100L);
//    }
//
//    @Test
//    @DisplayName("Should get conversation by ID and user ID")
//    void getConversation_ValidIds_ReturnsConversation() {
//        when(conversationRepository.findByIdAndUserId(1L, 100L))
//                .thenReturn(Optional.of(testConversation));
//
//        Conversation result = conversationService.getConversation(1L, 100L);
//
//        assertThat(result).isNotNull();
//        assertThat(result.getId()).isEqualTo(1L);
//    }
//
//    @Test
//    @DisplayName("Should throw exception when conversation not found")
//    void getConversation_NotFound_ThrowsException() {
//        when(conversationRepository.findByIdAndUserId(999L, 100L))
//                .thenReturn(Optional.empty());
//
//        assertThatThrownBy(() -> conversationService.getConversation(999L, 100L))
//                .isInstanceOf(RuntimeException.class)
//                .hasMessageContaining("Conversation not found");
//    }
//
//    @Test
//    @DisplayName("Should get messages for conversation")
//    void getMessages_ValidConversationId_ReturnsMessages() {
//        when(messageRepository.findByConversationIdOrderByCreatedAt(1L))
//                .thenReturn(List.of(testMessage));
//
//        List<Message> result = conversationService.getMessages(1L);
//
//        assertThat(result).hasSize(1);
//        assertThat(result.get(0).getContent()).isEqualTo("Test message");
//    }
//
//    @Test
//    @DisplayName("Should save message successfully")
//    void saveMessage_ValidInput_ReturnsMessage() {
//        when(messageRepository.save(any(Message.class))).thenReturn(testMessage);
//
//        Message result = conversationService.saveMessage(1L, "user", "Test content", 100, 500);
//
//        assertThat(result).isNotNull();
//        verify(messageRepository).save(any(Message.class));
//    }
//
//    @Test
//    @DisplayName("Should delete conversation")
//    void deleteConversation_ValidIds_DeletesConversation() {
//        when(conversationRepository.findByIdAndUserId(1L, "1"))
//                .thenReturn(Optional.of(testConversation));
//        doNothing().when(conversationRepository).delete(testConversation);
//
//        conversationService.deleteConversation(1L, 100L);
//
//        verify(conversationRepository).delete(testConversation);
//    }
//}
