package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ragas / TruLens / GERBIL-aligned evaluation metrics for SecWiki-Bench.
 * Reusable from benchmark tests, CI, and future monitoring endpoints.
 */
@Service
public class EvaluationMetricsService {

    private static final Pattern SOURCE_CONTEXT_SUFFIX =
            Pattern.compile("\\[Source Context:.*?]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATOMIC_SPLIT =
            Pattern.compile("(?<=[.!?;])\\s+|\\s+(?:and|và|,)\\s+", Pattern.CASE_INSENSITIVE);

    public List<String> decomposeToAtomicClaims(String claim) {
        if (claim == null || claim.isBlank()) return List.of();

        String cleaned = SOURCE_CONTEXT_SUFFIX.matcher(claim).replaceAll("").trim();
        if (cleaned.isBlank()) return List.of();

        List<String> atomics = Arrays.stream(ATOMIC_SPLIT.split(cleaned))
                .map(String::trim)
                .filter(s -> s.length() >= 8)
                .collect(Collectors.toList());

        return atomics.isEmpty() ? List.of(cleaned) : atomics;
    }

    /**
     * Ragas-style faithfulness: decompose each claim into atomic statements, verify each against context.
     */
    public double computeRagasFaithfulness(List<String> claims, String sourceText) {
        if (claims == null || claims.isEmpty()) return 1.0;

        String normSource = normalize(sourceText);
        int totalAtomic = 0;
        int faithfulAtomic = 0;

        for (String claim : claims) {
            if (claim == null || claim.isBlank()) continue;
            List<String> atomics = decomposeToAtomicClaims(claim);
            if (atomics.isEmpty()) atomics = List.of(claim);

            for (String atomic : atomics) {
                totalAtomic++;
                if (isGroundedInSource(atomic, normSource)) faithfulAtomic++;
            }
        }
        return totalAtomic == 0 ? 1.0 : (double) faithfulAtomic / totalAtomic;
    }

    /** @deprecated name kept for callers — delegates to Ragas-style implementation */
    public double computeFaithfulness(List<String> claims, String sourceText) {
        return computeRagasFaithfulness(claims, sourceText);
    }

    public double computeGroundednessRate(Collection<String> texts, String sourceText) {
        if (texts == null || texts.isEmpty()) return 1.0;
        String normSource = normalize(sourceText);
        long grounded = texts.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> isGroundedInSource(t, normSource))
                .count();
        long total = texts.stream().filter(t -> t != null && !t.isBlank()).count();
        return total == 0 ? 1.0 : (double) grounded / total;
    }

    public Map<String, Double> computeContextRecallPrecision(Set<String> predicted, Set<String> groundTruth) {
        if (groundTruth.isEmpty()) {
            return Map.of(
                    "contextRecall", 1.0,
                    "contextPrecision", predicted.isEmpty() ? 1.0 : 0.0,
                    "extractionF1", predicted.isEmpty() ? 1.0 : 0.0);
        }
        Set<String> predNorm = normalizeSet(predicted);
        Set<String> gtNorm = normalizeSet(groundTruth);

        int matchedGt = 0;
        for (String gt : gtNorm) {
            if (predNorm.stream().anyMatch(p -> fuzzyTopicMatch(p, gt))) matchedGt++;
        }
        int matchedPred = 0;
        for (String p : predNorm) {
            if (gtNorm.stream().anyMatch(gt -> fuzzyTopicMatch(p, gt))) matchedPred++;
        }

        double recall = (double) matchedGt / gtNorm.size();
        double precision = predNorm.isEmpty() ? 0.0 : (double) matchedPred / predNorm.size();
        double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);

