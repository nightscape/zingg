package zingg.zs

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types._
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import scala.util.Random

/** Properties over the *candidate set presented to the labeller*, rather than
  * over downstream clustering quality.
  *
  * These target a whole class of "the labeller keeps showing me the same
  * useless pair" bugs. The canonical instance: a record that is blank on a
  * sparse blocking field collapses into one giant `""` block together with
  * every other blank-on-that-field record; the lowest-id blank record then
  * becomes the *left* side of every pair in that block (because
  * [[PairBuilder.selfPairs]] keeps `l_id < r_id`). If that block dominates the
  * pool, every sampled pair shows the same empty left record — so the human can
  * only ever answer "no" and labelling never produces a positive.
  *
  * The two invariants below are deliberately independent of *which* record is
  * to blame:
  *
  *   A. A record carrying no signal on the blocking dimension(s) must never be
  *      a labelling candidate at all.
  *   B. No single field-value tuple may dominate one side of the candidate set
  *      — the same content must not be shown over and over, even if it
  *      originates from several different rows.
  *
  * The generator is half the test: it deliberately injects blank / whitespace /
  * sparse records, because a property that never sees a degenerate input can
  * never catch a degenerate-input bug.
  */
@TestInstance(Lifecycle.PER_CLASS)
class CandidateDiversityPropertyTest extends SharedSpark {

  // First (blockable) field is `name`; the seed tree blocks on it. We make it
  // SPARSE on purpose so blanks share a block with real records.
  private def cfg(seed: Long) = ZinggConf(
    fields = Seq(
      FieldDef("name",  MatchType.Fuzzy),
      FieldDef("email", MatchType.Email)
    ),
    blockSize = 50,
    sampleSeed = Some(seed) // pin per-iteration so any failure reproduces
  )

  private val blanks = Vector("", "   ", "\t", " \n ")

  /** A dataset of mostly-blank-`name` records plus one all-blank "villain".
    * Returns the DataFrame and the villain's id. */
  private def genData(seed: Long): (DataFrame, Long) = {
    val rng   = new Random(seed)
    val nReal = 25 + rng.nextInt(25)

    // id 0: blank on EVERY field — pure noise, must never be shown.
    val villain = Row(0L, blanks(rng.nextInt(blanks.size)), blanks(rng.nextInt(blanks.size)))

    val real = (1L to nReal.toLong).map { i =>
      // 70% of real records are also blank on the blocking field `name`, but
      // carry a real email — these are the rows the villain wrongly pairs with.
      val name  = if (rng.nextDouble() < 0.3) s"Person ${rng.nextInt(8)}" else blanks(rng.nextInt(blanks.size))
      val email = s"user$i@example.com"
      Row(i, name, email)
    }

    val schema = StructType(Seq(
      StructField("z_id",  LongType,   nullable = false),
      StructField("name",  StringType, nullable = true),
      StructField("email", StringType, nullable = true)
    ))
    val df = spark.createDataFrame(
      spark.sparkContext.parallelize(villain +: real, 2), schema)
    (df, 0L)
  }

  private def candidateIdPairs(df: DataFrame, c: ZinggConf): Array[(Long, Long)] = {
    val cands = new Zingg(c).findTrainingData(df, n = 30)
    val lId = s"${ZinggConf.LeftPrefix}${c.idCol}"
    val rId = s"${ZinggConf.RightPrefix}${c.idCol}"
    cands.select(lId, rId).collect().map(r => (r.getLong(0), r.getLong(1)))
  }

  /** Distinct left-side value tuples among the candidate pairs, and the share
    * held by the single most frequent one. */
  private def topLeftShare(df: DataFrame, c: ZinggConf): Double = {
    val cands = new Zingg(c).findTrainingData(df, n = 30)
    val lCols = c.fields.map(f => s"${ZinggConf.LeftPrefix}${f.name}")
    val rows = cands.select(lCols.head, lCols.tail: _*).collect()
      .map(r => (0 until r.length).map(i => Option(r.get(i)).map(_.toString.trim).getOrElse("")).toList)
    if (rows.isEmpty) 0.0
    else rows.groupBy(identity).values.map(_.length).max.toDouble / rows.length
  }

  // ─── Property A: signal-free records are never candidates ──────────────────

  @Test
  def blankRecordIsNeverACandidate(): Unit = {
    val rng = new Random()
    val violations = (0 until 8).flatMap { _ =>
      val seed = rng.nextLong()
      val (df, villainId) = genData(seed)
      val appearances = candidateIdPairs(df, cfg(seed))
        .count { case (l, r) => l == villainId || r == villainId }
      if (appearances > 0) Some(seed -> appearances) else None
    }
    assert(violations.isEmpty,
      s"all-blank record was shown to the labeller in: ${violations.mkString(", ")}")
  }

  // ─── Property B: no single value tuple dominates the left side ─────────────

  @Test
  def noLeftValueTupleDominates(): Unit = {
    val rng = new Random()
    val shares = (0 until 8).map { _ =>
      val seed = rng.nextLong()
      val (df, _) = genData(seed)
      topLeftShare(df, cfg(seed))
    }
    val median = shares.sorted.apply(shares.length / 2)
    assert(median <= 0.5,
      f"one left-side value tuple dominated the candidate set; median share=$median%.2f " +
      f"over ${shares.length} datasets (shares=${shares.map(s => f"$s%.2f").mkString(",")})")
  }
}
