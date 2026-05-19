package zingg.zs

import org.apache.spark.sql.DataFrame
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import scala.util.Random

/** Property-style tests over a Plan-driven oracle.
  *
  * Each test draws K random plans (fresh seeds every run, so the suite keeps
  * exploring new inputs) → builds DataFrames → derives ORACLE labels from ground
  * truth → trains Zingg → clusters → scores pairwise F1.
  *
  * Why median-over-K rather than ScalaCheck `forAll`:
  * clustering is connected-components, so a single false-positive pair edge
  * transitively merges two whole clusters and collapses precision. That makes
  * per-sample precision genuinely high-variance — a `forAll` with a meaningful
  * threshold will eventually hit an unlucky plan and flake, and flooring the
  * threshold at the collapse value asserts almost nothing. Asserting the MEDIAN
  * over K random plans tolerates the occasional collapse while still failing on
  * a systematic regression (one that degrades the typical case, not just the
  * tail).
  */
@TestInstance(Lifecycle.PER_CLASS)
class ZinggPropertyTest extends SharedSpark {

  private val cfg = ZinggConf(
    fields = Seq(
      FieldDef("summary",     MatchType.Text),
      FieldDef("description", MatchType.CveId),
      FieldDef("priority",    MatchType.Exact)
    ),
    blockSize = 50,
    threshold = 0.5
  )

  // Unseeded: every test run explores different plans.
  private val rng = new Random()
  private def randomSeed(): Long = rng.nextLong()

  private def median(xs: Seq[Double]): Double = {
    require(xs.nonEmpty, "median of empty sequence")
    val s = xs.sorted
    val n = s.length
    if (n % 2 == 1) s(n / 2) else (s(n / 2 - 1) + s(n / 2)) / 2.0
  }

  // ─────────────────────────────────────────────────────────────────────────

  @Test
  def cveIdEnablesGoodClusteringTypically(): Unit = {
    val K = 7
    val results = (0 until K).map { _ =>
      trainAndEvaluate(Plan.build(randomSeed(), nEntities = 6, variantsPerEntity = 4),
        holdoutFraction = 0.3)
    }
    val medF1   = median(results.map(_._3))
    val medPrec = median(results.map(_._1))
    assert(medF1 >= 0.75 && medPrec >= 0.7,
      f"CVE-id signal should give good typical clustering; median over $K plans " +
      f"f1=$medF1%.3f precision=$medPrec%.3f")
  }

  @Test
  def perfectLabelsRecoverGroundTruthOnTraining(): Unit = {
    // With all-positive + all-negative oracle labels covering every row,
    // clustering the same data should match truth in the typical case.
    val K = 7
    val f1s = (0 until K).map { _ =>
      val plan = Plan.build(randomSeed(), nEntities = 4, variantsPerEntity = 4)
      val labeled = Plan.labeledTrainingSet(spark, cfg, plan, nNegatives = 50)
      val (model, tree) = new Zingg(cfg).train(labeled)
      clusterF1(plan, plan.toDF(spark, cfg), model, tree)
    }
    val m = median(f1s)
    assert(m >= 0.85, f"median f1=$m%.3f over $K plans")
  }

  @Test
  def singletonEntitiesRarelyFalseMerge(): Unit = {
    // Each entity has exactly 1 variant — truth has no within-cluster pairs, so
    // any predicted match is a false positive. A model trained on a separate
    // plan must not spuriously merge these singletons (precision stays high).
    val K = 7
    val precs = (0 until K).map { _ =>
      val seed    = randomSeed()
      val plan    = Plan.build(seed, nEntities = 8, variantsPerEntity = 1)
      val auxPlan = Plan.build(seed + 1, nEntities = 4, variantsPerEntity = 3)
      val labeled = Plan.labeledTrainingSet(spark, cfg, auxPlan, nNegatives = 30)
      val z = new Zingg(cfg)
      val (model, tree) = z.train(labeled)
      val clusters = z.cluster(plan.toDF(spark, cfg), model, tree)
      val pred = clusters.select(cfg.idCol, cfg.clusterCol).collect()
        .map(r => r.getLong(0) -> r.getAs[Any](cfg.clusterCol)).toMap
      Evaluator.pairwiseF1(pred, plan.truth)._1
    }
    val m = median(precs)
    assert(m >= 0.8, f"median precision=$m%.3f over $K plans")
  }

