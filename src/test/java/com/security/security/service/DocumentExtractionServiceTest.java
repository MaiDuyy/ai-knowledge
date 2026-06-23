package com.security.security.service;

import com.security.security.service.docling.DoclingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentExtractionService Integration Unit Tests")
class DocumentExtractionServiceTest {

    @Mock
    private DoclingClient doclingClient;

    private DocumentExtractionService documentExtractionService;

    @BeforeEach
    void setUp() {
        documentExtractionService = new DocumentExtractionService(doclingClient);
    }

    @Test
    @DisplayName("Should parse file using DoclingClient and return markdown content")
    void testExtractMarkdownSuccess() throws Exception {
        Path tempImage = Files.createTempFile("photo", ".png");
        File imageFile = tempImage.toFile();

        try {
            DoclingClient.DoclingResult mockResult = DoclingClient.DoclingResult.success("# OCR Image Text", "GEMINI_SUCCESS", 100);
            when(doclingClient.convertToMarkdown(any(Resource.class), eq(imageFile.getName()), eq("gemini")))
                    .thenReturn(mockResult);

            String result = documentExtractionService.extractMarkdown(imageFile.getAbsolutePath());

            assertThat(result).isEqualTo("# OCR Image Text");
            verify(doclingClient, times(1)).convertToMarkdown(any(Resource.class), eq(imageFile.getName()), eq("gemini"));
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }

    @Test
    @DisplayName("Should throw exception if DoclingClient fails")
    void testExtractMarkdownFailure() throws Exception {
        Path tempImage = Files.createTempFile("photo", ".png");
        File imageFile = tempImage.toFile();

        try {
            DoclingClient.DoclingResult mockResult = DoclingClient.DoclingResult.failed("Gemini limit reached", 100);
            when(doclingClient.convertToMarkdown(any(Resource.class), eq(imageFile.getName()), eq("gemini")))
                    .thenReturn(mockResult);

            assertThatThrownBy(() -> documentExtractionService.extractMarkdown(imageFile.getAbsolutePath()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to extract text from")
                    .hasMessageContaining("Gemini limit reached");
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }
}
