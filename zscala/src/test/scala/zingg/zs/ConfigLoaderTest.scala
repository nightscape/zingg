package zingg.zs

import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

/** Config parsing, with emphasis on CSV reader options. A dropped `multiLine`
  * option shreds quoted multi-line CSV rows into mostly-empty fragments that
  * then surface as half-empty pairs in the labeller, so the passthrough is
  * load-bearing and pinned here. */
@TestInstance(Lifecycle.PER_CLASS)
class ConfigLoaderTest {

  private def loadConfig(json: String): ConfigLoader.Loaded = {
    val tmp = os.temp(json, suffix = ".json")
    ConfigLoader.load(tmp)
  }

  @Test
  def csvPropsBecomeReaderOptions(): Unit = {
    val loaded = loadConfig(
      """{
        |  "link": true,
        |  "fieldDefinition": [{ "fieldName": "assignee", "matchType": "fuzzy" }],
        |  "data": [{
        |    "name": "src",
        |    "format": "csv",
        |    "props": {
        |      "path": "examples/x.csv",
        |      "delimiter": ",",
        |      "header": true,
        |      "multiLine": "true"
        |    },
        |    "fieldMapping": { "assignee": "Assignee" }
        |  }],
        |  "output": [{ "name": "o", "format": "csv",
        |               "props": { "path": "/tmp/out", "header": true } }]
        |}""".stripMargin)

    val io = loaded.inputs.head
    assert(io.header, "header should be parsed")
    assert(io.options.get("multiLine").contains("true"),
      s"multiLine must pass through to reader options; got ${io.options}")
    assert(io.options.get("delimiter").contains(","),
      s"delimiter must pass through; got ${io.options}")
    // path/header are handled as dedicated fields, not duplicated as options.
    assert(!io.options.contains("path"), "path must not leak into reader options")
    assert(!io.options.contains("header"), "header must not be duplicated into options")
  }

  private def fieldsOf(matchTypeJson: String): Seq[FieldDef] = loadConfig(
    s"""{
       |  "fieldDefinition": [{ "fieldName": "f", "matchType": $matchTypeJson }],
       |  "data": [{ "name": "s", "format": "csv",
       |             "props": { "path": "x.csv", "header": true } }],
       |  "output": [{ "name": "o", "format": "csv",
       |              "props": { "path": "/tmp/out", "header": true } }]
       |}""".stripMargin).cfg.fields

  @Test
  def matchTypeAsObjectRegex(): Unit = {
    val f = fieldsOf("""{ "type": "regex", "pattern": "ISSUE-\\d+" }""").head
    assert(f.matchTypes == Seq(MatchType.Regex("ISSUE-\\d+")),
      s"object form should parse a regex matcher; got ${f.matchTypes}")
  }

  @Test
  def matchTypeAsArrayOfStringAndObject(): Unit = {
    val f = fieldsOf("""[ "fuzzy", { "type": "regex", "pattern": "(?i)CVE-\\d{4}-\\d+" } ]""").head
    assert(f.matchTypes == Seq(MatchType.Fuzzy, MatchType.Regex("(?i)CVE-\\d{4}-\\d+")),
      s"array form should apply several matchers in order; got ${f.matchTypes}")
  }

  @Test
  def cveStringIsRegexAlias(): Unit = {
    val f = fieldsOf("\"cve\"").head
    assert(f.matchTypes == Seq(MatchType.cve) && f.matchTypes.head.isInstanceOf[MatchType.Regex],
      s"\"cve\" should be a regex alias; got ${f.matchTypes}")
  }

  @Test
  def dontUseInArrayIsDropped(): Unit = {
    val f = fieldsOf("""[ "dont_use", "exact" ]""").head
    assert(f.matchTypes == Seq(MatchType.Exact),
      s"dont_use entries should be dropped from a matcher list; got ${f.matchTypes}")
  }

  @Test
  def fieldWithOnlyDontUseIsExcluded(): Unit = {
    val fields = loadConfig(
      """{
        |  "fieldDefinition": [
        |    { "fieldName": "ignored", "matchType": "dont_use" },
        |    { "fieldName": "kept",    "matchType": "exact" }
        |  ],
        |  "data": [{ "name": "s", "format": "csv",
        |             "props": { "path": "x.csv", "header": true } }],
        |  "output": [{ "name": "o", "format": "csv",
        |              "props": { "path": "/tmp/out", "header": true } }]
        |}""".stripMargin).cfg.fields
    assert(fields.map(_.name) == Seq("kept"),
      s"a field with no usable matcher must be excluded; got ${fields.map(_.name)}")
  }
}
