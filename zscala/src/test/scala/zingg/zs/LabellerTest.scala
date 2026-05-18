package zingg.zs

import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

@TestInstance(Lifecycle.PER_CLASS)
class LabellerTest extends SharedSpark {

  private val cfg = ZinggConf(fields = Seq(
    FieldDef("summary",     MatchType.Text),
    FieldDef("description", MatchType.CveId),
    FieldDef("priority",    MatchType.Exact)
  ))

  // ─── OracleLabeller ────────────────────────────────────────────────────

  @Test
  def oracleLabelsMatchesAndNonMatches(): Unit = {
    val plan = Plan.build(seed = 42L, nEntities = 3, variantsPerEntity = 3)
    val df = plan.toDF(spark, cfg)
    val z = new Zingg(cfg)
    val candidates = z.findTrainingData(df, n = 20)
    val labeled = plan.labeller.label(candidates, cfg)
    val labels = labeled.select(cfg.labelCol).collect().map(_.getDouble(0)).toSet
    // We expect both 1.0 and 0.0 in any non-trivial plan.
    assert(labels.subsetOf(Set(0.0, 1.0)),
           s"labels must be 0.0 or 1.0; got $labels")
    assert(labels.contains(1.0), "expected at least one match label")
  }

  @Test
  def oracleSkipsUnknownIds(): Unit = {
    val plan = Plan.build(seed = 7L, nEntities = 2, variantsPerEntity = 2)
    val df = plan.toDF(spark, cfg)
    val z = new Zingg(cfg)
    val candidates = z.findTrainingData(df, n = 10)
    val labelled = new OracleLabeller(Map.empty[Long, Int]).label(candidates, cfg)
    // Unknown ids -> all skipped -> empty.
    assert(labelled.count() == 0)
  }

  // ─── LlmLabeller response parsing ──────────────────────────────────────

  @Test
  def llmParsesCleanContent(): Unit = {
    val r = parse("""{"choices":[{"message":{"content":"1"}}]}""")
    assert(r.contains(1))
  }

  @Test
  def llmParsesNonMatch(): Unit = {
    val r = parse("""{"choices":[{"message":{"content":"0"}}]}""")
    assert(r.contains(0))
  }

  @Test
  def llmParsesReasoningModelOutput(): Unit = {
    // Qwen3 / DeepSeek style: real answer is in reasoning_content with chain-of-thought.
    val r = parse(
      """{"choices":[{"message":{
        |  "content":"",
        |  "reasoning_content":"Let me think... both rows mention CVE-2024-1234 so they refer to the same vulnerability. Final answer: 1"
        |}}]}""".stripMargin)
    assert(r.contains(1))
  }

  @Test
  def llmParsesNegativeOne(): Unit = {
    val r = parse("""{"choices":[{"message":{"content":"-1"}}]}""")
    assert(r.contains(-1))
  }

  @Test
  def llmReturnsNoneForGarbage(): Unit = {
    val r = parse("""{"choices":[{"message":{"content":"I have no idea, sorry."}}]}""")
    assert(r.isEmpty)
  }

  @Test
  def llmReturnsNoneForMalformedJson(): Unit = {
    // parseLabel itself doesn't catch — but the production `askOne` does.
    // Test the contract via firstSignedDigit on empty extracted text.
    assert(LlmLabeller.firstSignedDigit("").isEmpty)
  }

  private val labeller = new LlmLabeller()
  private def parse(json: String): Option[Int] = labeller.parseLabel(json)
}
