package coverage.ziotest

import coverage.Decision
import coverage.ziotest.Coverage._
import zio._
import zio.test._

object CoverageSpec extends ZIOSpecDefault {

  def spec = suite("Coverage")(
    test("runCoverage accepts a label occurring well above the requirement") {
      // ~30% of uniform [1, 100] is <= 30; required 20% -> Accept
      runCoverage(Gen.int(1, 100), required = 0.2)(a => ZIO.succeed(a <= 30))
        .map(o => assertTrue(o.decision == Decision.Accept, o.ratio > 0.2))
    },
    test("runCoverage rejects a label occurring well below the requirement") {
      // ~10% of uniform [1, 100] is <= 10; required 50% -> Reject
      runCoverage(Gen.int(1, 100), required = 0.5)(a => ZIO.succeed(a <= 10))
        .map(o => assertTrue(o.decision == Decision.Reject))
    },
    test("checkCoverage passes for a sufficiently covered label") {
      checkCoverage(Gen.int(1, 100), required = 0.2)(_ <= 30)
    },
    test("quorum passes when enough repetitions succeed") {
      assertTrue(true)
    } @@ quorum(minRatio = 0.5, n = 10)
  )
}
