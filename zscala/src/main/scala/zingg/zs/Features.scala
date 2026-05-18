package zingg.zs

import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.{col, struct, udf}

object Features {

  val FeatureCol = "z_features"

  def addFeatures(pairs: DataFrame, cfg: ZinggConf): DataFrame = {
    val fields = cfg.fields
    val widths = fields.map(f => Similarity.featureWidth(f.matchType))
    val total  = widths.sum
    val featUdf = udf { (row: Row) =>
      val arr = new Array[Double](total)
      var idx = 0
      fields.foreach { f =>
        val a = row.getAs[Any](s"${ZinggConf.LeftPrefix}${f.name}")
        val b = row.getAs[Any](s"${ZinggConf.RightPrefix}${f.name}")
        val v = Similarity.features(f.matchType, a, b)
        System.arraycopy(v, 0, arr, idx, v.length)
        idx += v.length
      }
      Vectors.dense(arr)
    }
    val structCols = pairs.columns.map(col)
    pairs.withColumn(FeatureCol, featUdf(struct(structCols: _*)))
  }
}
