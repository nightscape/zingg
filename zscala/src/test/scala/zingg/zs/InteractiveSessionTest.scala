package zingg.zs

import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

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

  @Test
  def labelsUniquePairsAndConvergesToWorkingModel(): Unit = {
    val plan    = Plan.build(seed = 17L, nEntities = 4, variantsPerEntity = 4)
    val df      = plan.toDF(spark, cfg)
    val oracle  = plan.labeller
    val z       = new Zingg(cfg)
    val session = z.interactiveSession(df)

    try {
      val seenKeys = scala.collection.mutable.Set.empty[(Long, Long)]
      val lIdIdx = session.labeled.schema.fieldIndex(s"${ZinggConf.LeftPrefix}${cfg.idCol}")
      val rIdIdx = session.labeled.schema.fieldIndex(s"${ZinggConf.RightPrefix}${cfg.idCol}")

      val maxLabels = 30
      var labelCount = 0
      var more = true
      while (more && labelCount < maxLabels) {
        session.nextPair() match {
          case None => more = false
          case Some(pair) =>
            val key = (pair.getLong(lIdIdx), pair.getLong(rIdIdx))
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
      val (precision, recall, f1) = Evaluator.pairwiseF1(pred, plan.truth)
      assert(f1 >= 0.6,
        f"interactive session should reach reasonable F1; got precision=$precision%.3f recall=$recall%.3f f1=$f1%.3f")
    } finally session.close()
  }

  @Test
  def backgroundRetrainProducesModelEventually(): Unit = {
    // After labelling enough pairs to have both classes, a retrain should
    // complete and currentModel should become non-empty (possibly only after
    // finalModel() blocks for the in-flight future).
    val plan    = Plan.build(seed = 3L, nEntities = 3, variantsPerEntity = 4)
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
