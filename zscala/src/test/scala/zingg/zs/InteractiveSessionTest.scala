package zingg.zs

import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import scala.util.Random

@TestInstance(Lifecycle.PER_CLASS)
class InteractiveSessionTest extends SharedSpark {

  private val cfg = ZinggConf(
    fields = Seq(
      FieldDef("summary",     MatchType.Text),
      FieldDef("description", MatchType.CveId),
      FieldDef("priority",    MatchType.Exact)
    ),
    blockSize = 50,
    threshold = 0.5
  )

  private val rng = new Random()

  private def median(xs: Seq[Double]): Double = {
    require(xs.nonEmpty, "median of empty sequence")
    val s = xs.sorted
    val n = s.length
    if (n % 2 == 1) s(n / 2) else (s(n / 2 - 1) + s(n / 2)) / 2.0
  }

  @Test
  def labelsUniquePairsAndLearnsUsableModel(): Unit = {
    val K = 5
    val recalls = scala.collection.mutable.ArrayBuffer.empty[Double]
    val f1s     = scala.collection.mutable.ArrayBuffer.empty[Double]

    (0 until K).foreach { _ =>
      val plan    = Plan.build(rng.nextLong(), nEntities = 4, variantsPerEntity = 4)
      val df      = plan.toDF(spark, cfg)
      val oracle  = plan.labeller
      val z       = new Zingg(cfg)
      val session = z.interactiveSession(df)

      try {
        val seenKeys = scala.collection.mutable.Set.empty[(Long, Long)]
        val lIdIdx = session.labeled.schema.fieldIndex(s"${ZinggConf.LeftPrefix}${cfg.idCol}")
        val rIdIdx = session.labeled.schema.fieldIndex(s"${ZinggConf.RightPrefix}${cfg.idCol}")

        var labelCount = 0
        var more = true
        while (more && labelCount < 30) {
          session.nextPair() match {
            case None => more = false
            case Some(pair) =>
              val key = (pair.getLong(lIdIdx), pair.getLong(rIdIdx))
              // Strict per-run invariant: a pair is never offered twice.
              assert(!seenKeys.contains(key), s"duplicate pair offered: $key")
              seenKeys += key
              oracle.decide(pair, cfg) match {
                case RowLabeller.Match    => session.submitLabel(pair, 1.0); labelCount += 1
                case RowLabeller.NonMatch => session.submitLabel(pair, 0.0); labelCount += 1
                case _                    => session.skip(pair)
              }
          }
        }
        assert(labelCount > 0, "expected at least one definite label from the oracle")

        val (model, tree) = session.finalModel()
        val clusters = z.cluster(df, model, tree)
        val pred = clusters.select(cfg.idCol, cfg.clusterCol).collect()
          .map(r => r.getLong(0) -> r.getAs[Any](cfg.clusterCol)).toMap
        val (_, recall, f1) = Evaluator.pairwiseF1(pred, plan.truth)
        recalls += recall
        f1s     += f1
      } finally session.close()
    }

    // The unique-pair invariant above is the strict per-run check. Model quality
    // on this tiny dataset is fragile because connected-components clustering
    // collapses precision when one false-positive edge bridges clusters, so we
    // assert the MEDIAN over K sessions: the typical session recovers true
    // matches (recall) and stays above the all-merge f1 floor. That still
    // catches a regression toward under-matching (which drops both) without
    // flaking on the occasional precision collapse.
    val medRecall = median(recalls.toSeq)
    val medF1     = median(f1s.toSeq)
    assert(medRecall >= 0.7, f"median recall=$medRecall%.3f over $K sessions")
    assert(medF1 >= 0.3,    f"median f1=$medF1%.3f over $K sessions")
  }

  @Test
  def backgroundRetrainProducesModelEventually(): Unit = {
    // After labelling enough pairs to have both classes, finalModel must yield a
    // populated currentModel.
    val plan    = Plan.build(rng.nextLong(), nEntities = 3, variantsPerEntity = 4)
    val df      = plan.toDF(spark, cfg)
    val oracle  = plan.labeller
    val z       = new Zingg(cfg)
    val session = z.interactiveSession(df)

    try {
      var labelled = 0
      var more = true
      while (more && labelled < 12) {
        session.nextPair() match {
          case None => more = false
          case Some(p) =>
            oracle.decide(p, cfg) match {
              case RowLabeller.Match    => session.submitLabel(p, 1.0); labelled += 1
              case RowLabeller.NonMatch => session.submitLabel(p, 0.0); labelled += 1
              case _                    => session.skip(p)
            }
        }
      }
      val (_, _) = session.finalModel()
      assert(session.currentModel.isDefined || labelled == 0,
        "after finalModel, currentModel must be populated when any labels exist")
    } finally session.close()
  }
}
