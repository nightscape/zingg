package zingg.zs

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{abs, col, lit, rand}

object ActiveLearning {

  def queryUncertain(scored: DataFrame, cfg: ZinggConf, n: Int = 20): DataFrame =
    scored
      .withColumn("z_uncertainty", lit(1.0) - abs(col(cfg.scoreCol) - lit(0.5)) * lit(2.0))
      .orderBy(col("z_uncertainty").desc)
      .limit(n)

  /** Random sample of candidate pairs for cold-start labelling.
    *
    * `seed` defaults to a fresh value each call so re-running surfaces a
    * different draw (e.g. to escape a block dominated by one record). Pass an
    * explicit seed for reproducibility — tests do this. */
  def coldStartSample(pairs: DataFrame, n: Int = 30,
                      seed: Long = scala.util.Random.nextLong()): DataFrame =
    pairs.orderBy(rand(seed)).limit(n)
}
