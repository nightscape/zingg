package zingg.zs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

import org.apache.spark.sql.Row

/** LLM-backed labeller for OpenAI-compatible chat completion endpoints.
  *
  * Defaults to a local Ollama instance; override via env or constructor:
  *
  *   ZINGG_AI_ENDPOINT   default http://localhost:11434/v1/chat/completions
  *   ZINGG_AI_MODEL      default llama3
  *   ZINGG_AI_KEY        default unset (sent as `Authorization: Bearer …` if set)
  *
  * The prompt asks the model to answer with a single digit (1/0/-1). Pairs
  * the model can't decide on (-1, unparseable, or HTTP error) are skipped
  * — they're dropped from the returned DataFrame, not labelled.
  *
  * One HTTP call per pair. Active-learning batches are small (~30 pairs)
  * so latency rarely matters; if you do need throughput, wrap with a
  * thread pool — this class is thread-safe modulo the underlying HttpClient.
  */
final class LlmLabeller(
    endpoint: String = sys.env.getOrElse("ZINGG_AI_ENDPOINT", "http://localhost:11434/v1/chat/completions"),
    model:    String = sys.env.getOrElse("ZINGG_AI_MODEL",    "llama3"),
    apiKey:   Option[String] = sys.env.get("ZINGG_AI_KEY"),
    requestTimeout: Duration = Duration.ofSeconds(60)
) extends RowLabeller {

  @transient private lazy val mapper = new ObjectMapper()
  @transient private lazy val http   = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

  def decide(r: Row, cfg: ZinggConf): RowLabeller.Decision =
    askOne(r, cfg) match {
      case Some(1) => RowLabeller.Match
      case Some(0) => RowLabeller.NonMatch
      case _       => RowLabeller.Unknown  // unsure / error – defer
    }

  // Visible for testing.
  private[zs] def askOne(r: Row, cfg: ZinggConf): Option[Int] = {
    val prompt = buildPrompt(r, cfg)
    try {
      val body = callApi(prompt)
      parseLabel(body)
    } catch {
      case _: Throwable => None
    }
  }

  private def buildPrompt(r: Row, cfg: ZinggConf): String = {
    val left  = cfg.fields.map(f => f.name -> Option(r.getAs[Any](
      s"${ZinggConf.LeftPrefix}${f.name}")).map(_.toString).getOrElse("")).toMap
    val right = cfg.fields.map(f => f.name -> Option(r.getAs[Any](
      s"${ZinggConf.RightPrefix}${f.name}")).map(_.toString).getOrElse("")).toMap
    val rendered = cfg.fields.map { f =>
      f"  ${f.name}%-20s | A: ${left(f.name)}%n  ${" " * 20}  | B: ${right(f.name)}"
    }.mkString("\n")
    s"""You are deciding whether two records refer to the same real-world entity.
       |Answer with EXACTLY one digit on a line by itself, no other text:
       |  1  = same entity (match)
       |  0  = different entities (non-match)
       | -1  = cannot tell from the information given
       |
       |Record pair:
       |$rendered
       |
       |Answer:""".stripMargin
  }

  private def callApi(prompt: String): String = {
    val body = mapper.createObjectNode()
    body.put("model", model)
    body.put("temperature", 0.0)
    val messages = body.putArray("messages")
    val userMsg = messages.addObject()
    userMsg.put("role", "user")
    userMsg.put("content", prompt)

    val builder = HttpRequest.newBuilder()
      .uri(URI.create(endpoint))
      .timeout(requestTimeout)
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),
                                                 StandardCharsets.UTF_8))
    apiKey.foreach(k => builder.header("Authorization", s"Bearer $k"))

    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200)
      throw new RuntimeException(s"LLM HTTP ${response.statusCode()}: ${response.body()}")
    response.body()
  }

  /** Extract the integer label from an OpenAI chat-completions response.
    * Handles `message.content` and the `reasoning_content` field used by
    * Qwen3 / DeepSeek when their normal content is empty.
    */
  private[zs] def parseLabel(json: String): Option[Int] = {
    val root = mapper.readTree(json)
    val choices = root.path("choices")
    if (!choices.isArray || choices.size() == 0) return None
    val msg = choices.get(0).path("message")
    val text = Seq("content", "reasoning_content", "reasoning")
      .map(msg.path).map(n =>
        if (n.isMissingNode || n.isNull) "" else n.asText("")
      ).find(_.trim.nonEmpty).getOrElse("")
    LlmLabeller.firstSignedDigit(text)
  }
}

object LlmLabeller {
  /** Find the LAST standalone occurrence of `-1`, `0`, or `1` in the text —
    * standalone meaning not part of a larger number like `1234` or `2024`.
    *
    * "Last" because reasoning models put the final answer at the end after
    * chain-of-thought. CVE ids and other digit-runs embedded in the text
    * are skipped via the lookahead/lookbehind constraints.
    */
  private[zs] def firstSignedDigit(s: String): Option[Int] = {
    val re = "(?<![0-9])(-?[01])(?![0-9])".r
    re.findAllMatchIn(s).toList.lastOption.map(_.group(1).toInt)
  }
}
