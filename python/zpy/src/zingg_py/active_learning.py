"""Pair selection — port of ``ActiveLearning.scala``."""

from __future__ import annotations

import numpy as np
import pandas as pd

from . import features
from .schema import ZinggConf

_PROXY_COL = "z_proxy"
_HIGH_POOL_FACTOR = 3  # high-similarity pool size relative to the "yes" slots


def _proxy(feat: np.ndarray) -> float:
    """Unsupervised similarity proxy: mean of the feature vector."""
    return 0.0 if feat.size == 0 else float(feat.mean())


def query_uncertain(scored: pd.DataFrame, cfg: ZinggConf, n: int = 20) -> pd.DataFrame:
    out = scored.copy()
    out["z_uncertainty"] = 1.0 - (out[cfg.score_col] - 0.5).abs() * 2.0
    return out.sort_values("z_uncertainty", ascending=False, kind="stable").head(n)


def cold_start_sample(pairs: pd.DataFrame, n: int = 30, seed=None) -> pd.DataFrame:
    """Balanced cold-start sample: stratify on the similarity proxy, drawing
    half from the most-similar pairs (likely "yes") and half from the rest."""
    if pairs.empty:
        return pairs
    proxy = pairs[features.FEATURE_COL].map(_proxy)
    total = len(pairs)
    if total <= n:
        return pairs

    n_yes = (n + 1) // 2
    n_no = n - n_yes
    high_pool = min(total, n_yes * _HIGH_POOL_FACTOR)
    high_frac = 1.0 - high_pool / total
    thr = float(np.quantile(proxy.to_numpy(), high_frac))

    high = pairs[proxy >= thr]
    low = pairs[proxy < thr]
    high = _take_random(high, n_yes, seed)
    low = _take_random(low, n_no, None if seed is None else seed + 1)
    return pd.concat([high, low], ignore_index=True)


def _take_random(df: pd.DataFrame, k: int, seed) -> pd.DataFrame:
    if len(df) <= k:
        return df
    rng = np.random.RandomState(None if seed is None else int(seed) % (2 ** 32))
    idx = rng.permutation(len(df))[:k]
    return df.iloc[idx]
