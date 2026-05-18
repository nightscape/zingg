package coverage

import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

class SequentialCoverageTest {

  private val tol = 1e-6

  // --- inverseNormalCdf against well-known standard-normal quantiles ---

  @Test def inverseNormalCdfMedianIsZero(): Unit =
    assertEquals(0.0, SequentialCoverage.inverseNormalCdf(0.5), tol)

  @Test def inverseNormalCdfKnownQuantiles(): Unit = {
    assertEquals(1.959964, SequentialCoverage.inverseNormalCdf(0.975), 1e-5)
    assertEquals(-1.959964, SequentialCoverage.inverseNormalCdf(0.025), 1e-5)
    assertEquals(1.644854, SequentialCoverage.inverseNormalCdf(0.95), 1e-5)
    assertEquals(2.326348, SequentialCoverage.inverseNormalCdf(0.99), 1e-5)
  }

  @Test def inverseNormalCdfIsAntisymmetric(): Unit = {
    val z = SequentialCoverage.inverseNormalCdf(0.8)
    assertEquals(-z, SequentialCoverage.inverseNormalCdf(0.2), 1e-9)
  }

  @Test def inverseNormalCdfRejectsOutOfRange(): Unit = {
    assertThrows(classOf[IllegalArgumentException], () => SequentialCoverage.inverseNormalCdf(0.0))
    assertThrows(classOf[IllegalArgumentException], () => SequentialCoverage.inverseNormalCdf(1.0))
  }

  // --- Wilson interval sanity ---

  @Test def wilsonIntervalBracketsPointEstimate(): Unit = {
    val alpha = 0.05
    val low = SequentialCoverage.wilsonLow(k = 50, n = 100, alpha)
    val high = SequentialCoverage.wilsonHigh(k = 50, n = 100, alpha)
    assertTrue(low < 0.5, s"low $low should be below 0.5")
    assertTrue(high > 0.5, s"high $high should be above 0.5")
    assertTrue(low > 0.0 && high < 1.0)
  }

  @Test def wilsonIntervalNarrowsWithMoreSamples(): Unit = {
    val alpha = 0.05
    val widthSmall = SequentialCoverage.wilsonHigh(5, 10, alpha) - SequentialCoverage.wilsonLow(5, 10, alpha)
    val widthLarge = SequentialCoverage.wilsonHigh(5000, 10000, alpha) - SequentialCoverage.wilsonLow(5000, 10000, alpha)
    assertTrue(widthLarge < widthSmall, s"more samples should narrow the interval: $widthLarge !< $widthSmall")
  }

  // --- sequential decision ---

  private val conf = Confidence()

  @Test def decideContinuesWithNoOrFewSamples(): Unit = {
    assertEquals(Decision.Continue, SequentialCoverage.decide(conf, n = 0, k = 0, required = 0.2))
    assertEquals(Decision.Continue, SequentialCoverage.decide(conf, n = 5, k = 1, required = 0.2))
  }

  @Test def decideAcceptsWhenClearlyAboveRequirement(): Unit = {
    // observed 25% against a required 20%, with many samples -> confidently sufficient
    assertEquals(Decision.Accept, SequentialCoverage.decide(conf, n = 10000, k = 2500, required = 0.2))
  }

  @Test def decideRejectsWhenClearlyBelowRequirement(): Unit = {
    // observed 10% against a required 20%, with many samples -> confidently insufficient
    assertEquals(Decision.Reject, SequentialCoverage.decide(conf, n = 10000, k = 1000, required = 0.2))
  }

  @Test def decideAcceptsWithinToleranceBand(): Unit = {
    // Observed 19% against a required 20%. Without tolerance this would Reject (the whole
    // interval sits below 0.20), but tolerance 0.9 lowers the accept threshold to 0.18,
    // and the interval's lower bound (~0.1825) clears it -> Accept. This is exactly the
    // slack that lets the sequential test terminate near the boundary.
    assertEquals(Decision.Accept, SequentialCoverage.decide(conf, n = 100000, k = 19000, required = 0.2))
  }

  @Test def decideRejectsInvalidRequirement(): Unit = {
    assertThrows(classOf[IllegalArgumentException], () => SequentialCoverage.decide(conf, 10, 5, required = 0.0))
    assertThrows(classOf[IllegalArgumentException], () => SequentialCoverage.decide(conf, 10, 5, required = 1.5))
  }

  // --- a seeded end-to-end run of the sequential loop ---

  @Test def sequentialLoopConvergesOnSeededBernoulli(): Unit = {
    def run(trueP: Double, required: Double): Decision = {
      val rng = new scala.util.Random(42)
      var n = 0L
      var k = 0L
      var decision: Decision = Decision.Continue
      // cap iterations so a bug can't hang the test
      while (decision == Decision.Continue && n < 5000000L) {
        if (rng.nextDouble() < trueP) k += 1
        n += 1
        decision = SequentialCoverage.decide(conf, n, k, required)
      }
      decision
    }
    assertEquals(Decision.Accept, run(trueP = 0.30, required = 0.20))
    assertEquals(Decision.Reject, run(trueP = 0.10, required = 0.20))
  }
}
