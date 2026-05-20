package com.security.security.service;

import com.security.security.dto.AiRefactorResponse;
import com.security.security.dtorequest.AiRefactorRequest;
import com.security.security.entity.Document;
import com.security.security.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiRefactorService {

    private final ChatModel chatModel;
    private final DocumentService documentService;

    public AiRefactorResponse refactorDocument(Long documentId, AiRefactorRequest request, String userId) {
        log.info("Refactoring document {} mode {} for user {}", documentId, request.getMode(), userId);

        ChatClient chatClient = ChatClient.builder(chatModel).build();

        Document document = documentService.getDocument(documentId, userId);
        
        if (!document.getUserId().equals(userId) && !userId.equals("system-user")) {
            throw new ApiException("Permission denied. You can only refactor your own documents.");
        }

        String currentMarkdown = document.getMarkdownContent();
        if (currentMarkdown == null || currentMarkdown.isBlank()) {
            throw new ApiException("Document has no markdown content to refactor.");
        }

        String systemPrompt = """
                Bạn là một chuyên gia về định dạng văn bản Markdown.
                Nhiệm vụ của bạn là sửa các lỗi về cấu trúc Markdown (như Heading ##, list, table) dựa trên văn bản người dùng cung cấp.
                TUYỆT ĐỐI KHÔNG thay đổi ý nghĩa, không tự ý thêm bớt nội dung, không bịa đặt.
                CHỈ TRẢ VỀ mã Markdown đã sửa, KHÔNG giải thích.
                """;

        String userPrompt = "";
        boolean isPartial = "PARTIAL".equalsIgnoreCase(request.getMode());

        if (isPartial) {
            if (request.getTargetText() == null || request.getTargetText().isBlank()) {
                throw new ApiException("targetText is required for PARTIAL mode.");
            }
            if (!currentMarkdown.contains(request.getTargetText())) {
                throw new ApiException("targetText not found in the original document. Please ensure the target text is exactly as it appears in the document.");
            }

            userPrompt = "Đoạn văn bản cần sửa:\n" + request.getTargetText();
            if (request.getInstruction() != null && !request.getInstruction().isBlank()) {
                userPrompt += "\n\nYêu cầu sửa (Instruction): " + request.getInstruction();
            }
        } else {
            userPrompt = "Văn bản cần sửa toàn bộ:\n" + currentMarkdown;
            if (request.getInstruction() != null && !request.getInstruction().isBlank()) {
                userPrompt += "\n\nYêu cầu sửa (Instruction): " + request.getInstruction();
            }
        }

        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse();

        String refactoredOutput = response.getResult().getOutput().getText().trim();
        
        // Remove markdown block backticks if LLM adds them
        if (refactoredOutput.startsWith("```markdown")) {
            refactoredOutput = refactoredOutput.substring(11).trim();
        }
        if (refactoredOutput.startsWith("```")) {
            refactoredOutput = refactoredOutput.substring(3).trim();
        }
        if (refactoredOutput.endsWith("```")) {
            refactoredOutput = refactoredOutput.substring(0, refactoredOutput.length() - 3).trim();
        }

        String finalMarkdown;
        if (isPartial) {
            finalMarkdown = currentMarkdown.replace(request.getTargetText(), refactoredOutput);
        } else {
            finalMarkdown = refactoredOutput;
        }

        // Apply changes to database by triggering ingestDocument
        documentService.ingestDocument(documentId, finalMarkdown, userId);

        return AiRefactorResponse.builder()
                .refactoredMarkdown(finalMarkdown)
                .build();
    }
}
