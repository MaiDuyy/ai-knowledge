//package com.security.security.service;
//
//import com.security.security.dto.DocumentUploadResponse;
//import com.security.security.entity.Document;
//import com.security.security.entity.enumeration.DocStatus;
//import com.security.security.entity.enumeration.DocType;
//import com.security.security.event.DocumentUploadedEvent;
//import com.security.security.repository.DocumentRepository;
//import com.security.security.repository.EmbeddingRepository;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.junit.jupiter.api.io.TempDir;
//import org.mockito.ArgumentCaptor;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.context.ApplicationEventPublisher;
//import org.springframework.mock.web.MockMultipartFile;
//import org.springframework.test.util.ReflectionTestUtils;
//
//import java.io.IOException;
//import java.nio.file.Path;
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
//@DisplayName("DocumentService Tests")
//class DocumentServiceTest {
//
//    @Mock
//    private DocumentRepository documentRepository;
//
//    @Mock
//    private EmbeddingRepository embeddingRepository;
//
//    @Mock
//    private ApplicationEventPublisher eventPublisher;
//
//    @InjectMocks
//    private DocumentService documentService;
//
//    @TempDir
//    Path tempDir;
//
//    private Document testDocument;
//
//    @BeforeEach
//    void setUp() {
//        // Set the upload directory to temp directory
//        ReflectionTestUtils.setField(documentService, "uploadDir", tempDir.toString());
//
//        testDocument = Document.builder()
//                .id(1L)
//                .userId(100L)
//                .fileName("test.pdf")
//                .fileSize(1024)
//                .filePath(tempDir.resolve("test.pdf").toString())
//                .status(DocStatus.COMPLETED)
//                .documentType(DocType.pdf)
//                .chunkCount(5)
//                .createdAt(LocalDateTime.now())
//                .build();
//    }
//
//    @Test
//    @DisplayName("Should upload document successfully")
//    void uploadDocument_ValidFile_ReturnsResponse() throws IOException {
//        MockMultipartFile file = new MockMultipartFile(
//                "file", "test.pdf", "application/pdf", "test content".getBytes());
//
//        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
//            Document doc = invocation.getArgument(0);
//            doc.setId(1L);
//            return doc;
//        });
//
//        DocumentUploadResponse result = documentService.uploadDocument(file, 100L);
//
//        assertThat(result.getDocumentId()).isEqualTo(1L);
//        assertThat(result.getFileName()).isEqualTo("test.pdf");
//        assertThat(result.getStatus()).isEqualTo("PENDING");
//
//        // Verify event was published
//        verify(eventPublisher).publishEvent(any(DocumentUploadedEvent.class));
//    }
//
//    @Test
//    @DisplayName("Should reject empty file")
//    void uploadDocument_EmptyFile_ThrowsException() {
//        MockMultipartFile emptyFile = new MockMultipartFile(
//                "file", "empty.pdf", "application/pdf", new byte[0]);
//
//        assertThatThrownBy(() -> documentService.uploadDocument(emptyFile, 100L))
//                .isInstanceOf(IllegalArgumentException.class)
//                .hasMessageContaining("empty");
//    }
//
//    @Test
//    @DisplayName("Should reject file exceeding size limit")
//    void uploadDocument_TooLargeFile_ThrowsException() {
//        // Create file > 50MB
//        byte[] largeContent = new byte[52428801];
//        MockMultipartFile largeFile = new MockMultipartFile(
//                "file", "large.pdf", "application/pdf", largeContent);
//
//        assertThatThrownBy(() -> documentService.uploadDocument(largeFile, 100L))
//                .isInstanceOf(IllegalArgumentException.class)
//                .hasMessageContaining("50MB");
//    }
//
//    @Test
//    @DisplayName("Should reject unsupported file type")
//    void uploadDocument_UnsupportedType_ThrowsException() {
//        MockMultipartFile unsupportedFile = new MockMultipartFile(
//                "file", "test.exe", "application/octet-stream", "content".getBytes());
//
//        assertThatThrownBy(() -> documentService.uploadDocument(unsupportedFile, 100L))
//                .isInstanceOf(IllegalArgumentException.class)
//                .hasMessageContaining("Unsupported");
//    }
//
//    @Test
//    @DisplayName("Should get user documents")
//    void getUserDocuments_ValidUserId_ReturnsDocuments() {
//        when(documentRepository.findByUserIdOrderByCreatedAtDesc(100L))
//                .thenReturn(List.of(testDocument));
//
//        List<Document> result = documentService.getUserDocuments(100L);
//
//        assertThat(result).hasSize(1);
//        assertThat(result.get(0).getUserId()).isEqualTo(100L);
//    }
//
//    @Test
//    @DisplayName("Should get completed documents")
//    void getCompletedDocuments_ValidUserId_ReturnsCompletedOnly() {
//        when(documentRepository.findCompletedByUserId(100L))
//                .thenReturn(List.of(testDocument));
//
//        List<Document> result = documentService.getCompletedDocuments(100L);
//
//        assertThat(result).hasSize(1);
//        assertThat(result.get(0).getStatus()).isEqualTo(DocStatus.COMPLETED);
//    }
//
//    @Test
//    @DisplayName("Should get document by ID and user ID")
//    void getDocument_ValidIds_ReturnsDocument() {
//        when(documentRepository.findByIdAndUserId(1L, 100L))
//                .thenReturn(Optional.of(testDocument));
//
//        Document result = documentService.getDocument(1L, 100L);
//
//        assertThat(result.getId()).isEqualTo(1L);
//        assertThat(result.getFileName()).isEqualTo("test.pdf");
//    }
//
//    @Test
//    @DisplayName("Should throw exception when document not found")
//    void getDocument_NotFound_ThrowsException() {
//        when(documentRepository.findByIdAndUserId(999L, 100L))
//                .thenReturn(Optional.empty());
//
//        assertThatThrownBy(() -> documentService.getDocument(999L, 100L))
//                .isInstanceOf(RuntimeException.class)
//                .hasMessageContaining("Document not found");
//    }
//
//    @Test
//    @DisplayName("Should delete document and its embeddings")
//    void deleteDocument_ValidDocument_DeletesSuccessfully() {
//        when(documentRepository.findByIdAndUserId(1L, 100L))
//                .thenReturn(Optional.of(testDocument));
//        doNothing().when(embeddingRepository).deleteByDocumentId(1L);
//        doNothing().when(documentRepository).delete(testDocument);
//
//        documentService.deleteDocument(1L, 100L);
//
//        verify(embeddingRepository).deleteByDocumentId(1L);
//        verify(documentRepository).delete(testDocument);
//    }
//}
