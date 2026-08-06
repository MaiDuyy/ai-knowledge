#!/usr/bin/env python3
"""
Phase 2 stub: TruLens groundedness on claim export samples.

Not required for phase-1 hybrid Ragas evaluation. When ready:

  pip install trulens-eval
  .venv-bench\\Scripts\\python.exe benchmark-eval/evaluate_with_trulens.py \\
    --export-dir benchmark/evaluation/latest/export

This script currently validates export presence and writes a placeholder report
so the hybrid pipeline can document the phase-2 slot.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description="TruLens groundedness (phase 2 stub)")
    parser.add_argument(
        "--export-dir",
        type=Path,
        default=Path("benchmark/evaluation/latest/export"),
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=None,
    )
    args = parser.parse_args()

    out_dir = args.out_dir or (args.export_dir.parent / "trulens")
    out_dir.mkdir(parents=True, exist_ok=True)

    claims_path = args.export_dir / "ragas_claims.jsonl"
    claim_count = 0
    if claims_path.exists():
        claim_count = sum(1 for line in claims_path.read_text(encoding="utf-8").splitlines() if line.strip())

    payload = {
        "metric_source": "trulens_official",
        "status": "not_implemented",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "export_dir": str(args.export_dir).replace("\\", "/"),
        "claim_samples": claim_count,
        "corpus": {"groundedness": None},
        "message": (
            "Phase 2: install trulens-eval and implement groundedness feedback "
            "over ragas_claims.jsonl and ragas_publish.jsonl "
            "(claim/wiki markdown as answer, source as context)."
        ),
    }

    out_json = out_dir / "trulens-metrics.json"
    out_json.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    (out_dir / "trulens-report.md").write_text(
        "\n".join(
            [
                "# SecWiki-Bench — TruLens Report (Phase 2 Stub)",
                "",
                payload["message"],
                "",
                f"Claim samples available: **{claim_count}**",
                "",
            ]
        ),
        encoding="utf-8",
    )
    print(f"Wrote stub {out_json} (claim_samples={claim_count})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
