package com.security.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("TextChunkingService Tests")
class TextChunkingServiceTest {

    @InjectMocks
    private TextChunkingService textChunkingService;

    @BeforeEach
    void setUp() {
        textChunkingService = new TextChunkingService();
    }

    @Test
    @DisplayName("Should return empty list for null text")
    void chunkText_NullText_ReturnsEmptyList() {
        List<String> chunks = textChunkingService.chunkText(null);
        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list for empty text")
    void chunkText_EmptyText_ReturnsEmptyList() {
        List<String> chunks = textChunkingService.chunkText("");
        assertThat(chunks).isEmpty();
    }

    @Test
    @DisplayName("Should return single chunk for short text")
    void chunkText_ShortText_ReturnsSingleChunk() {
        String shortText = "This is a short text that should fit in one chunk.";
        List<String> chunks = textChunkingService.chunkText(shortText);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(shortText);
    }

    @Test
    @DisplayName("Should split long text into multiple chunks")
    void chunkText_LongText_ReturnsMultipleChunks() {
        // Create text longer than CHUNK_SIZE (1200 chars)
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append("This is sentence number ").append(i).append(". ");
        }

        List<String> chunks = textChunkingService.chunkText(longText.toString());

        assertThat(chunks).hasSizeGreaterThan(1);
        // Each chunk should not exceed the max size
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(1300); // Allow some margin
        }
    }

    @Test
    @DisplayName("Should normalize whitespace in chunks")
    void chunkText_TextWithExtraWhitespace_NormalizesWhitespace() {
        String textWithWhitespace = "This   has    multiple     spaces";
        List<String> chunks = textChunkingService.chunkText(textWithWhitespace);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).doesNotContain("  "); // No double spaces
    }

    @Test
    @DisplayName("Should estimate tokens for text")
    void estimateTokens_ValidText_ReturnsEstimate() {
        String text = "This is a test text with approximately twenty characters.";
        int tokens = textChunkingService.estimateTokens(text);

        // ~4 chars per token, so ~14 tokens expected
        assertThat(tokens).isGreaterThan(0);
        assertThat(tokens).isLessThan(text.length()); // Should be less than char count
    }

    @Test
    @DisplayName("Should return 0 tokens for null text")
    void estimateTokens_NullText_ReturnsZero() {
        int tokens = textChunkingService.estimateTokens(null);
        assertThat(tokens).isZero();
    }

    @Test
    @DisplayName("Should return 0 tokens for empty text")
    void estimateTokens_EmptyText_ReturnsZero() {
        int tokens = textChunkingService.estimateTokens("");
        assertThat(tokens).isZero();
    }

    @Test
    @DisplayName("Should return at least 1 token for very short text")
    void estimateTokens_VeryShortText_ReturnsAtLeastOne() {
        int tokens = textChunkingService.estimateTokens("Hi");
        assertThat(tokens).isGreaterThanOrEqualTo(1);
    }
}
