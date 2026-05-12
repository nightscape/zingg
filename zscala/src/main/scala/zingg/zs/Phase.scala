package zingg.zs

/** The Zingg pipeline phases recognised by the CLI. Same names as the legacy
  * `zingg.common.client.options.ZinggOptions` so existing `zingg.sh` scripts
  * keep working.
  */
sealed abstract class Phase(val name: String)

object Phase {
  case object Train            extends Phase("train")
  case object Match            extends Phase("match")
  case object TrainMatch       extends Phase("trainMatch")
  case object FindTrainingData extends Phase("findTrainingData")
  case object Label            extends Phase("label")
  case object Link             extends Phase("link")
  case object GenerateDocs     extends Phase("generateDocs")
  case object Recommend        extends Phase("recommend")
  case object UpdateLabel      extends Phase("updateLabel")
  case object FindAndLabel     extends Phase("findAndLabel")

  val all: Seq[Phase] = Seq(
    Train, Match, TrainMatch, FindTrainingData, Label,
    Link, GenerateDocs, Recommend, UpdateLabel, FindAndLabel
  )

  def parse(s: String): Either[String, Phase] =
    all.find(_.name == s).toRight(
      s"unknown phase '$s'. Valid: ${all.map(_.name).mkString("|")}"
    )
}
