package zingg.zs

import org.apache.spark.sql.functions.{col, lit}
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import scala.util.Random

/** Property over the *label balance* of the cold-start candidate batch.
  *
  * A uniform random draw over candidate pairs is swamped by non-matches: even
  * after blocking, the overwhelming majority of pairs are non-matches, so the
  * human is asked to answer "no" almost every time and training starves for
  * positives. [[ActiveLearning.coldStartSample]] therefore stratifies on the
  * similarity proxy, drawing half the batch from the most-similar pairs (likely
  * "yes") and half from the rest (likely "no").
  *
  * The plan oracle gives us ground-truth labels for free, so we can measure the
  * actual yes-fraction of the presented batch and assert it sits near balance
  * rather than collapsing to ~all-"no".
  */
@TestInstance(Lifecycle.PER_CLASS)
class ColdStartBalancePropertyTest extends SharedSpark {

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

  /** Fraction of the cold-start batch that the oracle labels "yes" (match). */
  private def yesFraction(seed: Long): Double = {
    val plan = Plan.build(seed, nEntities = 6, variantsPerEntity = 4)
    val df   = plan.toDF(spark, cfg)
    val cands   = new Zingg(cfg).findTrainingData(df, n = 30)
    val labeled = plan.labeller.label(cands, cfg)
    val total = labeled.count()
    if (total == 0) 0.0
    else labeled.filter(col(cfg.labelCol) === lit(1.0)).count().toDouble / total
  }

  @Test
  def coldStartBatchIsRoughlyBalanced(): Unit = {
    val K = 7
    val fractions = (0 until K).map(_ => yesFraction(rng.nextLong()))
    val m = median(fractions)
    assert(m >= 0.2 && m <= 0.8,
      f"cold-start batch should be roughly balanced, not all-no; median yes-fraction=$m%.2f " +
      f"over $K plans (fractions=${fractions.map(f => f"$f%.2f").mkString(",")})")
  }
}
