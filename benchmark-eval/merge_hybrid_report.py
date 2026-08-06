#!/usr/bin/env python3
"""
Merge Java evaluation report + official Ragas metrics + MAP extraction F1
+ post-Publish wiki metrics into a hybrid scorecard.

Release gates (default):
  - Java faithfulness / extraction F1 / link F1
  - MAP structuredExtractionF1
  - Ragas claim faithfulness (hard only if n>=20)
  - Ragas publish_faithfulness (hard only if n>=5)
  - Offline forbiddenHallucinationRate == 0 when publish samples exist

Diagnostic only:
  - answer_correctness (MAP JSON / Q&A mismatch)
  - answer_relevancy, context_recall (reported; soft unless thresholds set)
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from structured_extraction_metrics import evaluate_structured_export
from publish_wiki_metrics import evaluate_publish_export


def load_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def status(val: float | None, threshold: float | None, *, higher_is_better: bool = True) -> str:
    if val is None:
        return "N/A"
    if threshold is None:
        return "—"
    if higher_is_better:
        return "PASS" if val >= threshold else "FAIL"
    return "PASS" if val <= threshold else "FAIL"


def fmt(val: Any) -> str:
    if val is None:
        return "—"
    if isinstance(val, float):
        return f"{val:.4f}"
    return str(val)


def as_float(val: Any) -> float | None:
    if isinstance(val, (int, float)):
        return float(val)
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description="Merge Java + Ragas hybrid report")
    parser.add_argument(
        "--java-report",
        type=Path,
        default=Path("benchmark/evaluation/latest/evaluation-benchmark-report.json"),
    )
    parser.add_argument(
        "--ragas-metrics",
        type=Path,
        default=Path("benchmark/evaluation/latest/ragas/ragas-metrics.json"),
    )
    parser.add_argument(
        "--export-dir",
        type=Path,
        default=Path("benchmark/evaluation/latest/export"),
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path("benchmark/evaluation/latest"),
    )
    parser.add_argument("--faithfulness-threshold", type=float, default=0.80)
    parser.add_argument("--extraction-f1-threshold", type=float, default=0.55)
    parser.add_argument("--link-f1-threshold", type=float, default=0.70)
    parser.add_argument("--publish-faithfulness-threshold", type=float, default=0.80)
    parser.add_argument("--topic-coverage-threshold", type=float, default=0.50)
    parser.add_argument(
        "--max-forbidden-hallu-rate",
        type=float,
        default=0.0,
        help="Max allowed forbiddenHallucinationRate on publish pages (default 0)",
    )
    parser.add_argument(
        "--answer-correctness-threshold",
        type=float,
        default=None,
        help="Optional diagnostic threshold for Ragas AC (default: no pass/fail gate)",
    )
    parser.add_argument(
        "--answer-relevancy-threshold",
        type=float,
        default=None,
        help="Optional gate for publish answer_relevancy (default: diagnostic)",
    )
    parser.add_argument(
        "--context-recall-threshold",
        type=float,
        default=None,
        help="Optional gate for publish context_recall (default: diagnostic)",
    )
    args = parser.parse_args()

    java = load_json(args.java_report)
    ragas = load_json(args.ragas_metrics)
    links = load_jsonl(args.export_dir / "link_samples.jsonl")
    map_metrics = evaluate_structured_export(args.export_dir)
    publish_offline = evaluate_publish_export(args.export_dir)

    # persist offline artifacts
    ragas_dir = args.out_dir / "ragas"
    ragas_dir.mkdir(parents=True, exist_ok=True)
    map_out = ragas_dir / "map-extraction-metrics.json"
    map_out.write_text(json.dumps(map_metrics, indent=2, ensure_ascii=False), encoding="utf-8")
    pub_out = ragas_dir / "publish-wiki-metrics.json"
    pub_out.write_text(json.dumps(publish_offline, indent=2, ensure_ascii=False), encoding="utf-8")

    metrics = java.get("metrics") or {}
    java_faith = as_float(metrics.get("corpusFaithfulness"))
    java_ground = as_float(metrics.get("corpusGroundedness"))
    java_f1 = as_float(metrics.get("corpusExtractionF1"))
    java_link = as_float(metrics.get("corpusLinkF1"))
    java_judge = as_float(metrics.get("crossModelJudgeAvg"))
    java_answer_rel = as_float(metrics.get("corpusAnswerRelevancy"))
    docs_eval = metrics.get("documentsEvaluated")
    export_claims = metrics.get("exportClaimSamples")
    export_struct = metrics.get("exportStructuredSamples")
    export_publish = metrics.get("exportPublishSamples")

    ragas_corpus = ragas.get("corpus") or {}
    ragas_samples = ragas.get("sample_counts") or {}
    ragas_faith = as_float(ragas_corpus.get("faithfulness"))
    ragas_ac = as_float(ragas_corpus.get("answer_correctness"))
    publish_faith = as_float(ragas_corpus.get("publish_faithfulness"))
    answer_relevancy = as_float(ragas_corpus.get("answer_relevancy"))
    context_recall = as_float(ragas_corpus.get("context_recall"))

    map_corpus = map_metrics.get("corpus") or {}
    map_f1 = as_float(map_corpus.get("structuredExtractionF1"))
    map_n = (map_metrics.get("sample_counts") or {}).get("structuredExtractionF1", 0)

    pub_corpus = publish_offline.get("corpus") or {}
    topic_cov = as_float(pub_corpus.get("topicCoverage"))
    hallu_rate = as_float(pub_corpus.get("forbiddenHallucinationRate"))
    publish_link = as_float(pub_corpus.get("publishLinkF1"))
    # prefer ragas offline keys if present in ragas corpus
    if topic_cov is None:
        topic_cov = as_float(ragas_corpus.get("topicCoverage"))
    if hallu_rate is None:
        hallu_rate = as_float(ragas_corpus.get("forbiddenHallucinationRate"))
    if publish_link is None:
        publish_link = as_float(ragas_corpus.get("publishLinkF1"))

    link_f1s = [r.get("link_f1") for r in links if isinstance(r.get("link_f1"), (int, float))]
    export_link_avg = sum(link_f1s) / len(link_f1s) if link_f1s else None

    # Gates
    gate_faith_j = status(java_faith, args.faithfulness_threshold)
    gate_faith_r = status(ragas_faith, args.faithfulness_threshold)
    gate_f1_j = status(java_f1, args.extraction_f1_threshold)
    gate_f1_map = status(map_f1, args.extraction_f1_threshold)
    gate_link = status(
        java_link if java_link is not None else export_link_avg, args.link_f1_threshold
    )
    gate_pub_faith = status(publish_faith, args.publish_faithfulness_threshold)
    gate_topic = status(topic_cov, args.topic_coverage_threshold)
    gate_hallu = status(hallu_rate, args.max_forbidden_hallu_rate, higher_is_better=False)
    gate_ar = status(answer_relevancy, args.answer_relevancy_threshold)
    gate_cr = status(context_recall, args.context_recall_threshold)

    ragas_faith_n = int(ragas_samples.get("faithfulness") or 0)
    publish_faith_n = int(ragas_samples.get("publish_faithfulness") or 0)
    ragas_faith_hard_gate = ragas_faith_n >= 20
    publish_faith_hard_gate = publish_faith_n >= 5
    publish_pages = int((publish_offline.get("sample_counts") or {}).get("publish") or 0)

    overall_checks = [gate_faith_j, gate_f1_j, gate_f1_map, gate_link]
    if ragas_faith_hard_gate:
        overall_checks.append(gate_faith_r)
    if publish_faith_hard_gate:
        overall_checks.append(gate_pub_faith)
    if publish_pages > 0 and hallu_rate is not None:
        overall_checks.append(gate_hallu)
    if args.answer_relevancy_threshold is not None:
        overall_checks.append(gate_ar)
    if args.context_recall_threshold is not None:
        overall_checks.append(gate_cr)

    overall_fail = any(s == "FAIL" for s in overall_checks if s != "N/A")

    hybrid = {
        "reportType": "hybrid-evaluation",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "overall": "FAIL" if overall_fail else "PASS",
        "methodology": [
            "java_proxy (Ragas-aligned heuristics)",
            "ragas_official faithfulness on MAP claims",
            "ragas_official post-Publish (faithfulness, answer_relevancy, context_recall)",
            "map_extraction_f1 (entities+concepts on export JSON)",
            "publish_offline (topic coverage, forbidden hallu, link F1)",
            "ragas_answer_correctness (diagnostic only — Q&A metric, not MAP gate)",
            "java_gerbil (wiki linking)",
            "cross_model_judge",
        ],
        "sources": {
            "java_report": str(args.java_report).replace("\\", "/"),
            "ragas_metrics": str(args.ragas_metrics).replace("\\", "/"),
            "map_extraction_metrics": str(map_out).replace("\\", "/"),
            "publish_wiki_metrics": str(pub_out).replace("\\", "/"),
            "export_dir": str(args.export_dir).replace("\\", "/"),
        },
        "export_coverage": {
            "java_documentsEvaluated": docs_eval,
            "export_claim_samples": export_claims or ragas_samples.get("faithfulness"),
            "export_structured_samples": export_struct or map_n,
            "export_publish_samples": export_publish or publish_pages,
            "link_samples": len(links),
            "note": "Ragas/MAP metrics score export only — not necessarily full golden 40.",
        },
        "comparison": {
            "faithfulness_map_claims": {
                "java_proxy": java_faith,
                "ragas_official": ragas_faith,
                "threshold": args.faithfulness_threshold,
                "status_java": gate_faith_j,
                "status_ragas": gate_faith_r,
                "ragas_sample_count": ragas_faith_n,
                "ragas_hard_gate": ragas_faith_hard_gate,
                "note": (
                    "MAP claim faithfulness. Hard overall gate only when claim samples >= 20."
                ),
            },
            "faithfulness_publish": {
                "ragas_publish_faithfulness": publish_faith,
                "threshold": args.publish_faithfulness_threshold,
                "status": gate_pub_faith,
                "sample_count": publish_faith_n,
                "hard_gate": publish_faith_hard_gate,
                "note": (
                    "Post-Publish Markdown WikiPage faithfulness. "
                    "Hard overall gate when publish samples >= 5."
                ),
            },
            "extraction_f1": {
                "java_corpusExtractionF1": java_f1,
                "map_structuredExtractionF1": map_f1,
                "threshold": args.extraction_f1_threshold,
                "status_java": gate_f1_j,
                "status_map": gate_f1_map,
                "note": "Both are fuzzy name F1 on topics/entities — comparable.",
            },
            "publish_semantic": {
                "answer_relevancy": answer_relevancy,
                "context_recall": context_recall,
                "answer_relevancy_threshold": args.answer_relevancy_threshold,
                "context_recall_threshold": args.context_recall_threshold,
                "status_answer_relevancy": gate_ar,
                "status_context_recall": gate_cr,
                "note": "Ragas on published markdown; diagnostic unless thresholds set.",
            },
            "publish_offline": {
                "topicCoverage": topic_cov,
                "forbiddenHallucinationRate": hallu_rate,
                "publishLinkF1": publish_link,
                "topic_coverage_threshold": args.topic_coverage_threshold,
                "max_forbidden_hallu_rate": args.max_forbidden_hallu_rate,
                "status_topic": gate_topic,
                "status_hallu": gate_hallu,
                "pages": publish_pages,
            },
            "answer_correctness_diagnostic": {
                "ragas_answer_correctness": ragas_ac,
                "threshold": args.answer_correctness_threshold,
                "status": status(ragas_ac, args.answer_correctness_threshold)
                if args.answer_correctness_threshold is not None
                else "diagnostic_only",
                "note": (
                    "Ragas answer_correctness expects free-text Q&A. "
                    "MAP response is denser than partial GT reference → scores look low. "
                    "Do NOT gate release on this metric for extraction pipelines."
                ),
            },
            "groundedness": {
                "java_proxy": java_ground,
                "trulens_official": None,
                "note": "TruLens phase 2 optional",
            },
            "wiki_linking": {
                "java_corpusLinkF1": java_link,
                "export_avg_link_f1": export_link_avg,
                "publish_offline_link_f1": publish_link,
                "link_samples": len(links),
                "threshold": args.link_f1_threshold,
                "status": gate_link,
            },
            "cross_model_judge_avg": java_judge,
            "java_answer_relevancy_proxy": java_answer_rel,
        },
        "java_metrics": metrics,
        "ragas_corpus": ragas_corpus,
        "map_extraction_corpus": map_corpus,
        "publish_offline_corpus": pub_corpus,
    }

    args.out_dir.mkdir(parents=True, exist_ok=True)
    out_json = args.out_dir / "hybrid-evaluation-report.json"
    out_md = args.out_dir / "hybrid-evaluation-report.md"
    out_json.write_text(json.dumps(hybrid, indent=2, ensure_ascii=False), encoding="utf-8")

    lines = [
        "# SecWiki-Bench — Hybrid Evaluation Report",
        "",
        f"**Generated:** {hybrid['timestamp']}",
        "",
        f"**Overall gate:** **{hybrid['overall']}**",
        "",
        "Gates: Java Faithfulness + Extraction F1 + Link F1; "
        "Ragas claim faith hard if n≥20; Publish faith hard if n≥5; "
        "forbidden hallu rate on publish pages; AC diagnostic only.",
        "",
        f"**Export coverage:** Java docs={docs_eval}, "
        f"claims={export_claims or ragas_samples.get('faithfulness')}, "
        f"structured={export_struct or map_n}, "
        f"publish={export_publish or publish_pages}, links={len(links)}",
        "",
        f"**Ragas claim faith samples:** {ragas_faith_n} "
        f"(hard gate: {'yes' if ragas_faith_hard_gate else 'no — soft'})",
        f"**Ragas publish faith samples:** {publish_faith_n} "
        f"(hard gate: {'yes' if publish_faith_hard_gate else 'no — soft'})",
        "",
        "## 1. Scorecard (release gates)",
        "",
        "| Metric | Java | Official / Offline | Threshold | Status |",
        "|--------|------|--------------------|-----------|--------|",
        (
            f"| **Faithfulness (MAP claims)** | {fmt(java_faith)} | "
            f"Ragas {fmt(ragas_faith)} (n={ragas_faith_n}"
            f"{'' if ragas_faith_hard_gate else ', soft'}) | "
            f"{args.faithfulness_threshold:.2f} | "
            f"J:{gate_faith_j} / R:{gate_faith_r} |"
        ),
        (
            f"| **Faithfulness (Publish MD)** | — | "
            f"Ragas {fmt(publish_faith)} (n={publish_faith_n}"
            f"{'' if publish_faith_hard_gate else ', soft'}) | "
            f"{args.publish_faithfulness_threshold:.2f} | {gate_pub_faith} |"
        ),
        (
            f"| **Extraction F1** | {fmt(java_f1)} | "
            f"MAP-export {fmt(map_f1)} | {args.extraction_f1_threshold:.2f} | "
            f"J:{gate_f1_j} / MAP:{gate_f1_map} |"
        ),
        (
            f"| **Wiki Link F1** | {fmt(java_link)} | export avg {fmt(export_link_avg)} "
            f"/ publish offline {fmt(publish_link)} | "
            f"{args.link_f1_threshold:.2f} | {gate_link} |"
        ),
        (
            f"| **Forbidden hallu rate (Publish)** | — | {fmt(hallu_rate)} | "
            f"≤ {args.max_forbidden_hallu_rate:.2f} | {gate_hallu} |"
        ),
        (
            f"| Topic coverage (Publish) | — | {fmt(topic_cov)} | "
            f"≥ {args.topic_coverage_threshold:.2f} | {gate_topic} |"
        ),
        (
            f"| Groundedness | {fmt(java_ground)} | — (TruLens phase 2) | 0.80 | "
            f"J:{status(java_ground, 0.80)} |"
        ),
        f"| Cross-Model Judge Avg | {fmt(java_judge)} | n/a | — | — |",
        "",
        "## 2. Post-Publish Ragas semantic (diagnostic unless threshold set)",
        "",
        "| Metric | Value | Threshold | Status |",
        "|--------|-------|-----------|--------|",
        (
            f"| answer_relevancy | {fmt(answer_relevancy)} | "
            f"{fmt(args.answer_relevancy_threshold)} | {gate_ar} |"
        ),
        (
            f"| context_recall | {fmt(context_recall)} | "
            f"{fmt(args.context_recall_threshold)} | {gate_cr} |"
        ),
        "",
        "## 3. Diagnostic only (not a release gate)",
        "",
        "| Metric | Value | Why not a gate |",
        "|--------|-------|----------------|",
        (
            f"| Ragas `answer_correctness` | {fmt(ragas_ac)} | "
            f"Designed for Q&A free-text. MAP `response` is full extract JSON; "
            f"`reference` is partial golden GT → score ~0.4 is expected. |"
        ),
        "",
        "## 4. Track map",
        "",
        "| Export file | Phase | Metric |",
        "|-------------|-------|--------|",
        "| `ragas_claims.jsonl` | MAP | Ragas faithfulness (claims) |",
        "| `ragas_structured.jsonl` | MAP | MAP Extraction F1 (+ AC diagnostic) |",
        "| `ragas_publish.jsonl` | **PUBLISH** | faithfulness + answer_relevancy + context_recall |",
        "| `link_samples.jsonl` | PUBLISH / GERBIL | Java link F1 |",
        "",
        "## Notes",
        "",
        "- Java metrics: `EvaluationMetricsService` heuristics on full eval run.",
        "- Ragas claim faithfulness: official package on MAP claims.",
        "- **Post-Publish Ragas**: official package on approved Markdown WikiPages.",
        "- MAP Extraction F1: fuzzy name F1 offline (no API).",
        "- Offline publish: topic coverage + forbidden hallucination scan + optional link F1.",
        "- Do **not** fail the pipeline only because `answer_correctness` is low.",
        "",
        "## Artifacts",
        "",
        f"- Java: `{hybrid['sources']['java_report']}`",
        f"- Ragas: `{hybrid['sources']['ragas_metrics']}`",
        f"- MAP F1: `{hybrid['sources']['map_extraction_metrics']}`",
        f"- Publish offline: `{hybrid['sources']['publish_wiki_metrics']}`",
        f"- Export: `{hybrid['sources']['export_dir']}`",
        "",
    ]
    out_md.write_text("\n".join(lines), encoding="utf-8")

    print(f"Wrote {out_json}")
    print(f"Wrote {out_md}")
    print(f"Overall: {hybrid['overall']}")
    print(f"MAP Extraction F1: {fmt(map_f1)} (n={map_n})")
    print(
        f"Ragas claim faith: {fmt(ragas_faith)} | "
        f"publish faith: {fmt(publish_faith)} | "
        f"AR: {fmt(answer_relevancy)} | CR: {fmt(context_recall)}"
    )
    print(
        f"Publish offline: topic={fmt(topic_cov)} hallu_rate={fmt(hallu_rate)} "
        f"linkF1={fmt(publish_link)}"
    )
    return 1 if overall_fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
