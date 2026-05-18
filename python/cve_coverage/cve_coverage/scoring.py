"""Partition-independent coverage metric.

Instead of joining on the cluster id (which mis-keys when the clustering's
partition differs from the regex one — e.g. zingg splitting CVE-2017-68001 into
Oracle + Azul, reading as a phantom false-positive), this re-aggregates the
pipeline's clusters back to the TRUE CVE/category via ticket membership, then
asks per true cluster whether its linked ops match the strict gold. So the
metric only measures "did each CVE ticket end up linked to the ops ticket(s)
that remediate its vulnerability?" — independent of how tickets were partitioned.

The STRICT / RELATED / EXTRA hand labels live in `ground_truth_spec.py` inside
this package.
"""

from __future__ import annotations

import csv
import os
from collections import defaultdict

from .ground_truth_spec import EXTRA, RELATED, STRICT

from .text import cluster_key

STRICT_ALL = set(STRICT) | {(c, k) for c, k, _ in EXTRA}


def _load(path: str) -> list[dict]:
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def _true_cluster(row: dict) -> str:
    return cluster_key(row.get("Summary") or "", row.get("Custom field (Vulnerability Title)") or "")


def _prf(tp: int, fp: int, fn: int) -> tuple[float, float, float]:
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    return p, r, (2 * p * r / (p + r) if p + r else 0.0)


def score(stat_path: str, candidates_path: str, members_path: str) -> None:
    # ticket -> true cluster (regex), and tickets grouped by true cluster
    tcl = {}
    for r in _load(stat_path):
        g = _true_cluster(r)
        if g:
            tcl[r["Issue key"]] = g
    g_tickets = defaultdict(list)
    for t, g in tcl.items():
        g_tickets[g].append(t)

    ticket_zc = {m["ticket_key"]: m["cluster"] for m in _load(members_path)}

    # pipeline cluster -> judged-match ops (all, and rank-1 only)
    zc_match, zc_rank1 = defaultdict(set), defaultdict(set)
    for r in _load(candidates_path):
        if r["verdict"] == "match":
            zc_match[r["cluster"]].add(r["ops_key"])
            if int(r["rank"]) == 1:
                zc_rank1[r["cluster"]].add(r["ops_key"])

    gold = defaultdict(set)
    for g, k in STRICT_ALL:
        gold[g].add(k)
    related = defaultdict(set)
    for g, k in RELATED:
        related[g].add(k)

    true_clusters = sorted(g_tickets)

    def pred_ops(g, zc_map):
        out = set()
        for t in g_tickets[g]:
            zc = ticket_zc.get(t)
            if zc:
                out |= zc_map.get(zc, set())
        return out

    # ── 1. ops-assignment correctness: (true cluster, ops) pred vs strict gold ──
    for label, zc_map in (("all judged matches", zc_match), ("rank-1 gated", zc_rank1)):
        tp = fp = fn = 0
        fps, fns = [], []
        for g in true_clusters:
            p, golds = pred_ops(g, zc_map), gold.get(g, set())
            tp += len(p & golds)
            for k in p - golds:
                fp += 1
                fps.append((g, k, "related" if k in related.get(g, set()) else "wrong"))
            for k in golds - p:
                fn += 1
                fns.append((g, k))
        pr, rc, f1 = _prf(tp, fp, fn)
        print(f"OPS-ASSIGNMENT ({label}): are CVE tickets linked to the right ops ticket?")
        print(f"  pairs TP={tp} FP={fp} FN={fn}   precision={pr:.2f} recall={rc:.2f} F1={f1:.2f}")
        if label == "rank-1 gated":
            print(f"    FPs: {[(g[:22], k, why) for g, k, why in fps]}")
            print(f"    FNs: {[(g[:22], k) for g, k in fns]}")
        print()

    # ── 2. cluster coverage (covered? = rank-1 match), robust to the partition ──
    strict_cov = {g for g in true_clusters if gold.get(g)}
    covered = {g for g in true_clusters if pred_ops(g, zc_rank1)}
    covered_ok = {g for g in true_clusters if pred_ops(g, zc_rank1) & gold.get(g, set())}
    tp, fp, fn = len(covered & strict_cov), len(covered - strict_cov), len(strict_cov - covered)
    p1, r1, f1c = _prf(tp, fp, fn)
    tp2, fp2, fn2 = len(covered_ok), len(covered - covered_ok), len(strict_cov - covered_ok)
    p2, r2, f2 = _prf(tp2, fp2, fn2)
    print(f"CLUSTER COVERAGE over {len(true_clusters)} TRUE clusters (strict-covered={len(strict_cov)}):")
    print(f"  covered? vs strict-covered:        P/R/F1 = {p1:.2f}/{r1:.2f}/{f1c:.2f}  covered={len(covered)}")
    print(f"  covered by a CORRECT ops vs strict: P/R/F1 = {p2:.2f}/{r2:.2f}/{f2:.2f}")
    print(f"  cluster FPs (covered, not strict): {sorted(covered - strict_cov)}")
    print(f"  cluster FNs (strict, not covered): {sorted(strict_cov - covered)}")
