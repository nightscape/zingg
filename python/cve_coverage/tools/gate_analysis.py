"""Design the confidence gate. Consumes rejudge_verdicts.csv (judge verdict + rerank
score + strict truth per pair) and evaluates candidate gating rules, since raw
judge=match precision is ~0.5. Reports pair- and cluster-level P/R/F1 for each gate.

The README warns rerank scores are uncalibrated ACROSS clusters, so we test both
absolute-score gates and WITHIN-cluster signals (rank, gap to the next candidate).

Run (from python/, after rejudge.py has written rejudge_verdicts.csv here):
    uv run python cve_coverage/tools/gate_analysis.py
"""
import csv, os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))


def load_verdicts():
    with open(os.path.join(HERE, "rejudge_verdicts.csv"), newline="") as f:
        rows = list(csv.DictReader(f))
    for r in rows:
        r["rank"] = int(r["rank"])
        r["score"] = float(r["rerank_score"]) if r["rerank_score"] else float("nan")
        r["match"] = r["verdict"] == "match"
        r["truth"] = r["truth_strict"] == "1"
    return rows


def prf(tp, fp, fn):
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    f = 2 * p * r / (p + r) if p + r else 0.0
    return p, r, f


def pair_eval(rows, accept):
    """accept(row, ctx) -> bool. ctx has per-cluster gap info."""
    # precompute per-cluster gap-to-next for each row
    by_c = defaultdict(list)
    for r in rows:
        by_c[r["cluster"]].append(r)
    for c, rs in by_c.items():
        rs.sort(key=lambda r: r["rank"])
        for i, r in enumerate(rs):
            nxt = rs[i + 1]["score"] if i + 1 < len(rs) else r["score"]
            r["gap_next"] = r["score"] - nxt
            r["is_top"] = r["rank"] == 1
    tp = fp = fn = 0
    total_truth = sum(1 for r in rows if r["truth"])
    for r in rows:
        acc = accept(r)
        if acc and r["truth"]: tp += 1
        elif acc and not r["truth"]: fp += 1
    fn = total_truth - tp
    return prf(tp, fp, fn) + (tp, fp, fn)


def cluster_eval(rows, accept):
    """Cluster covered if any accepted pair; correct if that cluster has a truth pair."""
    by_c = defaultdict(list)
    for r in rows:
        by_c[r["cluster"]].append(r)
    truth_cov = {c for c, rs in by_c.items() if any(r["truth"] for r in rs)}
    pred_cov = {c for c, rs in by_c.items() if any(accept(r) for r in rs)}
    tp = len(truth_cov & pred_cov); fp = len(pred_cov - truth_cov); fn = len(truth_cov - pred_cov)
    return prf(tp, fp, fn) + (tp, fp, fn)


def main():
    rows = load_verdicts()
    # populate gap/is_top
    pair_eval(rows, lambda r: False)

    gates = {
        "A judge=match (baseline)":          lambda r: r["match"],
        "B match AND rank==1":               lambda r: r["match"] and r["is_top"],
        "C match AND score>=6":              lambda r: r["match"] and r["score"] >= 6,
        "C match AND score>=7":              lambda r: r["match"] and r["score"] >= 7,
        "D match AND gap_next>=1.0":         lambda r: r["match"] and r["gap_next"] >= 1.0,
        "D match AND gap_next>=1.5":         lambda r: r["match"] and r["gap_next"] >= 1.5,
        "E match AND rank==1 AND score>=6":  lambda r: r["match"] and r["is_top"] and r["score"] >= 6,
        "F match AND (rank==1 OR gap to none)": lambda r: r["match"] and r["is_top"],
        "G match AND rank==1 AND gap_next>=1.0": lambda r: r["match"] and r["is_top"] and r["gap_next"] >= 1.0,
    }
    print(f"{'gate':42} | {'PAIR  P/R/F1 (tp,fp,fn)':28} | CLUSTER P/R/F1 (tp,fp,fn)")
    print("-" * 110)
    for name, acc in gates.items():
        pp, pr, pf, ptp, pfp, pfn = pair_eval(rows, acc)
        cp, cr, cf, ctp, cfp, cfn = cluster_eval(rows, acc)
        print(f"{name:42} | {pp:.2f}/{pr:.2f}/{pf:.2f} ({ptp},{pfp},{pfn})".ljust(73)
              + f"| {cp:.2f}/{cr:.2f}/{cf:.2f} ({ctp},{cfp},{cfn})")


if __name__ == "__main__":
    main()
