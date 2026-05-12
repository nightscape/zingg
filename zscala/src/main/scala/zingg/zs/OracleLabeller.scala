package zingg.zs

import org.apache.spark.sql.Row

/** Labels pairs from a known ground-truth map (rowId → clusterId).
  *
  * Use this in tests or when bootstrapping with a small hand-curated set
  * of "definitely-same" assignments. Two rows match iff they share a
  * cluster id in the truth map; rows missing from the map are skipped.
  */
final class OracleLabeller(truth: Map[Long, Int]) extends RowLabeller {
  private val lIdName = s"${ZinggConf.LeftPrefix}"
  private val rIdName = s"${ZinggConf.RightPrefix}"

  def decide(r: Row, cfg: ZinggConf): RowLabeller.Decision = {
    val l  = r.getAs[Long](s"$lIdName${cfg.idCol}")
    val rr = r.getAs[Long](s"$rIdName${cfg.idCol}")
    (truth.get(l), truth.get(rr)) match {
      case (Some(a), Some(b)) =>
        if (a == b) RowLabeller.Match else RowLabeller.NonMatch
      case _ => RowLabeller.Unknown  // unknown id – defer / drop
    }
  }
}
