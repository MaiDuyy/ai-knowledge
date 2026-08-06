package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BenchmarkDataExporter — GT normalize + Ragas JSONL export")
class BenchmarkDataExporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("normalizeGroundTruthToMapSchema maps expected* fields to MAP schema")
    void normalizeGroundTruth_fromRealOauth2Gt() throws Exception {
        JsonNode gt = loadResourceJson("benchmark/evaluation/ground-truth/oauth2-flow.json");

        Map<String, Object> map = BenchmarkDataExporter.normalizeGroundTruthToMapSchema(gt);

        assertThat(map).containsKeys("entities", "concepts", "claims", "contradictions", "recommendations");
        assertThat((List<?>) map.get("entities")).isNotEmpty();
        assertThat((List<?>) map.get("concepts")).isNotEmpty();
        assertThat((List<?>) map.get("claims")).isNotEmpty();
        assertThat((List<?>) map.get("contradictions")).isEmpty();
        // oauth2-flow GT has expectedRecommendations=true → stub recommendation entry
        assertThat((List<?>) map.get("recommendations")).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> firstEntity = (Map<String, Object>) ((List<?>) map.get("entities")).get(0);
        assertThat(firstEntity.get("name")).isEqualTo("OAuth2");
        assertThat(firstEntity.get("type")).isEqualTo("entity");
    }

    @Test
    @DisplayName("normalizeGroundTruth recommendations stub when expectedRecommendations=true")
    void normalizeGroundTruth_recommendationsFlag() throws Exception {
        JsonNode gt = loadResourceJson("benchmark/mrp/short-note-ground-truth.json");
        Map<String, Object> map = BenchmarkDataExporter.normalizeGroundTruthToMapSchema(gt);
        assertThat((List<?>) map.get("recommendations")).hasSize(1);
    }

    @Test
    @DisplayName("aggregateMapExtracts merges chunk roots")
    void aggregateMapExtracts_mergesArrays() throws Exception {
        JsonNode a = MAPPER.readTree("""
                {"entities":[{"name":"JWT"}],"concepts":[],"claims":[{"claim":"c1"}],"contradictions":[],"recommendations":[]}
                """);
        JsonNode b = MAPPER.readTree("""
                {"entities":[{"name":"OAuth2"}],"concepts":[{"name":"PKCE"}],"claims":[],"contradictions":[{"note":"x"}],"recommendations":[{"r":1}]}
                """);

        Map<String, Object> agg = BenchmarkDataExporter.aggregateMapExtracts(List.of(a, b));

        assertThat((List<?>) agg.get("entities")).hasSize(2);
        assertThat((List<?>) agg.get("concepts")).hasSize(1);
        assertThat((List<?>) agg.get("claims")).hasSize(1);
        assertThat((List<?>) agg.get("contradictions")).hasSize(1);
        assertThat((List<?>) agg.get("recommendations")).hasSize(1);
    }

    @Test
    @DisplayName("flushTo writes three JSONL tracks + export-manifest")
    void flushTo_writesAllTracks() throws Exception {
        BenchmarkDataExporter exporter = new BenchmarkDataExporter();
        JsonNode gt = loadResourceJson("benchmark/evaluation/ground-truth/rbac-model.json");
        Map<String, Object> reference = BenchmarkDataExporter.normalizeGroundTruthToMapSchema(gt);

        JsonNode extract = MAPPER.readTree("""
                {
                  "entities":[{"name":"ADMIN"}],
                  "concepts":[{"name":"RBAC"}],
                  "claims":[{"claim":"RBAC scopes Workspace","subject":"RBAC"}],
                  "contradictions":[],
                  "recommendations":[]
                }
                """);
        Map<String, Object> response = BenchmarkDataExporter.aggregateMapExtracts(List.of(extract));

        String source = "RBAC with ADMIN role and Redis cache.";
        String sourceContext = "RBAC scopes permissions at Workspace and Department level.";
        exporter.appendStructuredSample("rbac-model", source, response, reference);
        exporter.appendClaimSample(
                "rbac-model", "RBAC scopes Workspace", sourceContext, source, "RBAC", false);
        exporter.appendLinkSample(
                "rbac-model",
                "[[concept/rbac]]",
                Set.of("concept/rbac"),
                Set.of("concept/rbac", "concept/identity-service"),
                Set.of("topic/graphql"),
                Map.of("linkF1", 0.67, "linkPrecision", 1.0, "linkRecall", 0.5));

        String wikiMarkdown = """
                # RBAC Model
                OTT Chat uses Workspace, Department and Global scope.
                See [[concept/rbac]] for details.
                """;
        String publishRef = BenchmarkDataExporter.buildPublishReference(gt);
        exporter.appendPublishSample(
                "rbac-model",
                "RBAC Model",
                "concept/rbac",
                wikiMarkdown,
                source,
                publishRef,
                List.of("RBAC", "ADMIN", "Workspace"),
                List.of("Kubernetes", "MongoDB"),
                Set.of("concept/rbac"),
                Set.of("topic/graphql"));

        Path exportDir = tempDir.resolve("export");
        exporter.flushTo(exportDir, Map.of("runId", "test-run"));

        assertThat(exportDir.resolve(BenchmarkDataExporter.CLAIMS_FILE)).exists();
        assertThat(exportDir.resolve(BenchmarkDataExporter.STRUCTURED_FILE)).exists();
        assertThat(exportDir.resolve(BenchmarkDataExporter.PUBLISH_FILE)).exists();
        assertThat(exportDir.resolve(BenchmarkDataExporter.LINKS_FILE)).exists();
        assertThat(exportDir.resolve(BenchmarkDataExporter.MANIFEST_FILE)).exists();
        assertThat(exportDir.resolve("prompts/map_system_prompt.txt")).exists();
        assertThat(exportDir.resolve("prompts/map_phase_schema.json")).exists();
        assertThat(exportDir.resolve("prompts/ragas_claim_task.txt")).exists();
        assertThat(exportDir.resolve("prompts/ragas_structured_task.txt")).exists();
        assertThat(exportDir.resolve("prompts/ragas_publish_task.txt")).exists();
        assertThat(exportDir.resolve("extracts/rbac-model.map.json")).exists();
        assertThat(exportDir.resolve("extracts/rbac-model.reference.json")).exists();
        assertThat(exportDir.resolve("pages/rbac-model/concept_rbac.md")).exists();
        JsonNode mapSnap = MAPPER.readTree(exportDir.resolve("extracts/rbac-model.map.json").toFile());
        assertThat(mapSnap.get("entities").isArray()).isTrue();

        String claimLine = Files.readString(exportDir.resolve(BenchmarkDataExporter.CLAIMS_FILE)).trim();
        JsonNode claimRow = MAPPER.readTree(claimLine);
        assertThat(claimRow.get("document_id").asText()).isEqualTo("rbac-model");
        assertThat(claimRow.get("response").asText()).contains("RBAC");
        assertThat(claimRow.get("retrieved_contexts").isArray()).isTrue();
        assertThat(claimRow.get("retrieved_contexts").get(0).asText()).contains("Workspace");
        assertThat(claimRow.get("metadata").get("context_mode").asText()).isEqualTo("production_sourceContext");
        assertThat(claimRow.get("metadata").get("production_prompt_version").asText())
                .isEqualTo(MrpProductionPrompts.PROMPT_VERSION);
        assertThat(claimRow.get("reference").isNull()).isTrue();

        String structuredLine = Files.readString(exportDir.resolve(BenchmarkDataExporter.STRUCTURED_FILE)).trim();
        JsonNode structuredRow = MAPPER.readTree(structuredLine);
        JsonNode responseJson = MAPPER.readTree(structuredRow.get("response").asText());
        JsonNode referenceJson = MAPPER.readTree(structuredRow.get("reference").asText());
        assertThat(responseJson.has("entities")).isTrue();
        assertThat(referenceJson.has("entities")).isTrue();
        assertThat(referenceJson.get("entities").isArray()).isTrue();
        assertThat(referenceJson.get("entities").size()).isGreaterThan(0);

        String publishLine = Files.readString(exportDir.resolve(BenchmarkDataExporter.PUBLISH_FILE)).trim();
        JsonNode publishRow = MAPPER.readTree(publishLine);
        assertThat(publishRow.get("document_id").asText()).isEqualTo("rbac-model");
        assertThat(publishRow.get("response").asText()).contains("Workspace");
        assertThat(publishRow.get("retrieved_contexts").get(0).asText()).contains("RBAC");
        assertThat(publishRow.get("reference").asText()).isNotBlank();
        assertThat(publishRow.get("metadata").get("pipeline_stage").asText()).isEqualTo("PUBLISH");
        assertThat(publishRow.get("user_input").asText()).contains("Required topics");

        JsonNode manifest = MAPPER.readTree(exportDir.resolve(BenchmarkDataExporter.MANIFEST_FILE).toFile());
        assertThat(manifest.get("claimSampleCount").asInt()).isEqualTo(1);
        assertThat(manifest.get("structuredSampleCount").asInt()).isEqualTo(1);
        assertThat(manifest.get("publishSampleCount").asInt()).isEqualTo(1);
        assertThat(manifest.get("linkSampleCount").asInt()).isEqualTo(1);
        assertThat(manifest.get("schemaVersion").asText()).isEqualTo(BenchmarkDataExporter.SCHEMA_VERSION);
        assertThat(manifest.get("productionPromptVersion").asText()).isEqualTo(MrpProductionPrompts.PROMPT_VERSION);
        assertThat(manifest.get("tracks").has("publish")).isTrue();
    }

    @Test
    @DisplayName("buildPublishReference joins topics/entities/claims into free-text")
    void buildPublishReference_fromOauth2Gt() throws Exception {
        JsonNode gt = loadResourceJson("benchmark/evaluation/ground-truth/oauth2-flow.json");
        String ref = BenchmarkDataExporter.buildPublishReference(gt);
        assertThat(ref).contains("Topics:");
        assertThat(ref).contains("OAuth2");
        assertThat(ref).contains("Claims:");
    }

    @Test
    @DisplayName("stripSourceContextSuffix removes production suffix from claim text")
    void stripSourceContextSuffix() {
        String raw = "JWT expires in 15 minutes. [Source Context: tokens expire after 15 minutes]";
        assertThat(BenchmarkDataExporter.stripSourceContextSuffix(raw))
                .isEqualTo("JWT expires in 15 minutes.");
    }

    private static JsonNode loadResourceJson(String path) throws Exception {
        var stream = BenchmarkDataExporterTest.class.getClassLoader().getResourceAsStream(path);
        assertThat(stream).as("resource %s", path).isNotNull();
        try (stream) {
            return MAPPER.readTree(stream);
        }
    }
}
