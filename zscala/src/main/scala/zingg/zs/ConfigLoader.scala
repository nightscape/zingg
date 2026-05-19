package zingg.zs

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.jdk.CollectionConverters._

/** Reader for the Zingg JSON config.
  *
  * Recognises:
  *   fieldDefinition[] : { fieldName, matchType }   – the global logical schema
  *   data[]            : { name, format, props.path, props.header,
  *                         fieldMapping, fieldNormalizers }
  *   output[]          : { format, props.path }
  *   link              : boolean — run cross-source linkage instead of dedup
  *
  * `fieldDefinition` defines the canonical (logical) fields by name. Each `data`
  * source may map those logical fields onto its own physical columns via
  * `fieldMapping` (logical → physical) and pre-normalise them via
  * `fieldNormalizers` (logical → normalizer). An absent mapping is identity, so
  * single-schema configs keep working unchanged.
  */
object ConfigLoader {

  final case class IO(
      format: String,
      path: os.Path,
      header: Boolean,
      name: String = "",
      mapping: Map[String, String] = Map.empty,
      normalizers: Map[String, String] = Map.empty
  )

  final case class Loaded(cfg: ZinggConf, inputs: Seq[IO], outputs: Seq[IO], link: Boolean)

  def load(path: os.Path): Loaded = {
    val mapper         = new ObjectMapper()
    val root: JsonNode = mapper.readTree(path.toIO)

    val fields = nodes(root, "fieldDefinition").flatMap(parseField)
    val inputs = nodes(root, "data").zipWithIndex.map { case (n, i) => parseInput(n, i) }
    val outputs = nodes(root, "output").map(parseOutput)
    val link    = root.path("link").asBoolean(false)

    require(fields.nonEmpty, s"config $path has no usable fieldDefinition entries")
    Loaded(ZinggConf(fields = fields), inputs, outputs, link)
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

  private def parseInput(n: JsonNode, idx: Int): IO = {
    val base = parseOutput(n)
    val name = {
      val explicit = n.path("name").asText("")
      if (explicit.nonEmpty) explicit else s"source_$idx"
    }
    base.copy(
      name        = name,
      mapping     = strMap(n.path("fieldMapping")),
      normalizers = strMap(n.path("fieldNormalizers"))
    )
  }

  private def parseOutput(n: JsonNode): IO = {
    val format = n.path("format").asText("csv")
    val props  = n.path("props")
    val path   = props.path("path").asText("")
    val header = props.path("header").asBoolean(false)
    require(path.nonEmpty, "data/output entry missing props.path")
    IO(format, os.Path(path, os.pwd), header)
  }

  private def strMap(n: JsonNode): Map[String, String] =
    if (!n.isObject) Map.empty
    else n.fields().asScala.map(e => e.getKey -> e.getValue.asText("")).toMap
}
