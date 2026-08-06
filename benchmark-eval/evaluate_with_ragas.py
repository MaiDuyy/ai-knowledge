#!/usr/bin/env python3
"""
Run official Ragas metrics on SecWiki evaluation export (JSONL).

Tracks:
  - ragas_claims.jsonl     → faithfulness (MAP claims)
  - ragas_structured.jsonl → answer_correctness (diagnostic) + MAP extraction F1
  - ragas_publish.jsonl    → post-Publish Markdown:
        faithfulness, answer_relevancy, context_recall
        + offline topic_coverage / forbidden hallu / publish_link_f1

Usage:
  .venv-bench\\Scripts\\python.exe benchmark-eval/evaluate_with_ragas.py \\
    --export-dir benchmark/evaluation/latest/export \\
    --out-dir benchmark/evaluation/latest/ragas
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import types
from pathlib import Path
from typing import Any


def _install_langchain_community_shims() -> None:
    """
    ragas 0.2.x imports VertexAI helpers removed from langchain-community 0.4+.
    Install lightweight stubs so `import ragas` succeeds when we only use Gemini/OpenAI wrappers.
    """
    stubs = {
        "langchain_community.chat_models.vertexai": "ChatVertexAI",
        "langchain_community.llms.vertexai": "VertexAI",
    }
    # Also support: from langchain_community.llms import VertexAI
    for mod_name, attr in stubs.items():
        if mod_name in sys.modules:
            continue
        mod = types.ModuleType(mod_name)
        setattr(mod, attr, type(attr, (), {}))
        sys.modules[mod_name] = mod

    # Ensure parent packages expose attributes used by ragas
    try:
        import langchain_community.llms as community_llms  # type: ignore

        if not hasattr(community_llms, "VertexAI"):
            community_llms.VertexAI = sys.modules["langchain_community.llms.vertexai"].VertexAI  # type: ignore
    except Exception:
        pass


_install_langchain_community_shims()


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    return rows


def _truncate(text: str, max_chars: int) -> str:
    if text is None:
        return ""
    if max_chars <= 0 or len(text) <= max_chars:
        return text
    return text[: max_chars - 20] + "\n...[truncated]..."


def _normalize_contexts(ctx: Any, max_chars: int) -> list[str]:
    if ctx is None:
        return []
    if isinstance(ctx, str):
        items = [ctx]
    else:
        items = list(ctx)
    return [_truncate(str(c), max_chars) for c in items if c is not None]


def to_ragas_claims_dataset(
    rows: list[dict[str, Any]],
    max_claims_per_doc: int | None,
    max_context_chars: int,
    limit: int | None,
):
    from datasets import Dataset

    by_doc: dict[str, int] = {}
    user_inputs: list[str] = []
    responses: list[str] = []
    contexts: list[list[str]] = []
    doc_ids: list[str] = []

    for row in rows:
        if limit is not None and len(doc_ids) >= limit:
            break
        doc_id = row.get("document_id") or "unknown"
        if max_claims_per_doc is not None:
            used = by_doc.get(doc_id, 0)
            if used >= max_claims_per_doc:
                continue
            by_doc[doc_id] = used + 1

        user_inputs.append(row.get("user_input") or "")
        responses.append(_truncate(row.get("response") or "", 4000))
        contexts.append(_normalize_contexts(row.get("retrieved_contexts"), max_context_chars))
        doc_ids.append(doc_id)

    if not user_inputs:
        return None, []

    data = {
        "user_input": user_inputs,
        "response": responses,
        "retrieved_contexts": contexts,
    }
    return Dataset.from_dict(data), doc_ids


def to_ragas_structured_dataset(
    rows: list[dict[str, Any]],
    max_context_chars: int,
    max_json_chars: int,
    limit: int | None,
):
    from datasets import Dataset

    user_inputs: list[str] = []
    responses: list[str] = []
    references: list[str] = []
    contexts: list[list[str]] = []
    doc_ids: list[str] = []

    for row in rows:
        if limit is not None and len(doc_ids) >= limit:
            break
        ref = row.get("reference")
        if ref is None:
            ref = ""
        if not isinstance(ref, str):
            ref = json.dumps(ref, ensure_ascii=False)

        user_inputs.append(row.get("user_input") or "")
        responses.append(_truncate(row.get("response") or "", max_json_chars))
        references.append(_truncate(ref, max_json_chars))
        contexts.append(_normalize_contexts(row.get("retrieved_contexts"), max_context_chars))
        doc_ids.append(row.get("document_id") or "unknown")

    if not user_inputs:
        return None, []

    data = {
        "user_input": user_inputs,
        "response": responses,
        "reference": references,
        "retrieved_contexts": contexts,
    }
    return Dataset.from_dict(data), doc_ids


def to_ragas_publish_dataset(
    rows: list[dict[str, Any]],
    max_context_chars: int,
    max_markdown_chars: int,
    limit: int | None,
):
    """
    Post-Publish WikiPage samples for Ragas:
      user_input, response (markdown), retrieved_contexts (source), reference (GT free-text)
    """
    from datasets import Dataset

    user_inputs: list[str] = []
    responses: list[str] = []
    references: list[str] = []
    contexts: list[list[str]] = []
    doc_ids: list[str] = []
    page_slugs: list[str] = []

    for row in rows:
        if limit is not None and len(doc_ids) >= limit:
            break
        response = row.get("response") or ""
        if not str(response).strip():
            continue
        ctx = _normalize_contexts(row.get("retrieved_contexts"), max_context_chars)
        if not ctx:
            # faithfulness / context metrics need context
            continue
        ref = row.get("reference")
        if ref is None:
            ref = ""
        if not isinstance(ref, str):
            ref = json.dumps(ref, ensure_ascii=False)

        meta = row.get("metadata") or {}
        user_inputs.append(row.get("user_input") or "")
        responses.append(_truncate(str(response), max_markdown_chars))
        references.append(_truncate(ref, max_markdown_chars))
        contexts.append(ctx)
        doc_ids.append(row.get("document_id") or "unknown")
        page_slugs.append(str(meta.get("page_slug") or ""))

    if not user_inputs:
        return None, [], []

    data = {
        "user_input": user_inputs,
        "response": responses,
        "reference": references,
        "retrieved_contexts": contexts,
    }
    return Dataset.from_dict(data), doc_ids, page_slugs


def resolve_gemini_api_key() -> str | None:
    """Resolve Gemini key from env (API_KEY / GEMINI_API_KEY / GOOGLE_API_KEY)."""
    for name in ("GOOGLE_API_KEY", "GEMINI_API_KEY", "API_KEY"):
        key = os.environ.get(name, "").strip()
        if key and not key.startswith("dummy") and not key.startswith("sk-dummy"):
            return key
    return None


def resolve_openai_api_key() -> str | None:
    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if key and not key.startswith("sk-dummy") and not key.startswith("dummy"):
        return key
    return None


def configure_llm():
    """
    Configure Ragas LLM + embeddings.

    Provider selection (RAGAS_PROVIDER):
      - gemini (default when Gemini key present)
      - openai (when OPENAI_API_KEY present)
    """
    from ragas.llms import LangchainLLMWrapper
    from ragas.embeddings import LangchainEmbeddingsWrapper

    provider = os.environ.get("RAGAS_PROVIDER", "").strip().lower()
    gemini_key = resolve_gemini_api_key()
    openai_key = resolve_openai_api_key()

    if not provider:
        if gemini_key:
            provider = "gemini"
        elif openai_key:
            provider = "openai"
        else:
            raise SystemExit(
                "No LLM API key found for Ragas.\n"
                "Set one of:\n"
                "  API_KEY / GEMINI_API_KEY / GOOGLE_API_KEY  (Gemini — recommended for this project)\n"
                "  OPENAI_API_KEY                              (OpenAI)\n"
                "Optional: RAGAS_PROVIDER=gemini|openai"
            )

    if provider in ("gemini", "google", "google-genai"):
        if not gemini_key:
            raise SystemExit(
                "Gemini provider selected but no API key found.\n"
                "Set API_KEY or GEMINI_API_KEY or GOOGLE_API_KEY."
            )
        # langchain-google-genai reads GOOGLE_API_KEY
        os.environ["GOOGLE_API_KEY"] = gemini_key

        # Prefer lite flash models (separate free-tier quotas). Override via RAGAS_LLM_MODEL.
        model = os.environ.get("RAGAS_LLM_MODEL", "gemini-3.1-flash-lite")
        emb_model = os.environ.get(
            "RAGAS_EMBEDDING_MODEL",
            "models/gemini-embedding-001",
        )
        try:
            from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings

            # max_retries low: long retry storms look like TimeoutError (180s/job) in Ragas
            llm = LangchainLLMWrapper(
                ChatGoogleGenerativeAI(
                    model=model,
                    temperature=0,
                    google_api_key=gemini_key,
                    max_retries=2,
                )
            )
            embeddings = LangchainEmbeddingsWrapper(
                GoogleGenerativeAIEmbeddings(model=emb_model, google_api_key=gemini_key)
            )
            print(f"Ragas provider=gemini llm={model} embeddings={emb_model}")
            return llm, embeddings, model, emb_model
        except Exception as e:
            raise SystemExit(f"Failed to configure Gemini for Ragas: {e}") from e

    if provider == "openai":
        if not openai_key:
            raise SystemExit("OpenAI provider selected but OPENAI_API_KEY is missing.")
        model = os.environ.get("RAGAS_LLM_MODEL", "gpt-4o-mini")
        emb_model = os.environ.get("RAGAS_EMBEDDING_MODEL", "text-embedding-3-small")
        try:
            from langchain_openai import ChatOpenAI, OpenAIEmbeddings

            llm = LangchainLLMWrapper(ChatOpenAI(model=model, temperature=0, api_key=openai_key))
            embeddings = LangchainEmbeddingsWrapper(
                OpenAIEmbeddings(model=emb_model, api_key=openai_key)
            )
            print(f"Ragas provider=openai llm={model} embeddings={emb_model}")
            return llm, embeddings, model, emb_model
        except Exception as e:
            raise SystemExit(f"Failed to configure OpenAI for Ragas: {e}") from e

    raise SystemExit(f"Unknown RAGAS_PROVIDER={provider!r}. Use gemini or openai.")


def preflight_llm(llm, embeddings, model: str) -> None:
    """Fail fast if Gemini quota/API is dead — avoid 16×180s TimeoutError waste."""
    print(f"Preflight LLM ({model})…")
    try:
        # LangchainLLMWrapper may expose .langchain_llm or just be callable via generate
        base = getattr(llm, "langchain_llm", None) or getattr(llm, "llm", None) or llm
        if hasattr(base, "invoke"):
            msg = base.invoke("Reply with exactly: OK")
            content = getattr(msg, "content", msg)
            print(f"  LLM OK: {str(content)[:80]!r}")
        else:
            print("  WARN: cannot invoke wrapped LLM directly; skip LLM preflight")
    except Exception as e:
        err = str(e)
        if "429" in err or "RESOURCE_EXHAUSTED" in err or "quota" in err.lower():
            raise SystemExit(
                "\n"
                "=== Gemini quota / rate limit exhausted ===\n"
                f"{err[:400]}\n\n"
                "Ragas jobs will all TimeoutError after 180s if you continue.\n"
                "Fix options:\n"
                "  1) Wait for free-tier quota reset (often minutes or next day)\n"
                "  2) Switch model:  $env:RAGAS_LLM_MODEL='gemini-2.5-flash-lite'\n"
                "  3) Run small sample:  .\\scripts\\run-ragas-eval.ps1 -Limit 4\n"
                "  4) Enable billing / higher quota on Google AI Studio\n"
            ) from e
        raise SystemExit(f"LLM preflight failed: {e}") from e

    print("Preflight embeddings…")
    try:
        emb_base = getattr(embeddings, "embeddings", None) or getattr(embeddings, "langchain_embeddings", None) or embeddings
        if hasattr(emb_base, "embed_query"):
            vec = emb_base.embed_query("secwiki test")
            print(f"  Embeddings OK: dim={len(vec)}")
        else:
            print("  WARN: skip embedding preflight")
    except Exception as e:
        err = str(e)
        raise SystemExit(
            f"Embedding preflight failed (check RAGAS_EMBEDDING_MODEL): {err[:400]}\n"
            "Default: models/gemini-embedding-001"
        ) from e


def run_metrics(dataset, metrics, llm, embeddings, max_workers: int = 1, timeout: int = 180):
    from ragas import evaluate
    from ragas.run_config import RunConfig

    # max_workers=1 critical for free tier; timeout is per-sample job.
    run_config = RunConfig(max_workers=max_workers, timeout=timeout)
    result = evaluate(
        dataset=dataset,
        metrics=metrics,
        llm=llm,
        embeddings=embeddings,
        run_config=run_config,
    )
    return result


def _safe_mean(series) -> float | None:
    import math

    try:
        import pandas as pd

        s = pd.to_numeric(series, errors="coerce").dropna()
        if s.empty:
            return None
        val = float(s.mean())
        if math.isnan(val) or math.isinf(val):
            return None
        return val
    except Exception:
        return None


def result_to_dict(result) -> dict[str, Any]:
    """Normalize ragas EvaluationResult to a plain dict. NaN → None (not JSON NaN)."""
    import math

    out: dict[str, Any] = {}
    try:
        df = result.to_pandas()
        # keep compact per-sample scores only
        score_cols = [
            c
            for c in df.columns
            if c not in ("user_input", "response", "reference", "retrieved_contexts")
        ]
        slim = df[score_cols].copy() if score_cols else df
        out["per_sample"] = slim.to_dict(orient="records")
        out["valid_sample_counts"] = {}
        for col in score_cols:
            mean = _safe_mean(df[col])
            if mean is not None:
                out[col] = mean
            try:
                import pandas as pd

                n_valid = int(pd.to_numeric(df[col], errors="coerce").notna().sum())
                out["valid_sample_counts"][col] = n_valid
            except Exception:
                pass
    except Exception:
        if hasattr(result, "scores") and isinstance(result.scores, dict):
            for k, v in result.scores.items():
                try:
                    fv = float(v)
                    out[k] = None if (math.isnan(fv) or math.isinf(fv)) else fv
                except (TypeError, ValueError):
                    out[k] = v
        elif isinstance(result, dict):
            for k, v in result.items():
                try:
                    fv = float(v)
                    out[k] = None if (math.isnan(fv) or math.isinf(fv)) else fv
                except (TypeError, ValueError):
                    out[k] = v
        else:
            out["raw"] = str(result)
    return out


def extract_metric(parsed: dict[str, Any], *keys: str) -> float | None:
    for k in keys:
        if k in parsed and parsed[k] is not None:
            return float(parsed[k])
    for k, v in parsed.items():
        if k in ("per_sample", "valid_sample_counts", "raw"):
            continue
        if isinstance(v, (int, float)):
            import math

            if not (math.isnan(float(v)) or math.isinf(float(v))):
                kl = k.lower()
                if any(x in kl for x in keys):
                    return float(v)
    return None


def write_markdown(path: Path, payload: dict[str, Any]) -> None:
    lines = [
        "# SecWiki-Bench — Official Ragas Report",
        "",
        f"**Source:** `{payload.get('export_dir')}`",
        f"**Generated:** {payload.get('timestamp')}",
        f"**LLM:** `{payload.get('llm_model')}`",
        f"**Embeddings:** `{payload.get('embedding_model')}`",
        f"**Metric source label:** `ragas_official`",
        "",
        "## Corpus metrics",
        "",
        "| Metric | Value | Samples |",
        "|--------|-------|---------|",
    ]
    corpus = payload.get("corpus") or {}
    counts = payload.get("sample_counts") or {}
    for key, val in corpus.items():
        n = counts.get(key, "—")
        if val is None:
            lines.append(f"| `{key}` | **null (all jobs failed)** | {n} |")
        elif isinstance(val, float):
            lines.append(f"| `{key}` | {val:.4f} | {n} |")
        else:
            lines.append(f"| `{key}` | {val} | {n} |")

    lines.extend(
        [
            "",
            "## Tracks",
            "",
            "| Track | File | Metrics |",
            "|-------|------|---------|",
            "| MAP claims | `ragas_claims.jsonl` | `faithfulness` |",
            "| MAP structured | `ragas_structured.jsonl` | `structuredExtractionF1` + AC diagnostic |",
            "| **Post-Publish wiki** | `ragas_publish.jsonl` | `publish_faithfulness`, `answer_relevancy`, `context_recall` |",
            "| Offline publish | same | topic_coverage, forbidden hallu, publish_link_f1 |",
            "",
            "## Notes",
            "",
            "- **faithfulness** (claim track) uses MAP `claims[].claim` samples.",
            "- **publish_*** metrics score **approved Markdown WikiPage** (Publish phase), not Reduce JSON.",
            "- **answer_correctness** is diagnostic only for MAP JSON (not a release gate).",
            "- Wiki **linkF1** (GERBIL) remains Java + offline publish scan.",
            "",
            "Merge with Java report via `merge_hybrid_report.py`.",
            "",
        ]
    )
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate SecWiki export with official Ragas")
    parser.add_argument(
        "--export-dir",
        type=Path,
        default=Path("benchmark/evaluation/latest/export"),
        help="Directory containing ragas_*.jsonl",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=None,
        help="Output directory (default: <export-dir>/../ragas)",
    )
    parser.add_argument(
        "--metrics",
        default="faithfulness,answer_correctness,map_extraction_f1,publish",
        help=(
            "Comma-separated metrics/tracks: faithfulness,answer_correctness,"
            "map_extraction_f1,publish,publish_faithfulness,answer_relevancy,context_recall"
        ),
    )
    parser.add_argument(
        "--max-markdown-chars",
        type=int,
        default=12000,
        help="Truncate published wiki markdown for Ragas (default 12000)",
    )
    parser.add_argument(
        "--skip-publish-offline",
        action="store_true",
        help="Skip offline publish topic/forbidden/link metrics",
    )
    parser.add_argument(
        "--max-claims-per-doc",
        type=int,
        default=None,
        help="Optional cap (export already capped by Java judgeConfig)",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Max samples per metric track (useful when free-tier quota is tight)",
    )
    parser.add_argument(
        "--max-context-chars",
        type=int,
        default=6000,
        help="Truncate each retrieved context to this many chars (default 6000)",
    )
    parser.add_argument(
        "--max-json-chars",
        type=int,
        default=8000,
        help="Truncate structured response/reference JSON (default 8000)",
    )
    parser.add_argument(
        "--max-workers",
        type=int,
        default=1,
        help="Ragas parallel workers (default 1 for Gemini free tier)",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=180,
        help="Per-job timeout seconds",
    )
    parser.add_argument(
        "--fail-under",
        type=float,
        default=None,
        help="Exit non-zero if any corpus metric is below this threshold",
    )
    parser.add_argument(
        "--skip-preflight",
        action="store_true",
        help="Skip Gemini LLM/embedding preflight (not recommended)",
    )
    parser.add_argument(
        "--allow-nan",
        action="store_true",
        help="Exit 0 even if all samples failed (default: exit 1 on NaN/null corpus)",
    )
    args = parser.parse_args()

    export_dir: Path = args.export_dir
    out_dir: Path = args.out_dir or (export_dir.parent / "ragas")
    out_dir.mkdir(parents=True, exist_ok=True)

    claims_path = export_dir / "ragas_claims.jsonl"
    structured_path = export_dir / "ragas_structured.jsonl"
    publish_path = export_dir / "ragas_publish.jsonl"
    n_claims = len(load_jsonl(claims_path))
    n_structured = len(load_jsonl(structured_path))
    n_publish = len(load_jsonl(publish_path))
    print(
        f"Export: claims={n_claims} structured={n_structured} "
        f"publish={n_publish} dir={export_dir}"
    )
    if n_claims < 20 or n_structured < 20:
        print(
            "WARN: export is smaller than full golden set (~40 docs / ~80 claims).\n"
            "      'Full Ragas' only scores what Java already exported.\n"
            "      Re-run:  mvn test -Pevaluation-benchmark   then run-ragas-eval again.",
            file=sys.stderr,
        )
    if n_publish == 0:
        print(
            "WARN: ragas_publish.jsonl missing/empty — post-Publish Ragas track will be skipped.\n"
            "      Re-run Java evaluation-benchmark, or:\n"
            "      python benchmark-eval/build_publish_export.py --synthetic-from-map --force",
            file=sys.stderr,
        )

    metric_names = {m.strip() for m in args.metrics.split(",") if m.strip()}
    # Expand "publish" alias into the three official Ragas publish metrics
    if "publish" in metric_names:
        metric_names |= {"publish_faithfulness", "answer_relevancy", "context_recall"}
        metric_names.discard("publish")

    needs_llm = bool(
        metric_names
        & {
            "faithfulness",
            "answer_correctness",
            "publish_faithfulness",
            "answer_relevancy",
            "context_recall",
        }
    )
    llm = embeddings = llm_model = emb_model = None
    if needs_llm:
        llm, embeddings, llm_model, emb_model = configure_llm()
        if not args.skip_preflight:
            preflight_llm(llm, embeddings, llm_model)
    else:
        llm_model = emb_model = "n/a"

    from datetime import datetime, timezone

    corpus: dict[str, Any] = {}
    sample_counts: dict[str, int] = {}
    valid_counts: dict[str, int] = {}
    details: dict[str, Any] = {}

    if "faithfulness" in metric_names:
        from ragas.metrics import faithfulness
        claim_rows = load_jsonl(claims_path)
        ds, doc_ids = to_ragas_claims_dataset(
            claim_rows,
            args.max_claims_per_doc,
            args.max_context_chars,
            args.limit,
        )
        if ds is None:
            print("WARN: no claim samples — skip faithfulness", file=sys.stderr)
        else:
            print(f"Running faithfulness on {len(doc_ids)} claim samples…")
            result = run_metrics(
                ds, [faithfulness], llm, embeddings,
                max_workers=args.max_workers, timeout=args.timeout,
            )
            parsed = result_to_dict(result)
            details["faithfulness"] = {k: v for k, v in parsed.items() if k != "per_sample"}
            sample_counts["faithfulness"] = len(doc_ids)
            score = extract_metric(parsed, "faithfulness")
            corpus["faithfulness"] = score
            valid_counts["faithfulness"] = (parsed.get("valid_sample_counts") or {}).get(
                "faithfulness", 0 if score is None else len(doc_ids)
            )
            if score is None:
                print(
                    "ERROR: faithfulness produced no valid scores "
                    "(TimeoutError / API errors on all samples).",
                    file=sys.stderr,
                )

    if "answer_correctness" in metric_names:
        from ragas.metrics import answer_correctness

        structured_rows = load_jsonl(structured_path)
        ds, doc_ids = to_ragas_structured_dataset(
            structured_rows,
            args.max_context_chars,
            args.max_json_chars,
            args.limit,
        )
        if ds is None:
            print("WARN: no structured samples — skip answer_correctness", file=sys.stderr)
        else:
            print(
                f"Running answer_correctness on {len(doc_ids)} documents… "
                "(diagnostic only for MAP JSON — not used as release gate)"
            )
            result = run_metrics(
                ds, [answer_correctness], llm, embeddings,
                max_workers=args.max_workers, timeout=args.timeout,
            )
            parsed = result_to_dict(result)
            details["answer_correctness"] = {k: v for k, v in parsed.items() if k != "per_sample"}
            sample_counts["answer_correctness"] = len(doc_ids)
            score = extract_metric(parsed, "answer_correctness")
            corpus["answer_correctness"] = score
            valid_counts["answer_correctness"] = (parsed.get("valid_sample_counts") or {}).get(
                "answer_correctness", 0 if score is None else len(doc_ids)
            )
            if score is None:
                print(
                    "ERROR: answer_correctness produced no valid scores "
                    "(TimeoutError / API errors on all samples).",
                    file=sys.stderr,
                )

    # MAP extraction F1 — correct metric for structured JSONL (offline, no LLM)
    if "map_extraction_f1" in metric_names or "structured" in metric_names:
        from structured_extraction_metrics import evaluate_structured_export

        print("Computing MAP structured extraction F1 (entities/concepts, offline)…")
        map_result = evaluate_structured_export(export_dir)
        map_f1 = (map_result.get("corpus") or {}).get("structuredExtractionF1")
        corpus["structuredExtractionF1"] = map_f1
        sample_counts["structuredExtractionF1"] = (map_result.get("sample_counts") or {}).get(
            "structuredExtractionF1", 0
        )
        valid_counts["structuredExtractionF1"] = sample_counts["structuredExtractionF1"]
        details["map_extraction_f1"] = {
            "corpus": map_result.get("corpus"),
            "sample_counts": map_result.get("sample_counts"),
            "description": map_result.get("description"),
        }
        map_path = out_dir / "map-extraction-metrics.json"
        map_path.write_text(json.dumps(map_result, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"  structuredExtractionF1={map_f1} → {map_path}")

    # --- Post-Publish Markdown WikiPage track ---
    publish_metric_flags = metric_names & {
        "publish_faithfulness",
        "answer_relevancy",
        "context_recall",
    }
    if publish_metric_flags:
        from ragas.metrics import faithfulness as ragas_faithfulness
        from ragas.metrics import answer_relevancy, context_recall

        publish_rows = load_jsonl(publish_path)
        ds, doc_ids, page_slugs = to_ragas_publish_dataset(
            publish_rows,
            args.max_context_chars,
            args.max_markdown_chars,
            args.limit,
        )
        if ds is None:
            print("WARN: no publish samples — skip post-Publish Ragas metrics", file=sys.stderr)
        else:
            metrics_to_run = []
            name_map = []  # (corpus_key, ragas metric object name hints)
            if "publish_faithfulness" in publish_metric_flags:
                metrics_to_run.append(ragas_faithfulness)
                name_map.append("publish_faithfulness")
            if "answer_relevancy" in publish_metric_flags:
                metrics_to_run.append(answer_relevancy)
                name_map.append("answer_relevancy")
            if "context_recall" in publish_metric_flags:
                metrics_to_run.append(context_recall)
                name_map.append("context_recall")

            print(
                f"Running post-Publish Ragas on {len(doc_ids)} wiki pages: "
                f"{', '.join(name_map)}…"
            )
            result = run_metrics(
                ds,
                metrics_to_run,
                llm,
                embeddings,
                max_workers=args.max_workers,
                timeout=args.timeout,
            )
            parsed = result_to_dict(result)
            details["publish"] = {
                k: v for k, v in parsed.items() if k != "per_sample"
            }
            details["publish"]["document_ids"] = doc_ids
            details["publish"]["page_slugs"] = page_slugs

            # faithfulness on publish track is stored as publish_faithfulness
            faith_score = extract_metric(parsed, "faithfulness")
            if "publish_faithfulness" in name_map:
                corpus["publish_faithfulness"] = faith_score
                sample_counts["publish_faithfulness"] = len(doc_ids)
                valid_counts["publish_faithfulness"] = (
                    parsed.get("valid_sample_counts") or {}
                ).get("faithfulness", 0 if faith_score is None else len(doc_ids))
                if faith_score is None:
                    print(
                        "ERROR: publish_faithfulness produced no valid scores.",
                        file=sys.stderr,
                    )

            ar_score = extract_metric(parsed, "answer_relevancy", "answer_relevance")
            if "answer_relevancy" in name_map:
                corpus["answer_relevancy"] = ar_score
                sample_counts["answer_relevancy"] = len(doc_ids)
                valid_counts["answer_relevancy"] = (
                    parsed.get("valid_sample_counts") or {}
                ).get(
                    "answer_relevancy",
                    (parsed.get("valid_sample_counts") or {}).get(
                        "answer_relevance", 0 if ar_score is None else len(doc_ids)
                    ),
                )

            cr_score = extract_metric(parsed, "context_recall")
            if "context_recall" in name_map:
                corpus["context_recall"] = cr_score
                sample_counts["context_recall"] = len(doc_ids)
                valid_counts["context_recall"] = (
                    parsed.get("valid_sample_counts") or {}
                ).get("context_recall", 0 if cr_score is None else len(doc_ids))

    # Offline post-Publish deterministic metrics (no LLM)
    if not args.skip_publish_offline:
        from publish_wiki_metrics import evaluate_publish_export

        print("Computing offline post-Publish wiki metrics…")
        pub_offline = evaluate_publish_export(export_dir)
        pub_corpus = pub_offline.get("corpus") or {}
        for key in (
            "topicCoverage",
            "forbiddenHallucinationRate",
            "publishLinkF1",
        ):
            if key in pub_corpus:
                corpus[key] = pub_corpus.get(key)
                sample_counts[key] = (pub_offline.get("sample_counts") or {}).get(
                    "publish", 0
                )
                valid_counts[key] = sample_counts[key]
        details["publish_offline"] = {
            "corpus": pub_corpus,
            "sample_counts": pub_offline.get("sample_counts"),
            "description": pub_offline.get("description") or pub_offline.get("note"),
        }
        pub_path = out_dir / "publish-wiki-metrics.json"
        pub_path.write_text(
            json.dumps(pub_offline, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        print(f"  publish offline corpus={pub_corpus} → {pub_path}")

    # Gates for null: claim faith + map F1 + publish faith (if scored)
    gate_keys = [
        k
        for k in ("faithfulness", "structuredExtractionF1", "publish_faithfulness")
        if k in corpus
    ]
    if not gate_keys:
        gate_keys = list(corpus.keys())

    payload = {
        "metric_source": "ragas_official+map_extraction_f1+publish",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "export_dir": str(export_dir).replace("\\", "/"),
        "llm_model": llm_model,
        "embedding_model": emb_model,
        "corpus": corpus,
        "sample_counts": sample_counts,
        "valid_sample_counts": valid_counts,
        "details": details,
        "status": (
            "ok"
            if gate_keys and all(corpus.get(k) is not None for k in gate_keys)
            else "failed"
        ),
        "notes": {
            "answer_correctness": "diagnostic only for MAP pipelines",
            "structuredExtractionF1": "primary extraction metric on MAP export",
            "faithfulness": "primary MAP claim metric (Ragas official)",
            "publish_faithfulness": "primary post-Publish WikiPage faithfulness",
            "answer_relevancy": "post-Publish topic-conditioned relevancy",
            "context_recall": "post-Publish GT claims/topics covered by source contexts",
            "topicCoverage": "offline: expected topics mentioned in wiki markdown",
            "forbiddenHallucinationRate": "offline: fraction of pages with forbidden terms",
            "publishLinkF1": "offline: [[wikilinks]] F1 when expected_links present",
        },
    }

    metrics_path = out_dir / "ragas-metrics.json"
    metrics_path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False, allow_nan=False),
        encoding="utf-8",
    )
    write_markdown(out_dir / "ragas-report.md", payload)

    print(f"Wrote {metrics_path}")
    print(f"Corpus: {json.dumps(corpus, indent=2)}")
    print(f"Valid samples: {json.dumps(valid_counts, indent=2)}")

    any_null = (not gate_keys) or any(corpus.get(k) is None for k in gate_keys)
    if any_null and not args.allow_nan:
        print(
            "\nFAILED: Ragas corpus has null scores (all jobs timed out or errored).\n"
            "This is almost always Gemini free-tier 429 RESOURCE_EXHAUSTED.\n"
            "Do NOT trust hybrid report until corpus has real numbers.\n"
            "Try: wait / change RAGAS_LLM_MODEL / use -Limit 4 after preflight OK.\n",
            file=sys.stderr,
        )
        return 2

    if args.fail_under is not None:
        for name, val in corpus.items():
            if isinstance(val, (int, float)) and val < args.fail_under:
                print(f"FAIL: {name}={val} < {args.fail_under}", file=sys.stderr)
                return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
