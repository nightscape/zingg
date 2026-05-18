"""Command-line entry point for the CVE-coverage pipeline.

    coverage run    [--conf C] [name=path ...] [--only id1,id2] [model knobs]
    coverage score  [--stat S] [--candidates C] [--members M]

The *schema* and the *DAG* live in the JSON config (`--conf`, default
`config.json`): named sources carry their `fieldMapping`, a `pipeline` section
wires them through `cluster` / `link` steps (see config.json). The CLI only
rebinds a source name to a fresh file path — `coverage run statistix=Stat_2026-06-16.csv`
— because that is the part that changes every run (timestamped CSVs). `--only`
runs a slice of the DAG; omit it to run the whole thing.

Model flags (`--base/--judge-model/--device/...`) track the serving backend, not
the pipeline shape, and fall back to env vars (OPENAI_BASE, EMBED_MODEL, ...), so
the historical `OPENAI_BASE=... uv run ...` invocation still works.
"""

from __future__ import annotations

import argparse
import os

from .config import ModelConfig

# The project root (parent of the package) — where the default config.json and
# example CSVs live; the scorer's stat CSV defaults here too.
DATA_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_CONF = os.path.join(DATA_DIR, "config.json")


def _env(name: str, default, cast=str):
    v = os.environ.get(name)
    return cast(v) if v not in (None, "") else default


def _parse_header(kv: str) -> tuple[str, str]:
    if "=" not in kv:
        raise argparse.ArgumentTypeError(f"--header must be KEY=VALUE, got {kv!r}")
    k, v = kv.split("=", 1)
    return k.strip(), v


def _add_model_args(ap: argparse.ArgumentParser) -> None:
    d = ModelConfig()
    g = ap.add_argument_group("models (link steps)")
    g.add_argument("--base", default=_env("OPENAI_BASE", d.base),
                   help="judge chat endpoint (Copilot / OpenAI / LocalAI :8080 / LM Studio :1234)")
    g.add_argument("--embed-model", default=_env("EMBED_MODEL", d.embed_model),
                   help="local sentence-transformers embedding model id")
    g.add_argument("--rerank-model", default=_env("RERANK_MODEL", d.rerank_model))
    g.add_argument("--judge-model", default=_env("JUDGE_MODEL", d.judge_model))
    g.add_argument("--device", default=_env("DEVICE", d.device),
                   help="embed/rerank device (mps/cuda/cpu); autodetected if unset. MUST NOT be cpu in practice.")
    g.add_argument("--api-key", default=_env("OPENAI_API_KEY", d.api_key),
                   help="judge bearer token (env OPENAI_API_KEY); omit for LocalAI/LM Studio")
    g.add_argument("--header", dest="headers", action="append", type=_parse_header, default=None,
                   metavar="KEY=VALUE",
                   help="extra judge HTTP header (repeatable); e.g. for GitHub Copilot")
    g.add_argument("--judge-disable-thinking", action="store_true",
                   help="send LocalAI/LM-Studio enable_thinking switches (do NOT use on strict gateways)")
    g.add_argument("--topn", type=int, default=_env("TOPN", d.topn, int),
                   help="embedding pre-filter width (a link step may override in config)")
    g.add_argument("--topk", type=int, default=_env("TOPK", d.topk, int),
                   help="candidates reranked + judged per cluster (a link step may override in config)")
    g.add_argument("--judge-max-tokens", type=int, default=_env("JUDGE_MAXTOK", d.judge_max_tokens, int))
    g.add_argument("--judge-temp", type=float, default=_env("JUDGE_TEMP", d.judge_temp, float))
    g.add_argument("--judge-seed", type=int, default=_env("JUDGE_SEED", d.judge_seed, int))


def _model_config(args) -> ModelConfig:
    return ModelConfig(
        base=args.base, embed_model=args.embed_model, rerank_model=args.rerank_model,
        judge_model=args.judge_model, device=args.device,
        api_key=args.api_key, extra_headers=dict(args.headers) if args.headers else None,
        topn=args.topn, topk=args.topk, judge_max_tokens=args.judge_max_tokens,
        judge_temp=args.judge_temp, judge_seed=args.judge_seed,
        judge_disable_thinking=args.judge_disable_thinking)


def _bindings(args) -> dict[str, str]:
    """name=path positionals -> {source name: path}."""
    binds: dict[str, str] = {}
    for tok in args.bind:
        assert "=" in tok, f"binding must be name=path, got {tok!r}"
        name, path = tok.split("=", 1)
        binds[name] = path
    return binds


def cmd_run(args) -> None:
    from .config import load_pipeline
    from .runner import run_pipeline

    pipe = load_pipeline(args.conf, _bindings(args))
    only = [s.strip() for s in args.only.split(",") if s.strip()] if args.only else None
    run_pipeline(pipe, _model_config(args), out_dir=args.out_dir, only=only,
                 dry_run=args.dry_run, cluster_default=args.cluster,
                 cap_default=args.cap, block_default=args.block_strategy)


def cmd_score(args) -> None:
    from .scoring import score
    score(args.stat, args.candidates, args.members)


def build_parser() -> argparse.ArgumentParser:
    from .pipeline import CANDIDATES_CSV, MEMBERS_CSV

    ap = argparse.ArgumentParser(prog="coverage", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    run = sub.add_parser("run", help="run the config's pipeline DAG; write candidates/map/members CSVs")
    run.add_argument("bind", nargs="*", metavar="name=path",
                     help="rebind a config source name to a CSV path (overrides data[].props.path)")
    run.add_argument("--conf", default=DEFAULT_CONF, help="Zingg-style JSON config (schema + pipeline DAG)")
    run.add_argument("--only", default=None, metavar="id1,id2",
                     help="run only these pipeline step ids (upstream clusters loaded from cache)")
    run.add_argument("--out-dir", default=DATA_DIR, help="output directory for CSVs + cluster intermediates")
    run.add_argument("--cluster", choices=["deterministic", "zingg"], default="deterministic",
                     help="default Stage-1 strategy for cluster steps without one in config")
    run.add_argument("--block-strategy", choices=["tree", "canopies"], default="tree",
                     help="zingg only: default candidate generation")
    run.add_argument("--cap", type=int, default=8, help="zingg only: default rows/cluster for training (0 = all)")
    run.add_argument("--dry-run", action="store_true",
                     help="cluster steps only: write membership + intermediates, skip link steps")
    _add_model_args(run)
    run.set_defaults(func=cmd_run)

    sc = sub.add_parser("score", help="partition-independent coverage metric vs hand labels")
    sc.add_argument("--stat", default=os.path.join(DATA_DIR, "StatAnon.csv"))
    sc.add_argument("--candidates", default=os.path.join(DATA_DIR, CANDIDATES_CSV))
    sc.add_argument("--members", default=os.path.join(DATA_DIR, MEMBERS_CSV))
    sc.set_defaults(func=cmd_score)
    return ap


def main(argv=None) -> None:
    args = build_parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