  @Test
  def activeLearningDoesNotRegress(): Unit = {
    // Round 1: cold-start sample → oracle label → train → eval.
    // Round 2: uncertainty sample on round-1 model → add labels → retrain → eval.
    // The second round should not be systematically worse than the first.
    val K = 5
    val deltas = (0 until K).map { _ =>
      val plan = Plan.build(randomSeed(), nEntities = 5, variantsPerEntity = 4)
      val df = plan.toDF(spark, cfg)
      val z = new Zingg(cfg)

      val cands1   = z.findTrainingData(df, n = 25)
      val labeled1 = plan.labeller.label(cands1, cfg)
      val (m1, t1) = z.train(labeled1)
      val f1Round1 = clusterF1(plan, df, m1, t1)

      val cands2   = z.findTrainingData(df, Some(m1), Some(t1), n = 25)
      val labeled2 = plan.labeller.label(cands2, cfg)
      val lId = s"${ZinggConf.LeftPrefix}${cfg.idCol}"
      val rId = s"${ZinggConf.RightPrefix}${cfg.idCol}"
      val combined = labeled1.unionByName(labeled2, allowMissingColumns = true)
        .dropDuplicates(Seq(lId, rId))
      val (m2, t2) = z.train(combined)
      val f1Round2 = clusterF1(plan, df, m2, t2)

      f1Round2 - f1Round1
    }
    val m = median(deltas)
    assert(m >= -0.1, f"median round2-round1 delta=$m%.3f over $K plans (regression)")
  }

  @Test
  def tolerantToModerateLabelNoise(): Unit = {
    // 10% of labels flipped: the CVE-id block key keeps recall high; noise
    // mostly costs precision. Typical clustering F1 should still be solid.
    val K = 7
    val f1s = (0 until K).map { _ =>
      val plan = Plan.build(randomSeed(), nEntities = 6, variantsPerEntity = 4)
      val labeled = Plan.labeledTrainingSetWithNoise(
        spark, cfg, plan, nNegatives = 40, noiseFraction = 0.10, noiseSeed = randomSeed())
      val (model, tree) = new Zingg(cfg).train(labeled)
      clusterF1(plan, plan.toDF(spark, cfg), model, tree)
    }
    val m = median(f1s)
    assert(m >= 0.55, f"median f1=$m%.3f at 10%% noise over $K plans")
  }

  @Test
  def degradesGracefullyWithHeavyNoise(): Unit = {
    // 30% noise is close to random for binary labels. We only require that the
    // typical case doesn't collapse to noise.
    val K = 7
    val f1s = (0 until K).map { _ =>
      val plan = Plan.build(randomSeed(), nEntities = 5, variantsPerEntity = 4)
      val labeled = Plan.labeledTrainingSetWithNoise(
        spark, cfg, plan, nNegatives = 50, noiseFraction = 0.30, noiseSeed = randomSeed())
      val (model, tree) = new Zingg(cfg).train(labeled)
      clusterF1(plan, plan.toDF(spark, cfg), model, tree)
    }
    val m = median(f1s)
    assert(m >= 0.2, f"median f1=$m%.3f at 30%% noise over $K plans")
  }

  @Test
  def emptyPlanProducesEmptyOutput(): Unit = {
    val plan = Plan(Seq.empty, Seq.empty)
    val df = plan.toDF(spark, cfg)
    val z = new Zingg(cfg)
    val cands = z.findTrainingData(df, n = 10)
    assert(cands.count() == 0)
  }

  // ─────────────────────────────────────────────────────────────────────────

  /** Build oracle-labeled training set from the plan, train, cluster the full
    * dataset, return pairwise (precision, recall, f1) restricted to holdout ids.
    */
  private def trainAndEvaluate(plan: Plan, holdoutFraction: Double): (Double, Double, Double) = {
    val truth = plan.truth
    if (truth.isEmpty) return (1.0, 1.0, 1.0)

    val rngLocal = new Random(plan.entities.headOption.map(_.entityId.toLong).getOrElse(0L))
    val all = truth.keys.toVector
    val shuffled = rngLocal.shuffle(all)
    val nHoldout = (shuffled.size * holdoutFraction).toInt
    val holdoutIds = shuffled.take(nHoldout).toSet
    val trainIds   = shuffled.drop(nHoldout).toSet

    val trainPlan = plan.copy(rows = plan.rows.filter(r => trainIds.contains(r.rowId)))
    val labeled = Plan.labeledTrainingSet(spark, cfg, trainPlan, nNegatives = 40)

    val z = new Zingg(cfg)
    val (model, tree) = z.train(labeled)
    val df = plan.toDF(spark, cfg)
    val clusters = z.cluster(df, model, tree)
    val pred = clusters.select(cfg.idCol, cfg.clusterCol).collect()
      .map(r => r.getLong(0) -> r.getAs[Any](cfg.clusterCol)).toMap

    val evalIds = if (holdoutIds.isEmpty) truth.keySet else holdoutIds
    val predRestricted  = pred.view.filterKeys(evalIds.contains).toMap
    val truthRestricted = truth.view.filterKeys(evalIds.contains).toMap
    Evaluator.pairwiseF1(predRestricted, truthRestricted)
  }

  /** Cluster the full dataset and compute pairwise F1 vs ground truth. */
  private def clusterF1(plan: Plan, df: DataFrame,
                        model: org.apache.spark.ml.PipelineModel,
                        tree: BlockingTree): Double = {
    val z = new Zingg(cfg)
    val clusters = z.cluster(df, model, tree)
    val pred = clusters.select(cfg.idCol, cfg.clusterCol).collect()
      .map(r => r.getLong(0) -> r.getAs[Any](cfg.clusterCol)).toMap
    Evaluator.pairwiseF1(pred, plan.truth)._3
  }
}
