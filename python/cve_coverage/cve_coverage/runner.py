"""Execute a `Pipeline` DAG: cluster nodes group a source's tickets, link nodes
match the accumulated clusters against an ops source via retrieve->rerank->judge.

Nodes reference each other by id; the config order is already topological (the
loader validates every input is a source or an already-produced step). `--only`
runs a slice of the DAG — a selected link node loads any upstream cluster node it
didn't run this time from the cached intermediate (`cluster_<id>.json`), which
carries the title/desc counters so the reranker query (`canonical`) is rebuilt
intact, not just the ids.
"""

from __future__ import annotations

import json
import os

from .config import ModelConfig, Pipeline, Step, normalize_rows
from .pipeline import MEMBERS_CSV, run_linkage, write_members
from .types import Cluster


def _intermediate_path(out_dir: str, step_id: str) -> str:
    return os.path.join(out_dir, f"cluster_{step_id}.json")


def _save_clusters(out_dir: str, step_id: str, clusters: list[Cluster]) -> None:
    with open(_intermediate_path(out_dir, step_id), "w") as f:
        json.dump([c.to_dict() for c in clusters], f)


def _load_clusters(out_dir: str, step_id: str) -> list[Cluster]:
    path = _intermediate_path(out_dir, step_id)
    assert os.path.exists(path), (
        f"link step needs clusters from {step_id!r} but {path} is missing — "
        f"run that cluster step first (or drop it from --only)")
    with open(path) as f:
        return [Cluster.from_dict(d) for d in json.load(f)]


def _run_cluster(step: Step, pipe: Pipeline, *, cluster_default: str,
                 cap_default: int, block_default: str) -> list[Cluster]:
    from .clustering import make_clusterer

    source = pipe.sources[step.inputs[0]]
    strategy = step.params.get("strategy", cluster_default)
    rows = normalize_rows(source)
    print(f"[{step.id}] cluster ({strategy}): {len(rows)} rows from {source.name!r} "
          f"({source.path}) ...")
    clusters = make_clusterer(
        strategy,
        cap=step.params.get("cap", cap_default),
        block_strategy=step.params.get("block_strategy", block_default),
    ).cluster(rows)
    print(f"[{step.id}] -> {len(clusters)} clusters")
    return clusters


def run_pipeline(pipe: Pipeline, model: ModelConfig, *, out_dir: str,
                 only: list[str] | None = None, dry_run: bool = False,
                 cluster_default: str = "deterministic", cap_default: int = 8,
                 block_default: str = "tree") -> None:
    os.makedirs(out_dir, exist_ok=True)
    by_id = {s.id: s for s in pipe.steps}
    if only:
        for sid in only:
            assert sid in by_id, f"--only {sid!r}: no such step (have {sorted(by_id)})"
    selected = set(only) if only else set(by_id)

    results: dict[str, list[Cluster]] = {}

    # Cluster steps first (config order is topological).
    for step in pipe.steps:
        if step.step == "cluster" and step.id in selected:
            results[step.id] = _run_cluster(
                step, pipe, cluster_default=cluster_default,
                cap_default=cap_default, block_default=block_default)
            _save_clusters(out_dir, step.id, results[step.id])

    if results:  # membership for everything clustered this run (the scorer reads this)
        members = [c for cl in results.values() for c in cl]
        members_path = os.path.join(out_dir, MEMBERS_CSV)
        write_members(members, members_path)
        print(f"wrote membership ({len(members)} clusters) -> {members_path}")

    if dry_run:
        print("\n--dry-run: stopping before link steps (rerank+judge need the model server).")
        return

    link_steps = [s for s in pipe.steps if s.step == "link" and s.id in selected]
    if not link_steps:
        return

    # Resolve every link step's inputs (cheap, and asserts cached clusters exist)
    # BEFORE loading the embedder/reranker, so a missing intermediate fails fast
    # instead of after a multi-GB model download.
    plans = []
    for step in link_steps:
        against = step.params["against"]
        clusters = [c for cid in step.inputs if cid != against
                    for c in (results.get(cid) or _load_clusters(out_dir, cid))]
        limit = step.params.get("cluster_limit", 0)
        plans.append((step, against, clusters[:limit] if limit else clusters))

    from .judge import OpenAIJudge
    from .rerank import CrossEncoderReranker
    from .retrieval import LocalEmbedder

    embedder = LocalEmbedder(model.embed_model, device=model.device)
    reranker = CrossEncoderReranker(model.rerank_model, device=model.device)
    judge = OpenAIJudge(model.base, model.judge_model, api_key=model.api_key,
                        extra_headers=model.extra_headers, max_tokens=model.judge_max_tokens,
                        temperature=model.judge_temp, seed=model.judge_seed,
                        disable_thinking=model.judge_disable_thinking)
    multi = len(link_steps) > 1

    for step, against, clusters in plans:
        vmt = normalize_rows(pipe.sources[against])
        print(f"[{step.id}] link: {len(clusters)} clusters vs {len(vmt)} ops tickets "
              f"from {against!r} ({pipe.sources[against].path}) ...")
        run_linkage(
            clusters, vmt, embedder=embedder, reranker=reranker, judge=judge,
            topn=step.params.get("topn", model.topn),
            topk=step.params.get("topk", model.topk),
            out_dir=out_dir, prefix=f"{step.id}_" if multi else "")
