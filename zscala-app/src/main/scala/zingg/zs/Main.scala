package zingg.zs

import mainargs.{Flag, ParserForMethods, arg, main}
import org.apache.spark.sql.SparkSession

/** CLI entry point for the zscala module.
  *
  * Mirrors the option surface of the legacy `zingg.spark.client.SparkClient`
  * main class so existing `zingg.sh` invocations work unchanged. Every flag
  * is parsed to its final type (no post-hoc string-bashing): `--phase` lands
  * as a [[Phase]] ADT, every path-shaped flag as [[os.Path]], `--email` as
  * a validated [[Email]]. Unknown values fail at argument-parse time with a
  * pointed error, not deep in the pipeline.
  *
  * Example:
  * {{{
  *   spark-submit --class zingg.zs.Main zingg-zscala-app-0.6.0.jar \
  *     --phase trainMatch --conf config.json --zinggDir /tmp/zingg --modelId 100
  * }}}
  */
object Main {
  import CliReaders._

  @main
  def run(
      @arg(doc = "phase: train|match|trainMatch|findTrainingData|label|link|"
                 + "generateDocs|recommend|updateLabel|findAndLabel")
      phase: Phase,
      @arg(doc = "JSON configuration with data input/output and field definitions")
      conf: os.Path,
      @arg(doc = "location of license file")
      license: Option[os.Path] = None,
      @arg(name = "jobId", doc = "database job id for logging")
      jobId: Option[String] = None,
      @arg(doc = "notification email id")
      email: Option[Email] = None,
      @arg(doc = "format of the data (csv|parquet|...)")
      format: Option[String] = None,
      @arg(name = "zinggDir", doc = "location of Zingg models")
      zinggDir: os.Path = os.Path("/tmp/zingg"),
      @arg(name = "modelId", doc = "model identifier")
      modelId: Int = 1,
      @arg(name = "collectMetrics", doc = "collect analytics")
      collectMetrics: Boolean = true,
      @arg(name = "showConcise", doc = "show only fields used to build the model")
      showConcise: Boolean = false,
      @arg(doc = "location of CSV file for exported data")
      location: Option[os.Path] = None,
      @arg(doc = "name of the column")
      column: Option[String] = None,
      @arg(doc = "running remotely on databricks")
      remote: Boolean = false,
      @arg(doc = "convert files to unix format before processing")
      preprocess: Flag,
      @arg(doc = "Spark master URL (e.g. local[*], yarn, spark://host:7077)")
      master: String = "local[*]"
  ): Unit = {

    val loaded   = ConfigLoader.load(conf)
    val modelDir = zinggDir / modelId.toString

    val spark = SparkSession.builder()
      .appName(s"zingg-zscala-${phase.name}")
      .master(master)
      .getOrCreate()

    try Phases.run(phase, loaded, modelDir, location, spark)
    finally spark.stop()
  }

  def main(args: Array[String]): Unit =
    ParserForMethods(this).runOrExit(args.toIndexedSeq)
}
