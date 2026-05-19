package zingg.zs

import org.apache.spark.ml.PipelineModel
import org.apache.spark.ml.feature.PolynomialExpansion
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.{col, lit, monotonically_increasing_id, when}
import org.apache.spark.storage.StorageLevel

/** End-to-end entry point.
  *
  * {{{
  *   val cfg = ZinggConf(fields = Seq(
  *     FieldDef("summary", MatchType.Text),
  *     FieldDef("description", MatchType.CveId),
  *     FieldDef("priority", MatchType.Exact)
  *   ))
  *   val z = new Zingg(cfg)
  *   val candidates = z.findTrainingData(df)
  *   // label `candidates` (human or LLM) -> `labeled` with `z_label` in {0.0, 1.0}
  *   val (model, tree) = z.train(labeled)
  *   val clusters = z.cluster(df, model, tree)
  * }}}
  */
class Zingg(cfg: ZinggConf, link: Boolean = false) extends Serializable {

  def findTrainingData(
      df: DataFrame,
      priorModel: Option[PipelineModel] = None,
      priorTree: Option[BlockingTree] = None,
      n: Int = 30
  ): DataFrame = {
    val withIds = ensureId(df)
    val tree = priorTree.getOrElse(seedTree)
    val blocked = BlockingTree.assignBlocks(withIds, tree)
    val pairs = PairBuilder.selfPairs(blocked, cfg, crossSourceOnly = link)
    val featured = Features.addFeatures(pairs, cfg)
    val sampled = priorModel match {
      case Some(m) =>
        val scored = Classifier.score(m, featured, cfg)
        ActiveLearning.queryUncertain(scored, cfg, n)
      case None =>
        cfg.sampleSeed match {
          case Some(s) => ActiveLearning.coldStartSample(featured, n, s)
          case None    => ActiveLearning.coldStartSample(featured, n)
        }
    }
    stripPipelineColumns(sampled)
  }

  /** Pipeline-output columns left over from a prior model's `transform`.
    * Stripped before returning candidates so callers can pass the result
    * straight back into `train` without colliding with the pipeline's own
    * output columns on the next fit.
    */
  private def stripPipelineColumns(df: DataFrame): DataFrame = {
    val toDrop = Seq("z_poly_features", "z_prob", "rawPrediction",
                     cfg.scoreCol, cfg.predictionCol, "z_uncertainty")
    toDrop.foldLeft(df)((d, c) => if (d.columns.contains(c)) d.drop(c) else d)
  }

  /** One-pair-at-a-time active-learning session. See
    * [[InteractiveSession]] for the labelling loop contract.
    *
    * The candidate pool is built once (block → pair → featurize) and
    * persisted; only the classifier fit + score is re-run between labels,
    * and that runs on `ec` in the background so the human's labelling time
    * absorbs the retrain latency. */
  def interactiveSession(
      df: DataFrame,
      priorSlope: Double = 6.0,
      priorPrecision: Double = 1.0
  ): InteractiveSession = {
    val withIds  = ensureId(df)
    val tree     = seedTree
    val blocked  = BlockingTree.assignBlocks(withIds, tree)
    val pairs    = PairBuilder.selfPairs(blocked, cfg, crossSourceOnly = link)
    val featured = Features.addFeatures(pairs, cfg).persist(StorageLevel.MEMORY_AND_DISK)
    val poly = new PolynomialExpansion()
      .setInputCol(Features.FeatureCol)
      .setOutputCol("z_poly_features")
      .setDegree(2)
    val pool = poly.transform(featured).collect()
    new InteractiveSession(cfg, featured, pool, priorSlope, priorPrecision)
  }

  def train(labeled: DataFrame): (PipelineModel, BlockingTree) = {
    val posRows = labeled.filter(col(cfg.labelCol) === lit(1.0)).collect()
    val tree =
      if (posRows.length < 2) seedTree
      else BlockingTreeLearner.learn(rowsToPairMaps(posRows), cfg)
    val model = Classifier.train(labeled, cfg)
    (model, tree)
  }

  def cluster(df: DataFrame, model: PipelineModel, tree: BlockingTree): DataFrame = {
    val withIds = ensureId(df)
    val blocked = BlockingTree.assignBlocks(withIds, tree)
    val pairs = PairBuilder.selfPairs(blocked, cfg, crossSourceOnly = link)
    val featured = Features.addFeatures(pairs, cfg)
    val scored = Classifier.score(model, featured, cfg)
    val matched = scored.withColumn(
      cfg.predictionCol,
      when(col(cfg.scoreCol) >= lit(cfg.threshold), lit(1.0)).otherwise(lit(0.0))
    )
    val ids = withIds.select(col(cfg.idCol))
    val clustered = Clustering.connectedComponents(matched, ids, cfg)
    withIds.join(clustered, Seq(cfg.idCol), "left")
  }

  private def ensureId(df: DataFrame): DataFrame =
    if (df.columns.contains(cfg.idCol)) df
    else df.withColumn(cfg.idCol, monotonically_increasing_id())

  private def seedTree: BlockingTree = {
    cfg.fields.find(_.blockable) match {
      case None => BlockingTree.Leaf("root")
      case Some(f) =>
        val h = Hash.registry.find(_.applicableTo.contains(f.matchType))
                    .getOrElse(Hash.IdentityString)
        BlockingTree.Node(f.name, h, Map.empty, BlockingTree.Leaf("seed"))
    }
  }

  private def rowsToPairMaps(rows: Array[Row]): Seq[Map[String, (Any, Any)]] =
    rows.toSeq.map { r =>
      cfg.fields.map { f =>
        val a = r.getAs[Any](s"${ZinggConf.LeftPrefix}${f.name}")
        val b = r.getAs[Any](s"${ZinggConf.RightPrefix}${f.name}")
        f.name -> (a, b)
      }.toMap
    }
}
