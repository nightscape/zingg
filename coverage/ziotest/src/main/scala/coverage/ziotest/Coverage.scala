package coverage.ziotest

import coverage.{Confidence, Decision, SequentialCoverage}
import zio._
import zio.test._

/** zio-test binding for percentage-based ("coverage") invariant checking.
  *
  * Sits on top of the framework-agnostic [[coverage.SequentialCoverage]] core: it draws
  * values from a [[zio.test.Gen]] (whose `sample` is a `ZStream`), feeds the running
  * hit/total counts into the sequential decision, and stops as soon as the core is
  * confident the coverage requirement is met or violated.
  */
object Coverage {

  /** Aggregates observed (hits, total) up the spec tree so reports show actual coverage. */
  val coverageAnnotation: TestAnnotation[(Long, Long)] =
    TestAnnotation("coverage", (0L, 0L), { case ((h1, t1), (h2, t2)) => (h1 + h2, t1 + t2) })

  /** The result of running a coverage check: the decision plus the evidence behind it. */
  final case class CoverageOutcome(decision: Decision, hits: Long, total: Long) {
    def ratio: Double = if (total == 0L) 0.0 else hits.toDouble / total.toDouble
  }

  /** Run the sequential coverage test, returning the raw outcome (does not assert).
    * Useful for testing the binding itself and for custom reporting.
    */
  def runCoverage[R, A](
    gen: Gen[R, A],
    required: Double,
    confidence: Confidence = Confidence(),
    maxSamples: Long = 1000000L
  )(label: A => ZIO[R, Nothing, Boolean]): ZIO[R, Nothing, CoverageOutcome] =
    // `gen.sample` yields a single sample; `.forever` re-runs it to draw fresh values,
    // the same trick zio-test's own `check` uses to get a continuous stream.
    gen.sample.forever
      .map(_.value)
      .runFoldWhileZIO((0L, 0L)) { case (n, k) =>
        n < maxSamples && SequentialCoverage.decide(confidence, n, k, required) == Decision.Continue
      } { case ((n, k), a) =>
        label(a).map(passed => (n + 1L, if (passed) k + 1L else k))
      }
      .map { case (n, k) =>
        CoverageOutcome(SequentialCoverage.decide(confidence, n, k, required), k, n)
      }

  /** Assert that `label` covers at least `required` of the values produced by `gen`,
    * with effectful labels. Records the observed coverage as a test annotation.
    */
  def checkCoverageZIO[R, A](
    gen: Gen[R, A],
    required: Double,
    confidence: Confidence = Confidence(),
    maxSamples: Long = 1000000L
  )(label: A => ZIO[R, Nothing, Boolean]): ZIO[R, Nothing, TestResult] =
    runCoverage(gen, required, confidence, maxSamples)(label).flatMap { o =>
      Annotations.annotate(coverageAnnotation, (o.hits, o.total)).as {
        if (o.decision == Decision.Accept) assertCompletes
        else
          assertNever(
            s"coverage check failed: observed ${o.hits}/${o.total} = ${o.ratio}, " +
              s"required >= $required (decision = ${o.decision})"
          )
      }
    }

  /** Assert that `label` covers at least `required` of the values produced by `gen`. */
  def checkCoverage[R, A](
    gen: Gen[R, A],
    required: Double,
    confidence: Confidence = Confidence(),
    maxSamples: Long = 1000000L
  )(label: A => Boolean): ZIO[R, Nothing, TestResult] =
    checkCoverageZIO(gen, required, confidence, maxSamples)(a => ZIO.succeed(label(a)))

  /** A `TestAspect` that runs a (possibly nondeterministic) test `n` times and passes if
    * at least `minRatio` of the runs succeed. The "percentage of the time" analogue for
    * whole tests, rather than over generated inputs.
    */
  def quorum(minRatio: Double, n: Int = 100): TestAspectPoly =
    new TestAspect.PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        ZIO.foreach(1 to n)(_ => test.either).flatMap { results =>
          val passed = results.count(_.isRight)
          val ratio = passed.toDouble / n.toDouble
          if (ratio >= minRatio)
            ZIO.succeed(TestSuccess.Succeeded())
          else
            ZIO.fail(TestFailure.assertion(assertTrue(ratio >= minRatio)))
        }
    }
}
