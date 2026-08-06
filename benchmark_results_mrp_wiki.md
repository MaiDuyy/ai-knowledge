# SecWiki-Bench v2 — MRP & WikiLink Benchmark Report

**Generated:** 2026-07-14T10:59:02.735805800Z

**Methodology:** [Ragas, TruLens, GERBIL, LLMStructBench, AgentBench]

---

## 1. MRP Pipeline Accuracy (Ragas / TruLens / LLMStructBench)

| Metric | Value |
|--------|-------|
| `faithfulness` | 1.0000 |

## 2. WikiLink Entity Linking (GERBIL)

_No data recorded._

## 3. Agent Robustness (AgentBench)

_No data recorded._

---

## Summary Scorecard

| Suite | Key Metric | Value | Threshold | Status |
|-------|-----------|-------|-----------|--------|
| MRP | faithfulness | 1.000 | 0.9 | PASS |
| MRP | groundedness | N/A | 0.9 | SKIP |
| MRP | answerRelevancy | N/A | 0.6 | SKIP |
| MRP | extractionF1 | N/A | 0.7 | SKIP |
| WikiLink | linkF1 | N/A | 0.8 | SKIP |
| WikiLink | nilCount | N/A | 0.0 | SKIP |
| Robustness | retrySuccessRate | N/A | 1.0 | SKIP |
| Robustness | stateMachineCompliance | N/A | 1.0 | SKIP |

## Methodology Reference

| Framework | Metrics | Purpose |
|-----------|---------|----------|
| Ragas | faithfulness (atomic claims), answerRelevancy, extractionF1 | Extraction & hallucination |
| TruLens | groundedness, llmJudgeScoreAvg | Claim verification |
| GERBIL | linkPrecision, linkRecall, linkF1, nilCount | Entity linking |
| LLMStructBench | mapSchemaCompliance, reduceSchemaCompliance | JSON schema |
| AgentBench | retrySuccessRate, stateMachineCompliance | E2E resilience |

---

*Artifacts: `C:\Users\tizga\AppData\Local\Temp\junit10966237428702470027/mrp-wiki/2026-07-14_17-59/mrp-wiki-benchmark-report.json`, `C:\Users\tizga\AppData\Local\Temp\junit10966237428702470027/mrp-wiki/2026-07-14_17-59/mrp-wiki-benchmark-report.md`, `benchmark/mrp-wiki/latest/manifest.json`*
