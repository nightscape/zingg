"""Port of ``BayesianLogRegTest.scala``.

Plan-driven oracle: a random ground-truth weight vector defines a logistic
model; we sample design + Bernoulli labels, fit the BLR with a weak prior so the
MAP ~ MLE, and check recovery. Like the Scala original we assert on the MEDIAN
over K random plans, not per-sample (a single unlucky near-separable draw is
high-variance). Hypothesis drives the K seeds; the deterministic BALD properties
are exact.
"""

from __future__ import annotations

import numpy as np
from hypothesis import given, settings
from hypothesis import strategies as st

from zingg_py.bayesian_logreg import BayesianLogReg, Fitted

K = 9
_seed_lists = st.lists(st.integers(1, 2**31 - 1), min_size=K, max_size=K, unique=True)


def _median(xs):
    return float(np.median(np.asarray(xs)))


def _random_weights(rng: np.random.RandomState) -> np.ndarray:
    d_base = 2 + rng.randint(3)  # 2..4 features
    w = np.zeros(d_base + 1)
    w[0] = -1.0 + 2.0 * rng.random_sample()           # intercept in [-1, 1]
    w[1:] = -2.5 + 5.0 * rng.random_sample(d_base)     # slopes in [-2.5, 2.5]
    return w


def _sample(w_true: np.ndarray, n: int, rng: np.random.RandomState):
    d = w_true.shape[0]
    x = np.empty((n, d))
    x[:, 0] = 1.0
    x[:, 1:] = rng.standard_normal((n, d - 1))
    p = 1.0 / (1.0 + np.exp(-(x @ w_true)))
    y = (rng.random_sample(n) < p).astype(float)
    return x, y


def _weak_prior(d: int) -> BayesianLogReg:
    return BayesianLogReg(np.zeros(d), np.full(d, 1e-3))


@settings(max_examples=4, deadline=None)
@given(_seed_lists)
def test_recovers_true_weights(seeds):
    errors = []
    for s in seeds:
        rng = np.random.RandomState(s)
        w_true = _random_weights(rng)
        x, y = _sample(w_true, 5000, rng)
        fitted = _weak_prior(w_true.shape[0]).fit(x, y)
        errors.append(np.max(np.abs(fitted.w - w_true)))
    assert _median(errors) < 0.5, f"median max-coordinate recovery error={_median(errors):.3f}"


@settings(max_examples=4, deadline=None)
@given(_seed_lists)
def test_posterior_contracts_with_more_data(seeds):
    ratios = []
    for s in seeds:
        rng = np.random.RandomState(s)
        w_true = _random_weights(rng)
        xs, ys = _sample(w_true, 300, rng)
        xl, yl = _sample(w_true, 6000, rng)
        small = _weak_prior(w_true.shape[0]).fit(xs, ys)
        large = _weak_prior(w_true.shape[0]).fit(xl, yl)
        ratios.append(np.trace(large.sigma) / np.trace(small.sigma))
    assert _median(ratios) < 0.3, f"median trace(Sigma) ratio={_median(ratios):.3f}"


@settings(max_examples=4, deadline=None)
@given(_seed_lists)
def test_predictive_probability_tracks_truth(seeds):
    maes = []
    for s in seeds:
        rng = np.random.RandomState(s)
        w_true = _random_weights(rng)
        x_tr, y_tr = _sample(w_true, 5000, rng)
        fitted = _weak_prior(w_true.shape[0]).fit(x_tr, y_tr)
        x_te, _ = _sample(w_true, 1000, rng)
        p_true = 1.0 / (1.0 + np.exp(-(x_te @ w_true)))
        p_pred = np.array([fitted.predict_prob(x_te[i]) for i in range(x_te.shape[0])])
        maes.append(np.mean(np.abs(p_pred - p_true)))
    assert _median(maes) < 0.05, f"median predictive-prob MAE={_median(maes):.3f}"


# ── BALD acquisition: deterministic structural properties ──────────────────────

def _bald_at(mu: float, v: float) -> float:
    w = np.array([mu, 0.0])
    sigma = np.array([[v, 0.0], [0.0, v]])
    return Fitted(w, sigma).bald(np.array([1.0, 0.0]))


def test_bald_vanishes_with_no_epistemic_uncertainty():
    assert _bald_at(0.0, 1e-10) < 1e-4


def test_bald_increases_with_epistemic_uncertainty():
    low, mid, high = _bald_at(0.0, 0.01), _bald_at(0.0, 0.5), _bald_at(0.0, 5.0)
    assert low < mid < high


def test_bald_favours_the_decision_boundary():
    boundary, near, far = _bald_at(0.0, 2.0), _bald_at(3.0, 2.0), _bald_at(8.0, 2.0)
    assert boundary > near > far
