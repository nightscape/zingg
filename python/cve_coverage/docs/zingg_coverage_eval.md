# Zingg-pipeline evaluation of the CVE coverage task (two oracles)

`zingg_coverage_eval.py` is a Zingg-based analogue of the `cve_coverage` pipeline,
scored against the hand-labelled ground truth. Same two stages, each with its
**own oracle**. Feed part of the labelled data via the oracle (train), measure
the rest (held-out, median over splits).

Run from the zpy env:
```bash
cd zpy && uv run python ../examples/security/zingg_coverage_eval.py
```

## Results (deterministic)

### Stage 1 — intra-CVE clustering  ·  partition oracle (`OracleLabeller`, row→cluster)

142 records, 36 CVE/category clusters (capped at 8 rows/cluster), 5 by-cluster
held-out splits:

| metric (median) | value |
|---|---|
| precision | 1.00 |
| recall | 1.00 |
| **F1** | **1.00** |

Deduplicating StatistiX rows into CVE/category clusters is Zingg's home turf —
the CVE-regex feature is exact and category Summaries are identical within a
cluster, so the learned tree blocks and the classifier separates near-perfectly.
(README §2: "dedup by CVE/category is near trivial".)

**Confirmed uncapped:** `--cap 0 --splits 3` (all 819 rows, big clusters up to
225 rows included) gives the same **F1 = 1.00** — the cap is only a speed
convenience, the result holds at full scale (~2.5 min).

### Stage 2 — CVE-cluster → ops-ticket linkage  ·  bipartite oracle (`OraclePairLabeller`)

| | value |
|---|---|
| **Part A — blocking recall** | **0 / 24 (0%)** STRICT links surfaced as candidates |
| Part B — held-out precision (median) | 0.53 |
| Part B — held-out recall (median) | 0.33 |
| Part B — held-out **F1** (median) | **0.47** |
| Part B — held-out accuracy (median) | 0.90 |

Zingg's lexical blocking can't even propose the true links (0% recall ceiling),
and even given the curated candidates + perfect oracle training labels, the
lexical features only reach F1 ≈ 0.47 — vs the retrieve→rerank→judge pipeline's
cluster-F1 **0.92** on the same ground truth.

## Takeaway

The two stages, with two oracles, draw the line exactly where README §2 predicts:

- **Dedup / intra-CVE clustering → use Zingg** (F1 1.00).
- **Cross-system semantic linkage → do NOT use Zingg** (blocking 0%, F1 0.47);
  it needs semantic retrieval + a judge (the `cve_coverage` pipeline).
