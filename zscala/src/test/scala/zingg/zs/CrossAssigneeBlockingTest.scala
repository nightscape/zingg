package zingg.zs

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types._
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

/** Candidate-generation properties for the "always the same assignee" report.
  *
  * Scenario (the user's): JIRA tickets where `assignee` is the only frequently
  * identical field, but true matches share a CVE id while differing on assignee.
  * Cold-start blocking on a single field keyed on assignee, so cross-assignee /
  * same-CVE matches were never proposed and same-assignee / different-CVE pairs
  * dominated. Multi-key (canopy) blocking unions a CVE-id key, so a pair
  * surfaces when the CVE agrees regardless of assignee.
  *
  *   A: "Java 11.0.51 vulnerable to CVE-2021-12345", assignee joe123
  *   B: "Java 11.0.50 vulnerable to CVE-2021-12345", assignee ben456   (match A)
  *   C: "Tomcat 4.3.2 vulnerable to CVE-2019-98765", assignee joe123    (≠ A)
  */
@TestInstance(Lifecycle.PER_CLASS)
class CrossAssigneeBlockingTest extends SharedSpark {

  // `assignee` is listed FIRST on purpose — the old single-field seed would key
  // on it. It is marked non-blockable, so it is a scoring feature only.
  private val cfg = ZinggConf(
    fields = Seq(
      FieldDef("assignee", MatchType.Exact, blockable = false),
      FieldDef("summary",  MatchType.Text),
      FieldDef("priority", MatchType.Exact, blockable = false)
    ),
    blockSize = 50
  )

  private def df(rows: Seq[(Long, String, String, String)]): DataFrame = {
    val schema = StructType(Seq(
      StructField("z_id",     LongType,   nullable = false),
      StructField("assignee", StringType, nullable = true),
      StructField("summary",  StringType, nullable = true),
      StructField("priority", StringType, nullable = true)
    ))
    val data = rows.map { case (id, a, s, p) => Row(id, a, s, p) }
    spark.createDataFrame(spark.sparkContext.parallelize(data, 2), schema)
  }

  private def candidatePairs(data: DataFrame): Set[(Long, Long)] = {
    val cands = new Zingg(cfg).findTrainingData(data, n = 200)
    val lId = s"${ZinggConf.LeftPrefix}${cfg.idCol}"
    val rId = s"${ZinggConf.RightPrefix}${cfg.idCol}"
    cands.select(lId, rId).collect()
      .map(r => (r.getLong(0), r.getLong(1)))
      .map { case (a, b) => if (a < b) (a, b) else (b, a) }.toSet
  }

  @Test
  def sameCveDifferentAssigneeIsProposed(): Unit = {
    val data = df(Seq(
      (1L, "joe123", "Java 11.0.51 vulnerable to CVE-2021-12345", "P1"),
      (2L, "ben456", "Java 11.0.50 vulnerable to CVE-2021-12345", "P2"),
      (3L, "joe123", "Tomcat 4.3.2 vulnerable to CVE-2019-98765", "P1")
    ))
    val pairs = candidatePairs(data)
    assert(pairs.contains((1L, 2L)),
      s"the same-CVE / different-assignee match (1,2) must be a candidate; got $pairs")
  }

  @Test
  def candidatesAreNotDominatedBySharedAssignee(): Unit = {
    // Many tickets share assignee joe123 but have unrelated CVEs; only the two
    // CVE-2021-12345 tickets (1,2) are a true match. With assignee non-blockable
    // and CVE-keyed canopies, candidate generation must not collapse to the
    // assignee block (which would pair 1–3, 1–4, 3–4, … and miss 1–2).
    val data = df(Seq(
      (1L, "joe123", "Java 11.0.51 vulnerable to CVE-2021-12345", "P1"),
      (2L, "ben456", "Java 11.0.50 vulnerable to CVE-2021-12345", "P2"),
      (3L, "joe123", "Tomcat 4.3.2 vulnerable to CVE-2019-98765", "P1"),
      (4L, "joe123", "nginx 1.2.3 vulnerable to CVE-2020-55555",  "P3"),
      (5L, "joe123", "redis 6.0 vulnerable to CVE-2018-11111",    "P1")
    ))
    val pairs = candidatePairs(data)
    assert(pairs.contains((1L, 2L)),
      s"the only true match (1,2) must be proposed; got $pairs")
    // No pair among the four joe123 tickets with distinct CVEs should be a
    // candidate — they share nothing but the (non-blockable) assignee.
    val spuriousAssigneePairs =
      Set((1L, 3L), (1L, 4L), (1L, 5L), (3L, 4L), (3L, 5L), (4L, 5L))
    assert(pairs.intersect(spuriousAssigneePairs).isEmpty,
      s"pairs sharing only the non-blockable assignee must not be proposed; " +
      s"offending=${pairs.intersect(spuriousAssigneePairs)}")
  }
}
