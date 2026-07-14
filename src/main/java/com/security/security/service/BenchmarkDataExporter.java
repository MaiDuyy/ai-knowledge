package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Exports Golden Dataset MRP results to Ragas-ready JSONL tracks.
 * Production MAP prompt identity comes from {@link MrpProductionPrompts}.
 *
 * <p>Export layout (under {@code export/}):
 * <pre>
 *   ragas_claims.jsonl       — MAP claim faithfulness (claim + sourceContext/source)
 *   ragas_structured.jsonl   — MAP extract vs GT (extraction F1 / diagnostic AC)
 *   ragas_publish.jsonl      — post-Publish Markdown WikiPage (faithfulness / relevancy / recall)
 *   link_samples.jsonl       — wiki linking (Java GERBIL)
 *   pages/{docId}/*.md       — published page snapshots
 *   export-manifest.json
 *   prompts/
 *     map_system_prompt.txt           — production MAP system prompt
 *     map_phase_schema.json           — production JSON schema
 *     ragas_claim_task.txt            — evaluation task for claims
 *     ragas_structured_task.txt       — evaluation task for structured MAP
 *     ragas_publish_task.txt          — evaluation task for published wiki markdown
 * </pre>
 */
public final class BenchmarkDataExporter {

    public static final String SCHEMA_VERSION = "map-publish-v3";
    public static final String CLAIMS_FILE = "ragas_claims.jsonl";
    public static final String STRUCTURED_FILE = "ragas_structured.jsonl";
    public static final String PUBLISH_FILE = "ragas_publish.jsonl";
    public static final String LINKS_FILE = "link_samples.jsonl";
    public static final String MANIFEST_FILE = "export-manifest.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final ObjectMapper COMPACT = new ObjectMapper();

