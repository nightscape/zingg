"""Build a ground-truth labeling sheet from the rerank candidates.

Joins each (cluster, candidate-ops) pair in coverage_candidates_lmstudio.csv with
the cluster's canonical query text and the ops ticket's full Summary+Description,
so a human can label match/non-match with full context instead of an 80-char title.

Emits two files:
  ground_truth.csv  -- one row per (cluster, ops_key); fill the `truth` column with
                       1 (match) / 0 (non-match). Pre-seeded with the 3 known backlinks.
  ground_truth.md   -- the same, grouped per cluster and human-readable for labeling.

Run (from python/): uv run python cve_coverage/tools/make_labels.py
Env: CANDIDATES (cached rerank candidates CSV; default coverage_candidates.csv in the package root)
"""
import csv, os, re
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.dirname(HERE)            # package root: example CSVs + pipeline outputs
CANDIDATES = os.environ.get("CANDIDATES", os.path.join(DATA, "coverage_candidates.csv"))
CVE = re.compile(r"(?i)CVE-\d{4}-\d{3,7}")

# The 3 backlinks that exist explicitly in the data (README §4) -> seed as truth=1.
KNOWN = {
    ("SQL_LOG_STATEMENT", "STXIO-2429710"),
    ("SQL_INSTANCE_NOT_MONITORED", "STXIO-5624497"),
    ("linux-rhel-obsolete-version", "STXIO-1292640"),
}


def load(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def cluster_cve_tickets(stat):
    clusters = defaultdict(lambda: {"keys": [], "title": Counter(), "desc": Counter()})
    for r in stat:
        s = r.get("Summary") or ""
        t = r.get("Custom field (Vulnerability Title)") or ""
        d = r.get("Custom field (Misconfiguration Description)") or ""
        m = CVE.search(s + " " + t)
        key = m.group(0).upper() if m else s.strip()
        if not key:
            continue
        c = clusters[key]; c["keys"].append(r.get("Issue key"))
        if t.strip(): c["title"][t.strip()] += 1
        if d.strip(): c["desc"][d.strip()] += 1
    return clusters


def canonical(key, c):
    code  = "" if CVE.fullmatch(key) else re.sub(r"[_\-]+", " ", key).strip().lower()
    title = c["title"].most_common(1)[0][0] if c["title"] else ""
    desc  = c["desc"].most_common(1)[0][0] if c["desc"] else ""
    return ". ".join(p for p in (code, title, desc) if p).strip()[:600]


def main():
    stat = load(os.path.join(DATA, "StatAnon.csv"))
    vmt = load(os.path.join(DATA, "VmtAnon.csv"))
    clusters = cluster_cve_tickets(stat)
    canon = {k: canonical(k, c) for k, c in clusters.items()}

    ops_by_key = {r.get("Issue key"): r for r in vmt}
    cands = load(CANDIDATES)

    # ground_truth.csv
    out_csv = os.path.join(HERE, "ground_truth.csv")
    with open(out_csv, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["cluster", "ops_key", "ops_summary", "rerank_score",
                    "model_verdict", "truth", "note"])
        for r in cands:
            key, ok = r["cluster"], r["ops_key"]
            seed = "1" if (key, ok) in KNOWN else ""
            note = "known backlink" if (key, ok) in KNOWN else ""
            w.writerow([key, ok, r["ops_summary"], r["rerank_score"],
                        r["verdict"], seed, note])

    # ground_truth.md (grouped, with full ops description)
    by_cluster = defaultdict(list)
    for r in cands:
        by_cluster[r["cluster"]].append(r)
    out_md = os.path.join(HERE, "ground_truth.md")
    with open(out_md, "w") as f:
        f.write("# Ground-truth labeling sheet\n\n")
        f.write("For each candidate write **1** (true remediation of this cluster) or "
                "**0** (not) in the `truth` box. A match = the ops ticket actually fixes "
                "THIS vulnerability (same component/software/misconfig). Generic infra "
                "work that merely shares a word = 0.\n\n")
        f.write("The 3 known backlinks are pre-marked `[1]`.\n\n---\n\n")
        # preserve cluster order as in candidates file
        seen = []
        for r in cands:
            if r["cluster"] not in seen:
                seen.append(r["cluster"])
        for key in seen:
            rows = by_cluster[key]
            n = rows[0]["n_tickets"]
            f.write(f"## `{key}`  ({n} CVE tickets)\n\n")
            f.write(f"**cluster text:** {canon.get(key, '(missing)')}\n\n")
            for r in rows:
                ok = r["ops_key"]
                opsr = ops_by_key.get(ok, {})
                desc = (opsr.get("Description") or "").strip().replace("\n", " ")[:300]
                seed = "1" if (key, ok) in KNOWN else " "
                f.write(f"- `[{seed}]` **{ok}** (rerank {r['rerank_score']}, "
                        f"model={r['verdict']}) — {r['ops_summary']}\n")
                if desc:
                    f.write(f"    - _desc:_ {desc}\n")
            f.write("\n")
    print(f"wrote {out_csv}\n      {out_md}")
    print(f"{len(cands)} candidate pairs across {len(seen)} clusters")


if __name__ == "__main__":
    main()
