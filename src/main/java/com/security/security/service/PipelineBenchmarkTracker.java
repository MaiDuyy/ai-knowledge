package com.security.security.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tracks pipeline latency and estimated token cost per benchmark run.
 */
public final class PipelineBenchmarkTracker {

    private PipelineBenchmarkTracker() {}

    public record PhaseTiming(String phase, long durationMs) {}

    public record RunMetrics(
            long totalLatencyMs,
            int estimatedInputTokens,
            int estimatedOutputTokens,
            int estimatedTotalTokens,
            List<PhaseTiming> phases) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalLatencyMs", totalLatencyMs);
            m.put("estimatedInputTokens", estimatedInputTokens);
            m.put("estimatedOutputTokens", estimatedOutputTokens);
            m.put("estimatedTotalTokens", estimatedTotalTokens);
            if (!phases.isEmpty()) {
                m.put("phases", phases.stream().map(p -> Map.of("phase", p.phase(), "durationMs", p.durationMs())).toList());
            }
            return m;
        }
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.length() / 4);
    }

    public static RunMetrics measure(String inputText, Supplier<String> pipeline) {
        long start = System.nanoTime();
        String output = pipeline.get();
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        int inputTokens = estimateTokens(inputText);
        int outputTokens = estimateTokens(output);
        return new RunMetrics(latencyMs, inputTokens, outputTokens, inputTokens + outputTokens, List.of());
    }

    public static RunMetrics measureRunnable(String inputText, String outputText, Runnable pipeline) {
        List<PhaseTiming> phases = new ArrayList<>();
        long start = System.nanoTime();
        pipeline.run();
        phases.add(new PhaseTiming("pipeline", (System.nanoTime() - start) / 1_000_000));

        int inputTokens = estimateTokens(inputText);
        int outputTokens = estimateTokens(outputText);
        long totalMs = phases.stream().mapToLong(PhaseTiming::durationMs).sum();
        return new RunMetrics(totalMs, inputTokens, outputTokens, inputTokens + outputTokens, phases);
    }
}