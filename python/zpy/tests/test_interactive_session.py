"""Port of ``InteractiveSessionTest.scala``."""

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


@settings(max_examples=2, deadline=None)
@given(st.lists(st.integers(1, 2**31 - 1), min_size=5, max_size=5, unique=True))
def test_labels_unique_pairs_and_learns_usable_model(seeds):
    recalls, f1s = [], []
    for s in seeds:
        plan = P.build(s, 4, 4)
        df = plan.to_df(cfg)
        oracle = plan.labeller
        z = Zingg(cfg)
        session = z.interactive_session(df)
        try:
            seen = set()
            label_count = 0
            while label_count < 30:
                pair = session.next_pair()
                if pair is None:
                    break
                key = (pair[f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"],
                       pair[f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"])
                assert key not in seen, f"duplicate pair offered: {key}"
                seen.add(key)
                d = oracle.decide(pair, cfg)
                if d == Decision.Match:
                    session.submit_label(pair, 1.0); label_count += 1
                elif d == Decision.NonMatch:
                    session.submit_label(pair, 0.0); label_count += 1
                else:
                    session.skip(pair)
            assert label_count > 0, "expected at least one definite label"
            model, tree = session.final_model()
            clusters = z.cluster(df, model, tree)
            pred = dict(zip(clusters[cfg.id_col], clusters[cfg.cluster_col]))
            _, recall, f1 = P.pairwise_f1(pred, plan.truth)
            recalls.append(recall)
            f1s.append(f1)
        finally:
            session.close()
    assert float(np.median(recalls)) >= 0.7, f"median recall={np.median(recalls):.3f}"
    assert float(np.median(f1s)) >= 0.3, f"median f1={np.median(f1s):.3f}"


@settings(max_examples=2, deadline=None)
@given(st.integers(1, 2**31 - 1))
def test_background_retrain_produces_model_eventually(seed):
    plan = P.build(seed, 3, 4)
    df = plan.to_df(cfg)
    oracle = plan.labeller
    session = Zingg(cfg).interactive_session(df)
    try:
        labelled = 0
        while labelled < 12:
            p = session.next_pair()
            if p is None:
                break
            d = oracle.decide(p, cfg)
            if d == Decision.Match:
                session.submit_label(p, 1.0); labelled += 1
            elif d == Decision.NonMatch:
                session.submit_label(p, 0.0); labelled += 1
            else:
                session.skip(p)
        session.final_model()
        assert session.current_model is not None or labelled == 0
    finally:
        session.close()
