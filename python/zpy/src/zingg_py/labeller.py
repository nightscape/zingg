"""Labellers — port of ``Labeller.scala`` + ``OracleLabeller.scala``.

Attaches a ``z_label`` column (1.0 match / 0.0 non-match) to candidate pairs.
"""

from __future__ import annotations

import enum
from dataclasses import dataclass
from typing import Callable, Optional

import pandas as pd

from .schema import ZinggConf


class Decision(enum.Enum):
    Match = 1
    NonMatch = 2
    Skip = 3
    Unknown = 4

    @staticmethod
    def from_double(v: float) -> "Decision":
        if v == 1.0:
            return Decision.Match
        if v == 0.0:
            return Decision.NonMatch
        return Decision.Unknown


@dataclass(frozen=True)
class OnlineAnswer:
    """One answer in the online (pair-at-a-time) loop: quit / skip / labelled."""
    kind: str           # "quit" | "skip" | "label"
    value: float = 0.0

    @staticmethod
    def quit() -> "OnlineAnswer":
        return OnlineAnswer("quit")

    @staticmethod
    def skip() -> "OnlineAnswer":
        return OnlineAnswer("skip")

    @staticmethod
    def label(v: float) -> "OnlineAnswer":
        return OnlineAnswer("label", v)

    @property
    def is_quit(self) -> bool:
        return self.kind == "quit"

    @property
    def is_skip(self) -> bool:
        return self.kind == "skip"

    @property
    def is_label(self) -> bool:
        return self.kind == "label"


def attach_labels(pairs: pd.DataFrame, cfg: ZinggConf,
                  mk_label: Callable[[pd.Series], Optional[float]]) -> pd.DataFrame:
    """Append ``z_label`` per row; rows whose label is ``None`` are dropped."""
    kept_rows = []
    labels = []
    for _, r in pairs.iterrows():
        lab = mk_label(r)
        if lab is not None:
            kept_rows.append(r)
            labels.append(lab)
    if not kept_rows:
        out = pairs.iloc[0:0].copy()
        out[cfg.label_col] = pd.Series([], dtype=float)
        return out
    out = pd.DataFrame(kept_rows).reset_index(drop=True)
    out[cfg.label_col] = labels
    return out


class Labeller:
    def label(self, pairs: pd.DataFrame, cfg: ZinggConf) -> pd.DataFrame:  # pragma: no cover
        raise NotImplementedError


class RowLabeller(Labeller):
    def decide(self, r: pd.Series, cfg: ZinggConf) -> Decision:  # pragma: no cover
        raise NotImplementedError

    def label(self, pairs: pd.DataFrame, cfg: ZinggConf) -> pd.DataFrame:
        def mk(r):
            d = self.decide(r, cfg)
            if d == Decision.Match:
                return 1.0
            if d == Decision.NonMatch:
                return 0.0
            return None  # Skip / Unknown both drop the row

        return attach_labels(pairs, cfg, mk)

    def ask(self, row, cfg: ZinggConf, header: str) -> OnlineAnswer:
        """Online single-pair answer derived from ``decide`` (never quits)."""
        d = self.decide(row, cfg)
        if d == Decision.Match:
            return OnlineAnswer.label(1.0)
        if d == Decision.NonMatch:
            return OnlineAnswer.label(0.0)
        return OnlineAnswer.skip()


class OracleLabeller(RowLabeller):
    """Labels pairs from a ground-truth *partition* map ``rowId -> clusterId``.

    Two rows match iff they share a cluster id; rows missing from the map are
    deferred (``Unknown``). This models a hard partition — every record belongs
    to exactly one cluster — so it cannot represent N:M / bipartite ground truth
    (use :class:`OraclePairLabeller` for that).
    """

    def __init__(self, truth: dict):
        # Keys are coerced to str so an int runtime id (e.g. a generated z_id)
        # matches a truth file read as strings.
        self.truth = {str(k): v for k, v in truth.items()}

    def decide(self, r: pd.Series, cfg: ZinggConf) -> Decision:
        l = str(r[f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"])
        rr = str(r[f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"])
        a = self.truth.get(l)
        b = self.truth.get(rr)
        if a is not None and b is not None:
            return Decision.Match if a == b else Decision.NonMatch
        return Decision.Unknown


class OraclePairLabeller(RowLabeller):
    """Labels pairs from a ground-truth set of matching id pairs (closed world).

    A pair is a match iff ``{l_id, r_id}`` is in ``positives``; every other pair
    is a non-match. Unlike :class:`OracleLabeller` this represents arbitrary
    bipartite / N:M ground truth (one record may match several others), which a
    partition map cannot. ``known_ids`` (optional) restricts labelling to pairs
    whose both ids are known to the oracle — pairs touching an unknown id are
    deferred (``Unknown``) instead of asserted non-match.
    """

    def __init__(self, positives, known_ids=None):
        # Ids coerced to str so int runtime ids match a str truth file (see
        # :class:`OracleLabeller`).
        self.positives = {frozenset(str(x) for x in p) for p in positives}
        self.known_ids = {str(x) for x in known_ids} if known_ids is not None else None

    def decide(self, r: pd.Series, cfg: ZinggConf) -> Decision:
        l = str(r[f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"])
        rr = str(r[f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"])
        if self.known_ids is not None and (l not in self.known_ids or rr not in self.known_ids):
            return Decision.Unknown
        return Decision.Match if frozenset((l, rr)) in self.positives else Decision.NonMatch
