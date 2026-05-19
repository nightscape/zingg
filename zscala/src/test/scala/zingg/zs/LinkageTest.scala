package zingg.zs

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types._
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import ConfigLoader.IO

/** Tests for N-way heterogeneous record linkage (Option A: align then link).
  *
  * Three sources describe the same people under different column names and
  * different formatting. After canonicalisation onto the shared logical schema
  * they are linked across sources; the entity key (a unique email) is never a
  * model input — it is only used as the oracle to check the result.
  */
@TestInstance(Lifecycle.PER_CLASS)
class LinkageTest extends SharedSpark {

  private val cfg = ZinggConf(
    fields = Seq(
      FieldDef("name",  MatchType.Fuzzy),
      FieldDef("email", MatchType.Email)
    ),
    blockSize = 50,
    threshold = 0.5
  )

  // Three entities, each present in all three sources with different layouts
  // and casing. email is unique per entity and the ground-truth key.
  private val crmIO     = IO("csv", os.pwd, header = true, name = "crm",
    mapping = Map("name" -> "full_name", "email" -> "mail"),
    normalizers = Map("email" -> "lower"))
  private val billingIO = IO("csv", os.pwd, header = true, name = "billing",
    mapping = Map("email" -> "email_addr"))
  private val supportIO = IO("csv", os.pwd, header = true, name = "support",
    mapping = Map("name" -> "customer", "email" -> "contact_email"),
    normalizers = Map("email" -> "lower"))

  private def crm: DataFrame = df(
    Seq("full_name", "mail"),
    Seq(
      Row("Alice Smith", "ALICE@EXAMPLE.COM"),
      Row("Bob Jones",   "BOB@EXAMPLE.COM"),
      Row("Carol White", "CAROL@EXAMPLE.COM")
    ))

  private def billing: DataFrame = df(
    Seq("name", "email_addr"),
    Seq(
      Row("Alice Smith", "alice@example.com"),
      Row("Bob Jones",   "bob@example.com"),
      Row("Carol White", "carol@example.com")
    ))

  private def support: DataFrame = df(
    Seq("customer", "contact_email"),
    Seq(
      Row("Alice Smith", "Alice@Example.com"),
      Row("Bob Jones",   "Bob@Example.com"),
      Row("Carol White", "Carol@Example.com")
    ))

  private def df(cols: Seq[String], rows: Seq[Row]): DataFrame = {
    val schema = StructType(cols.map(c => StructField(c, StringType, nullable = true)))
    spark.createDataFrame(spark.sparkContext.parallelize(rows, 2), schema)
  }

  private def canonicalUnion: DataFrame =
    Seq(
      Canonicalizer.canonicalize(crm, crmIO, cfg),
      Canonicalizer.canonicalize(billing, billingIO, cfg),
      Canonicalizer.canonicalize(support, supportIO, cfg)
    ).reduce(_ unionByName _)

  // ─────────────────────────────────────────────────────────────────────────

  @Test
  def canonicalizeMapsHeterogeneousColumnsAndNormalizes(): Unit = {
    val c = Canonicalizer.canonicalize(crm, crmIO, cfg)
    assert(c.columns.toSet == Set("name", "email", ZinggConf.SourceCol),
      s"unexpected columns: ${c.columns.mkString(",")}")
    val emails = c.select("email").as(spark.implicits.newStringEncoder).collect().toSet
    assert(emails == Set("alice@example.com", "bob@example.com", "carol@example.com"),
      s"emails not lowercased: $emails")
    val sources = c.select(ZinggConf.SourceCol).as(spark.implicits.newStringEncoder).collect().toSet
    assert(sources == Set("crm"), s"source tag wrong: $sources")
  }

  @Test
  def missingMappedColumnFailsLoudly(): Unit = {
    val bad = IO("csv", os.pwd, header = true, name = "bad",
      mapping = Map("email" -> "nonexistent"))
    val ex = try {
      Canonicalizer.canonicalize(billing, bad, cfg)
      None
    } catch { case e: IllegalArgumentException => Some(e) }
    assert(ex.exists(_.getMessage.contains("nonexistent")), s"expected failure for missing column, got $ex")
  }

