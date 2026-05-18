package zingg.zs

import org.apache.spark.ml.linalg.Vector
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{abs, col, lit, rand, udf}

object ActiveLearning {

  private val ProxyCol = "z_proxy"

  /** How much larger the high-similarity pool is than the number of likely-match
    * slots we draw from it. >1 keeps the "yes" half diverse (random within the
    * pool) instead of always showing the single most-similar pairs. */
  private val HighPoolFactor = 3

  /** approxQuantile relative error for locating the high-similarity threshold. */
  private val QuantileRelError = 0.01

  def queryUncertain(scored: DataFrame, cfg: ZinggConf, n: Int = 20): DataFrame =
    scored
      .withColumn("z_uncertainty", lit(1.0) - abs(col(cfg.scoreCol) - lit(0.5)) * lit(2.0))
      .orderBy(col("z_uncertainty").desc)
      .limit(n)

  /** Unsupervised similarity proxy for a pair: the mean of its feature vector.
    * Every similarity feature is in `[0, 1]` and monotone (higher = more
    * similar), so the mean orders pairs from likely non-match to likely match.
    * This is the same prior [[InteractiveSession]] uses for cold-start ordering. */
  private val meanUdf = udf { (v: Vector) =>
    if (v.size == 0) 0.0
    else {
      var s = 0.0
      var i = 0
      while (i < v.size) { s += v(i); i += 1 }
      s / v.size
    }
  }

  /** Balanced cold-start sample of candidate pairs for labelling.
    *
    * A uniform random draw is dominated by non-matches: even after blocking, the
    * vast majority of candidate pairs are non-matches, so the labeller is asked
    * to answer "no" almost every time. That yields almost no positive examples,
    * which is exactly what training needs most.
    *
    * Instead we stratify on the similarity proxy (mean feature value): half the
    * batch is drawn from the most-similar pairs — the ones likely to be genuine
    * matches ("yes") — and half from the rest — the likely non-matches and
    * decision-boundary pairs ("no"). The result puts the human's "yes" and "no"
    * answers at roughly equal probability rather than swamping them with "no".
    *
    * `seed` defaults to a fresh value each call so re-running surfaces a
    * different draw within each stratum. Pass an explicit seed for
    * reproducibility — tests do this. */
  def coldStartSample(pairs: DataFrame, n: Int = 30,
                      seed: Long = scala.util.Random.nextLong()): DataFrame = {
    val withProxy = pairs.withColumn(ProxyCol, meanUdf(col(Features.FeatureCol)))
    val total = withProxy.count()
    if (total <= n) withProxy.drop(ProxyCol)
    else {
      val nYes = (n + 1) / 2
      val nNo  = n - nYes
      // Threshold that puts the top `HighPoolFactor * nYes` pairs (by proxy) into
      // the high-similarity stratum.
      val highPool = math.min(total, nYes.toLong * HighPoolFactor)
      val highFrac = 1.0 - highPool.toDouble / total
      val thr = withProxy.stat.approxQuantile(ProxyCol, Array(highFrac), QuantileRelError)(0)
      val high = withProxy.filter(col(ProxyCol) >= lit(thr)).orderBy(rand(seed)).limit(nYes)
      val low  = withProxy.filter(col(ProxyCol) <  lit(thr)).orderBy(rand(seed + 1)).limit(nNo)
      high.unionByName(low).drop(ProxyCol)
    }
  }
}
