package zingg.zs

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.col

object PairBuilder {

  def selfPairs(blocked: DataFrame, cfg: ZinggConf,
                blockCol: String = "z_block"): DataFrame = {
    val left  = prefix(blocked, ZinggConf.LeftPrefix)
    val right = prefix(blocked, ZinggConf.RightPrefix)
    val lBlock = s"${ZinggConf.LeftPrefix}$blockCol"
    val rBlock = s"${ZinggConf.RightPrefix}$blockCol"
    val lId    = s"${ZinggConf.LeftPrefix}${cfg.idCol}"
    val rId    = s"${ZinggConf.RightPrefix}${cfg.idCol}"
    left.join(right, left(lBlock) === right(rBlock))
        .filter(col(lId) < col(rId))
  }

  def crossPairs(leftBlocked: DataFrame, rightBlocked: DataFrame, cfg: ZinggConf,
                 blockCol: String = "z_block"): DataFrame = {
    val l = prefix(leftBlocked, ZinggConf.LeftPrefix)
    val r = prefix(rightBlocked, ZinggConf.RightPrefix)
    val lBlock = s"${ZinggConf.LeftPrefix}$blockCol"
    val rBlock = s"${ZinggConf.RightPrefix}$blockCol"
    l.join(r, l(lBlock) === r(rBlock))
  }

  private def prefix(df: DataFrame, p: String): DataFrame =
    df.columns.foldLeft(df) { (acc, c) => acc.withColumnRenamed(c, s"$p$c") }
}
