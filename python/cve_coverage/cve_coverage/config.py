"""Typed configuration for the coverage pipeline.

The *schema* and the *DAG* live in a Zingg-style JSON config (`--conf`): named
`data` sources each carry their own `fieldMapping` (CSV column -> internal field
name), and a `pipeline` section wires those names through `cluster` / `link`
steps. The CLI only ever rebinds a source `name` to a fresh file path (the part
that changes every run because the CSVs are timestamped), so the complicated
wiring is written once.

Internal field names (the keys every stage downstream operates on) are the
`fieldName`s from the config's `fieldDefinition`; `normalize_rows` remaps each
source's raw CSV columns onto them via `fieldMapping`, so no stage hardcodes a
physical column name.

`ModelConfig` (Stage-2 model selection + determinism knobs) stays CLI/env driven
— it tracks the serving backend, not the pipeline shape — so the historical
`OPENAI_BASE=... uv run ...` invocation keeps working.
"""

from __future__ import annotations

import csv
import json
import os
from dataclasses import dataclass, field

# Internal field names the pipeline stages operate on (from fieldDefinition).
F_ID = "issue_key"
F_SUMMARY = "vuln_code"               # CVE-bearing summary used for cluster keying
F_VULN_TITLE = "vulnerability_title"
F_MISCONF = "misconfiguration_desc"


@dataclass
class Source:
    """One named input. `field_mapping` is internal-name -> CSV-column."""

    name: str
    path: str
    fmt: str
    props: dict
    field_mapping: dict


@dataclass
class Step:
    """One DAG node. `inputs` reference source names or upstream step ids;
    `params` holds step-specific knobs (cluster: strategy/cap/block_strategy;
    link: against/topn/topk/cluster_limit)."""

    id: str
    step: str               # "cluster" | "link"
    inputs: list[str]
    params: dict = field(default_factory=dict)


@dataclass
class Pipeline:
    sources: dict[str, Source]
    steps: list[Step]


@dataclass
class ModelConfig:
    """Stage-2 model selection and the determinism knobs that matter.

    Embedder and reranker run locally (sentence-transformers, on `device`); only
    the judge calls `base` (an OpenAI-compatible chat endpoint). For a strict
    gateway (GitHub Copilot, OpenAI) set `api_key` and any `extra_headers`, and
    leave `judge_disable_thinking` False so no non-standard fields are sent. For
    LocalAI / LM Studio set `judge_disable_thinking` True to suppress reasoning.

    The judge MUST run at temperature 0 AND with a fixed seed: LocalAI randomises
    the seed even at temp 0, which flips verdicts on borderline pairs run-to-run.
    `judge_max_tokens` budgets for a reasoning model's thinking trace (~1400 tokens).
    """

    base: str = "http://127.0.0.1:1234/v1"           # judge chat endpoint
    embed_model: str = "Qwen/Qwen3-Embedding-0.6B"   # local sentence-transformers id
    rerank_model: str = "cross-encoder/ettin-reranker-150m-v1"
    judge_model: str = "lfm2-2.6b-exp"
    device: str | None = None                        # embed/rerank device; None = autodetect
    api_key: str | None = None                       # judge: Authorization: Bearer <key>
    extra_headers: dict | None = None                # judge: gateway-specific headers
    topn: int = 50                                   # embedding pre-filter width
    topk: int = 5                                    # candidates reranked + judged
    judge_max_tokens: int = 2048
    judge_temp: float = 0.0
    judge_seed: int = 42
    judge_disable_thinking: bool = False             # LocalAI/LM-Studio thinking-off switches


def _parse_sources(conf: dict, conf_dir: str) -> dict[str, Source]:
    sources = {}
    for d in conf["data"]:
        path = d["props"]["path"]
        if not os.path.isabs(path):       # config paths are relative to the config file
            path = os.path.join(conf_dir, path)
        sources[d["name"]] = Source(
            name=d["name"], path=path, fmt=d["format"],
            props=d["props"], field_mapping=d["fieldMapping"])
    return sources


def _parse_pipeline(conf: dict, sources: dict[str, Source]) -> list[Step]:
    """Read the explicit `pipeline`, or synthesise the default two-node DAG from a
    two-source `link` config (data[0] clustered -> linked against data[1])."""
    if "pipeline" in conf:
        steps = []
        for n in conf["pipeline"]:
            if n["step"] == "cluster":
                inputs = [n["input"]]
                params = {k: n[k] for k in ("strategy", "cap", "block_strategy") if k in n}
            elif n["step"] == "link":
                inputs = list(n["clusters"]) + [n["against"]]
                params = {"against": n["against"],
                          **{k: n[k] for k in ("topn", "topk", "cluster_limit") if k in n}}
            else:
                raise ValueError(f"unknown pipeline step {n['step']!r} (want cluster|link)")
            steps.append(Step(id=n["id"], step=n["step"], inputs=inputs, params=params))
        return steps

    names = list(sources)
    assert len(names) == 2, (
        f"no 'pipeline' in config and {len(names)} sources; the default DAG needs "
        "exactly two (cluster data[0] -> link against data[1])")
    return [
        Step(id="clusters", step="cluster", inputs=[names[0]], params={}),
        Step(id="coverage", step="link", inputs=["clusters", names[1]],
             params={"against": names[1]}),
    ]


def load_pipeline(conf_path: str, bindings: dict[str, str] | None = None) -> Pipeline:
    """Load sources + DAG from a Zingg-style JSON config. `bindings` (name->path)
    override `data[].props.path` so a timestamped filename never touches the config."""
    with open(conf_path) as f:
        conf = json.load(f)
    sources = _parse_sources(conf, os.path.dirname(os.path.abspath(conf_path)))
    for name, path in (bindings or {}).items():    # CLI bindings stay CWD-relative
        assert name in sources, (
            f"--bind {name}={path}: no source named {name!r} in {conf_path} "
            f"(have {sorted(sources)})")
        sources[name].path = path
    steps = _parse_pipeline(conf, sources)
    _validate(sources, steps)
    return Pipeline(sources=sources, steps=steps)


def _validate(sources: dict[str, Source], steps: list[Step]) -> None:
    """Fail fast: every input resolves, ids are unique, the DAG is acyclic."""
    produced: set[str] = set()
    seen_ids: set[str] = set()
    for s in steps:
        assert s.id not in seen_ids, f"duplicate pipeline step id {s.id!r}"
        seen_ids.add(s.id)
        for ref in s.inputs:
            assert ref in sources or ref in produced, (
                f"step {s.id!r} references {ref!r}, which is neither a source "
                f"({sorted(sources)}) nor an already-produced step")
        produced.add(s.id)


def normalize_rows(source: Source) -> list[dict]:
    """Read the CSV and remap raw columns onto internal field names so no stage
    hardcodes a physical column. Fields absent from `field_mapping` are absent."""
    assert source.fmt == "csv", f"source {source.name!r}: only csv is supported, got {source.fmt!r}"
    assert os.path.exists(source.path), f"source {source.name!r}: file not found: {source.path}"
    with open(source.path, newline="") as f:
        raw = list(csv.DictReader(f))
    return [{internal: r.get(col, "") for internal, col in source.field_mapping.items()}
            for r in raw]
