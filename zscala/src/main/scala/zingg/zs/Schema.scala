package zingg.zs

/** A logical field and the matchers applied to it. A field may carry several
  * matchers — e.g. a fuzzy text comparison *and* a regex id extraction on the
  * same column — each of which contributes its own similarity feature(s) and
  * (when the field is blockable) its own blocking canopies. */
final case class FieldDef(
    name: String,
    matchTypes: Seq[MatchType],
    blockable: Boolean = true
)

object FieldDef {
  /** Single-matcher convenience. */
  def apply(name: String, matchType: MatchType): FieldDef =
    FieldDef(name, Seq(matchType))
  def apply(name: String, matchType: MatchType, blockable: Boolean): FieldDef =
    FieldDef(name, Seq(matchType), blockable)
}

sealed trait MatchType
object MatchType {
  case object Exact   extends MatchType
  case object Fuzzy   extends MatchType
  case object Numeric extends MatchType
  case object Email   extends MatchType
  case object Text    extends MatchType
  case object Custom  extends MatchType

  /** Extract the first match of `pattern` from each value and compare the
    * extracted tokens for equality. Generalises the former `CveId` matcher to
    * any configurable regex (e.g. ticket ids, package coordinates, hostnames). */
  final case class Regex(pattern: String) extends MatchType

  /** The CVE-id pattern, shared by [[cve]] and the blocking hashes so the
    * scoring extraction and the block key agree. */
  val CvePattern = "(?i)CVE-\\d{4}-\\d{4,7}"

  /** Convenience alias: CVE-id extraction as a [[Regex]] matcher. */
  val cve: MatchType = Regex(CvePattern)
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
