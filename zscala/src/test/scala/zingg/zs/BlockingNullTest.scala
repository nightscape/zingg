package zingg.zs

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types._
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

/** Regression tests for blocking of null/empty values.
  *
  * Records that hash to null on every blocking field carry no signal and must
  * not be paired. The old behaviour collapsed every such record into one shared
  * "null" block, so the lowest-id empty record became the left side of a pair
  * with everything else — and never matched anything.
  */
@TestInstance(Lifecycle.PER_CLASS)
class BlockingNullTest extends SharedSpark {

  private val cfg = ZinggConf(fields = Seq(FieldDef("name", MatchType.Fuzzy)))

  // Seed-style tree: block on the one fuzzy field, no learned children.
  private val tree: BlockingTree =
    BlockingTree.Node("name", Hash.FirstChars(2), Map.empty, BlockingTree.Leaf("seed"))

  private def df(rows: Seq[Row]): DataFrame = {
    val schema = StructType(Seq(
      StructField(cfg.idCol, LongType, nullable = false),
      StructField("name",    StringType, nullable = true)
    ))
    spark.createDataFrame(spark.sparkContext.parallelize(rows, 2), schema)
  }

  @Test
  def nullValuedRecordsGetNullBlockKey(): Unit = {
    val data = df(Seq(
      Row(0L, null),
      Row(1L, "Alice Smith"),
      Row(2L, "Alice Adams")
    ))
    val blocked = BlockingTree.assignBlocks(data, tree)
    val byId = blocked.select(cfg.idCol, "z_block").collect()
      .map(r => r.getLong(0) -> Option(r.getString(1))).toMap

    assert(byId(0L).isEmpty, s"null value must yield a null block key, got ${byId(0L)}")
    assert(byId(1L).isDefined && byId(1L) == byId(2L),
      s"same 2-char prefix must share a block: ${byId(1L)} vs ${byId(2L)}")
  }

  @Test
  def nullValuedRecordsDoNotPair(): Unit = {
    val data = df(Seq(
      Row(0L, null),          // empty record, lowest id — used to dominate the left side
      Row(1L, null),
      Row(2L, "Alice Smith"),
      Row(3L, "Alice Adams"),
      Row(4L, "Bob Jones")
    ))
    val blocked = BlockingTree.assignBlocks(data, tree)
    val pairs   = PairBuilder.selfPairs(blocked, cfg)

    val lId = s"${ZinggConf.LeftPrefix}${cfg.idCol}"
    val rId = s"${ZinggConf.RightPrefix}${cfg.idCol}"
    val idPairs = pairs.select(lId, rId).collect()
      .map(r => (r.getLong(0), r.getLong(1))).toSet

    // The two Alice rows (shared "Al" prefix) pair; null rows pair with no one.
    assert(idPairs == Set((2L, 3L)), s"unexpected pairs: $idPairs")
  }
}
