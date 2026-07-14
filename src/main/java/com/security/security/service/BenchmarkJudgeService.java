package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.provider.LlmProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Ragas/TruLens-style LLM judge with chain-of-thought and few-shot examples.
 */
@Service
public class BenchmarkJudgeService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";

    static final String GROUNDEDNESS_JUDGE_PROMPT = """
            You are an expert NLI evaluator following Ragas and TruLens methodology.

            Task: Rate whether the CLAIM is fully supported by the SOURCE passage only (no outside knowledge).

            Chain-of-thought steps:
            1. List atomic facts stated in the CLAIM.
            2. Find supporting or contradicting evidence in SOURCE for each fact.
            3. Assign a final score 1-5.

            Scoring rubric:
            1 = hallucinated / contradicted / no support in SOURCE
            2 = mostly unsupported
            3 = partially supported
            4 = mostly supported with minor inference
            5 = fully supported (verbatim or faithful paraphrase)

            Few-shot examples:
            SOURCE: "JWT tokens expire after 15 minutes."
            CLAIM: "JWT tokens expire after 15 minutes."
            → {"score": 5, "reason": "verbatim match", "verdict": "supported"}

            SOURCE: "JWT tokens expire after 15 minutes."
            CLAIM: "Sessions last 24 hours without refresh."
            → {"score": 1, "reason": "not in source", "verdict": "unsupported"}

            SOURCE: "API Gateway routes HTTP requests to internal microservices."
            CLAIM: "The gateway forwards HTTP traffic to backend services."
            → {"score": 5, "reason": "faithful paraphrase", "verdict": "supported"}

            Return ONLY JSON: {"score": <1-5>, "reason": "<brief>", "verdict": "supported|partial|unsupported"}
            """;

    static final String ANSWER_RELEVANCY_JUDGE_PROMPT = """
            You are an answer relevancy evaluator (Ragas-style).

            Task: Rate how well the ANSWER addresses the QUESTION/INTENT using only information present in ANSWER.

            Chain-of-thought:
            1. Identify what the question asks for.
            2. Check if ANSWER directly addresses that intent.
            3. Penalize off-topic or missing key points.

            Scoring:
            1 = completely irrelevant
            2 = mostly irrelevant
            3 = partially relevant
            4 = mostly relevant
            5 = fully addresses the intent

            Few-shot:
            QUESTION: "What authentication mechanism does the API use?"
            ANSWER: "JWT tokens are validated at the API Gateway filter chain."
            → {"score": 5, "reason": "directly answers auth mechanism"}

            QUESTION: "How does NATS messaging work?"
            ANSWER: "PostgreSQL stores user profiles in the identity schema."
            → {"score": 1, "reason": "wrong topic"}

            Return ONLY JSON: {"score": <1-5>, "reason": "<brief>"}
            """;

    static final String LINK_ACCURACY_JUDGE_PROMPT = """
            You are a GERBIL-style entity linking judge.

            Task: Rate wiki link accuracy given CONTENT, ACTUAL_LINKS, EXPECTED_LINKS, FORBIDDEN_LINKS.

            Chain-of-thought:
            1. Compare ACTUAL vs EXPECTED (recall).
            2. Check false positives not in EXPECTED (precision).
            3. Penalize any FORBIDDEN_LINKS present.

            Scoring:
            1 = mostly wrong / forbidden links present
            3 = partial match
            5 = all expected present, no forbidden, no spurious links

            Return ONLY JSON: {"score": <1-5>, "reason": "<brief>"}
            """;

    private final EvaluationMetricsService metricsService;

    public BenchmarkJudgeService(EvaluationMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public String resolveJudgeModel(boolean openAiAvailable) {
        return openAiAvailable ? "gpt-4o" : "gemini-self";
    }

    public Optional<Double> judgeGroundedness(
            String claim,
            String sourcePassage,
            LlmProvider fallbackProvider,
            boolean openAiAvailable,
            String openAiKey) {

        if (claim == null || sourcePassage == null) return Optional.empty();

        String userMessage = "SOURCE:\n" + truncate(sourcePassage, 4000) + "\n\nCLAIM:\n" + claim;

        if (openAiAvailable && openAiKey != null && !openAiKey.isBlank()) {
            Optional<Double> openAi = callOpenAiJudge(GROUNDEDNESS_JUDGE_PROMPT, userMessage, openAiKey);
            if (openAi.isPresent()) return openAi;
        }
        return llmJudgeFaithfulness(fallbackProvider, claim, sourcePassage);
    }

    public Optional<Double> judgeAnswerRelevancy(
            String questionOrIntent,
            String generatedAnswer,
            LlmProvider fallbackProvider,
            boolean openAiAvailable,
            String openAiKey) {

        if (questionOrIntent == null || generatedAnswer == null) return Optional.empty();

        String userMessage = "QUESTION/INTENT:\n" + questionOrIntent
                + "\n\nANSWER:\n" + truncate(generatedAnswer, 4000);

        if (openAiAvailable && openAiKey != null && !openAiKey.isBlank()) {
            Optional<Double> openAi = callOpenAiJudge(ANSWER_RELEVANCY_JUDGE_PROMPT, userMessage, openAiKey);
            if (openAi.isPresent()) return openAi;
        }
        return llmJudgeWithPrompt(fallbackProvider, ANSWER_RELEVANCY_JUDGE_PROMPT, userMessage);
    }

    public Optional<Double> judgeLinkAccuracy(
            String content,
            Set<String> actualLinks,
            Set<String> expectedLinks,
            Set<String> forbiddenLinks,
            LlmProvider fallbackProvider,
            boolean openAiAvailable,
            String openAiKey) {

        if (content == null) return Optional.empty();

        String userMessage = "CONTENT:\n" + truncate(content, 2000)
                + "\n\nACTUAL_LINKS: " + actualLinks
                + "\nEXPECTED_LINKS: " + expectedLinks
                + "\nFORBIDDEN_LINKS: " + (forbiddenLinks == null ? Set.of() : forbiddenLinks);

        if (openAiAvailable && openAiKey != null && !openAiKey.isBlank()) {
            Optional<Double> openAi = callOpenAiJudge(LINK_ACCURACY_JUDGE_PROMPT, userMessage, openAiKey);
            if (openAi.isPresent()) return openAi;
        }
        return heuristicLinkJudge(actualLinks, expectedLinks, forbiddenLinks);
    }

    public Optional<Double> llmJudgeFaithfulness(LlmProvider provider, String claim, String sourcePassage) {
        if (provider == null || claim == null || sourcePassage == null) return Optional.empty();
        String userMessage = "SOURCE:\n" + truncate(sourcePassage, 4000) + "\n\nCLAIM:\n" + claim;
        return llmJudgeWithPrompt(provider, GROUNDEDNESS_JUDGE_PROMPT, userMessage);
    }

    private Optional<Double> llmJudgeWithPrompt(LlmProvider provider, String systemPrompt, String userMessage) {
        try {
            String response = provider.callChat(systemPrompt, userMessage, null, "bench-judge-" + UUID.randomUUID());
            JsonNode node = MAPPER.readTree(extractJson(response));
            if (node.has("score")) {
                return Optional.of(node.get("score").asDouble());
            }
        } catch (Exception ignored) {
            // judge is optional secondary layer
        }
        return Optional.empty();
    }

    private Optional<Double> callOpenAiJudge(String systemPrompt, String userMessage, String apiKey) {
        try {
            String model = System.getenv().getOrDefault("OPENAI_JUDGE_MODEL",
                    System.getProperty("OPENAI_JUDGE_MODEL", "gpt-4o"));

            String body = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                    .put("model", model)
                    .put("temperature", 0.0)
                    .set("messages", MAPPER.createArrayNode()
                            .add(MAPPER.createObjectNode().put("role", "system").put("content", systemPrompt))
                            .add(MAPPER.createObjectNode().put("role", "user").put("content", userMessage))));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OPENAI_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = MAPPER.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            JsonNode parsed = MAPPER.readTree(extractJson(content));
            if (parsed.has("score")) {
                return Optional.of(parsed.get("score").asDouble());
            }
        } catch (Exception ignored) {
            // fall through
        }
        return Optional.empty();
    }

    private Optional<Double> heuristicLinkJudge(
            Set<String> actual, Set<String> expected, Set<String> forbidden) {

        Set<String> exp = expected == null ? Set.of() : expected;
        Set<String> forb = forbidden == null ? Set.of() : forbidden;
        Set<String> act = actual == null ? Set.of() : actual;

        long forbiddenHit = act.stream().filter(forb::contains).count();
        if (forbiddenHit > 0) return Optional.of(1.0);
        if (exp.isEmpty() && act.isEmpty()) return Optional.of(5.0);
        if (exp.isEmpty() && !act.isEmpty()) return Optional.of(1.0);

        Map<String, Double> f1 = metricsService.computeEntityLinkingMetrics(act, exp);
        double score = 1.0 + f1.get("linkF1") * 4.0;
        return Optional.of(Math.min(5.0, Math.max(1.0, score)));
    }

    private static String extractJson(String response) {
        if (response == null) return "{}";
        String trimmed = response.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}