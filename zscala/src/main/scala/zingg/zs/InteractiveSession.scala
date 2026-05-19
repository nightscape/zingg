package zingg.zs

import breeze.linalg.{DenseMatrix, DenseVector}
import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types.{DoubleType, StructField, StructType}

import scala.collection.mutable

/** Interactive, single-pair active-learning session.
  *
  * Pair selection is driven by a Bayesian logistic regression with a Laplace
  * posterior (see [[BayesianLogReg]]). Each pair is described by an augmented
  * design vector `ψ = [1, proxy, poly(z_features)]`, where `proxy` is the
  * unsupervised similarity mean and `poly` is the degree-2 expansion the final
  * model also uses. The prior is centred so that, with no labels, the posterior
  * predictive is monotone in the similarity proxy — i.e. cold start already
  * orders pairs sensibly. Every label sharpens the same posterior; `nextPair`
  * returns the pair of maximal BALD (mutual information between its label and
  * the model weights). There is no cold-start/steady-state switch and no
  * background retrain: selection is exact, driver-side linear algebra.
  *
  * Typical loop:
  * {{{
  *   val s = z.interactiveSession(df)
  *   try {
  *     var pair = s.nextPair()
  *     while (pair.isDefined && !done) {
  *       val label = askHuman(pair.get)         // 1.0 / 0.0 / skip
  *       label.foreach(s.submitLabel(pair.get, _))
  *       pair = s.nextPair()
  *     }
  *     val (model, tree) = s.finalModel()       // Spark pipeline for clustering
  *   } finally s.close()
  * }}}
  *
  * @param pool          rows of `featured` with the degree-2 poly column appended last
  * @param priorSlope    `c`: prior log-odds slope on the similarity proxy
  *                       (prior predictive ≈ `σ(c·(proxy − 0.5))`)
  * @param priorPrecision diagonal of the Gaussian prior precision; larger means
  *                       the prior (proxy) holds longer before the data overrides it
  */
final class InteractiveSession private[zs] (
    cfg: ZinggConf,
    featured: DataFrame,
    pool: Array[Row],
    priorSlope: Double = 6.0,
    priorPrecision: Double = 1.0
) extends AutoCloseable {
  require(priorPrecision > 0.0, s"priorPrecision must be > 0, got $priorPrecision")

  private val lock          = new Object
  private val spark         = featured.sparkSession
  private val featuredSchema: StructType = featured.schema
  private val nClean        = featuredSchema.length
  private val labeledSchema: StructType =
    StructType(featuredSchema.fields :+ StructField(cfg.labelCol, DoubleType, nullable = false))
  private val lIdIdx  = featuredSchema.fieldIndex(s"${ZinggConf.LeftPrefix}${cfg.idCol}")
  private val rIdIdx  = featuredSchema.fieldIndex(s"${ZinggConf.RightPrefix}${cfg.idCol}")
  private val featIdx = featuredSchema.fieldIndex(Features.FeatureCol)

  private def keyOf(r: Row): (Long, Long) = (r.getLong(lIdIdx), r.getLong(rIdIdx))

  /** Unsupervised similarity prior per pool index: mean of the feature vector. */
  private val proxy: Array[Double] = pool.map { r =>
    val v = r.getAs[Vector](featIdx)
    if (v.size == 0) 0.0
    else {
      var s = 0.0
      var i = 0
      while (i < v.size) { s += v(i); i += 1 }
      s / v.size
    }
  }

  /** Augmented design vector per pool index: `[1, proxy, poly...]`. */
  private val psi: Array[DenseVector[Double]] = pool.indices.map { i =>
    val pv  = pool(i).getAs[Vector](nClean)
    val arr = new Array[Double](pv.size + 2)
    arr(0) = 1.0
    arr(1) = proxy(i)
    var j = 0
    while (j < pv.size) { arr(j + 2) = pv(j); j += 1 }
    DenseVector(arr)
  }.toArray
  private val dim = psi(0).length

  private val priorMean: DenseVector[Double] = {
    val m = DenseVector.zeros[Double](dim)
    m(0) = -priorSlope * 0.5 // intercept centres the proxy slope at proxy = 0.5
    m(1) = priorSlope        // positive log-odds slope on the similarity proxy
    m
  }
  private val blr = new BayesianLogReg(priorMean, DenseVector.fill(dim)(priorPrecision))

  private val keyToIndex: Map[(Long, Long), Int] =
    pool.indices.iterator.map(i => keyOf(pool(i)) -> i).toMap

  private val shown  = mutable.Set.empty[Int]
  private val labels = mutable.ArrayBuffer.empty[(Int, Double)] // (poolIndex, label)
  private val buffer = mutable.ArrayBuffer.empty[Row]           // featured cols + label
  private var modelOpt: Option[(PipelineModel, BlockingTree)] = None

  /** The unseen pair of maximal BALD under the current posterior, or `None` if
    * the pool is exhausted. */
  def nextPair(): Option[Row] = lock.synchronized {
    val fitted = fitPosterior()
    var bestIdx   = -1
    var bestScore = Double.NegativeInfinity
    var i = 0
    while (i < pool.length) {
      if (!shown.contains(i)) {
        val s = fitted.bald(psi(i))
        if (s > bestScore) { bestScore = s; bestIdx = i }
      }
      i += 1
    }
    if (bestIdx < 0) None
    else { shown += bestIdx; Some(pool(bestIdx)) }
  }

  /** Record a definite answer for `pair`. Labels must be `0.0` or `1.0`; use
    * `skip` to drop a pair without labelling it. */
  def submitLabel(pair: Row, label: Double): Unit = lock.synchronized {
    require(label == 0.0 || label == 1.0, s"label must be 0.0 or 1.0, got $label")
    val idx = keyToIndex(keyOf(pair))
    shown += idx
    labels += ((idx, label))
    val values = (0 until nClean).map(pair.get)
    buffer += Row.fromSeq(values :+ label)
  }

  /** Drop `pair` without contributing it to the training set. */
  def skip(pair: Row): Unit = lock.synchronized {
    shown += keyToIndex(keyOf(pair))
  }

  /** Snapshot of labels collected so far, as a Spark DataFrame in the schema
    * `train` expects (featured columns + `labelCol`). Safe to call any time. */
  def labeled: DataFrame = lock.synchronized {
    val rdd = spark.sparkContext.parallelize(buffer.toSeq, 2)
    spark.createDataFrame(rdd, labeledSchema)
  }

  /** Final model from the last `finalModel` call, if any. */
  def currentModel: Option[(PipelineModel, BlockingTree)] =
    lock.synchronized(modelOpt)

  /** Train the Spark pipeline on the full label set for downstream clustering. */
  def finalModel(): (PipelineModel, BlockingTree) = {
    val labeledDf = labeled
    val z = new Zingg(cfg)
    val (m, t) = z.train(labeledDf)
    lock.synchronized { modelOpt = Some((m, t)) }
    (m, t)
  }

  def close(): Unit = lock.synchronized {
    featured.unpersist()
  }

  // ── internals ───────────────────────────────────────────────────────────

  private def fitPosterior(): BayesianLogReg.Fitted = {
    val n = labels.length
    val x = DenseMatrix.zeros[Double](n, dim)
    val y = DenseVector.zeros[Double](n)
    var r = 0
    while (r < n) {
      val (idx, lab) = labels(r)
      x(r, ::) := psi(idx).t
      y(r) = lab
      r += 1
    }
    blr.fit(x, y)
  }
}
