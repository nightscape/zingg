"""Command-line entry point for zingg-py.

Two high-level commands cover the usual flow:

    zingg-py learn   --conf config.json [--labeller human|llm] [--batch]
    zingg-py cluster --conf config.json

`learn` reads the input(s), labels candidate pairs (adaptive online by default,
or a fixed batch with --batch), trains the model, and persists it to the model
dir. `cluster` loads that model, clusters/links the data, and writes the outputs
declared in the config.

A low-level `phase` command exposes the individual Scala phases for parity:

    zingg-py phase --phase findTrainingData|label|findAndLabel|train|match|link|trainMatch --conf config.json
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import replace

import pandas as pd

from . import auto, config_loader, persistence, phases
from .cli_labeller import CliLabeller
from .labeller import OracleLabeller, OraclePairLabeller
from .llm_labeller import LlmLabeller, endpoint_reachable
from .phase import parse as parse_phase

_DEFAULT_MODEL_DIR = "models/1"


def _load(args):
    """Load the config, applying ``--dedup-source`` if given (forces dedup mode
    and canonicalises the one named source)."""
    loaded = config_loader.load(args.conf)
    name = getattr(args, "dedup_source", None)
    if name is None:
        return loaded
    names = [i.name for i in loaded.inputs]
    if name not in names:
        raise SystemExit(f"--dedup-source '{name}' not found; sources: {', '.join(names)}")
    return replace(loaded, link=False, dedup_source=name)


def _oracle_from_file(path: str):
    """Build an oracle from a ground-truth CSV. A `cluster` column → partition
    oracle (`id,cluster`); otherwise the first two columns are matching id pairs."""
    gt = pd.read_csv(path, dtype=str)
    if "cluster" in gt.columns:
        return OracleLabeller(dict(zip(gt["id"], gt["cluster"])))
    a, b = gt.columns[:2]
    return OraclePairLabeller(set(zip(gt[a], gt[b])))


def _make_labeller(kind: str, truth: str | None = None):
    """Return ``(labeller, kind)``. ``auto`` picks the LLM if its endpoint is
    reachable, otherwise the human CLI."""
    if kind == "oracle":
        if not truth:
            raise SystemExit("--labeller oracle requires --truth <ground-truth.csv>")
        return _oracle_from_file(truth), "oracle"
    if kind == "auto":
        if endpoint_reachable():
            return LlmLabeller(), "llm"
        return CliLabeller(), "human"
    if kind == "llm":
        return LlmLabeller(), "llm"
    return CliLabeller(), "human"


def _cmd_learn(args) -> int:
    loaded = _load(args)
    labeller, _ = _make_labeller(args.labeller, args.truth)
    if args.batch:
        phases.find_training_data(loaded, args.model_dir)
        phases.label(loaded, args.model_dir, labeller)
        marked = persistence.read_frame(args.model_dir, persistence.MARKED)
        n = len(marked)
    else:
        n = phases.find_and_label(loaded, args.model_dir, labeller)
    if n == 0:
        print("No labels collected — nothing to train. Re-run and label some pairs.", file=sys.stderr)
        return 1
    phases.train(loaded, args.model_dir)
    print(f"Labelled {n} pairs and trained model → {args.model_dir}/")
    return 0


def _cmd_cluster(args) -> int:
    loaded = _load(args)
    phases.match_phase(loaded, args.model_dir)
    paths = ", ".join(o.path for o in loaded.outputs)
    print(f"Clustered {'(link mode) ' if loaded.link else ''}→ {paths}")
    return 0


def _cmd_phase(args) -> int:
    loaded = _load(args)
    labeller, _ = _make_labeller(args.labeller, args.truth)
    phases.run(parse_phase(args.phase), loaded, args.model_dir, labeller)
    print(f"Phase '{args.phase}' complete.")
    return 0


def _cmd_auto(args) -> int:
    loaded = _load(args)
    auto.run(loaded, args.model_dir, lambda: _make_labeller(args.labeller, args.truth),
             min_gap=args.min_gap, max_labels=args.max_labels)
    return 0


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="zingg-py", description="Zingg record-linkage (Python port).")
    sub = p.add_subparsers(dest="command", required=True)

    def add_common(sp):
        sp.add_argument("--conf", required=True, help="JSON config (fields, data, output, link)")
        sp.add_argument("--model-dir", default=_DEFAULT_MODEL_DIR, help=f"model dir (default {_DEFAULT_MODEL_DIR})")
        sp.add_argument("--dedup-source", metavar="NAME",
                        help="dedup a single named source: canonicalise just that input "
                             "(applies its fieldMapping) and cluster within it")

    learn = sub.add_parser("learn", help="label candidate pairs and train a model")
    add_common(learn)
    learn.add_argument("--labeller", choices=["human", "llm", "oracle"], default="human")
    learn.add_argument("--truth", help="ground-truth CSV for --labeller oracle (id,cluster or id-pairs)")
    learn.add_argument("--batch", action="store_true",
                       help="fixed-batch labelling (findTrainingData+label) instead of adaptive online")
    learn.set_defaults(func=_cmd_learn)

    cluster = sub.add_parser("cluster", help="cluster/link the data with the trained model")
    add_common(cluster)
    cluster.set_defaults(func=_cmd_cluster)

    au = sub.add_parser("auto", help="one-shot: finish if unambiguous, else label + train + cluster")
    add_common(au)
    au.add_argument("--labeller", choices=["auto", "human", "llm", "oracle"], default="auto",
                    help="fallback labeller when ambiguous (auto = LLM if reachable, else human)")
    au.add_argument("--truth", help="ground-truth CSV for --labeller oracle")
    au.add_argument("--min-gap", type=float, default=0.15,
                    help="min proxy-score valley width to count as unambiguous (default 0.15)")
    au.add_argument("--max-labels", type=int, default=60,
                    help="cap on labels in the ambiguous fallback (default 60)")
    au.set_defaults(func=_cmd_auto)

    ph = sub.add_parser("phase", help="run a single low-level phase (Scala parity)")
    add_common(ph)
    ph.add_argument("--phase", required=True)
    ph.add_argument("--labeller", choices=["human", "llm", "oracle"], default="human")
    ph.add_argument("--truth", help="ground-truth CSV for --labeller oracle")
    ph.set_defaults(func=_cmd_phase)

    return p


def main(argv=None) -> int:
    args = _build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
