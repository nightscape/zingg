"""Zingg-pipeline evaluation of the CVE coverage task, with two oracles.

A Zingg-based analogue of `coverage_lmstudio.py`, run against the hand-labelled
ground truth. Same two stages as the coverage pipeline, each scored against its
OWN oracle:

  Stage 1  intra-CVE clustering    — deduplicate StatistiX rows into CVE/category
                                     clusters. Oracle: a PARTITION (row -> cluster),
                                     `OracleLabeller`. This is what Zingg is built
                                     for (lexical dedup).
  Stage 2  CVE-cluster -> ops link — link each cluster to the ops ticket that
                                     remediates it. Oracle: a BIPARTITE / N:M set
                                     of (cluster, ops_key) matches,
                                     `OraclePairLabeller`.

Both stages: feed PART of the labelled data via the oracle (train), then measure
what fraction of the REST the pipeline gets right (held-out, median over splits).

Run from python/ (the workspace env has both zingg-py and cve-coverage):
    uv run --extra zingg python cve_coverage/tools/zingg_coverage_eval.py
"""

from __future__ import annotations

import csv
import itertools
import os
import random
import re
from collections import Counter, defaultdict

import numpy as np
import pandas as pd

from zingg_py import features, model as model_mod, pairs as pair_mod
from zingg_py.labeller import OracleLabeller, OraclePairLabeller
from zingg_py.schema import FieldDef, MatchType, ZinggConf
from zingg_py.zingg import Zingg

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.dirname(HERE)            # package root: where the example CSVs live
CVE = re.compile(r"(?i)CVE-\d{4}-\d{3,7}")

# Product-family -> remediation phrasing (from coverage_lmstudio.py): the only
# lexical bridge from a raw CVE title to an ops ticket like "remove obsolete Java".
PRODUCT_HINTS = [
    (re.compile(r"(?i)\b(zulu|jrockit|graalvm|openjdk|\bjre\b|\bjdk\b|javafx|java se|java cpu|\bjava\b)"),
     "Obsolete vulnerable Java runtime. Remediation: remove or upgrade obsolete Java (JDK/JRE)."),
    (re.compile(r"(?i)\b(red hat enterprise linux|rhel)\b"),
     "Obsolete Red Hat Enterprise Linux version. Remediation: upgrade RHEL."),
    (re.compile(r"(?i)\blog4j\b"), "Obsolete Apache Log4j library. Remediation: upgrade Log4j."),
]


