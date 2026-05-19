package zingg.zs

import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.feature.PolynomialExpansion
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, udf}

object Classifier {

  private val PolyCol = "z_poly_features"

  def pipeline(cfg: ZinggConf): Pipeline = {
    val poly = new PolynomialExpansion()
      .setInputCol(Features.FeatureCol)
      .setOutputCol(PolyCol)
      .setDegree(2)
    val lr = new LogisticRegression()
      .setFeaturesCol(PolyCol)
      .setLabelCol(cfg.labelCol)
      .setPredictionCol(cfg.predictionCol)
      .setProbabilityCol("z_prob")
      .setMaxIter(100)
      .setRegParam(0.01)
    new Pipeline().setStages(Array(poly, lr))
  }

  def train(labeled: DataFrame, cfg: ZinggConf): PipelineModel =
    pipeline(cfg).fit(labeled)

  def score(model: PipelineModel, pairs: DataFrame, cfg: ZinggConf): DataFrame = {
    val withProb = model.transform(pairs)
    val pMatch = udf((v: Vector) => v(1))
    withProb.withColumn(cfg.scoreCol, pMatch(col("z_prob")))
  }
}
