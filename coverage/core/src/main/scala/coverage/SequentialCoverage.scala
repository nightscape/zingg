package coverage

/** Framework-agnostic statistical core for percentage-based ("coverage") invariant
  * checking, in the style of QuickCheck's `checkCoverage`.
  *
  * Given a stream of Bernoulli trials (a label is present or absent on each generated
  * input) and a required coverage `p`, we want to decide ONE of:
  *
  *   - [[Decision.Accept]]  the true coverage is high enough, with confidence
  *   - [[Decision.Reject]]  the true coverage is too low, with confidence
  *   - [[Decision.Continue]] not enough evidence yet; draw more samples
  *
  * The test is sequential: a caller feeds in running totals `(n, k)` and stops as soon
  * as it gets Accept or Reject. This is what makes coverage checking sound rather than
  * "run 100 trials and compare a ratio" (which is itself flaky near the boundary).
  *
  * Method: rather than classic Wald SPRT (which needs a specific point alternative p1),
  * we use the Wilson-score-interval formulation that backs QuickCheck's `checkCoverage`.
  * After n trials with k hits we form a confidence interval for the true probability at
  * a tiny error level `alpha = 1 / certainty`, then:
  *   - Accept if the interval's LOWER bound is at least `tolerance * required`
  *   - Reject if the interval's UPPER bound is below `required`
  *
  * The `tolerance < 1` slack is essential: without it, a true coverage sitting exactly
  * on the boundary would never resolve and the test would sample forever.
  *
  * This object has zero dependencies on purpose — bindings (zio-test, ScalaCheck, ...)
  * sit on top of it.
  */
object SequentialCoverage {

  /** Inverse of the standard normal CDF (the probit / quantile function), via Acklam's
    * rational approximation. Relative error < 1.15e-9 across (0, 1), which is ample for
    * the extreme tails we hit at `certainty` up to ~1e9. (QuickCheck adds a Halley
    * refinement step for full double precision; unnecessary at these tolerances.)
    */
  def inverseNormalCdf(p: Double): Double = {
    require(p > 0.0 && p < 1.0, s"p must be in (0, 1), got $p")

    val a = Array(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                   1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
    val b = Array(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                   6.680131188771972e+01, -1.328068155288572e+01)
    val c = Array(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                  -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
    val d = Array(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                  3.754408661907416e+00)

    val pLow = 0.02425
    val pHigh = 1.0 - pLow

    if (p < pLow) {
      val q = math.sqrt(-2.0 * math.log(p))
      (((((c(0) * q + c(1)) * q + c(2)) * q + c(3)) * q + c(4)) * q + c(5)) /
        ((((d(0) * q + d(1)) * q + d(2)) * q + d(3)) * q + 1.0)
    } else if (p <= pHigh) {
      val q = p - 0.5
      val r = q * q
      (((((a(0) * r + a(1)) * r + a(2)) * r + a(3)) * r + a(4)) * r + a(5)) * q /
        (((((b(0) * r + b(1)) * r + b(2)) * r + b(3)) * r + b(4)) * r + 1.0)
    } else {
      val q = math.sqrt(-2.0 * math.log(1.0 - p))
      -(((((c(0) * q + c(1)) * q + c(2)) * q + c(3)) * q + c(4)) * q + c(5)) /
        ((((d(0) * q + d(1)) * q + d(2)) * q + d(3)) * q + 1.0)
    }
  }

  /** Wilson score bound for `k` successes in `n` trials at standard score `z`.
    * A negative `z` yields the lower bound, a positive `z` the upper bound.
    */
  def wilson(k: Long, n: Long, z: Double): Double = {
    require(n > 0, s"n must be positive, got $n")
    require(k >= 0 && k <= n, s"k must be in [0, n], got k=$k n=$n")
    val nf = n.toDouble
    val phat = k.toDouble / nf
    val z2 = z * z
    (phat + z2 / (2 * nf) + z * math.sqrt(phat * (1 - phat) / nf + z2 / (4 * nf * nf))) /
      (1 + z2 / nf)
  }

  /** Lower end of the two-sided `(1 - alpha)` Wilson confidence interval. */
  def wilsonLow(k: Long, n: Long, alpha: Double): Double =
    wilson(k, n, inverseNormalCdf(alpha / 2))

  /** Upper end of the two-sided `(1 - alpha)` Wilson confidence interval. */
  def wilsonHigh(k: Long, n: Long, alpha: Double): Double =
    wilson(k, n, inverseNormalCdf(1.0 - alpha / 2))

  /** The full confidence interval, handy for reporting the observed coverage. */
  def interval(conf: Confidence, n: Long, k: Long): Interval = {
    val alpha = 1.0 / conf.certainty.toDouble
    Interval(wilsonLow(k, n, alpha), wilsonHigh(k, n, alpha))
  }

  /** Sequential decision after observing `k` hits in `n` trials for a required
    * coverage of `required`. Feed this running totals and stop on Accept/Reject.
    */
  def decide(conf: Confidence, n: Long, k: Long, required: Double): Decision = {
    require(required > 0.0 && required <= 1.0, s"required coverage must be in (0, 1], got $required")
    require(n >= 0, s"n must be non-negative, got $n")
    require(k >= 0 && k <= n, s"k must be in [0, n], got k=$k n=$n")

    if (n == 0) Decision.Continue
    else {
      val alpha = 1.0 / conf.certainty.toDouble
      // Accept takes precedence: if we are confident coverage is within tolerance of the
      // requirement, that is "close enough" even when it is just below `required`.
      if (wilsonLow(k, n, alpha) >= conf.tolerance * required) Decision.Accept
      else if (wilsonHigh(k, n, alpha) < required) Decision.Reject
      else Decision.Continue
    }
  }
}

/** Confidence configuration, mirroring QuickCheck's `Confidence`.
  *
  * @param certainty the test answers incorrectly with probability at most `1 / certainty`
  * @param tolerance accept once we are confident true coverage is at least
  *                  `tolerance * required`; the slack that guarantees termination
  */
final case class Confidence(certainty: Long = 1000000000L, tolerance: Double = 0.9) {
  require(certainty > 1, s"certainty must be > 1, got $certainty")
  require(tolerance > 0.0 && tolerance <= 1.0, s"tolerance must be in (0, 1], got $tolerance")
}

/** A closed confidence interval for an observed probability. */
final case class Interval(low: Double, high: Double)

sealed trait Decision
object Decision {
  /** Confident the coverage requirement is met. */
  case object Accept extends Decision
  /** Confident the coverage requirement is violated. */
  case object Reject extends Decision
  /** Insufficient evidence; draw more samples. */
  case object Continue extends Decision
}