    private static final Pattern SOURCE_CONTEXT_SUFFIX =
            Pattern.compile("\\s*\\[Source Context:.*?]", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final List<Map<String, Object>> claimSamples = new ArrayList<>();
    private final List<Map<String, Object>> structuredSamples = new ArrayList<>();
    private final List<Map<String, Object>> publishSamples = new ArrayList<>();
    private final List<Map<String, Object>> linkSamples = new ArrayList<>();
    private Path lastExportDir;

    public void reset() {
        claimSamples.clear();
        structuredSamples.clear();
        publishSamples.clear();
        linkSamples.clear();
        lastExportDir = null;
    }

    /**
     * Claim faithfulness sample aligned with production MAP claim fields.
     *
     * @param sourceContext production {@code claims[].sourceContext} (preferred Ragas context)
     * @param fullSourceMarkdown full document markdown (fallback + metadata)
     */
    public void appendClaimSample(
            String documentId,
            String claimText,
            String sourceContext,
            String fullSourceMarkdown,
            String subject,
            boolean hasSourceContextSuffix) {
        if (claimText == null || claimText.isBlank()) return;

        String cleanClaim = stripSourceContextSuffix(claimText);
        if (cleanClaim.isBlank()) return;

        String context = (sourceContext != null && !sourceContext.isBlank())
                ? sourceContext.trim()
                : (fullSourceMarkdown != null ? fullSourceMarkdown : "");
        if (context.isBlank()) return;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("document_id", documentId);
        row.put("user_input", MrpProductionPrompts.RAGAS_CLAIM_FAITHFULNESS_TASK.trim());
        row.put("response", cleanClaim);
        // Ragas faithfulness: prefer production sourceContext (sentence-level), then full doc
        List<String> contexts = new ArrayList<>();
        contexts.add(context);
        if (fullSourceMarkdown != null && !fullSourceMarkdown.isBlank()
                && sourceContext != null && !sourceContext.isBlank()
                && !fullSourceMarkdown.trim().equals(sourceContext.trim())) {
            // secondary context for judges that can use multi-passage (optional)
            contexts.add(fullSourceMarkdown);
        }
        row.put("retrieved_contexts", contexts);
        row.put("reference", null);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("production_prompt_version", MrpProductionPrompts.PROMPT_VERSION);
        metadata.put("production_prompt_sha256", MrpProductionPrompts.mapPromptSha256());
        metadata.put("pipeline_stage", "MAP");
        metadata.put("map_schema", "entities/concepts/claims/contradictions/recommendations");
        if (subject != null && !subject.isBlank()) {
            metadata.put("subject", subject);
        }
        if (sourceContext != null && !sourceContext.isBlank()) {
            metadata.put("source_context", sourceContext.trim());
            metadata.put("context_mode", "production_sourceContext");
        } else {
            metadata.put("context_mode", "full_document");
        }
        metadata.put("has_source_context_suffix", hasSourceContextSuffix);
        metadata.put("claim_field", "claims[].claim");
        row.put("metadata", metadata);
        claimSamples.add(row);
    }

    /** Backward-compatible overload (no sourceContext). */
    public void appendClaimSample(
            String documentId,
            String claimText,
            String sourceMarkdown,
            String subject,
            boolean hasSourceContextSuffix) {
        appendClaimSample(documentId, claimText, null, sourceMarkdown, subject, hasSourceContextSuffix);
    }

    public void appendStructuredSample(
            String documentId,
            String sourceMarkdown,
            Map<String, Object> responseMap,
            Map<String, Object> referenceMap) {
        if (documentId == null || sourceMarkdown == null) return;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("document_id", documentId);
        row.put("user_input", MrpProductionPrompts.RAGAS_STRUCTURED_EXTRACTION_TASK.trim());
        row.put("response", toCompactJson(responseMap != null ? responseMap : emptyMapSchema()));
        row.put("retrieved_contexts", List.of(sourceMarkdown));
        row.put("reference", toCompactJson(referenceMap != null ? referenceMap : emptyMapSchema()));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schema_version", SCHEMA_VERSION);
        metadata.put("production_prompt_version", MrpProductionPrompts.PROMPT_VERSION);
        metadata.put("production_prompt_sha256", MrpProductionPrompts.mapPromptSha256());
        metadata.put("pipeline_stage", "map-aggregated");
        metadata.put("map_fields", List.of(
                "entities", "concepts", "claims", "contradictions", "recommendations"));
        metadata.put("primary_metric", "structuredExtractionF1");
        metadata.put("diagnostic_metric", "answer_correctness");
        row.put("metadata", metadata);
        structuredSamples.add(row);
    }

    public void appendLinkSample(
            String documentId,
            String content,
            Collection<String> actualLinks,
            Collection<String> expectedLinks,
            Collection<String> forbiddenLinks,
            Map<String, Double> metrics) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("document_id", documentId);
        row.put("content", content != null ? content : "");
        row.put("actual_links", sortedList(actualLinks));
        row.put("expected_links", sortedList(expectedLinks));
        row.put("forbidden_links", sortedList(forbiddenLinks));
        if (metrics != null) {
            row.put("link_f1", metrics.getOrDefault("linkF1", 0.0));
            row.put("link_precision", metrics.getOrDefault("linkPrecision", 0.0));
            row.put("link_recall", metrics.getOrDefault("linkRecall", 0.0));
        } else {
            row.put("link_f1", 0.0);
            row.put("link_precision", 0.0);
            row.put("link_recall", 0.0);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("metric", "gerbil_link_f1");
        metadata.put("ragas", false);
        row.put("metadata", metadata);
        linkSamples.add(row);
    }

    /**
     * Post-Publish Markdown WikiPage sample for official Ragas:
     * faithfulness, answer_relevancy, context_recall.
     *
     * @param wikiMarkdown published page content (after approveDraft)
     * @param sourceMarkdown original source document
     * @param referenceText free-text reference built from golden expectedClaims/topics
     * @param expectedTopics golden topics for topic-conditioned user_input
     * @param forbiddenHallucinations golden forbidden terms (for offline anti-hallu checks)
     */
    public void appendPublishSample(
            String documentId,
            String pageTitle,
            String pageSlug,
            String wikiMarkdown,
            String sourceMarkdown,
            String referenceText,
            Collection<String> expectedTopics,
            Collection<String> forbiddenHallucinations,
            Collection<String> expectedLinks,
            Collection<String> forbiddenLinks) {
        if (documentId == null || wikiMarkdown == null || wikiMarkdown.isBlank()) {
            return;
        }
        if (sourceMarkdown == null || sourceMarkdown.isBlank()) {
            return;
        }

        List<String> topics = sortedList(expectedTopics);
        String topicHint = topics.isEmpty()
                ? "the technical topics in the source procedure"
                : String.join(", ", topics);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("document_id", documentId);
        row.put(
                "user_input",
                MrpProductionPrompts.RAGAS_PUBLISH_WIKI_TASK.trim()
                        + "\nRequired topics: " + topicHint
                        + (pageTitle != null && !pageTitle.isBlank()
                        ? "\nPage title: " + pageTitle
                        : ""));
        row.put("response", wikiMarkdown);
        row.put("retrieved_contexts", List.of(sourceMarkdown));
        row.put("reference", referenceText != null ? referenceText : "");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pipeline_stage", "PUBLISH");
        metadata.put("artifact", "WikiPage.markdown");
        metadata.put("page_title", pageTitle != null ? pageTitle : "");
        metadata.put("page_slug", pageSlug != null ? pageSlug : "");
        metadata.put("production_prompt_version", MrpProductionPrompts.PROMPT_VERSION);
        metadata.put("production_prompt_sha256", MrpProductionPrompts.mapPromptSha256());
        metadata.put("primary_metrics", List.of(
                "publish_faithfulness", "answer_relevancy", "context_recall"));
        metadata.put("offline_metrics", List.of(
                "topic_coverage", "forbidden_hallucination_hits", "publish_link_f1"));
        metadata.put("expected_topics", topics);
        metadata.put("forbidden_hallucinations", sortedList(forbiddenHallucinations));
        metadata.put("expected_links", sortedList(expectedLinks));
        metadata.put("forbidden_links", sortedList(forbiddenLinks));
        row.put("metadata", metadata);
        publishSamples.add(row);
    }

    /**
     * Build free-text reference for Ragas context_recall from golden ground-truth JSON.
     * Prefer expectedClaims keywords + expectedTopics (not raw JSON).
     */
    public static String buildPublishReference(JsonNode groundTruth) {
        if (groundTruth == null || groundTruth.isNull()) {
            return "";
        }
        List<String> parts = new ArrayList<>();

        if (groundTruth.has("expectedTopics") && groundTruth.get("expectedTopics").isArray()) {
            List<String> topics = new ArrayList<>();
            groundTruth.get("expectedTopics").forEach(n -> {
                if (n != null && !n.asText("").isBlank()) {
                    topics.add(n.asText());
                }
            });
            if (!topics.isEmpty()) {
                parts.add("Topics: " + String.join("; ", topics));
            }
        }

        if (groundTruth.has("expectedEntities") && groundTruth.get("expectedEntities").isArray()) {
            List<String> entities = new ArrayList<>();
            groundTruth.get("expectedEntities").forEach(n -> {
                String name = textOrEmpty(n, "name");
                if (!name.isBlank()) {
                    entities.add(name);
                }
            });
            if (!entities.isEmpty()) {
                parts.add("Entities: " + String.join("; ", entities));
            }
        }

        if (groundTruth.has("expectedClaims") && groundTruth.get("expectedClaims").isArray()) {
            List<String> claims = new ArrayList<>();
            for (JsonNode n : groundTruth.get("expectedClaims")) {
                String subject = textOrEmpty(n, "subject");
                List<String> keywords = new ArrayList<>();
                if (n.has("keywords") && n.get("keywords").isArray()) {
                    n.get("keywords").forEach(k -> keywords.add(k.asText()));
                }
                if (!subject.isBlank() && !keywords.isEmpty()) {
                    claims.add(subject + ": " + String.join(", ", keywords));
                } else if (!subject.isBlank()) {
                    claims.add(subject);
                }
            }
            if (!claims.isEmpty()) {
                parts.add("Claims: " + String.join(". ", claims));
            }
        }

        return String.join("\n", parts);
    }

    public Path flushTo(Path exportDir, Map<String, Object> extraMeta) throws Exception {
        if (exportDir == null) {
            throw new IllegalArgumentException("exportDir is required");
        }
        Files.createDirectories(exportDir);

        writeJsonl(exportDir.resolve(CLAIMS_FILE), claimSamples);
        writeJsonl(exportDir.resolve(STRUCTURED_FILE), structuredSamples);
        writeJsonl(exportDir.resolve(PUBLISH_FILE), publishSamples);
        writeJsonl(exportDir.resolve(LINKS_FILE), linkSamples);
        writePromptArtifacts(exportDir.resolve("prompts"));
        writeExtractSnapshots(exportDir.resolve("extracts"));
        writePublishPageSnapshots(exportDir.resolve("pages"));

        Map<String, Object> manifest = new LinkedHashMap<>();
        if (extraMeta != null) {
            manifest.putAll(extraMeta);
        }
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("productionPromptVersion", MrpProductionPrompts.PROMPT_VERSION);
        manifest.put("productionPromptSha256", MrpProductionPrompts.mapPromptSha256());
        manifest.put("documentCount", structuredSamples.size());
        manifest.put("claimSampleCount", claimSamples.size());
        manifest.put("structuredSampleCount", structuredSamples.size());
        manifest.put("publishSampleCount", publishSamples.size());
        manifest.put("linkSampleCount", linkSamples.size());

        Map<String, String> files = new LinkedHashMap<>();
        files.put("claims", CLAIMS_FILE);
        files.put("structured", STRUCTURED_FILE);
        files.put("publish", PUBLISH_FILE);
        files.put("links", LINKS_FILE);
        files.put("mapSystemPrompt", "prompts/map_system_prompt.txt");
        files.put("mapPhaseSchema", "prompts/map_phase_schema.json");
        files.put("ragasClaimTask", "prompts/ragas_claim_task.txt");
        files.put("ragasStructuredTask", "prompts/ragas_structured_task.txt");
        files.put("ragasPublishTask", "prompts/ragas_publish_task.txt");
        files.put("extractSnapshots", "extracts/{documentId}.map.json");
        files.put("gtSnapshots", "extracts/{documentId}.reference.json");
        files.put("publishPages", "pages/{documentId}/{slug}.md");
        manifest.put("files", files);

        if (!manifest.containsKey("javaReport")) {
            manifest.put("javaReport", "../evaluation-benchmark-report.json");
        }

        Map<String, Object> tracks = new LinkedHashMap<>();
        tracks.put("claims", Map.of(
                "purpose", "Ragas faithfulness on MAP claims",
                "response", "MAP claims[].claim (clean)",
                "contexts", "prefer claims[].sourceContext, fallback full document",
                "production_prompt", MrpProductionPrompts.PROMPT_VERSION
        ));
        tracks.put("structured", Map.of(
                "purpose", "MAP extraction F1 (+ optional answer_correctness diagnostic)",
                "response", "aggregated MAP JSON from pipeline",
                "reference", "golden GT normalized to MAP schema",
                "production_prompt", MrpProductionPrompts.PROMPT_VERSION
        ));
        tracks.put("publish", Map.of(
                "purpose", "Post-Publish Markdown WikiPage: faithfulness + answer_relevancy + context_recall",
                "response", "approved WikiPage.content markdown",
                "contexts", "full source document markdown",
                "reference", "golden expectedTopics + expectedClaims free-text",
                "pipeline_stage", "PUBLISH"
        ));
        tracks.put("links", Map.of(
                "purpose", "GERBIL-style link F1 (Java only)",
                "ragas", false
        ));
        manifest.put("tracks", tracks);

        Map<String, String> metricSources = new LinkedHashMap<>();
        metricSources.put("faithfulness", "ragas_official on MAP claims + java_proxy");
        metricSources.put("publish_faithfulness", "ragas_official on published WikiPage markdown");
        metricSources.put("answer_relevancy", "ragas_official on published WikiPage vs topic-conditioned task");
        metricSources.put("context_recall", "ragas_official on publish reference vs source contexts");
        metricSources.put("structuredExtractionF1", "map fuzzy name F1 (entities+concepts)");
        metricSources.put("answer_correctness", "diagnostic only (Q&A metric, not MAP gate)");
        metricSources.put("linkF1", "java_gerbil");
        metricSources.put("forbiddenHallucinations", "java_assert + publish offline scan");
        manifest.put("metricSources", metricSources);

        MAPPER.writeValue(exportDir.resolve(MANIFEST_FILE).toFile(), manifest);
        lastExportDir = exportDir;
        return exportDir;
    }

    /**
     * Pretty-print production MAP extract + golden reference per document
     * so engineers can open the exact JSON shown in MRP "Cleaned JSON" logs.
     */
    private void writeExtractSnapshots(Path extractsDir) throws Exception {
        Files.createDirectories(extractsDir);
        for (Map<String, Object> row : structuredSamples) {
            Object docId = row.get("document_id");
            if (docId == null) continue;
            String id = docId.toString().replaceAll("[^a-zA-Z0-9._-]", "_");
            Object response = row.get("response");
            Object reference = row.get("reference");
            if (response instanceof String rs && !rs.isBlank()) {
                Object pretty = COMPACT.readValue(rs, Object.class);
                MAPPER.writeValue(extractsDir.resolve(id + ".map.json").toFile(), pretty);
            }
            if (reference instanceof String ref && !ref.isBlank()) {
                Object pretty = COMPACT.readValue(ref, Object.class);
                MAPPER.writeValue(extractsDir.resolve(id + ".reference.json").toFile(), pretty);
            }
        }
        Files.writeString(extractsDir.resolve("README.txt"), """
                Per-document MAP extraction snapshots
                =====================================
                {id}.map.json         — production MRP MAP extract (same content as Cleaned JSON logs)
                {id}.reference.json   — golden dataset GT normalized to MAP schema

                Ragas / MAP metrics read the JSONL tracks; these files are for human inspection.
                """.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }

    private void writePublishPageSnapshots(Path pagesDir) throws Exception {
        Files.createDirectories(pagesDir);
        for (Map<String, Object> row : publishSamples) {
            Object docIdObj = row.get("document_id");
            if (docIdObj == null) continue;
            String docId = docIdObj.toString().replaceAll("[^a-zA-Z0-9._-]", "_");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = row.get("metadata") instanceof Map
                    ? (Map<String, Object>) row.get("metadata")
                    : Map.of();
            String slug = String.valueOf(metadata.getOrDefault("page_slug", "page"));
            String safeSlug = slug.replaceAll("[^a-zA-Z0-9._/-]", "_").replace('/', '_');
            if (safeSlug.isBlank()) {
                safeSlug = "page";
            }
            Path docDir = pagesDir.resolve(docId);
            Files.createDirectories(docDir);
            Object response = row.get("response");
            if (response != null) {
                Files.writeString(docDir.resolve(safeSlug + ".md"), response.toString(), StandardCharsets.UTF_8);
            }
        }
        Files.writeString(pagesDir.resolve("README.txt"), """
                Published WikiPage markdown snapshots (post-Publish)
                ====================================================
                {documentId}/{slug}.md — approved WikiPage.content used for Ragas publish track.

                Primary Ragas metrics: faithfulness, answer_relevancy, context_recall.
                Offline: topic coverage, forbidden hallucination scan, link F1 on [[wikilinks]].
                """.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }

    private static void writePromptArtifacts(Path promptsDir) throws Exception {
        Files.createDirectories(promptsDir);
        Files.writeString(promptsDir.resolve("map_system_prompt.txt"),
                MrpProductionPrompts.MAP_SYSTEM_PROMPT, StandardCharsets.UTF_8);
        Files.writeString(promptsDir.resolve("map_phase_schema.json"),
                MrpProductionPrompts.MAP_PHASE_SCHEMA.trim() + "\n", StandardCharsets.UTF_8);
        Files.writeString(promptsDir.resolve("ragas_claim_task.txt"),
                MrpProductionPrompts.RAGAS_CLAIM_FAITHFULNESS_TASK.trim() + "\n", StandardCharsets.UTF_8);
        Files.writeString(promptsDir.resolve("ragas_structured_task.txt"),
                MrpProductionPrompts.RAGAS_STRUCTURED_EXTRACTION_TASK.trim() + "\n", StandardCharsets.UTF_8);
        Files.writeString(promptsDir.resolve("ragas_publish_task.txt"),
                MrpProductionPrompts.RAGAS_PUBLISH_WIKI_TASK.trim() + "\n", StandardCharsets.UTF_8);
        Files.writeString(promptsDir.resolve("README.txt"), """
                Production vs Ragas evaluation prompts
                ======================================
                map_system_prompt.txt     — exact system prompt used by MrpPipelineService MAP phase
                map_phase_schema.json     — JSON schema constrained on MAP LLM output
                ragas_claim_task.txt      — evaluation-only task text for MAP claim faithfulness
                ragas_structured_task.txt — evaluation-only task text for structured MAP JSONL
                ragas_publish_task.txt    — evaluation-only task text for post-Publish WikiPage markdown

                Pipeline uses production MAP/Reduce/Publish to GENERATE wiki pages.
                Ragas uses task prompts only as user_input labels for metrics;
                it does not re-run MRP extraction.
                """.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }

    public Path getLastExportDir() {
        return lastExportDir;
    }

    public int claimSampleCount() {
        return claimSamples.size();
    }

    public int structuredSampleCount() {
        return structuredSamples.size();
    }

    public int publishSampleCount() {
        return publishSamples.size();
    }

    public int linkSampleCount() {
        return linkSamples.size();
    }

    public static String stripSourceContextSuffix(String claim) {
        if (claim == null) return "";
        return SOURCE_CONTEXT_SUFFIX.matcher(claim).replaceAll("").trim();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> normalizeGroundTruthToMapSchema(JsonNode groundTruth) {
        Map<String, Object> map = emptyMapSchema();
        if (groundTruth == null || groundTruth.isNull()) {
            return map;
        }

        List<Map<String, Object>> entities = (List<Map<String, Object>>) map.get("entities");
        if (groundTruth.has("expectedEntities") && groundTruth.get("expectedEntities").isArray()) {
            for (JsonNode n : groundTruth.get("expectedEntities")) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("name", textOrEmpty(n, "name"));
                e.put("type", "entity");
                if (n.has("aliases") && n.get("aliases").isArray() && n.get("aliases").size() > 0) {
                    List<String> aliases = new ArrayList<>();
                    n.get("aliases").forEach(a -> aliases.add(a.asText()));
                    e.put("aliases", aliases);
                }
                if (!((String) e.get("name")).isBlank()) {
                    entities.add(e);
                }
            }
        }

        List<Map<String, Object>> concepts = (List<Map<String, Object>>) map.get("concepts");
        if (groundTruth.has("expectedConcepts") && groundTruth.get("expectedConcepts").isArray()) {
            for (JsonNode n : groundTruth.get("expectedConcepts")) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", textOrEmpty(n, "name"));
                c.put("type", "concept");
                if (n.has("keywords") && n.get("keywords").isArray()) {
                    List<String> keywords = new ArrayList<>();
                    n.get("keywords").forEach(k -> keywords.add(k.asText()));
                    c.put("keywords", keywords);
                }
                if (!((String) c.get("name")).isBlank()) {
                    concepts.add(c);
                }
            }
        }

        List<Map<String, Object>> claims = (List<Map<String, Object>>) map.get("claims");
        if (groundTruth.has("expectedClaims") && groundTruth.get("expectedClaims").isArray()) {
            for (JsonNode n : groundTruth.get("expectedClaims")) {
                String subject = textOrEmpty(n, "subject");
                List<String> keywords = new ArrayList<>();
                if (n.has("keywords") && n.get("keywords").isArray()) {
                    n.get("keywords").forEach(k -> keywords.add(k.asText()));
                }
                String claimText = subject;
                if (!keywords.isEmpty()) {
                    claimText = subject + ": " + String.join(", ", keywords);
                }
                Map<String, Object> claim = new LinkedHashMap<>();
                claim.put("subject", subject);
                claim.put("claim", claimText);
                if (!keywords.isEmpty()) {
                    claim.put("keywords", keywords);
                }
                claims.add(claim);
            }
        }

        map.put("contradictions", new ArrayList<>());

        List<Map<String, Object>> recommendations = new ArrayList<>();
        if (groundTruth.path("expectedRecommendations").asBoolean(false)) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("title", "Expected recommendations present in source");
            rec.put("description", "Golden GT expectedRecommendations=true");
            rec.put("priority", "MEDIUM");
            recommendations.add(rec);
        }
        map.put("recommendations", recommendations);

        return map;
    }

    public static Map<String, Object> aggregateMapExtracts(List<JsonNode> chunkRoots) {
        Map<String, Object> map = emptyMapSchema();
        if (chunkRoots == null || chunkRoots.isEmpty()) {
            return map;
        }

        @SuppressWarnings("unchecked")
        List<Object> entities = (List<Object>) map.get("entities");
        @SuppressWarnings("unchecked")
        List<Object> concepts = (List<Object>) map.get("concepts");
        @SuppressWarnings("unchecked")
        List<Object> claims = (List<Object>) map.get("claims");
        @SuppressWarnings("unchecked")
        List<Object> contradictions = (List<Object>) map.get("contradictions");
        @SuppressWarnings("unchecked")
        List<Object> recommendations = (List<Object>) map.get("recommendations");

        for (JsonNode root : chunkRoots) {
            if (root == null || !root.isObject()) continue;
            appendArrayObjects(root.get("entities"), entities);
            appendArrayObjects(root.get("concepts"), concepts);
            appendArrayObjects(root.get("claims"), claims);
            appendArrayObjects(root.get("contradictions"), contradictions);
            appendArrayObjects(root.get("recommendations"), recommendations);
        }
        return map;
    }

    public static Map<String, Object> emptyMapSchema() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("entities", new ArrayList<>());
        map.put("concepts", new ArrayList<>());
        map.put("claims", new ArrayList<>());
        map.put("contradictions", new ArrayList<>());
        map.put("recommendations", new ArrayList<>());
        return map;
    }

    private static void appendArrayObjects(JsonNode array, List<Object> target) {
        if (array == null || !array.isArray()) return;
        for (JsonNode n : array) {
            if (n == null || n.isNull()) continue;
            target.add(COMPACT.convertValue(n, Map.class));
        }
    }

    private static String textOrEmpty(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return "";
        return n.get(field).asText("");
    }

    private static List<String> sortedList(Collection<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(v -> v != null && !v.isBlank()).sorted().toList();
    }

    private static String toCompactJson(Map<String, Object> map) {
        try {
            return COMPACT.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize MAP schema JSON", e);
        }
    }

    private static void writeJsonl(Path file, List<Map<String, Object>> rows) throws Exception {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Map<String, Object> row : rows) {
                w.write(COMPACT.writeValueAsString(row));
                w.newLine();
            }
        }
    }
}
