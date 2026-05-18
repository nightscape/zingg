"""Port of ``CandidateDiversityPropertyTest.scala``.

Properties over the candidate set shown to the labeller:
  A. A record carrying no signal on the blocking dimension(s) is never a
     candidate (a hard invariant — asserted per Hypothesis example).
  B. No single left-side value tuple dominates the candidate set (high-variance
     — asserted on the median over K datasets, as in the Scala original).
"""

from __future__ import annotations

import random

import numpy as np
import pandas as pd
from hypothesis import given, settings
from hypothesis import strategies as st

from zingg_py.schema import FieldDef, MatchType, ZinggConf
from zingg_py.zingg import Zingg

_BLANKS = ["", "   ", "\t", " \n "]
_seeds = st.integers(1, 2**31 - 1)


def _cfg(seed):
    return ZinggConf(
        fields=[FieldDef("name", MatchType.Fuzzy), FieldDef("email", MatchType.Email)],
        block_size=50,
        sample_seed=seed,
    )


def _gen_data(seed):
    """Mostly-blank-`name` records plus one all-blank "villain" (id 0)."""
    rng = random.Random(seed)
    n_real = 25 + rng.randrange(25)
    rows = [{"z_id": 0, "name": _BLANKS[rng.randrange(4)], "email": _BLANKS[rng.randrange(4)]}]
    for i in range(1, n_real + 1):
        name = f"Person {rng.randrange(8)}" if rng.random() < 0.3 else _BLANKS[rng.randrange(4)]
        rows.append({"z_id": i, "name": name, "email": f"user{i}@example.com"})
    return pd.DataFrame(rows), 0


def _candidate_id_pairs(df, c):
    cands = Zingg(c).find_training_data(df, n=30)
    l_id = f"{ZinggConf.LEFT_PREFIX}{c.id_col}"
    r_id = f"{ZinggConf.RIGHT_PREFIX}{c.id_col}"
    return list(zip(cands[l_id], cands[r_id]))


def _top_left_share(df, c):
    cands = Zingg(c).find_training_data(df, n=30)
    l_cols = [f"{ZinggConf.LEFT_PREFIX}{f.name}" for f in c.fields]
    rows = [tuple("" if pd.isna(v) else str(v).strip() for v in row)
            for row in cands[l_cols].itertuples(index=False, name=None)]
    if not rows:
        return 0.0
    counts = {}
    for r in rows:
        counts[r] = counts.get(r, 0) + 1
    return max(counts.values()) / len(rows)


# ─── Property A: signal-free records are never candidates ──────────────────────

@settings(max_examples=10, deadline=None)
@given(_seeds)
def test_blank_record_is_never_a_candidate(seed):
    df, villain = _gen_data(seed)
    appearances = sum(1 for l, r in _candidate_id_pairs(df, _cfg(seed)) if l == villain or r == villain)
    assert appearances == 0, f"all-blank record shown to labeller (seed={seed}, {appearances}x)"


# ─── Property B: no single value tuple dominates the left side ─────────────────

@settings(max_examples=3, deadline=None)
@given(st.lists(_seeds, min_size=8, max_size=8, unique=True))
def test_no_left_value_tuple_dominates(seeds):
    shares = [_top_left_share(*(_gen_data(s)[:1] + (_cfg(s),))) for s in seeds]
    median = float(np.median(shares))
    assert median <= 0.5, f"a left-side value tuple dominated; median share={median:.2f} (shares={shares})"
