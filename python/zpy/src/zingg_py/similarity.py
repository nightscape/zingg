"""Similarity features — port of ``Similarity.scala``.

Uses ``rapidfuzz`` for Jaro-Winkler and Levenshtein (the commons-text functions
in the Scala original); Jaccard / bigram-Jaccard / numeric closeness are
hand-rolled exactly as in Scala.
"""

from __future__ import annotations

import math
import re
from typing import Any

from rapidfuzz.distance import JaroWinkler, Levenshtein

from .hashing import RegexExtract
from .schema import MatchType

_WORD_SPLIT = re.compile(r"\W+")


def _str(a: Any):
    """Scala ``Similarity.s``: None stays None, everything else stringified.

    Unlike the blocking hash stringify this does *not* treat blank as None.
    """
    if a is None:
        return None
    if isinstance(a, float) and math.isnan(a):
        return None
    return str(a)


def jaro_winkler(a, b) -> float:
    if a is None or b is None:
        return 0.0
    return JaroWinkler.similarity(a, b)


def normalized_levenshtein(a, b) -> float:
    if a is None or b is None:
        return 0.0
    max_len = max(len(a), len(b))
    if max_len == 0:
        return 1.0
    return 1.0 - (Levenshtein.distance(a, b) / max_len)


def jaccard(a, b) -> float:
    if a is None or b is None:
        return 0.0
    sa = {p for p in _WORD_SPLIT.split(a.lower()) if p}
    sb = {p for p in _WORD_SPLIT.split(b.lower()) if p}
    if not sa and not sb:
        return 1.0
    return len(sa & sb) / len(sa | sb)


def _bigrams(s: str) -> set[str]:
    t = s.lower()
    if len(t) < 2:
        return set()
    return {t[i:i + 2] for i in range(len(t) - 1)}


def bigram_jaccard(a, b) -> float:
    if a is None or b is None:
        return 0.0
    ga, gb = _bigrams(a), _bigrams(b)
    if not ga and not gb:
        return 1.0
    if not ga or not gb:
        return 0.0
    return len(ga & gb) / len(ga | gb)


def exact(a, b) -> float:
    if a is None or b is None:
        return 0.0
    return 1.0 if a == b else 0.0


def numeric_close(a: Any, b: Any) -> float:
    if a is None or b is None:
        return 0.0
    if isinstance(a, bool) or isinstance(b, bool):
        return 0.0
    if not isinstance(a, (int, float)) or not isinstance(b, (int, float)):
        return 0.0
    if (isinstance(a, float) and math.isnan(a)) or (isinstance(b, float) and math.isnan(b)):
        return 0.0
    d = abs(float(a) - float(b))
    m = max(abs(float(a)), abs(float(b)))
    if m == 0.0:
        return 1.0 if d == 0.0 else 0.0
    return max(0.0, 1.0 - d / m)


_extractors: dict[str, RegexExtract] = {}


def _extractor(pattern: str) -> RegexExtract:
    ex = _extractors.get(pattern)
    if ex is None:
        ex = RegexExtract(pattern)
        _extractors[pattern] = ex
    return ex


def features(mt: MatchType, a: Any, b: Any) -> list[float]:
    if mt == MatchType.Exact:
        return [exact(_str(a), _str(b))]
    if mt == MatchType.Fuzzy:
        return [jaro_winkler(_str(a), _str(b)),
                normalized_levenshtein(_str(a), _str(b)),
                jaccard(_str(a), _str(b))]
    if mt == MatchType.Numeric:
        return [numeric_close(a, b)]
    if mt == MatchType.Email:
        return [exact(_str(a), _str(b)), jaro_winkler(_str(a), _str(b))]
    if mt == MatchType.Text:
        return [bigram_jaccard(_str(a), _str(b)), jaccard(_str(a), _str(b))]
    if mt.is_regex:
        ex = _extractor(mt.pattern)
        return [exact(ex(a), ex(b))]
    if mt == MatchType.Custom:
        return [exact(_str(a), _str(b))]
    raise ValueError(f"unknown match type {mt}")


def feature_width(mt: MatchType) -> int:
    if mt in (MatchType.Exact, MatchType.Numeric, MatchType.Custom) or mt.is_regex:
        return 1
    if mt in (MatchType.Email, MatchType.Text):
        return 2
    if mt == MatchType.Fuzzy:
        return 3
    raise ValueError(f"unknown match type {mt}")
