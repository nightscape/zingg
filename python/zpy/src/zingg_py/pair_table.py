"""Side-by-side pair renderer — port of ``PairTable.scala``."""

from __future__ import annotations

import os

import pandas as pd

from .schema import ZinggConf

_SEP = " │ "
_MIN_COL = 8


def default_width() -> int:
    try:
        w = int(os.environ.get("COLUMNS", "").strip())
        return w if w >= 40 else 100
    except ValueError:
        return 100


def _value(row, prefix: str, name: str) -> str:
    v = row.get(f"{prefix}{name}")
    if v is None or (isinstance(v, float) and pd.isna(v)):
        return ""
    return str(v)


def _pad(s: str, w: int) -> str:
    return s if len(s) >= w else s + " " * (w - len(s))


def _wrap(s: str, w: int) -> list[str]:
    """Greedy word-wrap to `w` columns; over-long tokens are hard-split."""
    if s == "":
        return [""]
    out: list[str] = []
    line = ""
    for word in s.split(" "):
        while len(word) > w:
            if line:
                out.append(line); line = ""
            out.append(word[:w])
            word = word[w:]
        if not line:
            line = word
        elif len(line) + 1 + len(word) <= w:
            line += " " + word
        else:
            out.append(line); line = word
    out.append(line)
    return out


def render(cfg: ZinggConf, row, width: int = 100,
           left_label: str = "left", right_label: str = "right") -> list[str]:
    field_width = max(len(x) for x in ["field"] + [f.name for f in cfg.fields])
    avail = width - field_width - len(_SEP) * 2
    col_width = max(_MIN_COL, avail // 2)

    header = _pad("field", field_width) + _SEP + _pad(left_label, col_width) + _SEP + right_label
    rule = "─" * field_width + "─┼─" + "─" * col_width + "─┼─" + "─" * col_width

    body: list[str] = []
    for f in cfg.fields:
        left = _wrap(_value(row, ZinggConf.LEFT_PREFIX, f.name), col_width)
        right = _wrap(_value(row, ZinggConf.RIGHT_PREFIX, f.name), col_width)
        for i in range(max(len(left), len(right))):
            field_cell = _pad(f.name, field_width) if i == 0 else _pad("", field_width)
            l = left[i] if i < len(left) else ""
            r = right[i] if i < len(right) else ""
            body.append(field_cell + _SEP + _pad(l, col_width) + _SEP + r)
    return [header, rule] + body
