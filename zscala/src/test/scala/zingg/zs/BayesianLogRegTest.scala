package zingg.zs

import breeze.linalg.{DenseMatrix, DenseVector, max, trace}
import breeze.numerics.abs
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import scala.util.Random

/** Property-style tests for [[BayesianLogReg]] via a plan-driven oracle.
  *
  * The "plan" is a randomly drawn ground-truth weight vector `wTrue`. We sample
  * a design matrix and Bernoulli labels from the logistic model it defines, fit
  * the BLR with a deliberately weak prior (so the likelihood dominates and the
  * MAP ≈ MLE), and check the fit recovers `wTrue`.
  *
  * Like [[ZinggPropertyTest]] we assert on the MEDIAN over K random plans rather
  * than using ScalaCheck `forAll`: a single unlucky draw (noisy small sample,
  * near-separable design) is high-variance, so a per-sample threshold would
  * either flake or assert nothing. The median fails only on a systematic
  * regression. No Spark needed — this is pure driver-side linear algebra.
  */
@TestInstance(Lifecycle.PER_CLASS)
class BayesianLogRegTest {

  // Unseeded: every run explores fresh plans.
  private val rng = new Random()

  private def median(xs: Seq[Double]): Double = {
    val s = xs.sorted
    val n = s.length
    if (n % 2 == 1) s(n / 2) else (s(n / 2 - 1) + s(n / 2)) / 2.0
  }

  /** Draw n samples from the logistic model defined by `wTrue` (column 0 is the
    * intercept = 1, the rest are standard normal). */
  private def sample(wTrue: DenseVector[Double], n: Int): (DenseMatrix[Double], DenseVector[Double]) = {
    val d = wTrue.length
    val x = DenseMatrix.zeros[Double](n, d)
    val y = DenseVector.zeros[Double](n)
    var i = 0
    while (i < n) {
      x(i, 0) = 1.0
      var j = 1
      while (j < d) { x(i, j) = rng.nextGaussian(); j += 1 }
      val eta = x(i, ::).t dot wTrue
      val p   = 1.0 / (1.0 + math.exp(-eta))
      y(i)    = if (rng.nextDouble() < p) 1.0 else 0.0
      i += 1
    }
    (x, y)
  }

  private def weakPrior(d: Int) =
    new BayesianLogReg(DenseVector.zeros[Double](d), DenseVector.fill(d)(1e-3))

  private def randomWeights(): DenseVector[Double] = {
    val dBase = 2 + rng.nextInt(3)               // 2..4 features
    val w = DenseVector.zeros[Double](dBase + 1) // + intercept
    w(0) = -1.0 + 2.0 * rng.nextDouble()         // intercept in [-1, 1]
    var j = 1
    while (j < w.length) { w(j) = -2.5 + 5.0 * rng.nextDouble(); j += 1 } // slopes in [-2.5, 2.5]
    w
  }

  // ─────────────────────────────────────────────────────────────────────────

  @Test
  def recoversTrueWeights(): Unit = {
    val K = 9
    val errors = (0 until K).map { _ =>
      val wTrue   = randomWeights()
      val (x, y)  = sample(wTrue, n = 5000)
      val fitted  = weakPrior(wTrue.length).fit(x, y)
      max(abs(fitted.w - wTrue))               // worst-coordinate recovery error
    }
    val med = median(errors)
    assert(med < 0.5, f"median max-coordinate recovery error=$med%.3f over $K plans")
  }

  @Test
  def posteriorContractsWithMoreData(): Unit = {
    // With ~20x more data the Laplace covariance should shrink roughly ∝ 1/n.
    val K = 9
    val ratios = (0 until K).map { _ =>
      val wTrue        = randomWeights()
      val (xs, ys)     = sample(wTrue, n = 300)
      val (xl, yl)     = sample(wTrue, n = 6000)
      val small        = weakPrior(wTrue.length).fit(xs, ys)
      val large        = weakPrior(wTrue.length).fit(xl, yl)
      trace(large.sigma) / trace(small.sigma)
    }
    val med = median(ratios)
    assert(med < 0.3, f"median trace(Σ) ratio (6000 vs 300 labels)=$med%.3f over $K plans")
  }

  @Test
  def predictiveProbabilityTracksTruth(): Unit = {
    // On fresh test points the predictive prob should track the true logistic prob.
    val K = 9
    val maes = (0 until K).map { _ =>
      val wTrue      = randomWeights()
      val (xTr, yTr) = sample(wTrue, n = 5000)
      val fitted     = weakPrior(wTrue.length).fit(xTr, yTr)
      val (xTe, _)   = sample(wTrue, n = 1000)
      var sumAbs = 0.0
      var i = 0
      while (i < xTe.rows) {
        val xi    = xTe(i, ::).t
        val pTrue = 1.0 / (1.0 + math.exp(-(xi dot wTrue)))
        sumAbs += math.abs(fitted.predictProb(xi) - pTrue)
        i += 1
      }
      sumAbs / xTe.rows
    }
    val med = median(maes)
    assert(med < 0.05, f"median predictive-prob MAE=$med%.3f over $K plans")
  }

  // ── BALD acquisition: deterministic structural properties ─────────────────

  /** BALD for a latent score with mean `mu` and variance `v`. */
  private def baldAt(mu: Double, v: Double): Double = {
    val w       = DenseVector(mu, 0.0)
    val sigma   = DenseMatrix((v, 0.0), (0.0, v))
    val psiStar = DenseVector(1.0, 0.0)
    new BayesianLogReg.Fitted(w, sigma).bald(psiStar)
  }

  @Test
  def baldVanishesWithNoEpistemicUncertainty(): Unit = {
    // On the boundary (mu=0) with no posterior variance, label and weights share
    // no information.
    assert(baldAt(0.0, 1e-10) < 1e-4, s"BALD should vanish as v→0, got ${baldAt(0.0, 1e-10)}")
  }

  @Test
  def baldIncreasesWithEpistemicUncertainty(): Unit = {
    val low  = baldAt(0.0, 0.01)
    val mid  = baldAt(0.0, 0.5)
    val high = baldAt(0.0, 5.0)
    assert(low < mid && mid < high, s"BALD must increase with v: $low, $mid, $high")
  }

  @Test
  def baldFavoursTheDecisionBoundary(): Unit = {
    // At fixed uncertainty, a pair near the boundary is more informative than a
    // confidently-classified one.
    val boundary = baldAt(0.0, 2.0)
    val near     = baldAt(3.0, 2.0)
    val far      = baldAt(8.0, 2.0)
    assert(boundary > near && near > far, s"BALD must fall away from boundary: $boundary, $near, $far")
  }
}
