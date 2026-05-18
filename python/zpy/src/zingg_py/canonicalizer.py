"""Canonicalisation — port of ``Canonicalizer.scala`` + ``Normalizer``.

Projects a heterogeneous source onto the canonical (logical) schema: map each
source's physical columns to the shared logical fields, apply the configured
per-field normalizer, cast to string, rename, and tag with ``z_source``.
"""

from __future__ import annotations

import re

import pandas as pd

from .config_loader import IO
from .schema import ZinggConf

_NON_DIGIT = re.compile(r"\D")
_NON_ALNUM = re.compile(r"[^a-z0-9]")


def _to_str(v):
    if v is None or (isinstance(v, float) and pd.isna(v)):
        return None
    return str(v)


def _normalize(name: str, series: pd.Series) -> pd.Series:
    key = name.strip().lower()
    s = series.map(_to_str)

    def app(fn):
        return s.map(lambda x: None if x is None else fn(x))

    if key == "lower":
        return app(str.lower)
    if key == "upper":
        return app(str.upper)
    if key == "trim":
        return app(str.strip)
    if key == "digits":
        return app(lambda x: _NON_DIGIT.sub("", x))
    if key == "alnum":
        return app(lambda x: _NON_ALNUM.sub("", x.lower()))
    raise ValueError(f"unknown normalizer '{key}'. Valid: lower|upper|trim|digits|alnum")


def canonicalize(df: pd.DataFrame, io: IO, cfg: ZinggConf) -> pd.DataFrame:
    data = {}
    for f in cfg.fields:
        physical = io.mapping.get(f.name, f.name)
        if physical in df.columns:
            norm = io.normalizers.get(f.name)
            col = _normalize(norm, df[physical]) if norm else df[physical].map(_to_str)
            data[f.name] = col.to_numpy()
        else:
            # A field the source never carries is null-filled. An *explicit*
            # mapping to a missing column is a config error: fail loudly.
            assert f.name not in io.mapping, (
                f"source '{io.name}' maps logical field '{f.name}' to column "
                f"'{physical}', which does not exist. Available columns: "
                f"{', '.join(df.columns)}"
            )
            data[f.name] = [None] * len(df)
    out = pd.DataFrame(data)
    out[ZinggConf.SOURCE_COL] = io.name
    return out
