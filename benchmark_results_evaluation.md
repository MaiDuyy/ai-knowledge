# SecWiki-Bench v2 — Evaluation Benchmark Report

**Tier:** Periodic Evaluation (Real API + Golden Dataset)

**Generated:** 2026-07-12T12:28:18.896648600Z

**Judge Model:** gemini-self

**Methodology:** [Java Ragas-aligned heuristics, Official Ragas export (Python optional), GERBIL-style linking, Cross-Model Judge]

---

## Corpus-Level Metrics

| Metric | Value | Threshold | Status |
|--------|-------|-----------|--------|
| `corpusFaithfulness` | 0.9587 | 0.8 | PASS |
| `corpusGroundedness` | 0.9939 | 0.8 | PASS |
| `corpusExtractionF1` | 0.6976 | 0.55 | PASS |
| `corpusLinkF1` | 0.9778 | 0.7 | PASS |
| `corpusAnswerRelevancy` | 0.9611 | 0.6 | PASS |
| `crossModelJudgeAvg` | 4.8667 | 3.5 | PASS |
| `crossModelLinkJudgeAvg` | 4.9111 | 3.5 | PASS |

## Run Metadata

| Field | Value |
|-------|-------|
| `documentCount` | 40.0000 |
| `totalMrpWikiPagesCreated` | 103.0000 |
| `judgeModel` | gemini-self |
| `manifestVersion` | 2.0 |
| `totalEstimatedTokens` | 4054.0000 |
| `maxDocsLimit` | 15.0000 |
| `exportLinkSamples` | 15.0000 |
| `exportDir` | benchmark/evaluation/2026-07-12_19-18/export |
| `documentsEvaluated` | 15.0000 |
| `linkScenariosEvaluated` | 15.0000 |
| `exportClaimSamples` | 30.0000 |
| `linkEvalMode` | mrp-hybrid |
| `exportStructuredSamples` | 15.0000 |
| `avgPipelineLatencyMs` | 28190.4000 |
| `judgeSampleCount` | 30.0000 |
| `linkPagesResolved` | 15.0000 |
| `exportPublishSamples` | 45.0000 |

## Per-Document Scores

| Document | Faithfulness | Groundedness | Extraction F1 | Answer Rel. | Latency ms | Tokens | Link F1 | Judge Avg |
|----------|-------------|-------------|--------------|------------|-----------|--------|---------|----------|
| rate-limiting | 1.0000 | 1.0000 | 0.8571 | 1.0000 | 7819.0000 | 225.0000 | 1.0000 | 5.0000 |
| conflicting-doc | 1.0000 | 1.0000 | 0.8000 | 1.0000 | 6996.0000 | 154.0000 | 1.0000 | 3.0000 |
| postgresql-schema | 1.0000 | 1.0000 | 0.8333 | 1.0000 | 51262.0000 | 284.0000 | 1.0000 | 5.0000 |
| grpc-service | 1.0000 | 1.0000 | 0.6154 | 1.0000 | 14330.0000 | 277.0000 | 1.0000 | 5.0000 |
| socket-io | 1.0000 | 1.0000 | 0.7273 | 1.0000 | 43408.0000 | 239.0000 | 1.0000 | 5.0000 |
| redis-caching | 1.0000 | 1.0000 | 0.2500 | 1.0000 | 61944.0000 | 271.0000 | 1.0000 | 5.0000 |
| nats-messaging | 1.0000 | 1.0000 | 0.8000 | 1.0000 | 11099.0000 | 315.0000 | 1.0000 | 5.0000 |
| spring-csrf | 1.0000 | 1.0000 | 0.7742 | 1.0000 | 10691.0000 | 257.0000 | 1.0000 | 5.0000 |
| vector-embedding | 1.0000 | 1.0000 | 0.5455 | 1.0000 | 11357.0000 | 240.0000 | 1.0000 | 5.0000 |
| distinct-topics | 0.7143 | 1.0000 | 0.5000 | 0.7500 | 50885.0000 | 270.0000 | 1.0000 | 5.0000 |
| short-note | 1.0000 | 0.9091 | 0.8000 | 1.0000 | 12841.0000 | 433.0000 | 1.0000 | 5.0000 |
| oauth2-flow | 1.0000 | 1.0000 | 0.9091 | 1.0000 | 48918.0000 | 289.0000 | 0.6667 | 5.0000 |
| file-upload-s3 | 1.0000 | 1.0000 | 0.9091 | 1.0000 | 62986.0000 | 259.0000 | 1.0000 | 5.0000 |
| rbac-model | 0.6667 | 1.0000 | 0.4286 | 0.6667 | 13057.0000 | 284.0000 | 1.0000 | 5.0000 |
| api-gateway | 1.0000 | 1.0000 | 0.7143 | 1.0000 | 15263.0000 | 257.0000 | 1.0000 | 5.0000 |

---

*Artifacts: `benchmark/evaluation/2026-07-12_19-18/evaluation-benchmark-report.json`, `benchmark/evaluation/2026-07-12_19-18/evaluation-benchmark-report.md`, `benchmark/evaluation/latest/manifest.json`*
