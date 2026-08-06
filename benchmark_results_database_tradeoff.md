# Database Trade-Off Benchmark Report

**Tier:** PostgreSQL vs MongoDB experimental storage

**Generated:** 2026-07-24T07:49:08.124277500Z

**Methodology:** [PostgreSQL JPA + embeddings cosine (baseline), MongoDB nested/flat document model, RBAC metadata pre-filter + post-check, JGraphT in-memory BFS vs MongoDB $graphLookup]

---

## Suite: `mongodb`

| Metric | Value |
|--------|-------|
| `writeTotalMs` | 373.0000 |
| `graphTraversalMs` | 65.0000 |
| `graphStrategy` | MongoDB-$graphLookup |
| `searchP99Ms` | 674.0000 |
| `searchAvgMs` | 519.3500 |
| `searchP95Ms` | 611.0000 |
| `graphReachableCount` | 6.0000 |
| `docsWritten` | 50.0000 |
| `writeDocsPerSec` | 134.0483 |

## Suite: `postgres`

| Metric | Value |
|--------|-------|
| `writeTotalMs` | 728.0000 |
| `graphTraversalMs` | 20.0000 |
| `graphStrategy` | JGraphT-in-memory-BFS |
| `searchP99Ms` | 995.0000 |
| `searchAvgMs` | 755.4000 |
| `searchP95Ms` | 976.0000 |
| `graphReachableCount` | 6.0000 |
| `docsWritten` | 50.0000 |
| `writeDocsPerSec` | 68.6813 |

---

*Artifacts: `benchmark/database-tradeoff/2026-07-24_14-49/database-tradeoff-report.json`, `benchmark/database-tradeoff/2026-07-24_14-49/database-tradeoff-report.md`, `benchmark/database-tradeoff/latest/manifest.json`*
