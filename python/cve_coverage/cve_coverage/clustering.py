"""Stage 1 — cluster the StatistiX vulnerability tickets into one group per
distinct vulnerability.

Two strategies behind the `Clusterer` interface:
  * `DeterministicClusterer` — group by CVE id / category summary (a regex).
    Exact and dependency-free; the baseline.
  * `ZinggClusterer` — train zingg-py on weak labels from that same heuristic,
    then cluster all rows by the learned model. Reproduces the regex partition
    on this data (held-out F1 1.00) but degrades gracefully on fuzzier dedup a
    pure regex would miss. Requires the zingg-py package.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from typing import Protocol

from .config import F_ID, F_MISCONF, F_SUMMARY, F_VULN_TITLE
from .text import canonical, cluster_key
from .types import Cluster

# Internal field names carrying the dedup signal (rows are normalised via the
# source's fieldMapping before they reach the clusterer).
_SUMMARY = F_SUMMARY
_VULN_TITLE = F_VULN_TITLE
_MISCONF = F_MISCONF
_ID = F_ID


def _row_key(row: dict) -> str:
    return cluster_key(row.get(_SUMMARY) or "", row.get(_VULN_TITLE) or "")


def _build_cluster(key: str, rows: list[dict]) -> Cluster:
    title, desc = Counter(), Counter()
    for r in rows:
        t = (r.get(_VULN_TITLE) or "").strip()
        d = (r.get(_MISCONF) or "").strip()
        if t:
            title[t] += 1
        if d:
            desc[d] += 1
    return Cluster(key=key, ticket_ids=[r.get(_ID) for r in rows], title=title, desc=desc)


def _finalize(clusters: list[Cluster]) -> list[Cluster]:
    """Drop empty-text clusters and sort largest first."""
    clusters = [c for c in clusters if canonical(c)]
    clusters.sort(key=lambda c: -c.size)
    return clusters


class Clusterer(Protocol):
    def cluster(self, stat: list[dict]) -> list[Cluster]:
        ...


class DeterministicClusterer:
    def cluster(self, stat: list[dict]) -> list[Cluster]:
        groups: dict[str, list[dict]] = defaultdict(list)
        for r in stat:
            key = _row_key(r)
            if key:
                groups[key].append(r)
        return _finalize([_build_cluster(k, rows) for k, rows in groups.items()])


class ZinggClusterer:
    """`cap` rows/cluster are used for training (keeps the pair count small while
    the model generalises); all rows are then clustered. `block_strategy` selects
    zingg's candidate generation ("tree" learned single-key, or "canopies")."""

    def __init__(self, cap: int = 8, block_strategy: str = "tree"):
        self.cap = cap
        self.block_strategy = block_strategy

    def cluster(self, stat: list[dict]) -> list[Cluster]:
        import pandas as pd
        from zingg_py.labeller import OracleLabeller
        from zingg_py.schema import FieldDef, MatchType, ZinggConf
        from zingg_py.zingg import Zingg

        cfg = ZinggConf(fields=[
            FieldDef("summary", [MatchType.Fuzzy, MatchType.cve]),
            FieldDef("vuln_title", [MatchType.Fuzzy, MatchType.cve]),
            FieldDef("misconf", [MatchType.Text]),
        ], block_size=50)

        rows = [r for r in stat if _row_key(r)]
        df = pd.DataFrame([{
            cfg.id_col: r.get(_ID),
            "summary": r.get(_SUMMARY) or "",
            "vuln_title": r.get(_VULN_TITLE) or "",
            "misconf": r.get(_MISCONF) or "",
        } for r in rows])

        # Weak labels from the CVE/category heuristic (the dedup ground truth here).
        truth = {r.get(_ID): _row_key(r) for r in rows}
        by_key: dict[str, list[str]] = defaultdict(list)
        for rid, k in truth.items():
            by_key[k].append(rid)
        cap = self.cap or 10 ** 9
        train_ids = {rid for ids in by_key.values() for rid in ids[:cap]}
        train_df = df[df[cfg.id_col].isin(train_ids)]

        z = Zingg(cfg)
        cands = z.find_training_data(train_df, n=10 ** 9)
        labelled = OracleLabeller(truth).label(cands, cfg)
        model, tree = z.train(labelled)
        clustered = z.cluster(df, model, tree, block_strategy=self.block_strategy)

        row_by_id = {r.get(_ID): r for r in rows}
        groups: dict[object, list[str]] = defaultdict(list)
        for rid, cl in zip(clustered[cfg.id_col], clustered[cfg.cluster_col]):
            groups[cl].append(rid)

        clusters, used = [], {}
        for ids in groups.values():
            rs = [row_by_id[i] for i in ids]
            key = Counter(_row_key(r) for r in rs).most_common(1)[0][0]
            if key in used:                 # keep cluster keys unique across splits
                used[key] += 1
                key = f"{key}#{used[key]}"
            else:
                used[key] = 1
            clusters.append(_build_cluster(key, rs))
        return _finalize(clusters)


def make_clusterer(strategy: str, cap: int = 8, block_strategy: str = "tree") -> Clusterer:
    if strategy == "deterministic":
        return DeterministicClusterer()
    if strategy == "zingg":
        return ZinggClusterer(cap=cap, block_strategy=block_strategy)
    raise ValueError(f"unknown clustering strategy: {strategy!r}")
