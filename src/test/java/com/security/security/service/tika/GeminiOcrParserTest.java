package com.security.security.service.tika;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GeminiOcrParser Unit Tests")
class GeminiOcrParserTest {

    @Mock
    private ChatModel chatModel;

    private GeminiOcrParser geminiOcrParser;

    @BeforeEach
    void setUp() {
        geminiOcrParser = new GeminiOcrParser(chatModel);
        org.springframework.test.util.ReflectionTestUtils.setField(geminiOcrParser, "ocrModel", "gemini-2.5-flash");
    }

    @Test
    @DisplayName("Should correctly classify file types that require OCR")
    void testIsImageOrPdf() {
        assertThat(geminiOcrParser.isImageOrPdf("test.pdf")).isTrue();
        assertThat(geminiOcrParser.isImageOrPdf("image.PNG")).isTrue();
        assertThat(geminiOcrParser.isImageOrPdf("photo.jpg")).isTrue();
        assertThat(geminiOcrParser.isImageOrPdf("document.docx")).isFalse();
        assertThat(geminiOcrParser.isImageOrPdf(null)).isFalse();
    }

    @Test
    @DisplayName("Should successfully perform OCR on a single image file")
    void testParseImage() throws IOException {
        Path tempImage = Files.createTempFile("test-image", ".png");
        Files.writeString(tempImage, "fake image bytes");
        File imageFile = tempImage.toFile();

        try {
            ChatResponse mockResponse = mock(ChatResponse.class);
            Generation mockGeneration = mock(Generation.class);
            AssistantMessage mockOutput = mock(AssistantMessage.class);

            when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);
            when(mockResponse.getResult()).thenReturn(mockGeneration);
            when(mockGeneration.getOutput()).thenReturn(mockOutput);
            when(mockOutput.getText()).thenReturn("# OCR Image Content\nThis is text from the image.");

            String result = geminiOcrParser.parse(imageFile);

            assertThat(result).isEqualTo("# OCR Image Content\nThis is text from the image.");
            verify(chatModel, times(1)).call(any(Prompt.class));
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }

    @Test
    @DisplayName("Should successfully perform OCR on a PDF file page by page")
    void testParsePdf() throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(baos);
        }
        byte[] pdfBytes = baos.toByteArray();

        Path tempPdf = Files.createTempFile("test-doc", ".pdf");
        Files.write(tempPdf, pdfBytes);
        File pdfFile = tempPdf.toFile();

        try {
            ChatResponse mockResponse = mock(ChatResponse.class);
            Generation mockGeneration = mock(Generation.class);
            AssistantMessage mockOutput = mock(AssistantMessage.class);

            when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);
            when(mockResponse.getResult()).thenReturn(mockGeneration);
            when(mockGeneration.getOutput()).thenReturn(mockOutput);
            when(mockOutput.getText()).thenReturn("# OCR PDF Content\nThis is text from the PDF page.");

            String result = geminiOcrParser.parse(pdfFile);

            assertThat(result).isEqualTo("# OCR PDF Content\nThis is text from the PDF page.");
            verify(chatModel, times(1)).call(any(Prompt.class));
        } finally {
            Files.deleteIfExists(tempPdf);
        }
    }
}
