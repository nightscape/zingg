"""Config schema — port of ``Schema.scala``.

``MatchType`` is the one Scala-ism that needs care: it is a sealed trait with
case objects (``Exact``, ``Fuzzy``, …) plus a ``Regex(pattern)`` case class. We
model it as a single frozen value type keyed on ``(kind, pattern)`` so it is
hashable (used in sets / as dict keys / ``distinct``) and compares by value, with
the singletons exposed as class attributes and ``Regex`` as a constructor.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar, Optional, Sequence, Union


@dataclass(frozen=True)
class MatchType:
    kind: str
    pattern: Optional[str] = None

    # singletons / shared constants (assigned after the class body). Marked
    # ClassVar so the dataclass machinery does not treat them as fields.
    Exact: ClassVar["MatchType"]
    Fuzzy: ClassVar["MatchType"]
    Numeric: ClassVar["MatchType"]
    Email: ClassVar["MatchType"]
    Text: ClassVar["MatchType"]
    Custom: ClassVar["MatchType"]
    CvePattern: ClassVar[str]
    cve: ClassVar["MatchType"]

    def __repr__(self) -> str:
        return f"MatchType.Regex({self.pattern!r})" if self.kind == "regex" else f"MatchType.{self.kind}"

    @staticmethod
    def Regex(pattern: str) -> "MatchType":
        return MatchType("regex", pattern)

    @property
    def is_regex(self) -> bool:
        return self.kind == "regex"


MatchType.Exact = MatchType("exact")
MatchType.Fuzzy = MatchType("fuzzy")
MatchType.Numeric = MatchType("numeric")
MatchType.Email = MatchType("email")
MatchType.Text = MatchType("text")
MatchType.Custom = MatchType("custom")
MatchType.CvePattern = r"(?i)CVE-\d{4}-\d{4,7}"
MatchType.cve = MatchType.Regex(MatchType.CvePattern)


@dataclass(frozen=True)
class FieldDef:
    """A logical field and the matchers applied to it.

    A field may carry several matchers — e.g. a fuzzy text comparison *and* a
    regex id extraction on the same column — each contributing its own
    similarity feature(s) and (when blockable) its own blocking canopies.
    """

    name: str
    match_types: tuple[MatchType, ...]
    blockable: bool = True

    def __init__(
        self,
        name: str,
        match_types: Union[MatchType, Sequence[MatchType]],
        blockable: bool = True,
    ):
        mts = (match_types,) if isinstance(match_types, MatchType) else tuple(match_types)
        object.__setattr__(self, "name", name)
        object.__setattr__(self, "match_types", mts)
        object.__setattr__(self, "blockable", blockable)


@dataclass(frozen=True)
class ZinggConf:
    fields: tuple[FieldDef, ...]
    id_col: str = "z_id"
    label_col: str = "z_label"
    prediction_col: str = "z_prediction"
    score_col: str = "z_score"
    cluster_col: str = "z_cluster"
    block_size: int = 500
    num_partitions: int = 200
    threshold: float = 0.5
    sample_seed: Optional[int] = None

    SOURCE_COL = "z_source"
    LEFT_PREFIX = "l_"
    RIGHT_PREFIX = "r_"

    def __init__(self, fields, **kw):
        object.__setattr__(self, "fields", tuple(fields))
        for k, v in dict(
            id_col="z_id", label_col="z_label", prediction_col="z_prediction",
            score_col="z_score", cluster_col="z_cluster", block_size=500,
            num_partitions=200, threshold=0.5, sample_seed=None,
        ).items():
            object.__setattr__(self, k, kw.get(k, v))
