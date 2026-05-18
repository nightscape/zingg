package zingg.zs

import org.apache.spark.ml.linalg.Vector
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types._
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

/** Several matchers on one column: a fuzzy text comparison *and* a configurable
  * regex id extraction on `summary`. Each matcher must contribute independently
  * to both scoring (feature width) and candidate generation (its own canopy). */
@TestInstance(Lifecycle.PER_CLASS)
class MultiMatcherTest extends SharedSpark {

  private val cfg = ZinggConf(
    fields = Seq(
      FieldDef("summary", Seq(MatchType.Fuzzy, MatchType.cve))
    ),
    blockSize = 50
  )

  private def df(rows: Seq[(Long, String)]): DataFrame = {
    val schema = StructType(Seq(
      StructField("z_id",    LongType,   nullable = false),
      StructField("summary", StringType, nullable = true)
    ))
    val data = rows.map { case (id, s) => Row(id, s) }
    spark.createDataFrame(spark.sparkContext.parallelize(data, 2), schema)
  }

  @Test
  def featureWidthSumsAllMatchers(): Unit = {
    // Fuzzy = 3 features, regex = 1 → 4.
    val width = cfg.fields.flatMap(_.matchTypes).map(Similarity.featureWidth).sum
    assert(width == 4, s"expected 4 features for [fuzzy, regex]; got $width")
  }

  @Test
  def eachMatcherContributesItsOwnCanopy(): Unit = {
    val data = df(Seq(
      (1L, "Alpha widget vulnerable to CVE-2021-12345"),
      (2L, "Zeta gadget vulnerable to CVE-2021-12345"),  // shares CVE, differs on first chars
      (3L, "Alpha thing vulnerable to CVE-2019-99999")   // shares first chars, differs on CVE
    ))
    val cands = new Zingg(cfg).findTrainingData(data, n = 100)
    val lId = s"${ZinggConf.LeftPrefix}${cfg.idCol}"
    val rId = s"${ZinggConf.RightPrefix}${cfg.idCol}"
    val pairs = cands.select(lId, rId).collect()
      .map(r => (r.getLong(0), r.getLong(1)))
      .map { case (a, b) => if (a < b) (a, b) else (b, a) }.toSet

    assert(pairs.contains((1L, 2L)),
      s"the regex (CVE) canopy must pair records sharing a CVE despite different leading text; got $pairs")
    assert(pairs.contains((1L, 3L)),
      s"the fuzzy (firstChars) canopy must pair records sharing leading text; got $pairs")
    assert(!pairs.contains((2L, 3L)),
      s"records sharing neither CVE nor leading text must not be paired; got $pairs")

    // The feature vector carries all four components (3 fuzzy + 1 regex).
    val featIdx = cands.schema.fieldIndex(Features.FeatureCol)
    val size = cands.collect().head.getAs[Vector](featIdx).size
    assert(size == 4, s"feature vector should have 4 components; got $size")
  }
}
