package zingg.zs

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import scala.jdk.CollectionConverters._

/** Reader for the Zingg JSON config.
  *
  * Recognises:
  *   fieldDefinition[] : { fieldName, matchType, blocking? } – the logical schema
  *                       `matchType` is a string, an object `{ type, pattern }`
  *                       (e.g. `{ "type": "regex", "pattern": "ISSUE-\\d+" }`), or
  *                       an array of either to apply several matchers to one column.
  *                       `blocking: false` ⇒ scoring feature only, never a block key.
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
      normalizers: Map[String, String] = Map.empty,
      options: Map[String, String] = Map.empty
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
    // `"blocking": false` keeps a field as a scoring feature but bars it from
    // being used as a block key — for incidental fields (e.g. assignee) that
    // otherwise dominate candidate generation. Defaults to true.
    val blockable  = n.path("blocking").asBoolean(true)
    val matchTypes = parseMatchTypes(n.path("matchType"))
    if (name.isEmpty || matchTypes.isEmpty) None
    else Some(FieldDef(name, matchTypes, blockable))
  }

  /** `matchType` may be a single string, a single object, or an array mixing
    * both — yielding one or more matchers for the field. `dont_use` entries are
    * dropped; a field left with no matcher is excluded entirely. */
  private def parseMatchTypes(n: JsonNode): Seq[MatchType] = {
    val elems = if (n.isArray) n.elements().asScala.toSeq else Seq(n)
    elems.flatMap(parseMatcher)
  }

  /** One matcher. Object form: `{ "type": "regex", "pattern": "<regex>" }`
    * (any `type` that names a built-in match type is also accepted). */
  private def parseMatcher(n: JsonNode): Option[MatchType] =
    if (n.isObject) n.path("type").asText("").toLowerCase match {
      case "regex" =>
        val p = n.path("pattern").asText("")
        require(p.nonEmpty, "a \"regex\" matchType requires a non-empty \"pattern\"")
        Some(MatchType.Regex(p))
      case other => matchTypeOf(other)
    }
    else if (n.isTextual) matchTypeOf(n.asText("").toLowerCase)
    else None

  private def matchTypeOf(s: String): Option[MatchType] = s match {
    case "" | "dont_use" | "do_not_use" => None
    case "exact"                        => Some(MatchType.Exact)
    case "fuzzy" | "text"               => Some(MatchType.Fuzzy)
    case "numeric" | "number" | "int"   => Some(MatchType.Numeric)
    case "email"                        => Some(MatchType.Email)
    case "text_long" | "long_text"      => Some(MatchType.Text)
    case "cve" | "cve_id" | "cveid"     => Some(MatchType.cve)
    case _                              => Some(MatchType.Custom)
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
    IO(format, os.Path(path, os.pwd), header, options = readerOptions(props))
  }

  /** Every `props` entry except `path`/`header` (handled separately) is passed
    * straight through to the Spark reader/writer as an option. This is what
    * carries `delimiter`, `multiLine`, `quote`, `escape`, … from the config —
    * without it a multi-line quoted CSV is shredded into mostly-empty fragment
    * rows that then surface as half-empty pairs in the labeller. */
  private def readerOptions(props: JsonNode): Map[String, String] =
    if (!props.isObject) Map.empty
    else props.fields().asScala
      .filterNot(e => e.getKey == "path" || e.getKey == "header")
      .map(e => e.getKey -> e.getValue.asText(""))
      .toMap

  private def strMap(n: JsonNode): Map[String, String] =
    if (!n.isObject) Map.empty
    else n.fields().asScala.map(e => e.getKey -> e.getValue.asText("")).toMap
}
