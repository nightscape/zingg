"""Stage 2 — link clusters to remediation ops tickets and write the outputs.

retrieve (Embedder) -> rerank (Reranker) -> judge (Judge) -> bipartite coverage.

"covered" is gated on the RANK-1 reranked candidate being judged a match: on the
ground truth this calibration-free rule lifts cluster-level F1 from 0.71 (any
judged match) to 0.82 — hubs get matched at ranks 2-5, so the within-cluster rank
is a cleaner signal than the uncalibrated absolute score. Lower-ranked matches are
still emitted as N:M links, flagged 'review' rather than auto-accepted.
"""

from __future__ import annotations

import csv
import os

from .config import F_ID, F_VULN_TITLE
from .judge import Judge, MATCH, VERDICT_LABEL
from .retrieval import Embedder, retrieve
from .rerank import Reranker
from .text import canonical, ops_doc
from .types import Cluster

CANDIDATES_CSV = "coverage_candidates.csv"
MAP_CSV = "coverage_map.csv"
MEMBERS_CSV = "coverage_members.csv"


def write_members(clusters: list[Cluster], path: str) -> None:
    """(cluster, ticket_key) membership, so the scorer can re-aggregate the
    pipeline's clusters back to the true CVE/category independent of how they
    were partitioned."""
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["cluster", "ticket_key"])
        for c in clusters:
            for tk in c.ticket_ids:
                w.writerow([c.key, tk])


def run_linkage(clusters: list[Cluster], vmt: list[dict], *,
                embedder: Embedder, reranker: Reranker, judge: Judge,
                topn: int, topk: int, out_dir: str, prefix: str = "") -> tuple[int, int]:
    ops_text = [ops_doc(r) for r in vmt]
    ops_key = [r.get(F_ID) for r in vmt]
    ops_sum = [(r.get(F_VULN_TITLE) or "")[:80] for r in vmt]
    cand_path = os.path.join(out_dir, prefix + CANDIDATES_CSV)
    cov_path = os.path.join(out_dir, prefix + MAP_CSV)

    # Stage 2a: embedding retrieval.
    print("Stage 2a: embedding ...")
    ops_vecs = embedder.embed(ops_text)
    clus_vecs = embedder.embed([canonical(c) for c in clusters])
    retrieved = dict(zip((c.key for c in clusters), retrieve(clus_vecs, ops_vecs, topn)))

    # Stage 2b: cross-encoder rerank of the retrieved candidates.
    n_pairs = sum(len(retrieved[c.key]) for c in clusters)
    print(f"Stage 2b: reranking ({n_pairs} pairs) ...")
    reranked = {}
    for n, c in enumerate(clusters, 1):
        cand = retrieved[c.key]
        scores = reranker.score([(canonical(c), ops_text[i]) for i in cand])
        reranked[c.key] = sorted(zip(cand, scores), key=lambda t: -t[1])[:topk]
        if n % 5 == 0 or n == len(clusters):
            print(f"  reranked {n}/{len(clusters)} clusters", flush=True)

    # Stage 2c: LLM judge + bipartite output.
    print(f"Stage 2c: judging ({sum(len(v) for v in reranked.values())} calls) ...")
    covered = 0
    with open(cand_path, "w", newline="") as fc, open(cov_path, "w", newline="") as fm:
        wc, wm = csv.writer(fc), csv.writer(fm)
        wc.writerow(["cluster", "n_tickets", "rank", "ops_key", "ops_summary",
                     "rerank_score", "verdict", "confidence"])
        wm.writerow(["cluster", "n_tickets", "covered", "linked_ops_keys", "linked_ops_summaries"])
        for c in clusters:
            ctext, linked, rank1_match = canonical(c), [], False
            for rank, (i, sc) in enumerate(reranked[c.key], 1):
                v = judge.judge(ctext, ops_text[i])
                conf = "high" if (rank == 1 and v == MATCH) else ("review" if v == MATCH else "")
                if v == MATCH:
                    linked.append((ops_key[i], ops_sum[i]))
                    if rank == 1:
                        rank1_match = True
                wc.writerow([c.key, c.size, rank, ops_key[i], ops_sum[i],
                             f"{sc:.3f}", VERDICT_LABEL[v], conf])
            wm.writerow([c.key, c.size, "yes" if rank1_match else "no",
                         " | ".join(k for k, _ in linked), " | ".join(s for _, s in linked)])
            covered += 1 if rank1_match else 0
            tag = ("COVERED: " + linked[0][0]) if rank1_match else (
                  f"review ({len(linked)} lower-rank match)" if linked else "needs ticket")
            print(f"  [{c.size:3}] {c.key[:34]:34} {tag}")
    print(f"\nCoverage: {covered}/{len(clusters)} clusters covered; "
          f"{len(clusters) - covered} need a ticket.")
    print(f"Wrote {cov_path}\n      {cand_path}")
    return covered, len(clusters)
