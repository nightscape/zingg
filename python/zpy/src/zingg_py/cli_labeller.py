"""Interactive CLI labeller — port of ``CliLabeller.scala``.

Prints each pair as a side-by-side table and reads one answer from stdin:
  y / 1 / yes → match (1.0)
  n / 0 / no  → non-match (0.0)
  s           → skip this pair (no label)
  q / quit    → quit; remaining pairs get no label
"""

from __future__ import annotations

import sys

import pandas as pd

from . import pair_table
from .labeller import Labeller, OnlineAnswer
from .schema import ZinggConf


class CliLabeller(Labeller):
    def __init__(self, out=sys.stdout, inp=sys.stdin, width: int | None = None):
        self._out = out
        self._in = inp
        self._width = width if width is not None else pair_table.default_width()

    def ask(self, row, cfg: ZinggConf, header: str) -> OnlineAnswer:
        print("", file=self._out)
        print(header, file=self._out)
        for line in pair_table.render(cfg, row, self._width):
            print(line, file=self._out)
        print("Match? [y]es / [n]o / [s]kip / [q]uit: ", end="", file=self._out, flush=True)
        line = self._in.readline()
        if line == "":  # EOF
            return OnlineAnswer.quit()
        ans = line.strip().lower()
        if ans in ("y", "1", "yes"):
            return OnlineAnswer.label(1.0)
        if ans in ("n", "0", "no"):
            return OnlineAnswer.label(0.0)
        if ans in ("q", "quit"):
            return OnlineAnswer.quit()
        return OnlineAnswer.skip()

    def label(self, pairs: pd.DataFrame, cfg: ZinggConf) -> pd.DataFrame:
        kept, labels = [], []
        total = len(pairs)
        for idx, (_, r) in enumerate(pairs.iterrows()):
            ans = self.ask(r, cfg, f"━━━ Pair {idx + 1} / {total} ━━━")
            if ans.is_quit:
                break
            if ans.is_label:
                kept.append(r)
                labels.append(ans.value)
        if not kept:
            out = pairs.iloc[0:0].copy()
            out[cfg.label_col] = pd.Series([], dtype=float)
            return out
        out = pd.DataFrame(kept).reset_index(drop=True)
        out[cfg.label_col] = labels
        return out
