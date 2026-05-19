package zingg.zs

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types._
import org.scalacheck.Gen

/** Plan-driven test oracle.
  *
  * A `Plan` defines N "true" entities, each with K noisy row variants. Every
  * variant carries an `entity_id` field — this is ground truth and the only
  * thing the test compares against. The model never sees `entity_id`.
  *
  * The plan also serves as a perfect oracle labeller: for any pair of rows,
  * we know whether they should match (same entity_id) or not.
  *
  * The CVE-JIRA scenario: each entity has a CVE-YYYY-NNNNN identifier embedded
  * somewhere in its description (sometimes also in summary). Noisy variants
  * paraphrase, reorder, add prefixes, change priority. The blocking-tree
  * learner should discover the CVE regex hash from labels and use it as a
  * near-perfect block key.
  */
final case class PlanEntity(
    entityId: Int,
    cveId: String,
    product: String,
    coreText: String,
    severity: String
)

final case class PlanRow(
    rowId: Long,
    entityId: Int,
    summary: String,
    description: String,
    priority: String
)

final case class Plan(entities: Seq[PlanEntity], rows: Seq[PlanRow]) {

  /** True cluster assignment: rowId -> entityId. */
  def truth: Map[Long, Int] = rows.iterator.map(r => r.rowId -> r.entityId).toMap

  /** Convert to a Spark DataFrame WITHOUT the entity_id (the model never sees it). */
  def toDF(spark: SparkSession, cfg: ZinggConf): DataFrame = {
    val schema = StructType(Seq(
      StructField(cfg.idCol,     LongType,   nullable = false),
      StructField("summary",     StringType, nullable = true),
      StructField("description", StringType, nullable = true),
      StructField("priority",    StringType, nullable = true)
    ))
    val rdd = spark.sparkContext.parallelize(
      rows.map(r => Row(r.rowId, r.summary, r.description, r.priority)),
      numSlices = 2
    )
    spark.createDataFrame(rdd, schema)
  }

  /** Build an `OracleLabeller` wired to this plan's ground truth. */
  def labeller: OracleLabeller = new OracleLabeller(truth)
}

object Plan {

  private val products = Vector("nginx", "openssl", "log4j", "spring-boot", "kubernetes",
                                 "postgres", "redis", "kafka", "envoy", "grpc")
  private val severities = Vector("low", "medium", "high", "critical")
  private val priorities = Vector("P1", "P2", "P3", "P4")

  private val coreTemplates = Vector(
    "remote code execution in %s",
    "buffer overflow affecting %s",
    "authentication bypass for %s",
    "privilege escalation in %s",
    "denial of service in %s",
    "memory corruption in %s",
    "SQL injection via %s",
    "path traversal in %s"
  )

  private val summaryPrefixes = Vector(
    "[SECURITY] ", "URGENT: ", "Vuln: ", "", "Fix needed - ", "Audit finding - "
  )

  private val descPrefixes = Vector(
    "Reported by scanner. ", "Detected via dependabot. ", "Found in audit. ",
    "Customer reported: ", "Internal scan: ", ""
  )

  /** Generate one random plan with `nEntities` entities and `variantsPerEntity` rows each. */
  def gen(nEntities: Int, variantsPerEntity: Int): Gen[Plan] = for {
    seed <- Gen.choose(1L, Long.MaxValue)
  } yield build(seed, nEntities, variantsPerEntity)

  /** Deterministic build given a seed. */
  def build(seed: Long, nEntities: Int, variantsPerEntity: Int): Plan = {
    val rng = new scala.util.Random(seed)
    val entities = (0 until nEntities).map { i =>
      val year = 2018 + rng.nextInt(8)
      val num  = rng.nextInt(90000) + 10000
      val cve  = f"CVE-$year-$num%05d"
      val product  = products(rng.nextInt(products.size))
      val core     = coreTemplates(rng.nextInt(coreTemplates.size)).format(product)
      val severity = severities(rng.nextInt(severities.size))
      PlanEntity(i, cve, product, core, severity)
    }

    val rows = entities.flatMap { e =>
      (0 until variantsPerEntity).map { v =>
        val rowId = (e.entityId.toLong * 1000L) + v.toLong
        val sPrefix = summaryPrefixes(rng.nextInt(summaryPrefixes.size))
        val dPrefix = descPrefixes(rng.nextInt(descPrefixes.size))
        // Vary where the CVE id appears: always in description, sometimes in summary.
        val cveInSummary = rng.nextBoolean()
        val summary =
          if (cveInSummary) s"$sPrefix${e.cveId} — ${perturb(e.coreText, rng)}"
          else              s"$sPrefix${perturb(e.coreText, rng)}"
        val description =
          s"$dPrefix${perturb(e.coreText, rng)}. Affects ${e.product}. " +
          s"Severity: ${e.severity}. Reference: ${e.cveId}."
        // Priority is mostly stable per entity, sometimes drifts.
        val basePri = priorities(e.entityId % priorities.size)
        val priority =
          if (rng.nextDouble() < 0.2) priorities(rng.nextInt(priorities.size))
          else basePri
        PlanRow(rowId, e.entityId, summary, description, priority)
      }
    }

    Plan(entities, rows)
  }

  private def perturb(text: String, rng: scala.util.Random): String = {
    val words = text.split(" ").toBuffer
    // Occasional word swap.
    if (words.size > 2 && rng.nextDouble() < 0.3) {
      val i = rng.nextInt(words.size - 1)
      val tmp = words(i); words(i) = words(i + 1); words(i + 1) = tmp
    }
    // Occasional single-char typo.
    val joined = words.mkString(" ")
    if (rng.nextDouble() < 0.3 && joined.length > 4) {
      val i = rng.nextInt(joined.length)
      joined.substring(0, i) + joined.substring(i + 1)
    } else joined
  }

