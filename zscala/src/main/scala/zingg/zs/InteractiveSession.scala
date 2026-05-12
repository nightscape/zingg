package zingg.zs

import org.apache.spark.ml.PipelineModel
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions.{abs, col, lit}
import org.apache.spark.sql.types.{DoubleType, StructField, StructType}

import zingg.zs.InteractiveSession.Refreshed

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Interactive, single-pair active-learning session.
  *
  * `nextPair` returns the most-uncertain unseen pair under the *current* model
  * (or a random one before any model exists). `submitLabel` records the answer
  * and, once both classes have appeared at least once, kicks off a background
  * retrain. While the retrain runs, further calls to `nextPair` keep using the
  * old ranking; as soon as the retrain finishes, the next `nextPair` swaps in
  * the new ranking transparently.
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
  *     val (model, tree) = s.finalModel()       // blocks until pending retrain settles
  *   } finally s.close()
  * }}}
  *
  * Thread model: all public methods take the session monitor. The background
  * retrain runs on the supplied ExecutionContext and never touches mutable
  * state directly — it returns a fresh ranking + model that the next
  * `nextPair`/`finalModel` call splices in.
  */
final class InteractiveSession private[zs] (
    cfg: ZinggConf,
    featured: DataFrame,
    seedTreeVal: BlockingTree,
    initialRanking: Array[Row],
    ec: ExecutionContext
) extends AutoCloseable {

  private val lock          = new Object
  private val spark         = featured.sparkSession
  private val featuredCols  = featured.columns.toSeq
  private val featuredSchema: StructType = featured.schema
  private val labeledSchema: StructType =
    StructType(featuredSchema.fields :+ StructField(cfg.labelCol, DoubleType, nullable = false))
  private val lIdIdx = featuredSchema.fieldIndex(s"${ZinggConf.LeftPrefix}${cfg.idCol}")
  private val rIdIdx = featuredSchema.fieldIndex(s"${ZinggConf.RightPrefix}${cfg.idCol}")
  private val labelIdx = featuredSchema.length

  private var ranking: Array[Row] = initialRanking
  private var cursor: Int         = 0
  private val shown               = mutable.Set.empty[(Long, Long)]
  private val buffer              = mutable.ArrayBuffer.empty[Row]
  private var modelOpt: Option[(PipelineModel, BlockingTree)] = None
  private var pending: Option[Future[Refreshed]] = None

  /** Most-uncertain unseen pair under the current ranking, or `None` if the
    * pool has been exhausted. Causes a ranking swap if a background retrain
    * has completed since the last call. */
  def nextPair(): Option[Row] = lock.synchronized {
    maybeSwap()
    while (cursor < ranking.length) {
      val r   = ranking(cursor); cursor += 1
      val key = (r.getLong(lIdIdx), r.getLong(rIdIdx))
      if (!shown.contains(key)) {
        shown += key
        return Some(r)
      }
    }
    None
  }

  /** Record a definite answer for `pair`. Labels must be `0.0` or `1.0`; use
    * `skip` to drop a pair without labelling it. */
  def submitLabel(pair: Row, label: Double): Unit = lock.synchronized {
    require(label == 0.0 || label == 1.0, s"label must be 0.0 or 1.0, got $label")
    val values = (0 until pair.length).map(pair.get)
    buffer += Row.fromSeq(values :+ label)
    maybeStartTraining()
  }

  /** Drop `pair` without contributing it to the training set. Already-shown
    * pairs are never re-offered, so this is mostly a no-op marker today; it
    * exists for symmetry with `submitLabel` and to future-proof the API. */
  def skip(pair: Row): Unit = ()

  /** Snapshot of labels collected so far, as a Spark DataFrame in the schema
    * `train` expects (featured columns + `labelCol`). Safe to call any time. */
  def labeled: DataFrame = lock.synchronized {
    val rdd = spark.sparkContext.parallelize(buffer.toSeq, 2)
    spark.createDataFrame(rdd, labeledSchema)
  }

  /** Current (model, tree) if at least one retrain has completed. */
  def currentModel: Option[(PipelineModel, BlockingTree)] =
    lock.synchronized(modelOpt)

  /** Block until any pending retrain finishes, fold its result in, then train
    * one more time on the full label set so the returned model reflects every
    * submitted label. Suitable for the end of an interactive loop. */
  def finalModel(): (PipelineModel, BlockingTree) = {
    val pendingFuture = lock.synchronized(pending)
    pendingFuture.foreach { f =>
      scala.concurrent.Await.ready(f, scala.concurrent.duration.Duration.Inf)
      lock.synchronized(maybeSwap())
    }
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

  private def maybeSwap(): Unit = pending match {
    case Some(f) if f.isCompleted =>
      f.value.get match {
        case Success(refreshed) =>
          ranking  = refreshed.ranking
          cursor   = 0
          modelOpt = Some((refreshed.model, refreshed.tree))
        case Failure(_) =>
          // Keep the old ranking; the next label will trigger another attempt.
      }
      pending = None
    case _ =>
  }

  private def maybeStartTraining(): Unit = {
    if (pending.isDefined) return
    var nPos = 0
    var nNeg = 0
    val it = buffer.iterator
    while (it.hasNext) {
      val v = it.next().getDouble(labelIdx)
      if (v == 1.0) nPos += 1 else if (v == 0.0) nNeg += 1
    }
    if (nPos < 1 || nNeg < 1) return

    val snapshot      = buffer.toArray
    val sparkSnap     = spark
    val featuredSnap  = featured
    val cfgSnap       = cfg
    val featCols      = featuredCols
    val labeledSchemaSnap = labeledSchema

    pending = Some(Future {
      val rdd       = sparkSnap.sparkContext.parallelize(snapshot.toSeq, 2)
      val labeledDf = sparkSnap.createDataFrame(rdd, labeledSchemaSnap)
      val z         = new Zingg(cfgSnap)
      val (m, t)    = z.train(labeledDf)
      val scored    = Classifier.score(m, featuredSnap, cfgSnap)
      val ranked    = scored
        .withColumn("z_uncertainty",
          lit(1.0) - abs(col(cfgSnap.scoreCol) - lit(0.5)) * lit(2.0))
        .orderBy(col("z_uncertainty").desc)
        .select(featCols.map(col): _*)
        .collect()
      Refreshed(ranked, m, t)
    }(ec))
  }
}

object InteractiveSession {
  private[zs] final case class Refreshed(
      ranking: Array[Row],
      model: PipelineModel,
      tree: BlockingTree
  )
}
