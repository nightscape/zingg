"""Candidate pair generation — port of ``Pairs.scala`` (``PairBuilder``)."""

from __future__ import annotations

import pandas as pd

from . import blocking
from .blocking import BlockingTree, Leaf
from .schema import ZinggConf


def _prefix(df: pd.DataFrame, p: str) -> pd.DataFrame:
    return df.rename(columns={c: f"{p}{c}" for c in df.columns})


def self_pairs(
    blocked: pd.DataFrame,
    cfg: ZinggConf,
    block_col: str = blocking.BLOCK_COL,
    cross_source_only: bool = False,
) -> pd.DataFrame:
    """Candidate pairs from records sharing a block.

    ``l_id < r_id`` keeps each unordered pair once. A null block key never
    equi-joins, so signal-free records drop out instead of collapsing into one
    giant block — we filter null keys before the self-join (pandas would
    otherwise match ``None == None``).
    """
    # Drop rows with no blocking signal so they never pair.
    signal = blocked[blocked[block_col].notna()]
    left = _prefix(signal, ZinggConf.LEFT_PREFIX)
    right = _prefix(signal, ZinggConf.RIGHT_PREFIX)
    l_block = f"{ZinggConf.LEFT_PREFIX}{block_col}"
    r_block = f"{ZinggConf.RIGHT_PREFIX}{block_col}"
    l_id = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"
    r_id = f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"

    joined = left.merge(right, left_on=l_block, right_on=r_block)
    joined = joined[joined[l_id] < joined[r_id]]

    if cross_source_only:
        if ZinggConf.SOURCE_COL not in blocked.columns:
            raise ValueError(
                f"cross-source pairing needs the '{ZinggConf.SOURCE_COL}' column; "
                "canonicalise sources first"
            )
        l_src = f"{ZinggConf.LEFT_PREFIX}{ZinggConf.SOURCE_COL}"
        r_src = f"{ZinggConf.RIGHT_PREFIX}{ZinggConf.SOURCE_COL}"
        joined = joined[joined[l_src] != joined[r_src]]

    return joined.reset_index(drop=True)


def cold_start_pairs(
    df: pd.DataFrame,
    cfg: ZinggConf,
    cross_source_only: bool = False,
) -> pd.DataFrame:
    """Union of ``self_pairs`` over every cold-start canopy, deduped by id pair."""
    blockers = blocking.cold_start_blockers(cfg) or [Leaf("root")]
    per_blocker = [
        self_pairs(blocking.assign_blocks(df, b), cfg, cross_source_only=cross_source_only)
        for b in blockers
    ]
    l_id = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"
    r_id = f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"
    combined = pd.concat(per_blocker, ignore_index=True)
    if combined.empty:
        return combined
    return combined.drop_duplicates(subset=[l_id, r_id]).reset_index(drop=True)
