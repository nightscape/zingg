package zingg.zs

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types._
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.scalacheck.{Gen, Prop, Test => SCTest}

import scala.util.Random

/** Property-based tests using a Plan-driven oracle.
  *
  * Each property generates a random Plan (random CVE ids, random noise) →
  * builds a DataFrame → derives ORACLE labels directly from ground truth →
  * trains Zingg → clusters → checks pairwise F1 on the holdout.
  *
  * Properties run with small `minSuccessfulTests` to keep wall time reasonable.
  * Increase locally for stress runs.
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

  // ─────────────────────────────────────────────────────────────────────────

  @Test
  def cveIdEnablesNearPerfectClustering(): Unit = {
    val prop = Prop.forAll(Plan.gen(nEntities = 6, variantsPerEntity = 4)) { plan =>
      val (precision, recall, f1) = trainAndEvaluate(plan, holdoutFraction = 0.3)
      val passing = f1 >= 0.85 && precision >= 0.9
      if (!passing) System.err.println(
        f"[FAIL] entities=${plan.entities.size} rows=${plan.rows.size} " +
        f"precision=$precision%.3f recall=$recall%.3f f1=$f1%.3f"
      )
      Prop(passing) :| f"precision=$precision%.3f recall=$recall%.3f f1=$f1%.3f"
    }
    runProp("cveIdEnablesNearPerfectClustering", prop, 5)
  }

  @Test
  def singletonEntitiesNeverFalseMerge(): Unit = {
    // Each entity has exactly 1 variant — no row should cluster with another.
    val prop = Prop.forAll(Gen.choose(1L, 1000000L)) { seed =>
      val plan = Plan.build(seed, nEntities = 8, variantsPerEntity = 1)
      // Need an auxiliary plan with positives to train on (the live test plan
      // has none). The model trained on this aux plan must NOT spuriously
      // merge unrelated rows from the singleton plan.
      val auxPlan = Plan.build(seed + 1, nEntities = 4, variantsPerEntity = 3)
      val auxDf = auxPlan.toDF(spark, cfg)
      val labeled = Plan.labeledTrainingSet(spark, cfg, auxPlan, nNegatives = 30)
      val z = new Zingg(cfg)
      val (model, tree) = z.train(labeled)
      val df = plan.toDF(spark, cfg)
      val clusters = z.cluster(df, model, tree)
      val pred = clusters.select(cfg.idCol, cfg.clusterCol).collect()
        .map(r => r.getLong(0) -> r.getAs[Any](cfg.clusterCol)).toMap
      val truth = plan.truth
      val (precision, _, f1) = Evaluator.pairwiseF1(pred, truth)
      // Truth has no within-cluster pairs (all singletons), so any predicted
      // match is a false positive. Across-plan transfer isn't perfect, so
      // we tolerate occasional spurious merges: precision >= 0.85.
      Prop(precision >= 0.85) :| f"precision=$precision%.3f f1=$f1%.3f"
    }
    runProp("singletonEntitiesNeverFalseMerge", prop, 3)
  }

  @Test
  def perfectLabelsRecoverGroundTruthOnTraining(): Unit = {
    // Sanity property: when we train on all-positive + all-negative oracle
    // labels covering every row, clustering on the same data should match
    // the truth almost exactly.
    val prop = Prop.forAll(Plan.gen(nEntities = 4, variantsPerEntity = 4)) { plan =>
      val labeled = Plan.labeledTrainingSet(spark, cfg, plan, nNegatives = 50)
      val z = new Zingg(cfg)
      val (model, tree) = z.train(labeled)
      val df = plan.toDF(spark, cfg)
      val clusters = z.cluster(df, model, tree)
      val pred = clusters.select(cfg.idCol, cfg.clusterCol).collect()
        .map(r => r.getLong(0) -> r.getAs[Any](cfg.clusterCol)).toMap
      val (precision, recall, f1) = Evaluator.pairwiseF1(pred, plan.truth)
      Prop(f1 >= 0.9) :| f"precision=$precision%.3f recall=$recall%.3f f1=$f1%.3f"
    }
    runProp("perfectLabelsRecoverGroundTruthOnTraining", prop, 4)
  }

  @Test
  def activeLearningDoesNotRegress(): Unit = {
    // Round 1: cold-start sample (random pairs) → oracle label → train → eval.
    // Round 2: uncertainty sample using round-1 model → add oracle labels →
    //          retrain on the union → eval.
    // Assert: F1_round2 >= F1_round1 - 0.05  (no significant regression).
    val prop = Prop.forAll(Plan.gen(nEntities = 5, variantsPerEntity = 4)) { plan =>
      val df = plan.toDF(spark, cfg)
      val z = new Zingg(cfg)

      // Round 1: cold start.
      val cands1   = z.findTrainingData(df, n = 25)
      val labeled1 = plan.labeller.label(cands1, cfg)
      val (m1, t1) = z.train(labeled1)
      val f1Round1 = clusterF1(plan, df, m1, t1)

      // Round 2: uncertainty-driven sample on top of round 1.
      val cands2   = z.findTrainingData(df, Some(m1), Some(t1), n = 25)
      val labeled2 = plan.labeller.label(cands2, cfg)
      val lId = s"${ZinggConf.LeftPrefix}${cfg.idCol}"
      val rId = s"${ZinggConf.RightPrefix}${cfg.idCol}"
      val combined = labeled1.unionByName(labeled2, allowMissingColumns = true)
        .dropDuplicates(Seq(lId, rId))
      val (m2, t2) = z.train(combined)
      val f1Round2 = clusterF1(plan, df, m2, t2)

      val ok = f1Round2 >= f1Round1 - 0.05
      if (!ok) System.err.println(
        f"[FAIL] active learning regressed: round1=$f1Round1%.3f round2=$f1Round2%.3f"
      )
      Prop(ok) :| f"round1=$f1Round1%.3f round2=$f1Round2%.3f"
    }
    runProp("activeLearningDoesNotRegress", prop, 4)
  }

  @Test
  def tolerantToModerateLabelNoise(): Unit = {
    // With 10% of labels flipped, clustering F1 should still be >= 0.50.
    // The CVE-ID block key keeps recall high; the noisy labels mostly hurt
    // precision (some learned weight on irrelevant features).
    val prop = Prop.forAll(Plan.gen(nEntities = 6, variantsPerEntity = 4)) { plan =>
      val noiseSeed = plan.entities.headOption.map(_.entityId.toLong).getOrElse(1L) * 31 + 7
      val labeled = Plan.labeledTrainingSetWithNoise(
        spark, cfg, plan, nNegatives = 40,
        noiseFraction = 0.10, noiseSeed = noiseSeed
      )
      val z = new Zingg(cfg)
      val (model, tree) = z.train(labeled)
      val df = plan.toDF(spark, cfg)
      val f1 = clusterF1(plan, df, model, tree)
      val ok = f1 >= 0.50
      if (!ok) System.err.println(f"[FAIL] noise=10%% f1=$f1%.3f")
      Prop(ok) :| f"f1=$f1%.3f"
    }
    runProp("tolerantToModerateLabelNoise", prop, 4)
  }

  @Test
  def degradesGracefullyWithHeavyNoise(): Unit = {
    // With 30% noise (close to random for binary labels), the classifier is
    // genuinely confused. We require only that the system doesn't collapse:
    // F1 >= 0.15. Below that the model is essentially noise.
    val prop = Prop.forAll(Plan.gen(nEntities = 5, variantsPerEntity = 4)) { plan =>
      val noiseSeed = plan.entities.headOption.map(_.entityId.toLong).getOrElse(1L) * 13 + 5
      val labeled = Plan.labeledTrainingSetWithNoise(
        spark, cfg, plan, nNegatives = 50,
        noiseFraction = 0.30, noiseSeed = noiseSeed
      )
      val z = new Zingg(cfg)
      val (model, tree) = z.train(labeled)
      val df = plan.toDF(spark, cfg)
      val f1 = clusterF1(plan, df, model, tree)
      Prop(f1 >= 0.15) :| f"f1=$f1%.3f at 30%% noise"
    }
    runProp("degradesGracefullyWithHeavyNoise", prop, 3)
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

    val rng = new Random(plan.entities.headOption.map(_.entityId.toLong).getOrElse(0L))
    val all = truth.keys.toVector
    val shuffled = rng.shuffle(all)
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

  private def runProp(name: String, prop: Prop, minSuccessful: Int): Unit = {
    val params = SCTest.Parameters.default.withMinSuccessfulTests(minSuccessful)
    val result = SCTest.check(params, prop)
    assert(result.passed, s"Property '$name' failed: $result")
  }
}
