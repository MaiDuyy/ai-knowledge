package com.security.security.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        // Define strict schema for consistency
        var responseSchema = """
                {
                  "type": "object",
                  "properties": {
                    "summary": { "type": "string" },
                    "details": { "type": "array", "items": { "type": "string" } },
                    "sources": { "type": "array", "items": { "type": "string" } }
                  },
                  "required": ["summary", "details", "sources"]
                }
                """;

        return ChatClient.builder(chatModel)
                .defaultOptions(org.springframework.ai.google.genai.GoogleGenAiChatOptions.builder()
                        .responseMimeType("application/json")
                        .responseSchema(responseSchema)
                        .temperature(0.0)
                        .build())
                .build();
    }

    @Bean
    public DocumentRetriever documentRetriever(VectorStore vectorStore) {
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .similarityThreshold(0.5)
                .topK(5)
                .build();
    }

    @Bean
    public RetrievalAugmentationAdvisor ragAdvisor(DocumentRetriever documentRetriever) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();
    }
}