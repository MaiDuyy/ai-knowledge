package com.security.security.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reranks a list of Spring AI Documents by relevance to a query using LLM scoring.
 * Plugs into the RAG pipeline after hybrid search and graph expansion.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RerankService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.rerank.top-n:5}")
    private int rerankTopN;

    /**
     * Rerank documents by relevance to query using LLM scoring.
     * Returns top-N most relevant documents in descending relevance order.
     * Falls back to original order (truncated) on any error.
     *
     * @param query      the user's search query
     * @param candidates the documents to rerank
     * @return top-N documents sorted by LLM-assigned relevance score
     */
    public List<Document> rerank(String query, List<Document> candidates) {
        if (!rerankEnabled || candidates.isEmpty()) return candidates;
        if (candidates.size() <= rerankTopN) return candidates;

        log.info("[RerankService] Reranking {} documents for query='{}'", candidates.size(), query);

        try {
            // Build batch scoring prompt
            StringBuilder prompt = new StringBuilder();
            prompt.append("You are a relevance scorer. Given a search query and passages, ")
                  .append("score each passage's relevance to the query from 0-10.\n\n")
                  .append("Query: ").append(query).append("\n\n")
                  .append("Passages:\n");

            for (int i = 0; i < candidates.size(); i++) {
                String text = candidates.get(i).getText();
                if (text != null && text.length() > 500) text = text.substring(0, 500) + "...";
                prompt.append(i).append(". ").append(text).append("\n\n");
            }

            prompt.append("Return ONLY a JSON array of integer scores in order, ")
                  .append("e.g.: [8, 3, 9, 5, 7]. One score per passage. No explanation.");

//            var chatResponse = chatModel.call(new UserMessage(prompt.toString()));
//            String responseText = chatResponse.getResult().getOutput().getText();
            String responseText = chatModel.call(prompt.toString());

            if (responseText == null || responseText.isBlank()) {
                log.warn("[RerankService] Empty response from LLM, falling back to original order");
                return candidates.stream().limit(rerankTopN).toList();
            }

            // Strip markdown code blocks if present
            responseText = responseText.replaceAll("(?s)```[a-z]*", "").replaceAll("```", "").trim();

            int[] scores = objectMapper.readValue(responseText, int[].class);

            // Pair document with score and sort descending
            List<Map.Entry<Integer, Document>> scored = new ArrayList<>();
            for (int i = 0; i < Math.min(scores.length, candidates.size()); i++) {
                scored.add(Map.entry(scores[i], candidates.get(i)));
            }
            scored.sort((a, b) -> Integer.compare(b.getKey(), a.getKey()));

            List<Document> reranked = scored.stream()
                    .limit(rerankTopN)
                    .map(Map.Entry::getValue)
                    .toList();

            log.info("[RerankService] Reranking complete: kept {} of {} documents", reranked.size(), candidates.size());
            return reranked;

        } catch (Exception e) {
            log.warn("[RerankService] Reranking failed, returning original order: {}", e.getMessage());
            return candidates.stream().limit(rerankTopN).toList();
        }
    }
}