def _load_csv(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def _cluster_key(r):
    s = r.get("Summary") or ""
    t = r.get("Custom field (Vulnerability Title)") or ""
    m = CVE.search(s + " " + t)
    return m.group(0).upper() if m else s.strip()


def _product_hint(text):
    for rx, phrase in PRODUCT_HINTS:
        if rx.search(text):
            return phrase
    return ""


def _median(rows, key):
    return float(np.median([m[key] for m in rows])) if rows else float("nan")


def _pairwise(ids, pred, truth):
    """Pairwise (precision, recall, f1) over unordered pairs within `ids`."""
    tp = fp = fn = 0
    for a, b in itertools.combinations(ids, 2):
        same_truth = truth[a] == truth[b]
        same_pred = pred.get(a) is not None and pred.get(a) == pred.get(b)
        if same_truth and same_pred:
            tp += 1
        elif same_pred and not same_truth:
            fp += 1
        elif same_truth and not same_pred:
            fn += 1
    p = 1.0 if tp + fp == 0 else tp / (tp + fp)
    r = 1.0 if tp + fn == 0 else tp / (tp + fn)
    f = 0.0 if p + r == 0 else 2 * p * r / (p + r)
    return dict(precision=p, recall=r, f1=f)


# ── Stage 1: intra-CVE clustering (partition oracle) ──────────────────────────

STAGE1_CFG = ZinggConf(fields=[
    FieldDef("summary", [MatchType.Fuzzy, MatchType.cve]),
    FieldDef("vuln_title", [MatchType.Fuzzy, MatchType.cve]),
    FieldDef("misconf", [MatchType.Text]),
], block_size=50)


def _stat_records(stat, cap_per_cluster=8):
    """One record per StatistiX row (optionally capped per cluster), with the
    partition oracle truth (row id -> CVE/category cluster). ``cap_per_cluster``
    of 0/None means no cap (use every row)."""
    by_cluster = defaultdict(list)
    for r in stat:
        key = _cluster_key(r)
        if key:
            by_cluster[key].append(r)
    cap = cap_per_cluster or None
    records, truth = [], {}
    for key, rows in by_cluster.items():
        for r in rows[:cap]:
            rid = r.get("Issue key")
            records.append({
                STAGE1_CFG.id_col: rid,
                "summary": r.get("Summary") or "",
                "vuln_title": r.get("Custom field (Vulnerability Title)") or "",
                "misconf": r.get("Custom field (Misconfiguration Description)") or "",
            })
            truth[rid] = key
    return pd.DataFrame(records), truth


def stage1(stat, n_splits=5, test_frac=0.3, cap=8):
    df, truth = _stat_records(stat, cap_per_cluster=cap)
    oracle = OracleLabeller(truth)
    ids = list(truth)
    n_clusters = len(set(truth.values()))
    multi = sum(1 for v in Counter(truth.values()).values() if v > 1)

    results = []
    for seed in range(n_splits):
        rng = random.Random(seed)
        shuffled = ids[:]
        rng.shuffle(shuffled)
        n_test = max(1, int(len(shuffled) * test_frac))
        test_ids = set(shuffled[:n_test])
        train_ids = set(shuffled[n_test:])

        train_df = df[df[STAGE1_CFG.id_col].isin(train_ids)]
        z = Zingg(STAGE1_CFG)
        cands = z.find_training_data(train_df, n=10 ** 9)        # all cold-start pairs
        labelled = oracle.label(cands, STAGE1_CFG)               # oracle supplies train labels
        if labelled.empty or labelled[STAGE1_CFG.label_col].nunique() < 2:
            continue
        model, tree = z.train(labelled)
        clusters = z.cluster(df, model, tree)
        pred = dict(zip(clusters[STAGE1_CFG.id_col], clusters[STAGE1_CFG.cluster_col]))
        results.append(_pairwise(test_ids, pred, truth))

    cap_note = f"capped at {cap}/cluster" if cap else "uncapped (all rows)"
    print("=" * 72)
    print("STAGE 1 — intra-CVE clustering (partition oracle: row -> CVE/category)")
    print(f"  records: {len(df)}   clusters: {n_clusters} ({multi} with >1 row, {cap_note})")
    print(f"  {len(results)} by-cluster held-out splits (hold out {test_frac:.0%} of rows):")
    print(f"    precision (median): {_median(results, 'precision'):.2f}")
    print(f"    recall    (median): {_median(results, 'recall'):.2f}")
    print(f"    f1        (median): {_median(results, 'f1'):.2f}   <- dedup is Zingg's home turf")


# ── Stage 2: CVE-cluster -> ops-ticket linkage (bipartite oracle) ─────────────

STAGE2_CFG = ZinggConf(fields=[FieldDef("text", [MatchType.Fuzzy, MatchType.Text])])


def _cluster_text_map(stat):
    parts = defaultdict(lambda: {"title": Counter(), "desc": Counter()})
    for r in stat:
        key = _cluster_key(r)
        if not key:
            continue
        t = (r.get("Custom field (Vulnerability Title)") or "").strip()
        d = (r.get("Custom field (Misconfiguration Description)") or "").strip()
        if t:
            parts[key]["title"][t] += 1
        if d:
            parts[key]["desc"][d] += 1
    out = {}
    for key, c in parts.items():
        code = "" if CVE.fullmatch(key) else re.sub(r"[_\-]+", " ", key).strip().lower()
        title = c["title"].most_common(1)[0][0] if c["title"] else ""
        desc = c["desc"].most_common(1)[0][0] if c["desc"] else ""
        base = ". ".join(p for p in (code, title, desc) if p).strip()
        hint = _product_hint(base)
        out[key] = ((hint + " " + base) if hint else base).strip()[:600]
    return out


def _ops_text_map(vmt):
    out = {}
    for r in vmt:
        k = r.get("Issue key")
        if k:
            out[k] = ((r.get("Summary") or "") + ". " + (r.get("Description") or "")).strip()[:600]
    return out


def _featured(records):
    rows = [{
        f"{ZinggConf.LEFT_PREFIX}{STAGE2_CFG.id_col}": r["cluster"],
        f"{ZinggConf.RIGHT_PREFIX}{STAGE2_CFG.id_col}": r["ops_key"],
        f"{ZinggConf.LEFT_PREFIX}text": r["l_text"],
        f"{ZinggConf.RIGHT_PREFIX}text": r["r_text"],
    } for r in records]
    return features.add_features(pd.DataFrame(rows), STAGE2_CFG)


def _confusion(y_true, y_pred):
    y_true, y_pred = np.asarray(y_true), np.asarray(y_pred)
    tp = int(((y_pred == 1) & (y_true == 1)).sum())
    fp = int(((y_pred == 1) & (y_true == 0)).sum())
    fn = int(((y_pred == 0) & (y_true == 1)).sum())
    tn = int(((y_pred == 0) & (y_true == 0)).sum())
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    f = 2 * p * r / (p + r) if p + r else 0.0
    return dict(precision=p, recall=r, f1=f, accuracy=(tp + tn) / max(1, tp + fp + fn + tn),
                tp=tp, fp=fp, fn=fn, tn=tn)


def stage2(stat, vmt, gt, n_splits=8, test_frac=0.3):
    strict = set(gt.STRICT)
    cluster_text = _cluster_text_map(stat)
    ops_text = _ops_text_map(vmt)
    oracle = OraclePairLabeller({(c, k) for (c, k) in strict})

    # Part A — does Zingg's blocking even propose the true links?
    cluster_ids = sorted(cluster_text)
    union = pd.concat([
        pd.DataFrame([{STAGE2_CFG.id_col: k, "text": cluster_text[k], ZinggConf.SOURCE_COL: "cluster"}
                      for k in cluster_ids]),
        pd.DataFrame([{STAGE2_CFG.id_col: k, "text": ops_text[k], ZinggConf.SOURCE_COL: "ops"}
                      for k in ops_text]),
    ], ignore_index=True)
    cand = pair_mod.cold_start_pairs(union, STAGE2_CFG, cross_source_only=True)
    lid, rid = f"{ZinggConf.LEFT_PREFIX}{STAGE2_CFG.id_col}", f"{ZinggConf.RIGHT_PREFIX}{STAGE2_CFG.id_col}"
    cand_pairs = {frozenset(p) for p in zip(cand[lid], cand[rid])}
    strict_in = {(c, k) for (c, k) in strict if c in cluster_text and k in ops_text}
    recalled = sum(1 for (c, k) in strict_in if frozenset((c, k)) in cand_pairs)

    # Part B — held-out classification over the labelled candidate set.
    # ground_truth.csv is the sheet make_labels.py writes next to this script.
    gt_rows = _load_csv(os.path.join(HERE, "ground_truth.csv"))
    records = [{
        "cluster": r["cluster"], "ops_key": r["ops_key"],
        "l_text": cluster_text[r["cluster"]], "r_text": ops_text[r["ops_key"]],
        "label": 1 if (r["cluster"], r["ops_key"]) in strict else 0,
    } for r in gt_rows if r["cluster"] in cluster_text and r["ops_key"] in ops_text]
    labelled_clusters = sorted({r["cluster"] for r in records})

    results = []
    for seed in range(n_splits):
        rng = random.Random(seed)
        shuffled = labelled_clusters[:]
        rng.shuffle(shuffled)
        n_test = max(1, int(len(shuffled) * test_frac))
        test_cl = set(shuffled[:n_test])
        train_rec = [r for r in records if r["cluster"] not in test_cl]
        test_rec = [r for r in records if r["cluster"] in test_cl]
        if not train_rec or not test_rec:
            continue
        labelled = oracle.label(_featured(train_rec), STAGE2_CFG)
        if labelled[STAGE2_CFG.label_col].nunique() < 2:
            continue
        mdl = model_mod.train(labelled, STAGE2_CFG)
        scored = model_mod.score(mdl, _featured(test_rec), STAGE2_CFG)
        y_pred = (scored[STAGE2_CFG.score_col] >= STAGE2_CFG.threshold).astype(int).to_numpy()
        results.append(_confusion([r["label"] for r in test_rec], y_pred))

    print("\n" + "=" * 72)
    print("STAGE 2 — CVE-cluster -> ops-ticket linkage (bipartite oracle)")
    print(f"  clusters: {len(cluster_ids)}   ops tickets: {len(ops_text)}")
    print(f"  Part A (blocking recall): {recalled}/{len(strict_in)} STRICT links surfaced "
          f"as candidates ({recalled / max(1, len(strict_in)):.0%})")
    print(f"  Part B ({len(results)} by-cluster held-out splits, {len(records)} labelled candidates):")
    print(f"    precision (median): {_median(results, 'precision'):.2f}")
    print(f"    recall    (median): {_median(results, 'recall'):.2f}")
    print(f"    f1        (median): {_median(results, 'f1'):.2f}")
    print(f"    accuracy  (median): {_median(results, 'accuracy'):.2f}")
    print("    -> rerank->judge pipeline reaches cluster-F1 0.92 on the same ground truth")


def main():
    import argparse
    ap = argparse.ArgumentParser(description="Zingg two-stage coverage evaluation")
    ap.add_argument("--stage", choices=["1", "2", "both"], default="both")
    ap.add_argument("--cap", type=int, default=8, help="Stage 1 rows/cluster (0 = uncapped)")
    ap.add_argument("--splits", type=int, default=0, help="held-out splits (0 = stage default)")
    args = ap.parse_args()

    stat = _load_csv(os.path.join(DATA, "StatAnon.csv"))
    if args.stage in ("1", "both"):
        stage1(stat, cap=args.cap, **({"n_splits": args.splits} if args.splits else {}))
    if args.stage in ("2", "both"):
        vmt = _load_csv(os.path.join(DATA, "VmtAnon.csv"))
        import cve_coverage.ground_truth_spec as gt
        stage2(stat, vmt, gt, **({"n_splits": args.splits} if args.splits else {}))
    print("=" * 72)


if __name__ == "__main__":
    main()