  @Test
  def crossSourcePairingExcludesSameSourcePairs(): Unit = {
    val withId = canonicalUnion.withColumn(
      cfg.idCol, org.apache.spark.sql.functions.monotonically_increasing_id())
    val blocked = BlockingTree.assignBlocks(withId, BlockingTree.Leaf("root"))
    val pairs = PairBuilder.selfPairs(blocked, cfg, crossSourceOnly = true)
    val lSrc = s"${ZinggConf.LeftPrefix}${ZinggConf.SourceCol}"
    val rSrc = s"${ZinggConf.RightPrefix}${ZinggConf.SourceCol}"
    val sameSource = pairs.filter(org.apache.spark.sql.functions.col(lSrc) ===
                                  org.apache.spark.sql.functions.col(rSrc)).count()
    assert(sameSource == 0, s"$sameSource same-source pairs leaked into linkage candidates")
    assert(pairs.count() > 0, "no cross-source candidate pairs generated")
  }

  @Test
  def linksSameEntityAcrossThreeSources(): Unit = {
    val union = canonicalUnion
    val model = Classifier.train(labeledFrom(union), cfg)
    val z = new Zingg(cfg, link = true)
    assertLinkedCorrectly(z.cluster(union, model, BlockingTree.Leaf("root")))
  }

  @Test
  def blockingTreeJsonRoundTrips(): Unit = {
    val tree = BlockingTree.Node(
      "description", Hash.RegexExtract("(?i)CVE-\\d{4}-\\d{4,7}"),
      Map(
        "CVE-2021-0001" -> BlockingTree.Leaf("a"),
        "CVE-2020-9999" -> BlockingTree.Node(
          "priority", Hash.IdentityString, Map.empty, BlockingTree.Leaf("b"))
      ),
      BlockingTree.Leaf("fallback")
    )
    val restored = BlockingTree.fromJson(BlockingTree.toJson(tree))
    assert(restored == tree, s"round-trip mismatch:\nexpected $tree\ngot      $restored")
  }

  @Test
  def persistedTreeDrivesLinkClustering(): Unit = {
    val union = canonicalUnion
    val z = new Zingg(cfg, link = true)
    val (model, tree) = z.train(labeledFrom(union))
    val restored = BlockingTree.fromJson(BlockingTree.toJson(tree))
    // The deserialized tree must drive clustering exactly like the original.
    assertLinkedCorrectly(z.cluster(union, model, restored))
  }

  /** Assert every entity (unique email) lands in exactly one cluster and the
    * three distinct entities are not merged together. */
  private def assertLinkedCorrectly(clusters: DataFrame): Unit = {
    val byEmail: Map[String, Set[Any]] = clusters
      .select("email", cfg.clusterCol)
      .collect()
      .groupBy(_.getString(0))
      .map { case (email, rs) => email -> rs.map(_.getAs[Any](cfg.clusterCol)).toSet }
    byEmail.foreach { case (email, cs) =>
      assert(cs.size == 1, s"entity $email split across clusters $cs")
    }
    val allClusters = byEmail.values.flatten.toSet
    assert(allClusters.size == 3, s"expected 3 clusters, got ${allClusters.size}: $byEmail")
  }

  /** Oracle-labeled training set built from the canonical rows: a pair is a
    * match iff the two records share an email. */
  private def labeledFrom(union: DataFrame): DataFrame = {
    val rows = union.select("name", "email", ZinggConf.SourceCol).collect()
      .map(r => (r.getString(0), r.getString(1), r.getString(2)))
    val pairs = for {
      i <- rows.indices; j <- (i + 1) until rows.length
    } yield {
      val (ln, le, _) = rows(i)
      val (rn, re, _) = rows(j)
      Row(ln, le, rn, re, if (le == re) 1.0 else 0.0)
    }
    val schema = StructType(Seq(
      StructField(s"${ZinggConf.LeftPrefix}name",   StringType),
      StructField(s"${ZinggConf.LeftPrefix}email",  StringType),
      StructField(s"${ZinggConf.RightPrefix}name",  StringType),
      StructField(s"${ZinggConf.RightPrefix}email", StringType),
      StructField(cfg.labelCol,                     DoubleType)
    ))
    val pdf = spark.createDataFrame(spark.sparkContext.parallelize(pairs.toSeq, 2), schema)
    Features.addFeatures(pdf, cfg)
  }
}
