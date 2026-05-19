package zingg.zs

import breeze.linalg.{DenseMatrix, DenseVector, diag, inv, norm}
import breeze.numerics.sigmoid

/** Bayesian logistic regression with a Gaussian prior, fit by Newton/IRLS to
  * the MAP and a Laplace (Gaussian) approximation of the posterior around it.
  *
  * Unlike Spark's `LogisticRegression` this keeps the full posterior covariance
  * `Σ = H⁻¹` (so each prediction carries an epistemic variance), accepts a
  * non-zero prior mean (so the unsupervised similarity proxy can act as the
  * prior), and is well-defined with one class or even zero labels (the prior
  * regularizes). That is what lets a single model drive both cold start and
  * steady-state pair selection — see [[InteractiveSession]].
  *
  * @param priorMean `m₀`, the prior mean over the augmented weights
  * @param priorPrec the diagonal of the prior precision `A` (= `Σ₀⁻¹`)
  */
final class BayesianLogReg(
    priorMean: DenseVector[Double],
    priorPrec: DenseVector[Double],
    maxIter: Int = 50,
    tol: Double = 1e-8,
    jitter: Double = 1e-10
) {
  require(priorMean.length == priorPrec.length,
    s"prior mean dim ${priorMean.length} != precision dim ${priorPrec.length}")

  private val d = priorMean.length

  /** Fit the MAP and return the Laplace posterior. `psi` is `n x d` (each row an
    * augmented design vector), `y` the `0/1` labels. `n == 0` returns the prior
    * unchanged. */
  def fit(psi: DenseMatrix[Double], y: DenseVector[Double]): BayesianLogReg.Fitted = {
    require(psi.cols == d, s"feature dim ${psi.cols} != prior dim $d")
    val n = psi.rows
    var w = priorMean.copy
    var iter = 0
    var done = false
    while (iter < maxIter && !done) {
      val (g, h) = gradHess(psi, y, w, n)
      val step = h \ g
      w = w - step
      done = norm(step) < tol
      iter += 1
    }
    val (_, hFinal) = gradHess(psi, y, w, n)
    new BayesianLogReg.Fitted(w, inv(hFinal))
  }

  private def gradHess(
      psi: DenseMatrix[Double],
      y: DenseVector[Double],
      w: DenseVector[Double],
      n: Int
  ): (DenseVector[Double], DenseMatrix[Double]) = {
    val priorGrad = priorPrec *:* (w - priorMean)
    val baseH     = diag(priorPrec) + (DenseMatrix.eye[Double](d) * jitter)
    if (n == 0) (priorGrad, baseH)
    else {
      val eta = psi * w
      val s   = sigmoid(eta)
      val rw  = s *:* (DenseVector.ones[Double](n) - s)
      val grad = (psi.t * (s - y)) + priorGrad
      val h = baseH.copy
      var r = 0
      while (r < n) {
        val row = psi(r, ::).t
        h += (row * row.t) * rw(r)
        r += 1
      }
      (grad, h)
    }
  }
}

object BayesianLogReg {

  /** A fitted Laplace posterior `N(w, sigma)` over the augmented weights. */
  final class Fitted(val w: DenseVector[Double], val sigma: DenseMatrix[Double]) {

    /** Latent score `(mean, variance)` for an augmented design vector. */
    def latent(psiStar: DenseVector[Double]): (Double, Double) = {
      val mu = w dot psiStar
      val v  = psiStar dot (sigma * psiStar)
      (mu, math.max(v, 0.0))
    }

    /** Posterior predictive match probability (MacKay probit approximation). */
    def predictProb(psiStar: DenseVector[Double]): Double = {
      val (mu, v) = latent(psiStar)
      sigmoid(mu / math.sqrt(1.0 + math.Pi * v / 8.0))
    }

    /** BALD acquisition: mutual information between the label and the weights
      * (Houlsby et al. closed form). High when the pair is near the decision
      * boundary *and* the model is epistemically uncertain there; → 0 once the
      * region is well-determined, regardless of how ambiguous the label is. */
    def bald(psiStar: DenseVector[Double]): Double = {
      val (mu, v) = latent(psiStar)
      val p = sigmoid(mu / math.sqrt(1.0 + math.Pi * v / 8.0))
      val marginal = binaryEntropy(p)
      val c2 = math.Pi * math.log(2.0) / 2.0
      val conditional = math.sqrt(c2 / (v + c2)) * math.exp(-(mu * mu) / (2.0 * (v + c2)))
      marginal - conditional
    }
  }

  private def binaryEntropy(p: Double): Double = {
    val eps = 1e-12
    val q = math.min(1.0 - eps, math.max(eps, p))
    -(q * log2(q) + (1.0 - q) * log2(1.0 - q))
  }

  private def log2(x: Double): Double = math.log(x) / math.log(2.0)
}
