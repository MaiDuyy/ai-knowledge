package com.security.security.service.tika;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.content.Media;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
@RequiredArgsConstructor
public class GeminiOcrParser {

    private final ChatModel chatModel;

    @Value("${gemini.model.ocr:gemini-2.5-flash}")
    private String ocrModel;

    /**
     * Check if a file extension represents a scan/image that requires OCR.
     */
    public boolean isImageOrPdf(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".png") || lower.endsWith(".jpg") 
                || lower.endsWith(".jpeg") || lower.endsWith(".webp");
    }

    /**
     * Perform OCR on a PDF or image file and return Markdown.
     */
    public String parse(File file) throws IOException {
        String fileName = file.getName();
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        
        if (fileName.toLowerCase().endsWith(".pdf")) {
            return parsePdf(fileBytes);
        } else {
            String mimeType = getMimeType(fileName);
            return parseImage(fileBytes, mimeType);
        }
    }

    private String parsePdf(byte[] pdfBytes) throws IOException {
        log.info("[GeminiOcrParser] Rendering PDF to pages for OCR");
        List<byte[]> pageImages;
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            pageImages = new ArrayList<>();
            for (int page = 0; page < document.getNumberOfPages(); ++page) {
                // Render at 150 DPI for a balance of quality and speed
                BufferedImage bim = pdfRenderer.renderImageWithDPI(page, 150, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(bim, "png", baos);
                pageImages.add(baos.toByteArray());
            }
        }

        log.info("[GeminiOcrParser] Running OCR on {} pages", pageImages.size());
        StringBuilder mdBuilder = new StringBuilder();
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        
        String systemPrompt = """
                Bạn là một chuyên gia OCR tài liệu chính xác cao.
                Nhiệm vụ của bạn là nhận diện toàn bộ chữ viết, bảng biểu và cấu trúc từ hình ảnh trang tài liệu được cung cấp và chuyển đổi thành định dạng Markdown chuẩn.
                
                ## YÊU CẦU:
                1. Hãy giữ nguyên cấu trúc văn bản (headings, paragraphs, lists, tables).
                2. Chuyển đổi các bảng biểu (tables) thành Markdown tables hoàn chỉnh.
                3. Trả về nội dung Markdown sạch sẽ.
                4. KHÔNG viết lời mở đầu, giải thích hay ghi chú. Chỉ trả về nội dung Markdown của tài liệu.
                """;

        for (int i = 0; i < pageImages.size(); i++) {
            if (i > 0) {
                mdBuilder.append("\n\n<!-- PAGE_BREAK: ").append(i + 1).append(" -->\n\n");
            }
            
            byte[] imgBytes = pageImages.get(i);
            ByteArrayResource byteResource = new ByteArrayResource(imgBytes);
            Media media = new Media(MimeTypeUtils.IMAGE_PNG, byteResource);
            
            log.debug("[GeminiOcrParser] Call Gemini OCR for page {}", i + 1);
            String pageMarkdown = chatClient.prompt()
                    .options(GoogleGenAiChatOptions.builder()
                            .model(ocrModel)
                            .temperature(0.0)
                            .build())
                    .system(systemPrompt)
                    .user(u -> u.text("Trích xuất nội dung trang tài liệu này sang Markdown:").media(media))
                    .call()
                    .chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();
            
            if (pageMarkdown != null) {
                mdBuilder.append(pageMarkdown.trim());
            }
        }
        
        return mdBuilder.toString();
    }

    private String parseImage(byte[] imageBytes, String mimeType) {
        log.info("[GeminiOcrParser] Running OCR on single image with mime={}", mimeType);
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        
        String systemPrompt = """
                Bạn là một chuyên gia OCR tài liệu chính xác cao.
                Nhiệm vụ của bạn là nhận diện toàn bộ chữ viết, bảng biểu và cấu trúc từ hình ảnh tài liệu được cung cấp và chuyển đổi thành định dạng Markdown chuẩn.
                
                ## YÊU CẦU:
                1. Hãy giữ nguyên cấu trúc văn bản (headings, paragraphs, lists, tables).
                2. Chuyển đổi các bảng biểu (tables) thành Markdown tables hoàn chỉnh.
                3. Trả về nội dung Markdown sạch sẽ.
                4. KHÔNG viết lời mở đầu, giải thích hay ghi chú. Chỉ trả về nội dung Markdown của tài liệu.
                """;

        ByteArrayResource byteResource = new ByteArrayResource(imageBytes);
        Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), byteResource);
        
        return chatClient.prompt()
                .options(GoogleGenAiChatOptions.builder()
                        .model(ocrModel)
                        .temperature(0.0)
                        .build())
                .system(systemPrompt)
                .user(u -> u.text("Trích xuất nội dung tài liệu này sang Markdown:").media(media))
                .call()
                .chatResponse()
                .getResult()
                .getOutput()
                .getText()
                .trim();
    }

    private String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/png";
    }
}