        return Map.of("contextRecall", recall, "contextPrecision", precision, "extractionF1", f1);
    }

    /**
     * Ragas answer relevancy (heuristic): fraction of expected topics reflected in generated wiki content.
     */
    public double computeAnswerRelevancy(String generatedContent, Set<String> expectedTopics) {
        if (expectedTopics == null || expectedTopics.isEmpty()) return 1.0;
        if (generatedContent == null || generatedContent.isBlank()) return 0.0;

        String normContent = normalize(generatedContent);
        long matched = expectedTopics.stream()
                .filter(t -> t != null && !t.isBlank())
                .filter(t -> topicMentionedInContent(t, normContent))
                .count();
        return (double) matched / expectedTopics.size();
    }

    public List<String> detectHallucinations(Collection<String> extractedNames, String sourceText, List<String> forbidden) {
        String normSource = normalize(sourceText);
        List<String> hallucinations = new ArrayList<>();

        if (forbidden != null) {
            for (String f : forbidden) {
                if (f != null && normSource.contains(normalize(f))) continue;
                boolean inExtract = extractedNames.stream()
                        .anyMatch(n -> n != null && normalize(n).contains(normalize(f)));
                if (inExtract) hallucinations.add(f);
            }
        }
        for (String name : extractedNames) {
            if (name == null || name.isBlank()) continue;
            if (!isGroundedInSource(name, normSource)) hallucinations.add(name);
        }
        return hallucinations.stream().distinct().collect(Collectors.toList());
    }

    public boolean verifySourceContextSuffix(Collection<String> keyClaims) {
        if (keyClaims == null || keyClaims.isEmpty()) return true;
        return keyClaims.stream()
                .filter(c -> c != null && !c.contains("claim"))
                .allMatch(c -> c.contains("[Source Context:") || c.contains("[Source Context: "));
    }

    public double computeSchemaComplianceMap(JsonNode root) {
        if (root == null || !root.isObject()) return 0.0;
        String[] required = {"entities", "concepts", "claims", "contradictions", "recommendations"};
        int ok = 0;
        for (String key : required) {
            if (root.has(key) && root.get(key).isArray()) ok++;
        }
        return (double) ok / required.length;
    }

    public double computeSchemaComplianceReduce(JsonNode root) {
        if (root == null || !root.isArray() || root.isEmpty()) return 0.0;
        String[] required = {"title", "slug", "action", "pageType", "tags", "reason", "keyClaims"};
        int compliant = 0;
        for (JsonNode item : root) {
            boolean allPresent = true;
            for (String key : required) {
                if (!item.has(key)) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) compliant++;
        }
        return (double) compliant / root.size();
    }

    public Map<String, Double> computeEntityLinkingMetrics(Set<String> actual, Set<String> expected) {
        Set<String> act = new HashSet<>(actual == null ? Set.of() : actual);
        Set<String> exp = new HashSet<>(expected == null ? Set.of() : expected);
        Set<String> tp = new HashSet<>(act);
        tp.retainAll(exp);
        double precision = act.isEmpty() ? (exp.isEmpty() ? 1.0 : 0.0) : (double) tp.size() / act.size();
        double recall = exp.isEmpty() ? (act.isEmpty() ? 1.0 : 0.0) : (double) tp.size() / exp.size();
        double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        return Map.of("linkPrecision", precision, "linkRecall", recall, "linkF1", f1);
    }

    /**
     * GERBIL-style metrics with NIL detection (links to non-existent wiki slugs).
     */
    public GerbilLinkingMetrics computeGerbilLinkingMetrics(
            Set<String> actual,
            Set<String> expected,
            Set<String> existingSlugs) {

        Set<String> act = new HashSet<>(actual == null ? Set.of() : actual);
        Set<String> exp = new HashSet<>(expected == null ? Set.of() : expected);
        Set<String> existing = new HashSet<>(existingSlugs == null ? Set.of() : existingSlugs);

        Set<String> nilLinks = act.stream().filter(s -> !existing.contains(s)).collect(Collectors.toSet());
        Set<String> resolvedActual = act.stream().filter(existing::contains).collect(Collectors.toSet());

        Map<String, Double> micro = computeEntityLinkingMetrics(resolvedActual, exp);
        int nilCount = nilLinks.size();
        double nilRate = act.isEmpty() ? 0.0 : (double) nilCount / act.size();

        return new GerbilLinkingMetrics(
                micro.get("linkPrecision"),
                micro.get("linkRecall"),
                micro.get("linkF1"),
                nilCount,
                nilRate,
                nilLinks);
    }

    public double computeMacroF1(List<Map<String, Double>> perScenarioF1) {
        if (perScenarioF1 == null || perScenarioF1.isEmpty()) return 0.0;
        return perScenarioF1.stream().mapToDouble(m -> m.getOrDefault("linkF1", 0.0)).average().orElse(0.0);
    }

    private boolean topicMentionedInContent(String topic, String normContent) {
        String nt = normalize(topic);
        if (nt.isEmpty()) return false;
        if (normContent.contains(nt)) return true;
        for (String token : nt.split(" ")) {
            if (token.length() >= 3 && normContent.contains(token)) return true;
        }
        if (nt.equals("jwt") && normContent.contains("json web token")) return true;
        if (nt.contains("json web token") && normContent.contains("jwt")) return true;
        return false;
    }

    public boolean fuzzyTopicMatch(String a, String b) {
        if (a == null || b == null) return false;
        String na = normalize(a);
        String nb = normalize(b);
        if (na.equals(nb)) return true;
        if (na.contains(nb) || nb.contains(na)) return true;
        if ((na.equals("jwt") && nb.contains("json web token")) || (nb.equals("jwt") && na.contains("json web token"))) {
            return true;
        }
        return na.contains("spring") && nb.contains("spring");
    }

    public String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    boolean isGroundedInSource(String text, String normSource) {
        String norm = normalize(text);
        if (norm.isEmpty()) return true;
        if (normSource.contains(norm)) return true;
        for (String token : norm.split(" ")) {
            if (token.length() >= 3 && normSource.contains(token)) return true;
        }
        return false;
    }

    private Set<String> normalizeSet(Set<String> input) {
        return input.stream()
                .filter(Objects::nonNull)
                .map(this::normalize)
                .collect(Collectors.toSet());
    }

    public record GerbilLinkingMetrics(
            double microPrecision,
            double microRecall,
            double microF1,
            int nilCount,
            double nilRate,
            Set<String> nilLinks) {}
}