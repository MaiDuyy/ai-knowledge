package com.security.security.service;

import com.security.security.provider.LlmProvider;

import java.util.Optional;
import java.util.Set;

/**
 * Cross-model LLM judge facade — delegates to {@link BenchmarkJudgeService}.
 */
public final class BenchmarkCrossModelJudge {

    private static final BenchmarkJudgeService JUDGE =
            new BenchmarkJudgeService(new EvaluationMetricsService());

    private BenchmarkCrossModelJudge() {}

    public static String resolveJudgeModel() {
        return JUDGE.resolveJudgeModel(BenchmarkConditions.openAiKeyAvailable());
    }

    public static Optional<Double> judgeGroundedness(String claim, String sourcePassage, LlmProvider fallbackProvider) {
        return JUDGE.judgeGroundedness(
                claim,
                sourcePassage,
                fallbackProvider,
                BenchmarkConditions.openAiKeyAvailable(),
                BenchmarkConditions.resolveOpenAiKey());
    }

    public static Optional<Double> judgeAnswerRelevancy(
            String questionOrIntent, String generatedAnswer, LlmProvider fallbackProvider) {
        return JUDGE.judgeAnswerRelevancy(
                questionOrIntent,
                generatedAnswer,
                fallbackProvider,
                BenchmarkConditions.openAiKeyAvailable(),
                BenchmarkConditions.resolveOpenAiKey());
    }

    public static Optional<Double> judgeLinkAccuracy(
            String content,
            Set<String> actualLinks,
            Set<String> expectedLinks,
            Set<String> forbiddenLinks,
            LlmProvider fallbackProvider) {
        return JUDGE.judgeLinkAccuracy(
                content,
                actualLinks,
                expectedLinks,
                forbiddenLinks,
                fallbackProvider,
                BenchmarkConditions.openAiKeyAvailable(),
                BenchmarkConditions.resolveOpenAiKey());
    }
}