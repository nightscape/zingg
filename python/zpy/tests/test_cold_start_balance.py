"""Port of ``ColdStartBalancePropertyTest.scala``.

The cold-start batch must be roughly label-balanced, not all-"no": the oracle's
yes-fraction over the presented batch should sit near balance. Median over K
plans (Hypothesis drives the seeds).
"""

from __future__ import annotations

import numpy as np
from hypothesis import given, settings
from hypothesis import strategies as st

import plan_oracle as P
from zingg_py.schema import FieldDef, MatchType, ZinggConf
from zingg_py.zingg import Zingg

cfg = ZinggConf(
    fields=[FieldDef("summary", MatchType.Text), FieldDef("description", MatchType.cve),
            FieldDef("priority", MatchType.Exact)],
    block_size=50, threshold=0.5,
)


def _yes_fraction(seed):
    plan = P.build(seed, n_entities=6, variants_per_entity=4)
    df = plan.to_df(cfg)
    cands = Zingg(cfg).find_training_data(df, n=30)
    labeled = plan.labeller.label(cands, cfg)
    total = len(labeled)
    if total == 0:
        return 0.0
    return float((labeled[cfg.label_col] == 1.0).sum()) / total


@settings(max_examples=4, deadline=None)
@given(st.lists(st.integers(1, 2**31 - 1), min_size=7, max_size=7, unique=True))
def test_cold_start_batch_is_roughly_balanced(seeds):
    fractions = [_yes_fraction(s) for s in seeds]
    m = float(np.median(fractions))
    assert 0.2 <= m <= 0.8, f"cold-start batch should be balanced; median yes-fraction={m:.2f} ({fractions})"
