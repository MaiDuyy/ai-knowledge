#!/usr/bin/env python3
"""
Offline metrics for post-Publish Markdown WikiPages (ragas_publish.jsonl).

Complements official Ragas (faithfulness / answer_relevancy / context_recall)
with deterministic checks aligned to SecWiki golden GT:

  - topic_coverage: expected topics mentioned in wiki markdown
  - forbidden_hallucination_hits: forbidden terms appearing in wiki (anti-hallu)
  - publish_link_f1: GERBIL-style F1 on [[wikilinks]] vs expected/forbidden

No LLM required.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

WIKILINK_RE = re.compile(r"\[\[([^\]|#]+)(?:[|#][^\]]*)?\]\]")


def normalize(text: str) -> str:
    if not text:
        return ""
    t = text.lower()
    t = re.sub(r"[^\w\s]", " ", t, flags=re.UNICODE)
    t = re.sub(r"\s+", " ", t).strip()
    return t


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def extract_wikilinks(markdown: str) -> set[str]:
    if not markdown:
        return set()
    links: set[str] = set()
    for m in WIKILINK_RE.finditer(markdown):
        slug = m.group(1).strip()
        if slug:
            links.add(slug)
    return links


def topic_coverage(markdown: str, topics: list[str]) -> float:
    if not topics:
        return 1.0
    body = normalize(markdown)
    if not body:
        return 0.0
    hits = 0
    for t in topics:
        nt = normalize(str(t))
        if not nt:
            continue
        if nt in body or any(tok in body for tok in nt.split() if len(tok) >= 4):
            hits += 1
    return hits / len(topics)


def forbidden_hits(markdown: str, forbidden: list[str]) -> list[str]:
    body = normalize(markdown)
    hits: list[str] = []
    for term in forbidden:
        nt = normalize(str(term))
        if nt and nt in body:
            hits.append(str(term))
    return hits


def link_f1(actual: set[str], expected: set[str]) -> dict[str, float]:
    if not expected:
        return {
            "link_precision": 1.0 if not actual else 0.0,
            "link_recall": 1.0,
            "link_f1": 1.0 if not actual else 0.0,
        }
    if not actual:
        return {"link_precision": 0.0, "link_recall": 0.0, "link_f1": 0.0}
    tp = len(actual & expected)
    precision = tp / len(actual)
    recall = tp / len(expected)
    f1 = 0.0 if (precision + recall) == 0 else 2 * precision * recall / (precision + recall)
    return {"link_precision": precision, "link_recall": recall, "link_f1": f1}


def score_publish_row(row: dict[str, Any]) -> dict[str, Any]:
    md = row.get("response") or ""
    meta = row.get("metadata") or {}
    topics = list(meta.get("expected_topics") or [])
    forbidden = list(meta.get("forbidden_hallucinations") or [])
    expected_links = set(meta.get("expected_links") or [])
    forbidden_links = set(meta.get("forbidden_links") or [])

    actual_links = extract_wikilinks(md)
    link_metrics = link_f1(actual_links, expected_links) if expected_links else {
        "link_precision": None,
        "link_recall": None,
        "link_f1": None,
    }
    hallu = forbidden_hits(md, forbidden)
    forbidden_link_hits = sorted(actual_links & forbidden_links)

    return {
        "document_id": row.get("document_id"),
        "page_slug": meta.get("page_slug"),
        "page_title": meta.get("page_title"),
        "topic_coverage": topic_coverage(md, topics),
        "forbidden_hallucination_hits": hallu,
        "forbidden_hallucination_count": len(hallu),
        "actual_links": sorted(actual_links),
        "expected_links": sorted(expected_links),
        "forbidden_link_hits": forbidden_link_hits,
        **{k: v for k, v in link_metrics.items()},
        "markdown_chars": len(md),
    }


def evaluate_publish_export(export_dir: Path) -> dict[str, Any]:
    path = export_dir / "ragas_publish.jsonl"
    if not path.exists():
        return {
            "metric_source": "publish_wiki_offline",
            "corpus": {
                "topicCoverage": None,
                "forbiddenHallucinationRate": None,
                "publishLinkF1": None,
            },
            "sample_counts": {"publish": 0},
            "per_page": [],
            "note": "ragas_publish.jsonl missing — re-run Java evaluation-benchmark to export post-Publish pages",
        }

    rows = load_jsonl(path)
    per_page = [score_publish_row(r) for r in rows]
    n = len(per_page)
    if n == 0:
        return {
            "metric_source": "publish_wiki_offline",
            "corpus": {
                "topicCoverage": None,
                "forbiddenHallucinationRate": None,
                "publishLinkF1": None,
            },
            "sample_counts": {"publish": 0},
            "per_page": [],
            "note": "ragas_publish.jsonl empty",
        }

    topic_avg = sum(p["topic_coverage"] for p in per_page) / n
    hallu_docs = sum(1 for p in per_page if p["forbidden_hallucination_count"] > 0)
    link_f1s = [p["link_f1"] for p in per_page if isinstance(p.get("link_f1"), (int, float))]
    link_avg = sum(link_f1s) / len(link_f1s) if link_f1s else None
    forbidden_link_docs = sum(1 for p in per_page if p.get("forbidden_link_hits"))

    return {
        "metric_source": "publish_wiki_offline",
        "description": (
            "Deterministic post-Publish checks on WikiPage markdown: "
            "topic coverage, forbidden hallucination scan, optional link F1."
        ),
        "corpus": {
            "topicCoverage": topic_avg,
            "forbiddenHallucinationRate": hallu_docs / n,
            "forbiddenHallucinationDocs": hallu_docs,
            "publishLinkF1": link_avg,
            "forbiddenLinkDocs": forbidden_link_docs,
            "pagesEvaluated": n,
        },
        "sample_counts": {
            "publish": n,
            "publishWithLinkGt": len(link_f1s),
        },
        "per_page": per_page,
    }


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Offline post-Publish wiki metrics")
    parser.add_argument(
        "--export-dir",
        type=Path,
        default=Path("benchmark/evaluation/latest/export"),
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
    )
    args = parser.parse_args()
    result = evaluate_publish_export(args.export_dir)
    out = args.out or (args.export_dir.parent / "ragas" / "publish-wiki-metrics.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result.get("corpus"), indent=2, ensure_ascii=False))
    print(f"Wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
