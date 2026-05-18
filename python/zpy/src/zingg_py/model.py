"""Classifier — port of ``Model.scala`` (``Classifier``).

Spark's ``PolynomialExpansion(degree=2)`` + ``LogisticRegression`` pipeline maps
to scikit-learn's ``PolynomialFeatures(degree=2)`` + ``StandardScaler`` (Spark LR
standardizes by default) + ``LogisticRegression``. The wrapper keeps the model
well-defined when the label set has a single class (the prior/constant case),
which Spark MLlib tolerates but bare sklearn does not.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import PolynomialFeatures, StandardScaler

from . import features
from .schema import ZinggConf

# Spark's LR minimizes (1/N)·Σloss + regParam·½‖w‖²; sklearn minimizes
# ½‖w‖² + C·Σloss. Matching the two gives C = 1/(N·regParam). With Spark's
# regParam=0.01 that is C = 100/N — re-derived per fit from the sample count so
# the effective regularization tracks the Scala/MLlib model rather than
# under-regularizing (which overfits label noise and collapses precision).
_REG_PARAM = 0.01


class TrainedModel:
    def __init__(self, pipeline: Pipeline | None, constant: float | None):
        self._pipeline = pipeline
        self._constant = constant

    def score(self, X: np.ndarray) -> np.ndarray:
        if self._constant is not None:
            return np.full(X.shape[0], self._constant, dtype=float)
        proba = self._pipeline.predict_proba(X)
        one_idx = list(self._pipeline.classes_).index(1.0)
        return proba[:, one_idx]


def _pipeline(n: int) -> Pipeline:
    c = 1.0 / (max(n, 1) * _REG_PARAM)
    return Pipeline([
        ("poly", PolynomialFeatures(degree=2, include_bias=False)),
        ("scale", StandardScaler()),
        ("lr", LogisticRegression(C=c, max_iter=1000)),
    ])


def train(labeled: pd.DataFrame, cfg: ZinggConf) -> TrainedModel:
    X = features.feature_matrix(labeled)
    y = labeled[cfg.label_col].to_numpy(dtype=float)
    classes = np.unique(y)
    if len(classes) < 2:
        # Degenerate label set: predict the single class as a constant.
        return TrainedModel(None, float(classes[0]) if len(classes) else 0.0)
    pipe = _pipeline(len(y))
    pipe.fit(X, y)
    return TrainedModel(pipe, None)


def score(model: TrainedModel, pairs: pd.DataFrame, cfg: ZinggConf) -> pd.DataFrame:
    out = pairs.copy()
    if pairs.empty:
        out[cfg.score_col] = pd.Series([], dtype=float)
        return out
    X = features.feature_matrix(pairs)
    out[cfg.score_col] = model.score(X)
    return out
