"""One-shot ``auto`` workflow.

Feed the input(s) and let it decide: build candidate pairs, score them by the
unsupervised similarity proxy (mean feature value, in ``[0, 1]``), and check
whether that score distribution is **cleanly separated** — a wide empty valley
between a low (non-match) and high (match) mode.

  * Cleanly separated  → threshold at the valley, cluster, write outputs, finish.
                         No labels, no training needed.
  * Ambiguous          → fall back to labelling (auto-detected LLM endpoint, else
                         the human CLI), then train + cluster.

This is for dedup / linkage — Zingg's actual strength. It deliberately does NOT
attempt the CVE-cluster↔ops-ticket coverage mapping, which needs a bipartite
retrieve→rerank→judge pipeline rather than a connected-components partition
(see the cve_coverage package).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from . import active_learning, features, io, pairs as pair_mod, persistence, phases
from .clustering import connected_components
from .config_loader import Loaded
from .schema import ZinggConf
from .zingg import Zingg


@dataclass
class Separation:
    separable: bool
    threshold: float
    gap: float
    n_low: int
    n_high: int
    n_uncertain: int
    reason: str


def assess_separability(
    proxies: np.ndarray,
    min_gap: float = 0.15,
    central: tuple[float, float] = (0.15, 0.85),
    uncertain_band: float = 0.1,
) -> Separation:
    """Decide whether the proxy scores split cleanly into match / non-match."""
    lo, hi = central
    p = np.sort(np.asarray(proxies, dtype=float))
    n = p.size
    if n == 0:
        return Separation(True, 0.5, 1.0, 0, 0, 0, "no candidate pairs")
    if p.max() <= lo:
        return Separation(True, lo, 1.0, n, 0, 0, "all pairs clearly dissimilar (no matches)")
    if p.min() >= hi:
        return Separation(True, hi, 1.0, 0, n, 0, "all pairs clearly similar (all match)")

    # Largest gap between consecutive scores whose midpoint sits in the central
    # window — the candidate decision valley.
    best_gap, best_thr = 0.0, 0.5
    for a, b in zip(p[:-1], p[1:]):
        mid = (a + b) / 2.0
        if lo <= mid <= hi and (b - a) > best_gap:
            best_gap, best_thr = b - a, mid

    n_low = int((p < best_thr).sum())
    n_high = int((p >= best_thr).sum())
    n_uncertain = int(((p >= best_thr - uncertain_band) & (p <= best_thr + uncertain_band)).sum())
    if best_gap >= min_gap:
        return Separation(True, best_thr, best_gap, n_low, n_high, n_uncertain,
                          f"clean valley (gap={best_gap:.2f}) at {best_thr:.2f}")
    return Separation(False, best_thr, best_gap, n_low, n_high, n_uncertain,
                      f"no clean valley (largest central gap={best_gap:.2f} < {min_gap})")


def _ensure_id(df: pd.DataFrame, cfg: ZinggConf) -> pd.DataFrame:
    if cfg.id_col in df.columns:
        return df.reset_index(drop=True)
    out = df.reset_index(drop=True).copy()
    out[cfg.id_col] = range(len(out))
    return out


def run(
    loaded: Loaded,
    model_dir,
    labeller_factory,
    min_gap: float = 0.15,
    max_labels: int = 60,
    log=print,
) -> Separation:
    """Run the auto workflow; returns the separability verdict for inspection."""
    cfg = loaded.cfg
    df = io.read_inputs(loaded)
    with_ids = _ensure_id(df, cfg)

    pairs = pair_mod.cold_start_pairs(with_ids, cfg, cross_source_only=loaded.link)
    featured = features.add_features(pairs, cfg)
    proxies = featured[features.FEATURE_COL].map(active_learning._proxy).to_numpy() \
        if not featured.empty else np.array([])

    sep = assess_separability(proxies, min_gap=min_gap)
    log(f"auto: {len(featured)} candidate pairs; {sep.reason}; "
        f"low={sep.n_low} high={sep.n_high} uncertain={sep.n_uncertain}")

    if sep.separable:
        log(f"auto: unambiguous → thresholding at {sep.threshold:.2f}, no labelling needed")
        scored = featured.copy()
        scored[cfg.score_col] = proxies
        scored[cfg.prediction_col] = (proxies >= sep.threshold).astype(float)
        clustered = connected_components(scored, with_ids[cfg.id_col].tolist(), cfg)
        out = with_ids.merge(clustered, on=cfg.id_col, how="left")
        io.write_outputs(out, loaded.outputs)
        log(f"auto: wrote {len(out)} rows to {', '.join(o.path for o in loaded.outputs)}")
        return sep

    labeller, kind = labeller_factory()
    log(f"auto: ambiguous → labelling the uncertain pairs with the {kind} labeller")
    n = phases.find_and_label(loaded, model_dir, labeller, max_labels=max_labels)
    if n == 0:
        log("auto: no labels collected — leaving outputs unwritten")
        return sep
    phases.train(loaded, model_dir)
    phases.match_phase(loaded, model_dir)
    log(f"auto: labelled {n} pairs, trained, and wrote "
        f"{', '.join(o.path for o in loaded.outputs)}")
    return sep
