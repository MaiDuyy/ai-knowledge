#!/usr/bin/env python3
"""
MAP-schema extraction metrics for SecWiki structured export (ragas_structured.jsonl).

Ragas answer_correctness is Q&A-oriented and systematically under-scores dense MAP JSON
(response is richer than partial golden GT). This module mirrors Java extraction F1:
fuzzy name match of entities + concepts (+ optional claim subjects) between response and reference.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


def normalize(text: str) -> str:
    if not text:
        return ""
    t = text.lower()
    t = re.sub(r"[^\w\s]", " ", t, flags=re.UNICODE)
    t = re.sub(r"\s+", " ", t).strip()
    return t


def fuzzy_match(a: str, b: str) -> bool:
    na, nb = normalize(a), normalize(b)
    if not na or not nb:
        return False
    if na == nb:
        return True
    if na in nb or nb in na:
        return True
    # JWT ↔ json web token
    if (na == "jwt" and "json web token" in nb) or (nb == "jwt" and "json web token" in na):
        return True
    if "spring" in na and "spring" in nb:
        return True
    # token overlap (≥1 token length ≥3)
    ta = {t for t in na.split() if len(t) >= 3}
    tb = {t for t in nb.split() if len(t) >= 3}
    return bool(ta & tb)


def names_from_map(obj: dict[str, Any], fields: tuple[str, ...] = ("entities", "concepts")) -> set[str]:
    names: set[str] = set()
    if not isinstance(obj, dict):
        return names
    for field in fields:
        arr = obj.get(field) or []
        if not isinstance(arr, list):
            continue
        for item in arr:
            if not isinstance(item, dict):
                continue
            name = item.get("name") or item.get("title") or ""
            if isinstance(name, str) and name.strip():
                names.add(name.strip())
            aliases = item.get("aliases") or []
            if isinstance(aliases, list):
                for al in aliases:
                    if isinstance(al, str) and al.strip():
                        names.add(al.strip())
    return names


def parse_map_json(raw: Any) -> dict[str, Any]:
    if raw is None:
        return {}
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        try:
            data = json.loads(raw)
            return data if isinstance(data, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def extraction_f1(predicted: set[str], ground_truth: set[str]) -> dict[str, float]:
    if not ground_truth:
        return {
            "precision": 1.0 if not predicted else 0.0,
            "recall": 1.0,
            "extractionF1": 1.0 if not predicted else 0.0,
        }
    if not predicted:
        return {"precision": 0.0, "recall": 0.0, "extractionF1": 0.0}

    matched_gt = sum(1 for g in ground_truth if any(fuzzy_match(p, g) for p in predicted))
    matched_pred = sum(1 for p in predicted if any(fuzzy_match(p, g) for g in ground_truth))
    recall = matched_gt / len(ground_truth)
    precision = matched_pred / len(predicted)
    f1 = 0.0 if (precision + recall) == 0 else 2 * precision * recall / (precision + recall)
    return {"precision": precision, "recall": recall, "extractionF1": f1}


def score_structured_row(row: dict[str, Any]) -> dict[str, Any]:
    resp = parse_map_json(row.get("response"))
    ref = parse_map_json(row.get("reference"))
    pred = names_from_map(resp)
    gt = names_from_map(ref)
    metrics = extraction_f1(pred, gt)
    return {
        "document_id": row.get("document_id"),
        "predicted_names": sorted(pred),
        "ground_truth_names": sorted(gt),
        **metrics,
        "response_entity_count": len(resp.get("entities") or []),
        "response_concept_count": len(resp.get("concepts") or []),
        "reference_entity_count": len(ref.get("entities") or []),
        "reference_concept_count": len(ref.get("concepts") or []),
    }


def evaluate_structured_export(export_dir: Path) -> dict[str, Any]:
    path = export_dir / "ragas_structured.jsonl"
    if not path.exists():
        return {
            "metric_source": "map_extraction_f1",
            "corpus": {"structuredExtractionF1": None},
            "sample_counts": {"structuredExtractionF1": 0},
            "per_document": [],
            "note": "ragas_structured.jsonl missing",
        }

    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))

    per_doc = [score_structured_row(r) for r in rows]
    f1s = [d["extractionF1"] for d in per_doc]
    corpus_f1 = sum(f1s) / len(f1s) if f1s else None

    return {
        "metric_source": "map_extraction_f1",
        "description": (
            "Fuzzy name F1 on MAP entities+concepts (response vs reference). "
            "Aligned with Java corpusExtractionF1; not Ragas answer_correctness."
        ),
        "corpus": {
            "structuredExtractionF1": corpus_f1,
            "structuredExtractionPrecision": (
                sum(d["precision"] for d in per_doc) / len(per_doc) if per_doc else None
            ),
            "structuredExtractionRecall": (
                sum(d["recall"] for d in per_doc) / len(per_doc) if per_doc else None
            ),
        },
        "sample_counts": {"structuredExtractionF1": len(per_doc)},
        "per_document": per_doc,
    }


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="MAP structured extraction F1 on export")
    parser.add_argument(
        "--export-dir",
        type=Path,
        default=Path("benchmark/evaluation/latest/export"),
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Write JSON report (default: <export-dir>/../ragas/map-extraction-metrics.json)",
    )
    args = parser.parse_args()
    result = evaluate_structured_export(args.export_dir)
    out = args.out or (args.export_dir.parent / "ragas" / "map-extraction-metrics.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    # strip large name lists in per_document for compact report? keep them for debug
    out.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result.get("corpus"), indent=2))
    print(f"Wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
