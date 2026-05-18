"""Port of ``ZinggPropertyTest.scala``.

Each test draws K random plans (Hypothesis seeds) -> DataFrames -> ORACLE labels
-> train -> cluster -> pairwise F1. Asserts on the MEDIAN over K: clustering is
connected-components, so one false-positive edge can collapse precision, making
per-sample assertions high-variance. The median fails only on a systematic
regression.
"""

from __future__ import annotations

import random

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

_K7 = st.lists(st.integers(1, 2**31 - 1), min_size=7, max_size=7, unique=True)
_K5 = st.lists(st.integers(1, 2**31 - 1), min_size=5, max_size=5, unique=True)


def _median(xs):
    return float(np.median(xs))


def _pred_map(clusters):
    return dict(zip(clusters[cfg.id_col], clusters[cfg.cluster_col]))


def _cluster_f1(plan, df, model, tree):
    clusters = Zingg(cfg).cluster(df, model, tree)
    return P.pairwise_f1(_pred_map(clusters), plan.truth)[2]


def _train_and_evaluate(plan, holdout_fraction):
    truth = plan.truth
    if not truth:
        return 1.0, 1.0, 1.0
    rng = random.Random(plan.entities[0].entity_id if plan.entities else 0)
    shuffled = list(truth.keys())
    rng.shuffle(shuffled)
    n_holdout = int(len(shuffled) * holdout_fraction)
    holdout_ids = set(shuffled[:n_holdout])
    train_ids = set(shuffled[n_holdout:])

    train_plan = P.Plan(plan.entities, tuple(r for r in plan.rows if r.row_id in train_ids))
    labeled = P.labeled_training_set(cfg, train_plan, n_negatives=40)
    z = Zingg(cfg)
    model, tree = z.train(labeled)
    df = plan.to_df(cfg)
    pred = _pred_map(z.cluster(df, model, tree))

    eval_ids = holdout_ids if holdout_ids else set(truth.keys())
    pred_r = {k: v for k, v in pred.items() if k in eval_ids}
    truth_r = {k: v for k, v in truth.items() if k in eval_ids}
    return P.pairwise_f1(pred_r, truth_r)


@settings(max_examples=2, deadline=None)
@given(_K7)
def test_cve_id_enables_good_clustering_typically(seeds):
    results = [_train_and_evaluate(P.build(s, 6, 4), 0.3) for s in seeds]
    med_f1 = _median([r[2] for r in results])
    med_prec = _median([r[0] for r in results])
    assert med_f1 >= 0.75 and med_prec >= 0.7, f"f1={med_f1:.3f} precision={med_prec:.3f}"


@settings(max_examples=2, deadline=None)
@given(_K7)
def test_perfect_labels_recover_ground_truth_on_training(seeds):
    f1s = []
    for s in seeds:
        plan = P.build(s, 4, 4)
        labeled = P.labeled_training_set(cfg, plan, n_negatives=50)
        model, tree = Zingg(cfg).train(labeled)
        f1s.append(_cluster_f1(plan, plan.to_df(cfg), model, tree))
    assert _median(f1s) >= 0.85, f"median f1={_median(f1s):.3f}"


@settings(max_examples=2, deadline=None)
@given(_K7)
def test_singleton_entities_rarely_false_merge(seeds):
    precs = []
    for s in seeds:
        plan = P.build(s, 8, 1)
        aux = P.build(s + 1, 4, 3)
        labeled = P.labeled_training_set(cfg, aux, n_negatives=30)
        z = Zingg(cfg)
        model, tree = z.train(labeled)
        pred = _pred_map(z.cluster(plan.to_df(cfg), model, tree))
        precs.append(P.pairwise_f1(pred, plan.truth)[0])
    assert _median(precs) >= 0.8, f"median precision={_median(precs):.3f}"


@settings(max_examples=2, deadline=None)
@given(_K5)
def test_active_learning_does_not_regress(seeds):
    deltas = []
    for s in seeds:
        plan = P.build(s, 5, 4)
        df = plan.to_df(cfg)
        z = Zingg(cfg)
        cands1 = z.find_training_data(df, n=25)
        labeled1 = plan.labeller.label(cands1, cfg)
        m1, t1 = z.train(labeled1)
        f1_r1 = _cluster_f1(plan, df, m1, t1)

        cands2 = z.find_training_data(df, m1, t1, n=25)
        labeled2 = plan.labeller.label(cands2, cfg)
        l_id = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"
        r_id = f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"
        import pandas as pd
        combined = pd.concat([labeled1, labeled2], ignore_index=True).drop_duplicates(subset=[l_id, r_id])
        m2, t2 = z.train(combined)
        f1_r2 = _cluster_f1(plan, df, m2, t2)
        deltas.append(f1_r2 - f1_r1)
    assert _median(deltas) >= -0.1, f"median round2-round1 delta={_median(deltas):.3f}"


@settings(max_examples=2, deadline=None)
@given(_K7)
def test_tolerant_to_moderate_label_noise(seeds):
    f1s = []
    for s in seeds:
        plan = P.build(s, 6, 4)
        labeled = P.labeled_training_set_with_noise(cfg, plan, 40, 0.10, s + 7)
        model, tree = Zingg(cfg).train(labeled)
        f1s.append(_cluster_f1(plan, plan.to_df(cfg), model, tree))
    assert _median(f1s) >= 0.55, f"median f1={_median(f1s):.3f} at 10% noise"


@settings(max_examples=2, deadline=None)
@given(_K7)
def test_degrades_gracefully_with_heavy_noise(seeds):
    f1s = []
    for s in seeds:
        plan = P.build(s, 5, 4)
        labeled = P.labeled_training_set_with_noise(cfg, plan, 50, 0.30, s + 13)
        model, tree = Zingg(cfg).train(labeled)
        f1s.append(_cluster_f1(plan, plan.to_df(cfg), model, tree))
    assert _median(f1s) >= 0.2, f"median f1={_median(f1s):.3f} at 30% noise"


def test_empty_plan_produces_empty_output():
    plan = P.Plan((), ())
    df = plan.to_df(cfg)
    cands = Zingg(cfg).find_training_data(df, n=10)
    assert len(cands) == 0
