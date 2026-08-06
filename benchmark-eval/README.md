# SecWiki-Bench Hybrid Evaluation (Ragas + Golden)

Java runs the MRP pipeline on the **existing** Golden Dataset and exports Ragas-ready JSONL.  
Python (isolated `.venv-bench`) runs **official Ragas** + offline hybrid metrics.

```powershell
cd source/ai-knowledge

# Sample (tiết kiệm quota) — includes post-Publish track when export has pages
.\scripts\run-ragas-eval.ps1 -LoadTestProperties -Limit 12

# Full corpus
.\scripts\run-ragas-eval.ps1 -LoadTestProperties

# Đổi model nếu hết quota
$env:RAGAS_LLM_MODEL = "gemini-2.5-flash-lite"
.\scripts\run-ragas-eval.ps1 -LoadTestProperties -Limit 20
```

## Golden Dataset (do not recreate)

```
src/test/resources/benchmark/evaluation/
├── golden-dataset-manifest.json
├── docs/*.md
└── ground-truth/*.json
```

## Layout

```
benchmark-eval/
├── requirements.txt
├── evaluate_with_ragas.py       # claims + structured + **post-Publish**
├── publish_wiki_metrics.py      # offline publish: topic / hallu / link F1
├── structured_extraction_metrics.py
├── build_publish_export.py      # rebuild publish JSONL from pages or synthetic MAP
├── merge_hybrid_report.py       # Java vs Ragas scorecard
├── evaluate_with_trulens.py     # phase 2 stub
└── README.md
```

## Setup (once)

```powershell
cd source/ai-knowledge
.\scripts\setup-bench-venv.ps1
```

Requires Python 3.10+ on PATH.

## Flow

### 1. Java evaluation + export

```powershell
.\mvnw.cmd test -Pevaluation-benchmark "-Dbenchmark.eval.maxDocs=15"
# Or:
.\scripts\run-evaluation-benchmark.ps1 -MaxDocs 15
.\scripts\run-evaluation-benchmark.ps1 -MaxDocs 0    # full golden
```

Produces:

```
benchmark/evaluation/{runId}/export/
├── ragas_claims.jsonl          # MAP claims → faithfulness
├── ragas_structured.jsonl      # MAP JSON → extraction F1
├── ragas_publish.jsonl         # **Post-Publish WikiPage markdown**
├── link_samples.jsonl          # GERBIL link F1
├── pages/{docId}/{slug}.md     # published page snapshots
├── extracts/
└── export-manifest.json
```

### 2. Official Ragas (default Gemini)

```powershell
$env:API_KEY = "..."   # or -LoadTestProperties
.\scripts\run-ragas-eval.ps1 -LoadTestProperties
```

Metrics run:

| Track | File | Metrics |
|-------|------|---------|
| MAP claims | `ragas_claims.jsonl` | `faithfulness` |
| MAP structured | `ragas_structured.jsonl` | `structuredExtractionF1` + AC diagnostic |
| **Post-Publish** | `ragas_publish.jsonl` | `publish_faithfulness`, `answer_relevancy`, `context_recall` |
| Offline publish | same | `topicCoverage`, `forbiddenHallucinationRate`, `publishLinkF1` |
| Links | `link_samples.jsonl` | Java GERBIL (not Ragas) |

### 3. If `ragas_publish.jsonl` is missing

Prefer re-running Java eval (real approved WikiPages). For smoke tests only:

```powershell
.\.venv-bench\Scripts\python.exe benchmark-eval\build_publish_export.py --synthetic-from-map --force
.\scripts\run-ragas-eval.ps1 -LoadTestProperties -Limit 5
```

Synthetic mode renders markdown from MAP JSON and **must not** be used as a release gate.

## Hybrid release gates

| Gate | Source | Default threshold |
|------|--------|-------------------|
| MAP claim faithfulness | Java + Ragas | ≥ 0.80 (Ragas hard if n≥20) |
| **Publish faithfulness** | Ragas on WikiPage MD | ≥ 0.80 (hard if n≥5) |
| Extraction F1 | Java + MAP offline | ≥ 0.55 |
| Wiki Link F1 | Java GERBIL | ≥ 0.70 |
| Forbidden hallu rate | Offline on publish MD | = 0 |
| answer_correctness | Ragas | **diagnostic only** |
| answer_relevancy / context_recall | Ragas publish | diagnostic (optional flags) |

## Metric philosophy (MRP-aware)

```
MAP (Reduce JSON)     → Entity/Concept F1, claim faithfulness
PUBLISH (Markdown)    → publish_faithfulness, answer_relevancy, context_recall
                        + offline topic coverage + anti-hallucination
LINKS                 → GERBIL-style F1 (Java + offline on [[wikilinks]])
```

Ragas classic Q&A metrics are **remapped**:
- `response` = published Markdown WikiPage (not raw Reduce JSON)
- `retrieved_contexts` = source document used by MRP
- `reference` = golden expectedTopics + expectedClaims free-text
- `user_input` = topic-conditioned compile task

## Env vars

| Variable | Used by |
|----------|---------|
| `API_KEY` / `GEMINI_API_KEY` / `GOOGLE_API_KEY` | Java + Ragas (Gemini) |
| `RAGAS_PROVIDER` | `gemini` (default) or `openai` |
| `RAGAS_LLM_MODEL` | default `gemini-3.1-flash-lite` |
| `RAGAS_EMBEDDING_MODEL` | default `models/gemini-embedding-001` |
| `OPENAI_API_KEY` | only if `RAGAS_PROVIDER=openai` |

## Phase 2 — TruLens

```powershell
.\.venv-bench\Scripts\pip install trulens-eval
.\.venv-bench\Scripts\python.exe benchmark-eval\evaluate_with_trulens.py
```

Currently a stub; slot reserved for groundedness on claim/publish samples.
