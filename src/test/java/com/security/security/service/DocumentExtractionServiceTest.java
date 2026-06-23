package com.security.security.service;

import com.security.security.service.tika.GeminiOcrParser;
import com.security.security.service.tika.HtmlToMarkdownConverter;
import com.security.security.service.tika.TikaHtmlExtractor;
import com.security.security.service.tika.TikaHtmlResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentExtractionService Integration Unit Tests")
class DocumentExtractionServiceTest {

    @Mock
    private TikaHtmlExtractor tikaHtmlExtractor;

    @Mock
    private HtmlToMarkdownConverter htmlToMarkdownConverter;

    @Mock
    private GeminiOcrParser geminiOcrParser;

    private DocumentExtractionService documentExtractionService;

    @BeforeEach
    void setUp() {
        documentExtractionService = new DocumentExtractionService(
                tikaHtmlExtractor,
                htmlToMarkdownConverter,
                geminiOcrParser
        );
    }

    @Test
    @DisplayName("Should parse image using GeminiOcrParser directly")
    void testExtractImageDirectly() throws Exception {
        Path tempImage = Files.createTempFile("photo", ".png");
        File imageFile = tempImage.toFile();

        try {
            when(geminiOcrParser.parse(imageFile)).thenReturn("# OCR Image Text");

            String result = documentExtractionService.extractMarkdown(imageFile.getAbsolutePath());

            assertThat(result).isEqualTo("# OCR Image Text");
            verifyNoInteractions(tikaHtmlExtractor);
            verify(geminiOcrParser, times(1)).parse(imageFile);
        } finally {
            Files.deleteIfExists(tempImage);
        }
    }

    @Test
    @DisplayName("Should use Tika extraction for normal PDF file")
    void testExtractNormalPdf() throws Exception {
        Path tempPdf = Files.createTempFile("normal", ".pdf");
        File pdfFile = tempPdf.toFile();

        try {
            TikaHtmlResult mockTikaResult = new TikaHtmlResult("<html><body>Some normal text of a PDF file</body></html>", Map.of());
            String expectedMarkdown = "Some normal text of a PDF file. This contains more than 150 characters of actual text. ".repeat(3);

            when(tikaHtmlExtractor.extract(any(Resource.class))).thenReturn(mockTikaResult);
            when(htmlToMarkdownConverter.convert(anyString())).thenReturn(expectedMarkdown);

            String result = documentExtractionService.extractMarkdown(pdfFile.getAbsolutePath());

            assertThat(result).isEqualTo(expectedMarkdown);
            verifyNoInteractions(geminiOcrParser);
        } finally {
            Files.deleteIfExists(tempPdf);
        }
    }

    @Test
    @DisplayName("Should fallback to GeminiOcrParser for scanned or short-text PDF")
    void testExtractScannedPdfFallback() throws Exception {
        Path tempPdf = Files.createTempFile("scanned", ".pdf");
        File pdfFile = tempPdf.toFile();

        try {
            TikaHtmlResult mockTikaResult = new TikaHtmlResult("<html><body>Short</body></html>", Map.of());
            String shortMarkdown = "Short text.";

            when(tikaHtmlExtractor.extract(any(Resource.class))).thenReturn(mockTikaResult);
            when(htmlToMarkdownConverter.convert(anyString())).thenReturn(shortMarkdown);
            when(geminiOcrParser.parse(pdfFile)).thenReturn("# Scanned PDF Text From OCR");

            String result = documentExtractionService.extractMarkdown(pdfFile.getAbsolutePath());

            assertThat(result).isEqualTo("# Scanned PDF Text From OCR");
            verify(geminiOcrParser, times(1)).parse(pdfFile);
        } finally {
            Files.deleteIfExists(tempPdf);
        }
    }
}
