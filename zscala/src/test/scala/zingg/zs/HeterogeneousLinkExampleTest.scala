package zingg.zs

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.types._
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

/** Runs the `examples/heterogeneous-link` example end-to-end against the real
  * config + CSV files, so the shipped example can't silently rot.
  *
  * It loads the example `config.json` through [[ConfigLoader]] (validating the
  * per-source `fieldMapping` / `fieldNormalizers` actually match the CSV
  * headers — [[Canonicalizer]] throws otherwise), trains an oracle model in
  * place of the interactive labeller, links across the three sources, and
  * asserts every person is resolved into a single cross-source cluster.
  */
@TestInstance(Lifecycle.PER_CLASS)
class HeterogeneousLinkExampleTest extends SharedSpark {

  private val exampleDir: os.Path =
    Iterator.iterate(os.pwd)(_ / os.up)
      .take(20)
      .map(_ / "examples" / "heterogeneous-link")
      .find(p => os.exists(p / "config.json"))
      .getOrElse(throw new RuntimeException(
        s"could not locate examples/heterogeneous-link from ${os.pwd}"))

  @Test
  def linksSixPeopleAcrossThreeHeterogeneousSources(): Unit = {
    val loaded = ConfigLoader.load(exampleDir / "config.json")
    assert(loaded.link, "example config should enable link mode")
    assert(loaded.inputs.map(_.name).toSet == Set("crm", "billing", "support"),
      s"unexpected sources: ${loaded.inputs.map(_.name)}")

    // Read each real CSV by absolute path and canonicalise it with the config's
    // mapping/normalizers. canonicalize throws if a mapped column is missing,
    // so this also validates config.json against the actual headers.
    val canon = loaded.inputs.map { io =>
      val raw = spark.read
        .option("header", "true")
        .option("inferSchema", "true")
        .csv((exampleDir / s"${io.name}.csv").toString)
      Canonicalizer.canonicalize(raw, io, loaded.cfg)
    }
    val union = canon.reduce(_ unionByName _)
    assert(union.count() == 15, s"expected 15 source rows, got ${union.count()}")

    val z = new Zingg(loaded.cfg, link = true)
    val model = Classifier.train(oracleLabeled(union, loaded.cfg), loaded.cfg)
    val clusters = z.cluster(union, model, BlockingTree.Leaf("root"))

    val byEmail: Map[String, Set[Any]] = clusters
      .select("email", loaded.cfg.clusterCol)
      .collect()
      .groupBy(_.getString(0))
      .map { case (email, rs) => email -> rs.map(_.getAs[Any](loaded.cfg.clusterCol)).toSet }

    assert(byEmail.size == 6, s"expected 6 distinct people, got ${byEmail.size}")
    byEmail.foreach { case (email, cs) =>
      assert(cs.size == 1, s"$email split across clusters $cs")
    }
    val nClusters = byEmail.values.flatten.toSet.size
    assert(nClusters == 6, s"expected 6 clusters (one per person), got $nClusters: $byEmail")
  }

  /** Oracle labels over all logical fields: a pair matches iff the normalised
    * emails are equal (email is the entity key in this example). */
  private def oracleLabeled(union: DataFrame, cfg: ZinggConf): DataFrame = {
    val names = cfg.fields.map(_.name)
    val emailIdx = names.indexOf("email")
    val rows = union.select(names.head, names.tail: _*).collect()
      .map(r => names.indices.map(i => Option(r.get(i)).map(_.toString).orNull).toArray)

    val pairs = for {
      i <- rows.indices; j <- (i + 1) until rows.length
    } yield {
      val l = rows(i); val r = rows(j)
      val label = if (l(emailIdx) == r(emailIdx)) 1.0 else 0.0
      Row.fromSeq(l.toSeq ++ r.toSeq :+ label)
    }

    val lFields = names.map(n => StructField(s"${ZinggConf.LeftPrefix}$n", StringType))
    val rFields = names.map(n => StructField(s"${ZinggConf.RightPrefix}$n", StringType))
    val schema = StructType(lFields ++ rFields :+ StructField(cfg.labelCol, DoubleType))
    val pdf = spark.createDataFrame(spark.sparkContext.parallelize(pairs.toSeq, 2), schema)
    Features.addFeatures(pdf, cfg)
  }
}
