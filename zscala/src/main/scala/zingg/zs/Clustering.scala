package zingg.zs

import org.apache.spark.graphx.{Edge, Graph}
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.{col, lit}
import org.apache.spark.sql.types.StringType

object Clustering {

  def connectedComponents(
      pairs: DataFrame,
      idsDf: DataFrame,
      cfg: ZinggConf
  ): DataFrame = {
    val spark = pairs.sparkSession
    import spark.implicits._

    val leftIdCol  = s"${ZinggConf.LeftPrefix}${cfg.idCol}"
    val rightIdCol = s"${ZinggConf.RightPrefix}${cfg.idCol}"

    val idToLong = idsDf
      .select(col(cfg.idCol).cast(StringType).as("sid"))
      .distinct()
      .rdd.map(_.getString(0))
      .zipWithUniqueId()
      .toDF("sid", "lid")
      .cache()

    val edges = pairs
      .filter(col(cfg.predictionCol) === lit(1.0))
      .select(col(leftIdCol).cast(StringType).as("ls"),
              col(rightIdCol).cast(StringType).as("rs"))
      .join(idToLong.withColumnRenamed("sid", "ls").withColumnRenamed("lid", "lLid"), Seq("ls"))
      .join(idToLong.withColumnRenamed("sid", "rs").withColumnRenamed("lid", "rLid"), Seq("rs"))
      .select("lLid", "rLid").as[(Long, Long)]
      .rdd.map { case (a, b) => Edge(a, b, 1) }

    val vertices = idToLong.select("lid").as[Long].rdd.map(id => (id, id))

    val graph = Graph(vertices, edges, defaultVertexAttr = -1L)
    val cc = graph.connectedComponents().vertices.toDF("lid", "cluster")

    idToLong.join(cc, Seq("lid"))
      .select(col("sid").as(cfg.idCol), col("cluster").as(cfg.clusterCol))
  }
}
