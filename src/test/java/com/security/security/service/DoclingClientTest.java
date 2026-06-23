package com.security.security.service;

import com.security.security.service.docling.DoclingClient;
import com.security.security.service.tika.HtmlToMarkdownConverter;
import com.security.security.service.tika.TikaHtmlExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DoclingClient Tests")
public class DoclingClientTest {

    @Mock
    private WebClient.Builder webClientBuilder;
    @Mock
    private WebClient webClient;
    @Mock
    private GeminiMultimodalService geminiMultimodalService;
    @Mock
    private TikaHtmlExtractor tikaHtmlExtractor;
    @Mock
    private HtmlToMarkdownConverter htmlToMarkdownConverter;

    private DoclingClient doclingClient;

    @BeforeEach
    void setUp() {
        when(webClientBuilder.baseUrl(any())).thenReturn(webClientBuilder);
        when(webClientBuilder.defaultHeader(any(), any())).thenReturn(webClientBuilder);
        when(webClientBuilder.build()).thenReturn(webClient);
        
        doclingClient = new DoclingClient(
                "http://127.0.0.1:5001",
                webClientBuilder,
                geminiMultimodalService,
                tikaHtmlExtractor,
                htmlToMarkdownConverter
        );
    }

    @Test
    @DisplayName("Should successfully delegate PDF page-by-page OCR to GeminiMultimodalService")
    void convertToMarkdown_PdfWithGemini_DelegatesToMultimodalService() throws Exception {
        // Generate a valid 1-page PDF using PDFBox
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(baos);
        }
        byte[] pdfBytes = baos.toByteArray();

        Resource resource = mock(Resource.class);
        InputStream inputStream = new ByteArrayInputStream(pdfBytes);
        when(resource.getInputStream()).thenReturn(inputStream);

        Long documentId = 42L;

        when(geminiMultimodalService.parsePdf(any(byte[].class), eq(documentId)))
                .thenReturn("# Mocked Page Content");

        // Set geminiApiKey so isGeminiConfigured() is true
        org.springframework.test.util.ReflectionTestUtils.setField(doclingClient, "geminiApiKey", "dummy-api-key");

        DoclingClient.DoclingResult result = doclingClient.convertToMarkdown(resource, "test.pdf", "gemini", documentId);

        assertThat(result.success()).isTrue();
        assertThat(result.markdown()).isEqualTo("# Mocked Page Content");
        
        verify(geminiMultimodalService, times(1)).parsePdf(any(byte[].class), eq(documentId));
    }
}
