# SecWiki-Bench v2 — Evaluation Benchmark Report

**Tier:** Periodic Evaluation (Real API + Golden Dataset)

**Generated:** 2026-07-24T09:17:59.522369Z

**Judge Model:** gemini-self

**Methodology:** [Java Ragas-aligned heuristics, Official Ragas export (Python optional), GERBIL-style linking, Cross-Model Judge, MongoDB storage/retrieval track]

---

## Corpus-Level Metrics

| Metric | Value | Threshold | Status |
|--------|-------|-----------|--------|
| `corpusFaithfulness` | 0.9339 | 0.8 | PASS |
| `corpusGroundedness` | 0.9909 | 0.8 | PASS |
| `corpusExtractionF1` | 0.6674 | 0.55 | PASS |
| `corpusLinkF1` | 0.9667 | 0.7 | PASS |
| `corpusAnswerRelevancy` | 0.9417 | 0.6 | PASS |
| `crossModelJudgeAvg` | 4.8000 | 3.5 | PASS |
| `crossModelLinkJudgeAvg` | 4.8667 | 3.5 | PASS |

## Run Metadata

| Field | Value |
|-------|-------|
| `documentCount` | 40.0000 |
| `avgMongoGraphReachable` | 1.5000 |
| `totalMrpWikiPagesCreated` | 67.0000 |
| `judgeModel` | gemini-self |
| `manifestVersion` | 2.0 |
| `totalEstimatedTokens` | 2863.0000 |
| `storageEngine` | mongodb |
| `mongoUri` | mongodb://localhost:53458 |
| `maxDocsLimit` | 10.0000 |
| `tier` | evaluation-mongodb |
| `exportLinkSamples` | 10.0000 |
| `exportDir` | benchmark/evaluation-mongodb/2026-07-24_15-46-mongo/export |
| `documentsEvaluated` | 10.0000 |
| `linkScenariosEvaluated` | 10.0000 |
| `exportClaimSamples` | 20.0000 |
| `linkEvalMode` | mrp-hybrid |
| `exportStructuredSamples` | 10.0000 |
| `avgMongoWriteMs` | 117.2000 |
| `avgPipelineLatencyMs` | 142697.9000 |
| `avgMongoSearchMs` | 17.1000 |
| `linkPagesResolved` | 10.0000 |
| `exportPublishSamples` | 30.0000 |

## Per-Document Scores

| Document | Faithfulness | Groundedness | Extraction F1 | Answer Rel. | Latency ms | Tokens | Link F1 | Judge Avg |
|----------|-------------|-------------|--------------|------------|-----------|--------|---------|----------|
| short-note | 1.0000 | 0.9091 | 0.8000 | 1.0000 | 40290.0000 | 437.0000 | 1.0000 | 5.0000 |
| oauth2-flow | 1.0000 | 1.0000 | 0.9091 | 1.0000 | 208583.0000 | 290.0000 | 0.6667 | 5.0000 |
| rbac-model | 0.6250 | 1.0000 | 0.4286 | 0.6667 | 108440.0000 | 284.0000 | 1.0000 | 5.0000 |
| rate-limiting | 1.0000 | 1.0000 | 0.8571 | 1.0000 | 42448.0000 | 246.0000 | 1.0000 | 5.0000 |
| conflicting-doc | 1.0000 | 1.0000 | 0.8000 | 1.0000 | 92625.0000 | 154.0000 | 1.0000 | 3.0000 |
| grpc-service | 1.0000 | 1.0000 | 0.6154 | 1.0000 | 119840.0000 | 277.0000 | 1.0000 | 5.0000 |
| redis-caching | 1.0000 | 1.0000 | 0.2500 | 1.0000 | 74923.0000 | 312.0000 | 1.0000 | 5.0000 |
| nats-messaging | 1.0000 | 1.0000 | 0.8000 | 1.0000 | 386319.0000 | 320.0000 | 1.0000 | 5.0000 |
| api-gateway | 1.0000 | 1.0000 | 0.7143 | 1.0000 | 252280.0000 | 273.0000 | 1.0000 | 5.0000 |
| distinct-topics | 0.7143 | 1.0000 | 0.5000 | 0.7500 | 101231.0000 | 270.0000 | 1.0000 | 5.0000 |

---

*Artifacts: `benchmark/evaluation-mongodb/2026-07-24_15-46-mongo/evaluation-benchmark-report.json`, `benchmark/evaluation-mongodb/2026-07-24_15-46-mongo/evaluation-benchmark-report.md`, `benchmark/evaluation/latest/manifest.json`*
