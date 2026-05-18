package zingg.zs

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.jdk.CollectionConverters._

/** Minimal reader for the legacy Zingg JSON config.
  *
  * Recognises:
  *   fieldDefinition[] : { fieldName, matchType }   – mapped to [[FieldDef]]
  *   data[]            : { format, props.path, props.header }
  *   output[]          : { format, props.path }
  *
  * Anything else in the file is ignored. A real implementation would cover
  * multi-source input, JDBC, schemas, etc. — the goal here is to get a CLI
  * that can drive the zscala pipeline end-to-end on simple CSV inputs.
  */
object ConfigLoader {

  final case class IO(format: String, path: os.Path, header: Boolean)

  final case class Loaded(cfg: ZinggConf, inputs: Seq[IO], outputs: Seq[IO])

  def load(path: os.Path): Loaded = {
    val mapper         = new ObjectMapper()
    val root: JsonNode = mapper.readTree(path.toIO)

    val fields = nodes(root, "fieldDefinition").flatMap(parseField)
    val inputs = nodes(root, "data").map(parseIO)
    val outputs = nodes(root, "output").map(parseIO)

    require(fields.nonEmpty, s"config $path has no usable fieldDefinition entries")
    Loaded(ZinggConf(fields = fields), inputs, outputs)
  }

  private def nodes(root: JsonNode, name: String): Seq[JsonNode] = {
    val n = root.path(name)
    if (n.isArray) n.elements().asScala.toSeq else Seq.empty
  }

  private def parseField(n: JsonNode): Option[FieldDef] = {
    val name = n.path("fieldName").asText("")
    val mt   = n.path("matchType").asText("").toLowerCase
    if (name.isEmpty || mt == "dont_use" || mt == "do_not_use") None
    else Some(FieldDef(name, matchTypeOf(mt)))
  }

  private def matchTypeOf(s: String): MatchType = s match {
    case "exact"                       => MatchType.Exact
    case "fuzzy" | "text"              => MatchType.Fuzzy
    case "numeric" | "number" | "int"  => MatchType.Numeric
    case "email"                       => MatchType.Email
    case "text_long" | "long_text"     => MatchType.Text
    case "cve" | "cve_id" | "cveid"    => MatchType.CveId
    case _                             => MatchType.Custom
  }

  private def parseIO(n: JsonNode): IO = {
    val format = n.path("format").asText("csv")
    val props  = n.path("props")
    val path   = props.path("path").asText("")
    val header = props.path("header").asBoolean(false)
    require(path.nonEmpty, "data/output entry missing props.path")
    IO(format, os.Path(path, os.pwd), header)
  }
}
