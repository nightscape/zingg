"""Clustering — port of ``Clustering.scala``.

GraphX connected components becomes ``scipy.sparse.csgraph.connected_components``
over the matched-pair edge list. Every id is a vertex; predicted matches are
undirected edges; the component label is the cluster id.
"""

from __future__ import annotations

from typing import Iterable

import numpy as np
import pandas as pd
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components as _cc

from .schema import ZinggConf


def connected_components(pairs: pd.DataFrame, ids: Iterable, cfg: ZinggConf) -> pd.DataFrame:
    sids = list(dict.fromkeys(ids))  # distinct, stable order, original id type
    index = {s: i for i, s in enumerate(sids)}
    n = len(sids)
    if n == 0:
        return pd.DataFrame({cfg.id_col: [], cfg.cluster_col: []})

    l_id = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"
    r_id = f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"

    rows, cols = [], []
    if not pairs.empty:
        matched = pairs[pairs[cfg.prediction_col] == 1.0]
        for ls, rs in zip(matched[l_id], matched[r_id]):
            if ls in index and rs in index:
                rows.append(index[ls])
                cols.append(index[rs])

    graph = coo_matrix((np.ones(len(rows), dtype=int), (rows, cols)), shape=(n, n))
    _, labels = _cc(graph, directed=False)
    return pd.DataFrame({cfg.id_col: sids, cfg.cluster_col: labels})
