package zingg.zs

import org.apache.spark.sql.{DataFrame, Row}

import scala.io.StdIn

/** Interactive CLI labeller: prints each pair to stdout and reads y/n/s
  * from stdin.
  *
  *   y / 1 → match (1.0)
  *   n / 0 → non-match (0.0)
  *   s     → skip this pair (no label produced)
  *   q     → quit; remaining pairs get no label
  */
final class CliLabeller(
    out: java.io.PrintStream = System.out,
    in:  java.io.BufferedReader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)),
    width: Int = CliLabeller.defaultWidth
) extends Labeller {

  def label(pairs: DataFrame, cfg: ZinggConf): DataFrame = {
    import org.apache.spark.sql.types.{DoubleType, StructField, StructType}
    val rows = pairs.collect()
    var quit = false
    val outRows = rows.zipWithIndex.flatMap { case (r, idx) =>
      if (quit) None
      else askOne(r, idx, rows.length, cfg) match {
        case CliLabeller.Quit   => quit = true; None
        case CliLabeller.Skip   => None
        case CliLabeller.Lab(v) =>
          Some(Row.fromSeq((0 until r.length).map(r.get) :+ v))
      }
    }
    val schema = StructType(pairs.schema.fields :+
      StructField(cfg.labelCol, DoubleType, nullable = false))
    val spark = pairs.sparkSession
    spark.createDataFrame(spark.sparkContext.parallelize(outRows.toSeq, 2), schema)
  }

  private def askOne(r: Row, idx: Int, total: Int, cfg: ZinggConf): CliLabeller.Answer = {
    out.println()
    out.println(s"━━━ Pair ${idx + 1} / $total ━━━")
    PairTable.render(cfg, r, width).foreach(out.println)
    out.print("Match? [y]es / [n]o / [s]kip / [q]uit: ")
    out.flush()
    Option(in.readLine()).map(_.trim.toLowerCase).getOrElse("q") match {
      case "y" | "1" | "yes" => CliLabeller.Lab(1.0)
      case "n" | "0" | "no"  => CliLabeller.Lab(0.0)
      case "q" | "quit"      => CliLabeller.Quit
      case _                 => CliLabeller.Skip
    }
  }
}

object CliLabeller {
  /** Terminal width from `$COLUMNS` when sane, else a readable default. */
  def defaultWidth: Int =
    sys.env.get("COLUMNS").flatMap(s => scala.util.Try(s.trim.toInt).toOption)
      .filter(_ >= 40).getOrElse(100)

  sealed trait Answer
  case object Quit             extends Answer
  case object Skip             extends Answer
  final case class Lab(v: Double) extends Answer
}
