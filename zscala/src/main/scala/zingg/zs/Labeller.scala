package zingg.zs

import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.{DoubleType, StructField, StructType}

/** Attaches a `z_label` column to candidate pairs.
  *
  *   1.0  = match
  *   0.0  = non-match
  *   NaN  = skip / unsure  (filtered out before training)
  *
  * Candidate pairs come from `Zingg.findTrainingData`, so they already have
  * `l_*` / `r_*` columns and a `z_features` Vector. Implementations may use
  * any of those columns to make the labelling decision.
  *
  * Pair counts at the active-learning stage are small (typically 20–50 per
  * round), so driver-side `collect()` is fine. Concrete labellers shouldn't
  * worry about distributed execution.
  */
trait Labeller extends Serializable {
  def label(pairs: DataFrame, cfg: ZinggConf): DataFrame
}

/** A labeller that decides one row at a time with a tri-state answer:
  *
  *   Match / NonMatch  – definite answer, attach the label
  *   Skip              – drop this pair entirely (e.g. unknown ids)
  *   Unknown           – defer to the next labeller in a chain
  *
  * The `Skip` vs `Unknown` distinction is what `CompositeLabeller` needs;
  * the original [[Labeller]] trait conflated the two by simply dropping
  * rows for which `mkLabel` returned `None`.
  */
trait RowLabeller extends Labeller {
  def decide(r: Row, cfg: ZinggConf): RowLabeller.Decision

  final def label(pairs: DataFrame, cfg: ZinggConf): DataFrame =
    Labeller.attachLabels(pairs, cfg) { r =>
      decide(r, cfg) match {
        case RowLabeller.Match    => Some(1.0)
        case RowLabeller.NonMatch => Some(0.0)
        case RowLabeller.Skip     => None
        case RowLabeller.Unknown  => None
      }
    }
}

object RowLabeller {
  sealed trait Decision
  case object Match    extends Decision
  case object NonMatch extends Decision
  case object Skip     extends Decision
  case object Unknown  extends Decision

  def fromDouble(v: Double): Decision =
    if (v == 1.0) Match else if (v == 0.0) NonMatch else Unknown
}

object Labeller {

  /** Helper: build a labeled DataFrame by appending a `z_label` column to
    * the input rows, given a per-row label-producing function. Used by all
    * concrete labellers that label one row at a time.
    *
    * Rows for which `mkLabel` returns `None` are dropped (skipped pairs).
    */
  def attachLabels(pairs: DataFrame, cfg: ZinggConf)
                  (mkLabel: Row => Option[Double]): DataFrame = {
    val spark = pairs.sparkSession
    val collected = pairs.collect()
    val labeled = collected.flatMap { r =>
      mkLabel(r).map { lab =>
        val values = (0 until r.length).map(r.get)
        Row.fromSeq(values :+ lab)
      }
    }
    val newSchema = StructType(
      pairs.schema.fields :+ StructField(cfg.labelCol, DoubleType, nullable = false)
    )
    val rdd = spark.sparkContext.parallelize(labeled.toSeq, 2)
    spark.createDataFrame(rdd, newSchema)
  }
}