  /** Build a labeled training DataFrame directly from a plan, in the shape
    * `Classifier.train` expects: left-prefixed / right-prefixed columns + z_features + z_label.
    *
    * Positives: ALL same-entity pairs.
    * Negatives: `nNegatives` random cross-entity pairs.
    */
  def labeledTrainingSet(spark: SparkSession, cfg: ZinggConf,
                         plan: Plan, nNegatives: Int = 40): DataFrame = {
    import org.apache.spark.sql.functions.{lit, udf, struct, col}
    import org.apache.spark.ml.linalg.Vectors

    val byEntity = plan.rows.groupBy(_.entityId)
    val positives: Seq[(PlanRow, PlanRow)] = byEntity.values.flatMap { rs =>
      val v = rs.toVector
      for { i <- v.indices; j <- (i + 1) until v.size } yield (v(i), v(j))
    }.toSeq

    val rng = new scala.util.Random(0xBEEF)
    val negatives: Seq[(PlanRow, PlanRow)] =
      if (plan.entities.size < 2) Seq.empty
      else (0 until nNegatives).flatMap { _ =>
        val eA = plan.entities(rng.nextInt(plan.entities.size))
        val eB = plan.entities(rng.nextInt(plan.entities.size))
        if (eA.entityId == eB.entityId) None
        else {
          val a = byEntity(eA.entityId)(rng.nextInt(byEntity(eA.entityId).size))
          val b = byEntity(eB.entityId)(rng.nextInt(byEntity(eB.entityId).size))
          Some((a, b))
        }
      }

    val schema = StructType(Seq(
      StructField(s"${ZinggConf.LeftPrefix}${cfg.idCol}",        LongType),
      StructField(s"${ZinggConf.LeftPrefix}summary",              StringType),
      StructField(s"${ZinggConf.LeftPrefix}description",          StringType),
      StructField(s"${ZinggConf.LeftPrefix}priority",             StringType),
      StructField(s"${ZinggConf.RightPrefix}${cfg.idCol}",       LongType),
      StructField(s"${ZinggConf.RightPrefix}summary",             StringType),
      StructField(s"${ZinggConf.RightPrefix}description",         StringType),
      StructField(s"${ZinggConf.RightPrefix}priority",            StringType),
      StructField(cfg.labelCol,                                   DoubleType)
    ))
    def mkRow(l: PlanRow, r: PlanRow, label: Double): Row =
      Row(l.rowId, l.summary, l.description, l.priority,
          r.rowId, r.summary, r.description, r.priority, label)

    val rows = positives.map { case (l, r) => mkRow(l, r, 1.0) } ++
               negatives.map { case (l, r) => mkRow(l, r, 0.0) }
    val rdd = spark.sparkContext.parallelize(rows, 2)
    val pairs = spark.createDataFrame(rdd, schema)
    Features.addFeatures(pairs, cfg)
  }

  /** Same as `labeledTrainingSet` but flips each label independently with
    * probability `noiseFraction`. Simulates a fallible labeller (human or LLM).
    */
  def labeledTrainingSetWithNoise(spark: SparkSession, cfg: ZinggConf, plan: Plan,
                                  nNegatives: Int, noiseFraction: Double,
                                  noiseSeed: Long): DataFrame = {
    import org.apache.spark.sql.functions.{col, lit, udf, when}
    val clean = labeledTrainingSet(spark, cfg, plan, nNegatives)
    val rng = new scala.util.Random(noiseSeed)
    // Per-row deterministic flip: hash row id + seed → uniform [0,1).
    val nseed = noiseSeed
    val lFrac = noiseFraction
    val flip = udf { (lId: Long, rId: Long, label: Double) =>
      val r = new scala.util.Random(nseed ^ (lId * 0x9E3779B97F4A7C15L) ^ rId)
      if (r.nextDouble() < lFrac) 1.0 - label else label
    }
    val lId = s"${ZinggConf.LeftPrefix}${cfg.idCol}"
    val rId = s"${ZinggConf.RightPrefix}${cfg.idCol}"
    clean.withColumn(cfg.labelCol, flip(col(lId), col(rId), col(cfg.labelCol)))
  }
}

/** Evaluation: pairwise precision/recall vs ground truth. */
object Evaluator {

  /** Compute pairwise F1 between predicted clusters and ground truth.
    *
    *  Pairwise metrics: over all unordered pairs of rows,
    *    TP = pairs predicted-same AND truly-same
    *    FP = pairs predicted-same AND truly-different
    *    FN = pairs predicted-different AND truly-same
    */
  def pairwiseF1(predicted: Map[Long, Any], truth: Map[Long, Int]): (Double, Double, Double) = {
    val ids = truth.keys.toVector
    var tp, fp, fn = 0L
    for {
      i <- ids.indices
      j <- (i + 1) until ids.size
    } {
      val a = ids(i); val b = ids(j)
      val sameTruth = truth(a) == truth(b)
      val samePred  = predicted.get(a).exists(p => predicted.get(b).contains(p))
      (sameTruth, samePred) match {
        case (true,  true)  => tp += 1
        case (false, true)  => fp += 1
        case (true,  false) => fn += 1
        case _              => ()
      }
    }
    val precision = if (tp + fp == 0) 1.0 else tp.toDouble / (tp + fp)
    val recall    = if (tp + fn == 0) 1.0 else tp.toDouble / (tp + fn)
    val f1        = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
    (precision, recall, f1)
  }
}
