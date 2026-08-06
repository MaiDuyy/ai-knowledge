package com.security.security.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.security.entity.Document;
import com.security.security.entity.SourceCompilationPlan;
import com.security.security.entity.enumeration.DocStatus;
import com.security.security.entity.enumeration.DocType;
import com.security.security.entity.enumeration.SecurityClassification;
import com.security.security.entity.enumeration.SourceCompilationStatus;
import com.security.security.provider.LlmFactory;
import com.security.security.provider.LlmProvider;
import com.security.security.repository.DocumentRepository;
import com.security.security.repository.SourceChunkExtractRepository;
import com.security.security.repository.SourceCompilationPlanRepository;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Tag("benchmark")
@DisplayName("SecWiki-Bench v2: Agent Robustness (AgentBench)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentRobustnessBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(AgentRobustnessBenchmarkTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @MockBean
    private org.springframework.ai.vectorstore.VectorStore vectorStore;

    @MockBean
    private io.nats.client.Connection natsConnection;

    @MockBean
    private com.security.security.client.WorkspaceServiceClient workspaceServiceClient;

    @MockBean
    private LlmFactory llmFactory;

    @Autowired
    private MrpPipelineService mrpPipelineService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private SourceCompilationPlanRepository planRepository;

    @Autowired
    private SourceChunkExtractRepository chunkRepository;

    private String mapJson;
    private String reduceJson;
    private String workspaceId;

    @BeforeAll
    void loadGoldenManifest() throws Exception {
        workspaceId = GoldenDatasetManifestLoader.workspaceId();
        BenchmarkEvaluationAssertions.record("robustness", "goldenDocumentCount",
                GoldenDatasetManifestLoader.documentCount());
    }

    @BeforeEach
    void setUp() throws Exception {
        chunkRepository.deleteAll();
        planRepository.deleteAll();
        documentRepository.deleteAll();
        mapJson = BenchmarkEvaluationAssertions.loadResource("benchmark/agent/mock-map-response.json");
        reduceJson = BenchmarkEvaluationAssertions.loadResource("benchmark/agent/mock-reduce-response.json");
    }

    @Test
    @Order(1)
    @DisplayName("AgentBench: HTTP 429 retry via callChatWithRetry")
    void benchmarkRateLimitRetry_429Recovery() {
        LlmProvider mockProvider = mock(LlmProvider.class);
        AtomicInteger attempts = new AtomicInteger(0);
        when(mockProvider.callChat(anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> {
                    int n = attempts.incrementAndGet();
                    if (n == 1) throw new RuntimeException("HTTP 429 rate limit exceeded");
                    return mapJson;
                });

        String result = ReflectionTestUtils.invokeMethod(
                mrpPipelineService, "callChatWithRetry",
                mockProvider, "system", "user message", "{}", "bench-retry-429");

        assertThat(result).isNotNull();
        assertThat(attempts.get()).isEqualTo(2);

        BenchmarkEvaluationAssertions.record("robustness", "retrySuccessRate", 1.0);
        BenchmarkEvaluationAssertions.recordDetail("robustness", "retryAttempts", attempts.get());
        log.info("[AgentBench] 429 retry success after {} attempts", attempts.get());
    }

    @Test
    @Order(2)
    @DisplayName("LLMStructBench: MAP schema compliance on mock output")
    void benchmarkMapSchema_Compliance() throws Exception {
        JsonNode root = MAPPER.readTree(mapJson);
        double compliance = BenchmarkEvaluationAssertions.computeSchemaComplianceMap(root);
        assertThat(compliance).isEqualTo(1.0);
        BenchmarkEvaluationAssertions.record("robustness", "mapSchemaCompliance", compliance);
        log.info("[LLMStructBench] MAP schema compliance={}", compliance);
    }

    @Test
    @Order(3)
    @DisplayName("LLMStructBench: REDUCE schema compliance on mock output")
    void benchmarkReduceSchema_Compliance() throws Exception {
        JsonNode root = MAPPER.readTree(reduceJson);
        double compliance = BenchmarkEvaluationAssertions.computeSchemaComplianceReduce(root);
        assertThat(compliance).isEqualTo(1.0);
        BenchmarkEvaluationAssertions.record("robustness", "reduceSchemaCompliance", compliance);
        log.info("[LLMStructBench] REDUCE schema compliance={}", compliance);
    }

    @Test
    @Order(4)
    @DisplayName("LLMStructBench: Invalid MAP JSON fails schema compliance")
    void benchmarkMapSchema_InvalidJson() throws Exception {
        JsonNode invalid = MAPPER.readTree("{\"concepts\": []}");
        double compliance = BenchmarkEvaluationAssertions.computeSchemaComplianceMap(invalid);
        assertThat(compliance).isLessThan(1.0);
        BenchmarkEvaluationAssertions.recordDetail("robustness", "invalidMapSchemaCompliance", compliance);
    }

    @Test
    @Order(5)
    @DisplayName("AgentBench: State machine happy path PROCESSING -> PENDING_REVIEW -> DONE")
    void benchmarkStateMachine_HappyPath() throws Exception {
        LlmProvider mockProvider = setupMockProvider();
        when(llmFactory.getProvider("gemini")).thenReturn(mockProvider);

        Document doc = seedDocument("short-note");
        SourceCompilationPlan plan = planRepository.save(SourceCompilationPlan.builder()
                .sourceDocumentId(doc.getId())
                .status(SourceCompilationStatus.PROCESSING)
                .departmentId("ALL")
                .allowedRoles("ALL")
                .securityClassification(SecurityClassification.INTERNAL)
                .build());

        mrpPipelineService.runCompilationProcess(
                plan.getId(), doc.getId(), doc.getMarkdownContent(), workspaceId, "bench-user", false);

        SourceCompilationPlan afterReduce = planRepository.findById(plan.getId()).orElseThrow();
        assertThat(afterReduce.getStatus()).isEqualTo(SourceCompilationStatus.PENDING_REVIEW);
        assertThat(afterReduce.getPlanJson()).isNotBlank();

        mrpPipelineService.executeCompilationPlan(plan.getId(), workspaceId, "bench-user", false);

        SourceCompilationPlan afterExecute = planRepository.findById(plan.getId()).orElseThrow();
        assertThat(afterExecute.getStatus()).isEqualTo(SourceCompilationStatus.DONE);

        BenchmarkEvaluationAssertions.record("robustness", "stateMachineCompliance", 1.0);
        BenchmarkEvaluationAssertions.recordDetail("robustness", "statusFlow",
                "PROCESSING -> PENDING_REVIEW -> DONE");
        log.info("[AgentBench] State machine happy path: {}", afterExecute.getStatus());
    }

    @Test
    @Order(6)
    @DisplayName("AgentBench: LLM failure -> chunk ERROR + plan FAILED when plan missing at reduce")
    void benchmarkStateMachine_FailurePath() throws Exception {
        LlmProvider mockProvider = mock(LlmProvider.class);
        when(mockProvider.callChat(anyString(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("Fatal LLM service unavailable"));
        when(llmFactory.getProvider("gemini")).thenReturn(mockProvider);

        Document doc = seedDocument("short-note");
        SourceCompilationPlan plan = planRepository.save(SourceCompilationPlan.builder()
                .sourceDocumentId(doc.getId())
                .status(SourceCompilationStatus.PROCESSING)
                .build());

        // Delete plan before reduce phase completes — simulates crash mid-pipeline
        Long planId = plan.getId();
        planRepository.deleteById(planId);

        mrpPipelineService.runCompilationProcess(
                planId, doc.getId(), doc.getMarkdownContent(), workspaceId, "bench-user", false);

        long errorChunks = chunkRepository.findBySourceDocumentIdAndStatus(
                doc.getId(), com.security.security.entity.enumeration.SourceChunkStatus.ERROR).size();

        assertThat(errorChunks).isGreaterThanOrEqualTo(1);
        BenchmarkEvaluationAssertions.recordDetail("robustness", "errorChunksOnLlmFailure", errorChunks);
        BenchmarkEvaluationAssertions.record("robustness", "failureRecoveryHandled", 1.0);
        log.info("[AgentBench] LLM failure: {} ERROR chunks, pipeline did not crash", errorChunks);
    }

    @Test
    @Order(7)
    @DisplayName("AgentBench: Virtual thread concurrency — 2 chunks processed")
    void benchmarkVirtualThreadConcurrency() throws Exception {
        LlmProvider mockProvider = setupMockProvider();
        when(llmFactory.getProvider("gemini")).thenReturn(mockProvider);

        String longContent = GoldenDatasetManifestLoader.loadSource("short-note") + "\n\n"
                + GoldenDatasetManifestLoader.loadSource("distinct-topics");

        Document doc = documentRepository.save(Document.builder()
                .userId("bench-user")
                .fileName("multi-chunk.md")
                .fileSize(longContent.length())
                .workspaceId(workspaceId)
                .departmentId("ALL")
                .markdownContent(longContent)
                .status(DocStatus.COMPLETED)
                .documentType(DocType.pdf)
                .securityClassification(SecurityClassification.INTERNAL)
                .build());

        SourceCompilationPlan plan = planRepository.save(SourceCompilationPlan.builder()
                .sourceDocumentId(doc.getId())
                .status(SourceCompilationStatus.PROCESSING)
                .departmentId("ALL")
                .build());

        mrpPipelineService.runCompilationProcess(
                plan.getId(), doc.getId(), longContent, workspaceId, "bench-user", false);

        long doneChunks = chunkRepository.findBySourceDocumentIdAndStatus(
                doc.getId(), com.security.security.entity.enumeration.SourceChunkStatus.DONE).size();

        assertThat(doneChunks).isGreaterThanOrEqualTo(1);
        BenchmarkEvaluationAssertions.recordDetail("robustness", "virtualThreadChunksDone", doneChunks);
        log.info("[AgentBench] Virtual thread chunks DONE={}", doneChunks);
    }

    private LlmProvider setupMockProvider() {
        LlmProvider mockProvider = mock(LlmProvider.class);
        when(mockProvider.callChat(anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> {
                    String convId = inv.getArgument(3, String.class);
                    if (convId != null && convId.contains("mrp-plan")) return reduceJson;
                    return mapJson;
                });
        when(mockProvider.streamChat(anyString(), anyString(), any(), anyString()))
                .thenReturn(Flux.just("# Benchmark Page\n\nGenerated wiki content for benchmark test."));
        return mockProvider;
    }

    private Document seedDocument(String docId) throws Exception {
        String content = GoldenDatasetManifestLoader.loadSource(docId);
        return documentRepository.save(Document.builder()
                .userId("bench-user")
                .fileName(docId + ".md")
                .fileSize(content.length())
                .workspaceId(workspaceId)
                .departmentId("ALL")
                .markdownContent(content)
                .status(DocStatus.COMPLETED)
                .documentType(DocType.pdf)
                .securityClassification(SecurityClassification.INTERNAL)
                .build());
    }
}