package zingg.zs

final case class FieldDef(
    name: String,
    matchType: MatchType,
    blockable: Boolean = true
)

sealed trait MatchType
object MatchType {
  case object Exact   extends MatchType
  case object Fuzzy   extends MatchType
  case object Numeric extends MatchType
  case object Email   extends MatchType
  case object Text    extends MatchType
  case object CveId   extends MatchType
  case object Custom  extends MatchType
}

final case class ZinggConf(
    fields: Seq[FieldDef],
    idCol: String = "z_id",
    labelCol: String = "z_label",
    predictionCol: String = "z_prediction",
    scoreCol: String = "z_score",
    clusterCol: String = "z_cluster",
    blockSize: Int = 500,
    numPartitions: Int = 200,
    threshold: Double = 0.5,
    sampleSeed: Option[Long] = None
)

object ZinggConf {
  val SourceCol   = "z_source"
  val LeftPrefix  = "l_"
  val RightPrefix = "r_"
}
