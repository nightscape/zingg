package zingg.zs

import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import scala.collection.mutable
import scala.util.Random

/** Property over the *label stream* of the online interactive session.
  *
  * The complaint that motivated this test: the labeller asked "no" ~30 times in
  * a row. The root cause is selecting a whole batch from one never-updated
  * posterior. The online [[InteractiveSession]] re-fits after every label, so
  * BALD should stop dredging the "obvious non-match" region once the model has
  * learned it and move on to genuinely ambiguous pairs — keeping the yes/no
  * stream roughly balanced with no long single-class runs.
  *
  * We drive the session with the plan oracle (ground-truth labels) and assert
  * two things about the resulting label sequence:
  *   - the overall yes-fraction is near balance, and
  *   - there is no long run of a single answer.
  */
@TestInstance(Lifecycle.PER_CLASS)
class OnlineBalancePropertyTest extends SharedSpark {

  private val cfg = ZinggConf(
    fields = Seq(
      FieldDef("summary",     MatchType.Text),
      FieldDef("description", MatchType.cve),
      FieldDef("priority",    MatchType.Exact)
    ),
    blockSize = 50,
    threshold = 0.5
  )

  private val rng = new Random()

  private def median(xs: Seq[Double]): Double = {
    val s = xs.sorted; val n = s.length
    if (n % 2 == 1) s(n / 2) else (s(n / 2 - 1) + s(n / 2)) / 2.0
  }

  private def maxRun(labels: Seq[Double]): Int =
    if (labels.isEmpty) 0
    else labels.foldLeft((0, 0.0, 0)) { case ((best, prev, cur), x) =>
      val run = if (x == prev) cur + 1 else 1
      (math.max(best, run), x, run)
    }._1

  /** Drive the online session with the oracle; return the label stream. */
  private def labelStream(seed: Long, maxLabels: Int): Seq[Double] = {
    val plan    = Plan.build(seed, nEntities = 6, variantsPerEntity = 4)
    val df      = plan.toDF(spark, cfg)
    val oracle  = plan.labeller
    val session = new Zingg(cfg).interactiveSession(df)
    val stream  = mutable.ArrayBuffer.empty[Double]
    try {
      var more = true
      while (more && stream.length < maxLabels) {
        session.nextPair() match {
          case None => more = false
          case Some(pair) =>
            oracle.decide(pair, cfg) match {
              case RowLabeller.Match    => session.submitLabel(pair, 1.0); stream += 1.0
              case RowLabeller.NonMatch => session.submitLabel(pair, 0.0); stream += 0.0
              case _                    => session.skip(pair)
            }
        }
      }
    } finally session.close()
    stream.toSeq
  }

  @Test
  def onlineSessionKeepsLabelStreamBalanced(): Unit = {
    val K = 5
    val maxLabels = 24
    val streams = (0 until K).map(_ => labelStream(rng.nextLong(), maxLabels))

    val yesFracs = streams.map(s => s.count(_ == 1.0).toDouble / s.length)
    val runs     = streams.map(s => maxRun(s).toDouble)

    val medYes = median(yesFracs)
    val medRun = median(runs)

    assert(medYes >= 0.3 && medYes <= 0.7,
      f"online label stream should be roughly balanced; median yes-fraction=$medYes%.2f " +
      f"over $K sessions (fractions=${yesFracs.map(f => f"$f%.2f").mkString(",")})")
    assert(medRun <= 8.0,
      f"online label stream should not contain long single-class runs; median max-run=$medRun%.0f " +
      f"over $K sessions (runs=${runs.map(_.toInt).mkString(",")})")
  }
}
