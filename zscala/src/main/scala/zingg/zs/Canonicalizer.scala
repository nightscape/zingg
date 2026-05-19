package zingg.zs

import org.apache.spark.sql.{Column, DataFrame}
import org.apache.spark.sql.functions.{col, lit, lower, regexp_replace, trim, upper}
import org.apache.spark.sql.types.StringType

import ConfigLoader.IO

/** Projects a heterogeneous source onto the canonical (logical) schema.
  *
  * Record linkage across systems is decoupled into two steps: first *align*
  * each source's physical columns to the shared logical fields, then *link* on
  * those aligned fields. This object is the alignment step: for every logical
  * field in [[ZinggConf.fields]] it picks the source's mapped physical column
  * (identity when unmapped), applies the configured per-field normalizer, casts
  * to string, and renames it to the logical name. The source is tagged with
  * [[ZinggConf.SourceCol]] so the downstream linker can keep records' origins
  * distinct.
  *
  * A logical field a source genuinely doesn't carry is null-filled, so sources
  * with different column sets still align onto one schema. An *explicit* mapping
  * to a column that doesn't exist is treated as a config error and fails loudly
  * instead — a silently null-filled, all-blank record would otherwise surface as
  * a half-empty pair in the labeller.
  *
  * The result of canonicalising every source shares one schema, so the sources
  * can be `unionByName`-ed and fed through the existing block/pair/feature
  * pipeline without it needing to know anything about the original layouts.
  */
object Canonicalizer {

  def canonicalize(df: DataFrame, io: IO, cfg: ZinggConf): DataFrame = {
    val cols = cfg.fields.map { f =>
      val mapped   = io.mapping.get(f.name)
      val physical = mapped.getOrElse(f.name)
      if (df.columns.contains(physical)) {
        val normalized = io.normalizers.get(f.name).fold(col(physical))(n => Normalizer(n)(col(physical)))
        normalized.cast(StringType).as(f.name)
      } else {
        // A field the source never carries is null-filled so heterogeneous
        // sources can still be unioned onto the shared schema. But an explicit
        // mapping to a column that isn't there is a config error: fail loudly
        // rather than emit an all-blank record that breaks downstream display.
        require(
          mapped.isEmpty,
          s"source '${io.name}' maps logical field '${f.name}' to column " +
          s"'$physical', which does not exist. Available columns: " +
          s"${df.columns.mkString(", ")}"
        )
        lit(null).cast(StringType).as(f.name)
      }
    }
    df.select(cols: _*).withColumn(ZinggConf.SourceCol, lit(io.name))
  }
}

/** Named, per-field value normalizers applied during canonicalisation to absorb
  * formatting differences between sources (casing, punctuation, stray digits).
  * All operate on the string form of the column.
  */
object Normalizer {

  def apply(name: String): Column => Column = name.trim.toLowerCase match {
    case "lower"  => c => lower(c.cast(StringType))
    case "upper"  => c => upper(c.cast(StringType))
    case "trim"   => c => trim(c.cast(StringType))
    case "digits" => c => regexp_replace(c.cast(StringType), "\\D", "")
    case "alnum"  => c => regexp_replace(lower(c.cast(StringType)), "[^a-z0-9]", "")
    case other =>
      throw new IllegalArgumentException(
        s"unknown normalizer '$other'. Valid: lower|upper|trim|digits|alnum"
      )
  }
}
