package com.security.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cosine similarity helpers for experimental brute-force vector search
 * (portable offline baseline when Atlas $vectorSearch is unavailable).
 */
public final class VectorMath {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VectorMath() {}

    public static double cosineSimilarity(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int n = Math.min(a.size(), b.size());
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < n; i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static List<Double> parseEmbeddingJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Double>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    public static String toEmbeddingJson(List<Double> vector) {
        try {
            return MAPPER.writeValueAsString(vector);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** Deterministic-ish unit vector for benchmarks without an embedding API. */
    public static List<Double> randomUnitVector(int dimensions, long seed) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        // mix seed into first draws for reproducibility across engines when same seed used
        List<Double> v = new ArrayList<>(dimensions);
        double norm = 0.0;
        for (int i = 0; i < dimensions; i++) {
            double x = Math.sin(seed * 0.001 + i * 12.9898) * 43758.5453;
            x = x - Math.floor(x);
            x = (x * 2.0) - 1.0;
            // slight noise so vectors aren't identical across dims only
            x += (rnd.nextDouble() - 0.5) * 0.01;
            v.add(x);
            norm += x * x;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return v;
        }
        for (int i = 0; i < v.size(); i++) {
            v.set(i, v.get(i) / norm);
        }
        return v;
    }

    public static List<Double> randomUnitVector(int dimensions) {
        return randomUnitVector(dimensions, ThreadLocalRandom.current().nextLong());
    }
}
