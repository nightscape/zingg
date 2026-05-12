package zingg.zs

import org.apache.spark.sql.SparkSession

/** Singleton SparkSession reused across property iterations. Building a new
  * session per ScalaCheck `forAll` iteration would dominate test time;
  * caching here lets each property run in seconds.
  */
object SharedSpark {

  @volatile private var instance: SparkSession = _

  def spark: SparkSession = {
    if (instance == null) {
      this.synchronized {
        if (instance == null) {
          instance = SparkSession.builder()
            .master("local[2]")
            .appName("zscala-property-tests")
            .config("spark.ui.enabled", "false")
            .config("spark.sql.shuffle.partitions", "2")
            .config("spark.sql.adaptive.enabled", "false")
            .config("spark.driver.bindAddress", "127.0.0.1")
            .config("spark.driver.host", "127.0.0.1")
            .getOrCreate()
          instance.sparkContext.setLogLevel("WARN")
        }
      }
    }
    instance
  }
}

trait SharedSpark {
  lazy val spark: SparkSession = SharedSpark.spark
}
