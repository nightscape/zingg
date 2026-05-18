"""Blocking hash functions — port of ``Hash.scala``.

Every hash is a stateless ``Any -> Optional[str]`` function. null *and*
blank/whitespace-only inputs return ``None`` ("no blocking signal") so that
blank-on-this-field records do not collapse into one shared ``""`` block.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from typing import Any, Optional, Sequence

from .schema import FieldDef, MatchType


def _s(v: Any) -> Optional[str]:
    """Stringify, treating None / NaN / blank as 'no value' (None)."""
    if v is None:
        return None
    if isinstance(v, float) and math.isnan(v):
        return None
    t = str(v)
    return None if t.strip() == "" else t


class Hash:
    name: str

    def __call__(self, v: Any) -> Optional[str]:  # pragma: no cover - interface
        raise NotImplementedError

    def __eq__(self, other):
        return isinstance(other, Hash) and self.name == other.name

    def __hash__(self):
        return hash(self.name)


class IdentityString(Hash):
    name = "identityString"

    def __call__(self, v: Any) -> Optional[str]:
        return _s(v)


@dataclass(eq=False)
class FirstChars(Hash):
    n: int

    @property
    def name(self) -> str:
        return f"firstChars({self.n})"

    def __call__(self, v: Any) -> Optional[str]:
        t = _s(v)
        return None if t is None else t[: self.n]


@dataclass(eq=False)
class LastChars(Hash):
    n: int

    @property
    def name(self) -> str:
        return f"lastChars({self.n})"

    def __call__(self, v: Any) -> Optional[str]:
        t = _s(v)
        return None if t is None else t[-self.n:]


class LastWord(Hash):
    name = "lastWord"

    def __call__(self, v: Any) -> Optional[str]:
        t = _s(v)
        if t is None:
            return None
        parts = [p for p in re.split(r"\s+", t) if p]
        return parts[-1] if parts else None


@dataclass(eq=False)
class RangeLong(Hash):
    bucket: int

    @property
    def name(self) -> str:
        return f"rangeLong({self.bucket})"

    def __call__(self, v: Any) -> Optional[str]:
        if v is None:
            return None
        if isinstance(v, bool):
            return None
        if isinstance(v, (int, float)):
            if isinstance(v, float) and math.isnan(v):
                return None
            # Match Java long division: truncate the value to a long, then
            # integer-divide truncating toward zero.
            long_val = int(v)
            return str(int(long_val / self.bucket))
        return None


@dataclass(eq=False)
class RegexExtract(Hash):
    pattern: str

    def __post_init__(self):
        self._re = re.compile(self.pattern)

    @property
    def name(self) -> str:
        return f"regex({self.pattern})"

    def __call__(self, v: Any) -> Optional[str]:
        t = _s(v)
        if t is None:
            return None
        m = self._re.search(t)
        return m.group(0).upper() if m else None


_IDENTITY = IdentityString()
_LAST_WORD = LastWord()


def hashes_for(mt: MatchType) -> Sequence[Hash]:
    """Candidate hashes the blocking-tree *learner* may try for a match type."""
    if mt == MatchType.Exact:
        return [_IDENTITY]
    if mt == MatchType.Email:
        return [_IDENTITY, FirstChars(3), LastChars(3)]
    if mt == MatchType.Numeric:
        return [RangeLong(10), RangeLong(100), RangeLong(1000)]
    if mt == MatchType.Fuzzy:
        return [FirstChars(2), FirstChars(3), FirstChars(5), LastChars(2), LastChars(3), _LAST_WORD]
    if mt == MatchType.Text:
        return [_LAST_WORD, RegexExtract(MatchType.CvePattern)]
    if mt.is_regex:
        return [RegexExtract(mt.pattern)]
    if mt == MatchType.Custom:
        return [_IDENTITY]
    raise ValueError(f"unknown match type {mt}")


def cold_start_hashes(mt: MatchType) -> Sequence[Hash]:
    """High-recall hashes for cold-start (pre-labels) canopy blocking."""
    if mt == MatchType.Exact:
        return [_IDENTITY]
    if mt == MatchType.Email:
        return [_IDENTITY]
    if mt == MatchType.Numeric:
        return [RangeLong(100)]
    if mt == MatchType.Fuzzy:
        return [FirstChars(3)]
    if mt == MatchType.Text:
        return [RegexExtract(MatchType.CvePattern), _LAST_WORD]
    if mt.is_regex:
        return [RegexExtract(mt.pattern)]
    if mt == MatchType.Custom:
        return [_IDENTITY]
    raise ValueError(f"unknown match type {mt}")


def candidates_for(fields: Sequence[FieldDef]) -> list[tuple[FieldDef, Hash]]:
    """Every (blockable field, candidate hash) the learner may pick from."""
    out: list[tuple[FieldDef, Hash]] = []
    seen: set[tuple[str, str]] = set()
    for f in fields:
        if not f.blockable:
            continue
        for mt in f.match_types:
            for h in hashes_for(mt):
                key = (f.name, h.name)
                if key not in seen:
                    seen.add(key)
                    out.append((f, h))
    return out


_REGISTRY = [
    IdentityString(),
    FirstChars(2), FirstChars(3), FirstChars(5),
    LastChars(2), LastChars(3),
    LastWord(),
    RangeLong(10), RangeLong(100), RangeLong(1000),
    RegexExtract(MatchType.CvePattern),
]
_BY_NAME = {h.name: h for h in _REGISTRY}


def by_name(name: str) -> Hash:
    """Resolve a hash from its ``name``; any ``regex(<pattern>)`` round-trips."""
    if name in _BY_NAME:
        return _BY_NAME[name]
    if name.startswith("regex(") and name.endswith(")"):
        return RegexExtract(name[len("regex("):-1])
    raise ValueError(f"unknown hash '{name}'")
