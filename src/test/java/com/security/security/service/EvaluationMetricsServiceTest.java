package com.security.security.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationMetricsServiceTest {

    private EvaluationMetricsService metrics;

    @BeforeEach
    void setUp() {
        metrics = new EvaluationMetricsService();
    }

    @Test
    @DisplayName("Ragas faithfulness decomposes compound claims and verifies atomics")
    void ragasFaithfulness_decomposesClaims() {
        String source = "JWT tokens expire after 15 minutes. API Gateway validates requests.";
        List<String> claims = List.of(
                "JWT tokens expire after 15 minutes and API Gateway validates requests.");

        double score = metrics.computeRagasFaithfulness(claims, source);
        assertThat(score).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Ragas faithfulness penalizes unsupported atomic claim")
    void ragasFaithfulness_penalizesHallucination() {
        String source = "JWT tokens expire after 15 minutes.";
        List<String> claims = List.of("JWT tokens expire after 15 minutes. Sessions last 24 hours.");

        double score = metrics.computeRagasFaithfulness(claims, source);
        assertThat(score).isLessThan(1.0);
    }

    @Test
    @DisplayName("Answer relevancy measures topic coverage in generated content")
    void answerRelevancy_coversExpectedTopics() {
        String generated = "Wiki page about JWT authentication and API Gateway routing.";
        Set<String> expected = Set.of("JWT", "API Gateway");

        assertThat(metrics.computeAnswerRelevancy(generated, expected)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("GERBIL metrics detect NIL links to missing slugs")
    void gerbil_nilDetection() {
        EvaluationMetricsService.GerbilLinkingMetrics result = metrics.computeGerbilLinkingMetrics(
                Set.of("concept/jwt", "concept/missing-page"),
                Set.of("concept/jwt"),
                Set.of("concept/jwt", "concept/spring-security"));

        assertThat(result.nilCount()).isEqualTo(1);
        assertThat(result.nilLinks()).contains("concept/missing-page");
        assertThat(result.microF1()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Atomic claim decomposition strips source context suffix")
    void decompose_stripsSourceContext() {
        List<String> atomics = metrics.decomposeToAtomicClaims(
                "JWT is used for auth. [Source Context: paragraph 1]");

        assertThat(atomics).hasSize(1);
        assertThat(atomics.get(0)).contains("JWT");
        assertThat(atomics.get(0)).doesNotContain("Source Context");
    }
}