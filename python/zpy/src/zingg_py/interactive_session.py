"""Interactive active-learning session — port of ``InteractiveSession.scala``.

Pair selection is driven by a Bayesian logistic regression with a Laplace
posterior. Each pair has an augmented design vector ``psi = [1, proxy, poly(z_features)]``
where ``proxy`` is the unsupervised similarity mean and ``poly`` the degree-2
expansion the final model also uses. The prior is centred so cold start orders
pairs by the similarity proxy; every label sharpens the same posterior.

On top of that sits a second, tiny Bayesian model that keeps the label stream
balanced. A symmetric ``Beta(a0, a0)`` prior on the class balance, updated with
the ``m`` matches and ``u`` non-matches seen so far, gives the posterior-mean
non-match rate ``p_seek = (a0 + u) / (2 a0 + m + u)``. Each ``next_pair`` flips a
``Bernoulli(p_seek)``: on a hit it returns the most *informative likely-match*
(max BALD among the unseen pairs of highest posterior-predictive match
probability), otherwise it falls back to pure BALD. Every non-match raises
``p_seek`` (the stream is starved of positives, so go fetch one); every match
lowers it. The controller is self-correcting toward a 50/50 stream, the pull is
stronger the more imbalanced and the fewer the labels (``a0`` sets the rate).
"""

from __future__ import annotations

from typing import Optional

import numpy as np
import pandas as pd
from sklearn.preprocessing import PolynomialFeatures

from . import features
from .bayesian_logreg import BayesianLogReg
from .schema import ZinggConf
from .zingg import Zingg


class InteractiveSession:
    def __init__(self, cfg: ZinggConf, featured: pd.DataFrame,
                 prior_slope: float = 6.0, prior_precision: float = 1.0,
                 balance_prior: float = 1.0, seed: Optional[int] = 0):
        assert prior_precision > 0.0, f"prior_precision must be > 0, got {prior_precision}"
        assert balance_prior > 0.0, f"balance_prior must be > 0, got {balance_prior}"
        self.cfg = cfg
        self._balance_prior = balance_prior
        self._rng = np.random.RandomState(None if seed is None else int(seed) % (2 ** 32))
        self.featured = featured.reset_index(drop=True)
        self._l_id = f"{ZinggConf.LEFT_PREFIX}{cfg.id_col}"
        self._r_id = f"{ZinggConf.RIGHT_PREFIX}{cfg.id_col}"

        n = len(self.featured)
        if n == 0:
            self.psi = np.zeros((0, 0))
            self._key_to_index: dict = {}
        else:
            f_mat = features.feature_matrix(self.featured)
            poly = PolynomialFeatures(degree=2, include_bias=False).fit_transform(f_mat)
            proxy = np.where(f_mat.shape[1] == 0, 0.0, f_mat.mean(axis=1)) if f_mat.shape[1] else np.zeros(n)
            ones = np.ones((n, 1))
            self.psi = np.hstack([ones, proxy.reshape(-1, 1), poly])
            self._key_to_index = {
                (self.featured.at[i, self._l_id], self.featured.at[i, self._r_id]): i
                for i in range(n)
            }

        dim = self.psi.shape[1]
        prior_mean = np.zeros(dim)
        if dim >= 2:
            prior_mean[0] = -prior_slope * 0.5
            prior_mean[1] = prior_slope
        self._blr = BayesianLogReg(prior_mean, np.full(dim, prior_precision))

        self._shown: set[int] = set()
        self._labels: list[tuple[int, float]] = []  # (pool index, label)
        self._model: Optional[tuple] = None

    def next_pair(self) -> Optional[pd.Series]:
        """The next pair to label, refit in realtime after every label.

        With probability ``p_seek`` (the Beta posterior-mean non-match rate) this
        returns the most informative likely-match; otherwise the unseen pair of
        maximal BALD. So a run of non-matches steadily raises the odds of being
        handed a probable match next."""
        unseen = [i for i in range(len(self.featured)) if i not in self._shown]
        if not unseen:
            return None
        fitted = self._fit_posterior()
        if self._rng.random_sample() < self._seek_probability():
            idx = self._pick_probable_match(fitted, unseen)
        else:
            idx = self._pick_uncertain(fitted, unseen)
        self._shown.add(idx)
        return self.featured.iloc[idx]

    def _class_counts(self) -> tuple[int, int]:
        m = sum(1 for _, lab in self._labels if lab == 1.0)
        return m, len(self._labels) - m

    def _seek_probability(self) -> float:
        """Beta(a0, a0) posterior-mean non-match rate — the odds of seeking a match."""
        m, u = self._class_counts()
        a = self._balance_prior
        return (a + u) / (2.0 * a + m + u)

    def _pick_uncertain(self, fitted, unseen: list[int]) -> int:
        return max(unseen, key=lambda i: fitted.bald(self.psi[i]))

    def _pick_probable_match(self, fitted, unseen: list[int]) -> int:
        """Most informative pair among the unseen pairs likeliest to be a match."""
        by_prob = sorted(unseen, key=lambda i: fitted.predict_prob(self.psi[i]), reverse=True)
        top = by_prob[: max(1, len(unseen) // 5)]
        return max(top, key=lambda i: fitted.bald(self.psi[i]))

    def submit_label(self, pair: pd.Series, label: float) -> None:
        assert label in (0.0, 1.0), f"label must be 0.0 or 1.0, got {label}"
        idx = self._key_to_index[(pair[self._l_id], pair[self._r_id])]
        self._shown.add(idx)
        self._labels.append((idx, label))

    def skip(self, pair: pd.Series) -> None:
        self._shown.add(self._key_to_index[(pair[self._l_id], pair[self._r_id])])

    @property
    def labeled(self) -> pd.DataFrame:
        """Labels collected so far as a DataFrame (featured columns + label)."""
        if not self._labels:
            out = self.featured.iloc[0:0].copy()
            out[self.cfg.label_col] = pd.Series([], dtype=float)
            return out
        idxs = [i for i, _ in self._labels]
        out = self.featured.iloc[idxs].reset_index(drop=True).copy()
        out[self.cfg.label_col] = [lab for _, lab in self._labels]
        return out

    @property
    def current_model(self):
        return self._model

    def final_model(self):
        m, t = Zingg(self.cfg).train(self.labeled)
        self._model = (m, t)
        return m, t

    def close(self) -> None:
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def _fit_posterior(self):
        n = len(self._labels)
        dim = self.psi.shape[1]
        x = np.zeros((n, dim))
        y = np.zeros(n)
        for r, (idx, lab) in enumerate(self._labels):
            x[r] = self.psi[idx]
            y[r] = lab
        return self._blr.fit(x, y)
