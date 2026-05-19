package zingg.zs

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.col

object PairBuilder {

  /** Candidate pairs from records sharing a block.
    *
    * `l_id < r_id` keeps each unordered pair once. When `crossSourceOnly` is
    * set (linkage mode), pairs from the same source are dropped so only
    * cross-source candidates survive — this is what makes N-way linkage fall
    * out of the same self-join: every source is canonicalised into one
    * DataFrame tagged with [[ZinggConf.SourceCol]], and the filter keeps only
    * pairs whose two records came from different systems.
    */
  def selfPairs(blocked: DataFrame, cfg: ZinggConf,
                blockCol: String = "z_block",
                crossSourceOnly: Boolean = false): DataFrame = {
    val left  = prefix(blocked, ZinggConf.LeftPrefix)
    val right = prefix(blocked, ZinggConf.RightPrefix)
    val lBlock = s"${ZinggConf.LeftPrefix}$blockCol"
    val rBlock = s"${ZinggConf.RightPrefix}$blockCol"
    val lId    = s"${ZinggConf.LeftPrefix}${cfg.idCol}"
    val rId    = s"${ZinggConf.RightPrefix}${cfg.idCol}"
    val joined = left.join(right, left(lBlock) === right(rBlock))
                     .filter(col(lId) < col(rId))
    if (crossSourceOnly) {
      val lSrc = s"${ZinggConf.LeftPrefix}${ZinggConf.SourceCol}"
      val rSrc = s"${ZinggConf.RightPrefix}${ZinggConf.SourceCol}"
      require(
        blocked.columns.contains(ZinggConf.SourceCol),
        s"cross-source pairing needs the '${ZinggConf.SourceCol}' column; canonicalise sources first"
      )
      joined.filter(col(lSrc) =!= col(rSrc))
    } else joined
  }

  private def prefix(df: DataFrame, p: String): DataFrame =
    df.columns.foldLeft(df) { (acc, c) => acc.withColumnRenamed(c, s"$p$c") }
}
