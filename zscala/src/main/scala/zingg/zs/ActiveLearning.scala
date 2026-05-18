package zingg.zs

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{abs, col, lit, rand}

object ActiveLearning {

  def queryUncertain(scored: DataFrame, cfg: ZinggConf, n: Int = 20): DataFrame =
    scored
      .withColumn("z_uncertainty", lit(1.0) - abs(col(cfg.scoreCol) - lit(0.5)) * lit(2.0))
      .orderBy(col("z_uncertainty").desc)
      .limit(n)

  def coldStartSample(pairs: DataFrame, n: Int = 30): DataFrame =
    pairs.orderBy(rand(seed = 42L)).limit(n)
}
