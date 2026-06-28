package com.security.security.service;

import com.security.security.entity.OcrResult;
import com.security.security.entity.enumeration.OcrStatus;
import com.security.security.repository.OcrResultRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("GeminiMultimodalService Unit Tests")
class GeminiMultimodalServiceTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private OcrResultRepository ocrResultRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AppConfigService appConfigService;

    private ExecutorService executorService;
    private GeminiMultimodalService geminiMultimodalService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        geminiMultimodalService = new GeminiMultimodalService(
                chatModel,
                ocrResultRepository,
                Optional.of(redisTemplate),
                executorService,
                appConfigService
        );
        org.springframework.test.util.ReflectionTestUtils.setField(geminiMultimodalService, "geminiModel", "gemini-2.5-flash");
        org.springframework.test.util.ReflectionTestUtils.setField(geminiMultimodalService, "concurrencyLimit", 5);
        geminiMultimodalService.init();
    }

    @Test
    @DisplayName("Should correctly classify file types that require OCR")
    void testIsImageOrPdf() {
        assertThat(geminiMultimodalService.isImageOrPdf("test.pdf")).isTrue();
        assertThat(geminiMultimodalService.isImageOrPdf("image.PNG")).isTrue();
        assertThat(geminiMultimodalService.isImageOrPdf("photo.jpg")).isTrue();
        assertThat(geminiMultimodalService.isImageOrPdf("document.docx")).isFalse();
        assertThat(geminiMultimodalService.isImageOrPdf(null)).isFalse();
    }

    @Test
    @DisplayName("Should successfully check Redis cache and return cached value on hit")
    void testParseImageRedisCacheHit() throws IOException {
        Path tempImage = Files.createTempFile("test-image", ".png");
        Files.writeString(tempImage, "fake image bytes");
        File imageFile = tempImage.toFile();

        try {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(startsWith("ocr:hash:"))).thenReturn("# Cached Markdown Content");

            String result = geminiMultimodalService.parse(imageFile);

            assertThat(result).isEqualTo("# Cached Markdown Content");
            verifyNoInteractions(chatModel);
            verifyNoInteractions(ocrResultRepository);
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }

    @Test
    @DisplayName("Should successfully check DB cache on Redis miss and return cached value on hit")
    void testParseImageDbCacheHit() throws IOException {
        Path tempImage = Files.createTempFile("test-image", ".png");
        Files.writeString(tempImage, "fake image bytes");
        File imageFile = tempImage.toFile();

        try {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(startsWith("ocr:hash:"))).thenReturn(null);

            OcrResult mockOcr = OcrResult.builder()
                    .status(OcrStatus.COMPLETED)
                    .markdownContent("# Cached DB Content")
                    .build();
            when(ocrResultRepository.findByDocumentIdAndPageNumber(anyLong(), anyInt()))
                    .thenReturn(Optional.of(mockOcr));

            String result = geminiMultimodalService.parse(imageFile);

            assertThat(result).isEqualTo("# Cached DB Content");
            verifyNoInteractions(chatModel);
            verify(redisTemplate.opsForValue(), times(1)).set(startsWith("ocr:hash:"), eq("# Cached DB Content"), any());
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }

    @Test
    @DisplayName("Should call Gemini ChatModel on cache misses and populate cache")
    void testParseImageCacheMiss() throws IOException {
        Path tempImage = Files.createTempFile("test-image", ".png");
        Files.writeString(tempImage, "fake image bytes");
        File imageFile = tempImage.toFile();

        try {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.get(startsWith("ocr:hash:"))).thenReturn(null);
            when(ocrResultRepository.findByDocumentIdAndPageNumber(anyLong(), anyInt())).thenReturn(Optional.empty());

            ChatResponse mockResponse = mock(ChatResponse.class);
            Generation mockGeneration = mock(Generation.class);
            AssistantMessage mockOutput = mock(AssistantMessage.class);

            when(chatModel.call(any(Prompt.class))).thenReturn(mockResponse);
            when(mockResponse.getResult()).thenReturn(mockGeneration);
            when(mockGeneration.getOutput()).thenReturn(mockOutput);
            when(mockOutput.getText()).thenReturn("# OCR Image Content");

            String result = geminiMultimodalService.parse(imageFile);

            assertThat(result).isEqualTo("# OCR Image Content");
            verify(chatModel, times(1)).call(any(Prompt.class));
            verify(valueOperations, times(1)).set(startsWith("ocr:hash:"), eq("# OCR Image Content"), any());
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }
}
