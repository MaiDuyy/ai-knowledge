package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared loader for {@code golden-dataset-manifest.json} — single source of truth
 * for Tier 1 (mrp-wiki) and Tier 2 (evaluation) benchmarks.
 */
public final class GoldenDatasetManifestLoader {

    public static final String MANIFEST_PATH = "benchmark/evaluation/golden-dataset-manifest.json";

    private static final Set<String> PROMPT_FIXTURE_IDS = Set.of(
            "short-note", "distinct-topics", "conflicting-doc");

    private static JsonNode manifest;

    private GoldenDatasetManifestLoader() {}

    public static synchronized JsonNode manifest() throws Exception {
        if (manifest == null) {
            manifest = BenchmarkEvaluationAssertions.loadResourceJson(MANIFEST_PATH);
        }
        return manifest;
    }

    public static void reset() {
        manifest = null;
    }

    public static String workspaceId() throws Exception {
        return manifest().get("workspaceId").asText();
    }

    public static int documentCount() throws Exception {
        return manifest().get("documents").size();
    }

    public static List<JsonNode> documents() throws Exception {
        List<JsonNode> docs = new ArrayList<>();
        manifest().get("documents").forEach(docs::add);
        return docs;
    }

    public static List<JsonNode> promptFixtureDocuments() throws Exception {
        return documents().stream()
                .filter(d -> PROMPT_FIXTURE_IDS.contains(d.get("id").asText()))
                .toList();
    }

    public static List<JsonNode> linkEvaluationDocuments() throws Exception {
        return documents().stream()
                .filter(d -> d.has("linkEvaluation") && !d.get("linkEvaluation").isNull())
                .toList();
    }

    public static JsonNode getById(String id) throws Exception {
        for (JsonNode doc : documents()) {
            if (id.equals(doc.get("id").asText())) {
                return doc;
            }
        }
        throw new IllegalArgumentException("Document not found in manifest: " + id);
    }

    public static String loadSource(JsonNode doc) throws Exception {
        return BenchmarkEvaluationAssertions.loadResource(doc.get("sourceFile").asText());
    }

    public static String loadSource(String docId) throws Exception {
        return loadSource(getById(docId));
    }

    public static JsonNode loadGroundTruth(JsonNode doc) throws Exception {
        return BenchmarkEvaluationAssertions.loadResourceJson(doc.get("groundTruthFile").asText());
    }

    public static JsonNode loadGroundTruth(String docId) throws Exception {
        return loadGroundTruth(getById(docId));
    }

    public static JsonNode linkEvaluation(JsonNode doc) {
        if (!doc.has("linkEvaluation") || doc.get("linkEvaluation").isNull()) {
            throw new IllegalArgumentException("No linkEvaluation for doc: " + doc.get("id").asText());
        }
        return doc.get("linkEvaluation");
    }
}