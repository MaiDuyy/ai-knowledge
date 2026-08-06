#!/usr/bin/env python3
"""
Build ragas_publish.jsonl WITHOUT re-running Java when:
  - export/pages/{docId}/*.md snapshots already exist, OR
  - --synthetic-from-map is set (MAP extract → deterministic markdown stub)

Prefer real Java post-Publish export. Synthetic mode is for smoke-testing Ragas
wiring only and is labelled in metadata.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PUBLISH_TASK = """Compile a faithful technical Wiki page for the SecWiki knowledge base from the source document.
Rules:
- Only include facts explicitly present in the source (no external knowledge).
- Cover the required technical topics and claims from the enterprise procedure.
- Prefer precise wikilinks [[slug]] for known concepts when they appear in the source.
- Do not invent policies, stack items, or metrics not stated in the source.
"""


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


def map_json_to_markdown(doc_id: str, data: dict[str, Any]) -> str:
    lines = [f"# {doc_id}", ""]
    entities = data.get("entities") or []
    concepts = data.get("concepts") or []
    claims = data.get("claims") or []
    if entities:
        lines.append("## Entities")
        for e in entities:
            if not isinstance(e, dict):
                continue
            name = e.get("name") or "Entity"
            desc = e.get("description") or ""
            lines.append(f"- **{name}**: {desc}")
        lines.append("")
    if concepts:
        lines.append("## Concepts")
        for c in concepts:
            if not isinstance(c, dict):
                continue
            name = c.get("name") or "Concept"
            desc = c.get("description") or ""
            lines.append(f"- **{name}**: {desc}")
        lines.append("")
    if claims:
        lines.append("## Claims")
        for c in claims:
            if not isinstance(c, dict):
                continue
            subj = c.get("subject") or ""
            claim = c.get("claim") or ""
            lines.append(f"- {subj}: {claim}".strip(": "))
        lines.append("")
    return "\n".join(lines).strip() + "\n"


def parse_json_maybe(raw: Any) -> dict[str, Any]:
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        try:
            data = json.loads(raw)
            return data if isinstance(data, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def reference_from_structured_ref(ref_obj: dict[str, Any]) -> str:
    parts: list[str] = []
    names = []
    for field in ("entities", "concepts"):
        for item in ref_obj.get(field) or []:
            if isinstance(item, dict) and item.get("name"):
                names.append(str(item["name"]))
    if names:
        parts.append("Topics: " + "; ".join(names))
    claims = []
    for c in ref_obj.get("claims") or []:
        if not isinstance(c, dict):
            continue
        subj = c.get("subject") or ""
        claim = c.get("claim") or ""
        if subj or claim:
            claims.append(f"{subj}: {claim}".strip(": "))
    if claims:
        parts.append("Claims: " + ". ".join(claims))
    return "\n".join(parts)


def build_from_pages(export_dir: Path, structured_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    pages_dir = export_dir / "pages"
    if not pages_dir.exists():
        return []

    # index structured by doc for contexts/reference
    by_doc = {r.get("document_id"): r for r in structured_rows}
    out: list[dict[str, Any]] = []
    for doc_dir in sorted(p for p in pages_dir.iterdir() if p.is_dir()):
        doc_id = doc_dir.name
        struct = by_doc.get(doc_id) or {}
        contexts = struct.get("retrieved_contexts") or []
        source = contexts[0] if contexts else ""
        ref = struct.get("reference")
        ref_obj = parse_json_maybe(ref)
        reference = reference_from_structured_ref(ref_obj) if ref_obj else (ref or "")
        for md_path in sorted(doc_dir.glob("*.md")):
            md = md_path.read_text(encoding="utf-8")
            if len(md.strip()) < 40:
                continue
            slug = md_path.stem.replace("_", "/")
            topics = []
            if ref_obj:
                for field in ("entities", "concepts"):
                    for item in ref_obj.get(field) or []:
                        if isinstance(item, dict) and item.get("name"):
                            topics.append(str(item["name"]))
            row = {
                "document_id": doc_id,
                "user_input": PUBLISH_TASK.strip()
                + f"\nRequired topics: {', '.join(topics) if topics else 'source topics'}"
                + f"\nPage title: {md_path.stem}",
                "response": md,
                "retrieved_contexts": [source] if source else [],
                "reference": reference,
                "metadata": {
                    "pipeline_stage": "PUBLISH",
                    "artifact": "WikiPage.markdown",
                    "page_title": md_path.stem,
                    "page_slug": slug,
                    "source": "export/pages rebuild",
                    "expected_topics": topics,
                    "forbidden_hallucinations": [],
                    "expected_links": [],
                    "forbidden_links": [],
                },
            }
            if not source:
                row["metadata"]["warning"] = "missing source context from structured track"
            out.append(row)
    return out


def build_synthetic_from_map(structured_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for row in structured_rows:
        doc_id = row.get("document_id") or "unknown"
        resp = parse_json_maybe(row.get("response"))
        ref = parse_json_maybe(row.get("reference"))
        md = map_json_to_markdown(doc_id, resp)
        contexts = row.get("retrieved_contexts") or []
        topics = []
        for field in ("entities", "concepts"):
            for item in ref.get(field) or []:
                if isinstance(item, dict) and item.get("name"):
                    topics.append(str(item["name"]))
        out.append(
            {
                "document_id": doc_id,
                "user_input": PUBLISH_TASK.strip()
                + f"\nRequired topics: {', '.join(topics) if topics else 'source topics'}"
                + f"\nPage title: {doc_id}",
                "response": md,
                "retrieved_contexts": contexts,
                "reference": reference_from_structured_ref(ref),
                "metadata": {
                    "pipeline_stage": "PUBLISH_SYNTHETIC",
                    "artifact": "synthetic_from_map_json",
                    "page_title": doc_id,
                    "page_slug": f"synthetic/{doc_id}",
                    "source": "synthetic-from-map",
                    "expected_topics": topics,
                    "forbidden_hallucinations": [],
                    "expected_links": [],
                    "forbidden_links": [],
                    "warning": "Synthetic markdown from MAP JSON — not real Publish output",
                },
            }
        )
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="Build ragas_publish.jsonl from pages or MAP")
    parser.add_argument(
        "--export-dir",
        type=Path,
        default=Path("benchmark/evaluation/latest/export"),
    )
    parser.add_argument(
        "--synthetic-from-map",
        action="store_true",
        help="If pages missing, render markdown from MAP extract (smoke only)",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite existing ragas_publish.jsonl",
    )
    args = parser.parse_args()
    export_dir: Path = args.export_dir
    out_path = export_dir / "ragas_publish.jsonl"

    if out_path.exists() and out_path.stat().st_size > 0 and not args.force:
        print(f"Already exists: {out_path} (use --force to overwrite)")
        return 0

    structured = load_jsonl(export_dir / "ragas_structured.jsonl")
    rows = build_from_pages(export_dir, structured)
    mode = "pages"
    if not rows and args.synthetic_from_map:
        rows = build_synthetic_from_map(structured)
        mode = "synthetic-from-map"
    if not rows:
        print(
            "No publish samples built.\n"
            "  - Re-run Java: mvn test -Pevaluation-benchmark\n"
            "  - Or: python build_publish_export.py --synthetic-from-map --force"
        )
        return 1

    export_dir.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # patch manifest if present
    manifest_path = export_dir / "export-manifest.json"
    if manifest_path.exists():
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["publishSampleCount"] = len(rows)
            files = manifest.get("files") or {}
            files["publish"] = "ragas_publish.jsonl"
            manifest["files"] = files
            tracks = manifest.get("tracks") or {}
            tracks["publish"] = {
                "purpose": "Post-Publish Markdown WikiPage",
                "built_by": f"build_publish_export.py ({mode})",
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
            manifest["tracks"] = tracks
            manifest_path.write_text(
                json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8"
            )
        except Exception as e:
            print(f"WARN: could not patch export-manifest.json: {e}")

    print(f"Wrote {len(rows)} publish samples ({mode}) → {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
