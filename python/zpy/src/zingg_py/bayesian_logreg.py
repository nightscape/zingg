"""Bayesian logistic regression — port of ``BayesianLogReg.scala``.

Gaussian prior, fit by Newton/IRLS to the MAP, with a Laplace (Gaussian)
approximation of the posterior around it. Keeps the full posterior covariance
``Sigma = H^-1`` so each prediction carries an epistemic variance; accepts a
non-zero prior mean; well-defined with one class or zero labels (the prior
regularizes). Breeze -> numpy, otherwise identical.
"""

from __future__ import annotations

import numpy as np

_LOG2 = np.log(2.0)


def _sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


class Fitted:
    """A fitted Laplace posterior ``N(w, sigma)`` over the augmented weights."""

    def __init__(self, w: np.ndarray, sigma: np.ndarray):
        self.w = w
        self.sigma = sigma

    def latent(self, psi_star: np.ndarray) -> tuple[float, float]:
        """Latent score ``(mean, variance)`` for an augmented design vector."""
        mu = float(self.w @ psi_star)
        v = float(psi_star @ (self.sigma @ psi_star))
        return mu, max(v, 0.0)

    def predict_prob(self, psi_star: np.ndarray) -> float:
        """Posterior predictive match probability (MacKay probit approx)."""
        mu, v = self.latent(psi_star)
        return float(_sigmoid(mu / np.sqrt(1.0 + np.pi * v / 8.0)))

    def bald(self, psi_star: np.ndarray) -> float:
        """BALD acquisition: mutual information between label and weights."""
        mu, v = self.latent(psi_star)
        p = _sigmoid(mu / np.sqrt(1.0 + np.pi * v / 8.0))
        marginal = _binary_entropy(p)
        c2 = np.pi * _LOG2 / 2.0
        conditional = np.sqrt(c2 / (v + c2)) * np.exp(-(mu * mu) / (2.0 * (v + c2)))
        return float(marginal - conditional)


def _binary_entropy(p: float) -> float:
    eps = 1e-12
    q = min(1.0 - eps, max(eps, p))
    return -(q * np.log2(q) + (1.0 - q) * np.log2(1.0 - q))


class BayesianLogReg:
    def __init__(
        self,
        prior_mean: np.ndarray,
        prior_prec: np.ndarray,
        max_iter: int = 50,
        tol: float = 1e-8,
        jitter: float = 1e-10,
    ):
        prior_mean = np.asarray(prior_mean, dtype=float)
        prior_prec = np.asarray(prior_prec, dtype=float)
        assert prior_mean.shape[0] == prior_prec.shape[0], (
            f"prior mean dim {prior_mean.shape[0]} != precision dim {prior_prec.shape[0]}"
        )
        self.prior_mean = prior_mean
        self.prior_prec = prior_prec
        self.max_iter = max_iter
        self.tol = tol
        self.jitter = jitter
        self.d = prior_mean.shape[0]

    def fit(self, psi: np.ndarray, y: np.ndarray) -> Fitted:
        """Fit the MAP and return the Laplace posterior.

        ``psi`` is ``n x d``; ``y`` the 0/1 labels. ``n == 0`` returns the prior.
        """
        psi = np.asarray(psi, dtype=float).reshape(-1, self.d)
        y = np.asarray(y, dtype=float).reshape(-1)
        assert psi.shape[1] == self.d, f"feature dim {psi.shape[1]} != prior dim {self.d}"
        n = psi.shape[0]
        w = self.prior_mean.copy()
        for _ in range(self.max_iter):
            g, h = self._grad_hess(psi, y, w, n)
            step = np.linalg.solve(h, g)
            w = w - step
            if np.linalg.norm(step) < self.tol:
                break
        _, h_final = self._grad_hess(psi, y, w, n)
        return Fitted(w, np.linalg.inv(h_final))

    def _grad_hess(self, psi, y, w, n):
        prior_grad = self.prior_prec * (w - self.prior_mean)
        base_h = np.diag(self.prior_prec) + np.eye(self.d) * self.jitter
        if n == 0:
            return prior_grad, base_h
        eta = psi @ w
        s = _sigmoid(eta)
        rw = s * (1.0 - s)
        grad = psi.T @ (s - y) + prior_grad
        h = base_h + (psi.T * rw) @ psi
        return grad, h
