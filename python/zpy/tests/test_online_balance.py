"""Port of ``OnlineBalancePropertyTest.scala``.

The online session re-fits after every label, so BALD should stop dredging the
obvious-non-match region and keep the yes/no stream roughly balanced with no
long single-class runs. Median over K sessions.
"""

from __future__ import annotations

import numpy as np
from hypothesis import given, settings
from hypothesis import strategies as st

import plan_oracle as P
from zingg_py.labeller import Decision
from zingg_py.schema import FieldDef, MatchType, ZinggConf
from zingg_py.zingg import Zingg

cfg = ZinggConf(
    fields=[FieldDef("summary", MatchType.Text), FieldDef("description", MatchType.cve),
            FieldDef("priority", MatchType.Exact)],
    block_size=50, threshold=0.5,
)


def _max_run(labels):
    if not labels:
        return 0
    best = cur = 1
    for prev, x in zip(labels, labels[1:]):
        cur = cur + 1 if x == prev else 1
        best = max(best, cur)
    return best


def _label_stream(seed, max_labels):
    plan = P.build(seed, n_entities=6, variants_per_entity=4)
    df = plan.to_df(cfg)
    oracle = plan.labeller
    session = Zingg(cfg).interactive_session(df)
    stream = []
    try:
        while len(stream) < max_labels:
            pair = session.next_pair()
            if pair is None:
                break
            d = oracle.decide(pair, cfg)
            if d == Decision.Match:
                session.submit_label(pair, 1.0); stream.append(1.0)
            elif d == Decision.NonMatch:
                session.submit_label(pair, 0.0); stream.append(0.0)
            else:
                session.skip(pair)
    finally:
        session.close()
    return stream


@settings(max_examples=3, deadline=None)
@given(st.lists(st.integers(1, 2**31 - 1), min_size=5, max_size=5, unique=True))
def test_online_session_keeps_label_stream_balanced(seeds):
    streams = [_label_stream(s, 24) for s in seeds]
    yes_fracs = [s.count(1.0) / len(s) for s in streams if s]
    runs = [float(_max_run(s)) for s in streams]
    med_yes = float(np.median(yes_fracs))
    med_run = float(np.median(runs))
    # The seek-balance controller deliberately surfaces probable matches, so on a
    # match-dense plan the stream runs somewhat yes-heavy (seek-mode pairs are
    # near-certain matches, explore-mode pairs a mix). The guard is against class
    # *starvation* — neither class vanishes — not against an exact 50/50 split.
    assert 0.3 <= med_yes <= 0.75, f"stream should be balanced; median yes-fraction={med_yes:.2f} ({yes_fracs})"
    assert med_run <= 8.0, f"no long single-class runs; median max-run={med_run:.0f} ({runs})"
