"""Feature vectors — port of ``Features.scala``.

Adds a ``z_features`` column: per pair, the per-field/per-matcher similarity
scores concatenated into a numpy vector (the Spark ML dense vector in the
original).
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from . import similarity
from .schema import ZinggConf

FEATURE_COL = "z_features"


def feature_total(cfg: ZinggConf) -> int:
    return sum(similarity.feature_width(mt) for f in cfg.fields for mt in f.match_types)


def _row_features(row, cfg: ZinggConf) -> np.ndarray:
    arr: list[float] = []
    for f in cfg.fields:
        a = row.get(f"{ZinggConf.LEFT_PREFIX}{f.name}")
        b = row.get(f"{ZinggConf.RIGHT_PREFIX}{f.name}")
        for mt in f.match_types:
            arr.extend(similarity.features(mt, a, b))
    return np.asarray(arr, dtype=float)


def add_features(pairs: pd.DataFrame, cfg: ZinggConf) -> pd.DataFrame:
    out = pairs.copy()
    if pairs.empty:
        out[FEATURE_COL] = pd.Series([], dtype=object)
        return out
    feats = [_row_features(row, cfg) for _, row in pairs.iterrows()]
    out[FEATURE_COL] = pd.Series(feats, index=pairs.index)
    return out


def feature_matrix(df: pd.DataFrame) -> np.ndarray:
    """Stack the ``z_features`` column into an ``n x d`` matrix."""
    if df.empty:
        return np.zeros((0, 0))
    return np.vstack(df[FEATURE_COL].to_numpy())
