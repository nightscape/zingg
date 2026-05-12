package zingg.zs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.apache.spark.sql.Row
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

/** Records a simulated interactive labelling session as an asciicast v2 file
  * (`interactive-session.cast` at the repo root). Play it back with
  * `asciinema play interactive-session.cast` or upload to asciinema.org.
  *
  * Time in the cast is *simulated* — we advance a virtual clock between
  * frames so playback feels natural without making the test slow. Background
  * retrains still run on real wall time; we detect when the new model
  * becomes visible (identity-hash of `currentModel` changes) and emit a
  * banner at that point in the simulated timeline.
  */
@TestInstance(Lifecycle.PER_CLASS)
class InteractiveSessionDemoTest extends SharedSpark {

  private val ESC = ""
  private def fg(code: String, s: String): String = s"$ESC[${code}m$s$ESC[0m"

  private val cfg = ZinggConf(
    fields = Seq(
      FieldDef("summary",     MatchType.Text),
      FieldDef("description", MatchType.CveId),
      FieldDef("priority",    MatchType.Exact)
    ),
    blockSize = 50,
    threshold = 0.5
  )

  @Test
  def recordInteractiveSessionAsAsciicast(): Unit = {
    val plan    = Plan.build(seed = 7L, nEntities = 4, variantsPerEntity = 4)
    val df      = plan.toDF(spark, cfg)
    val oracle  = plan.labeller
    val z       = new Zingg(cfg)
    val session = z.interactiveSession(df)
    val cast    = new AsciicastRecorder(width = 110, height = 34,
                    title = "Zingg interactive active-learning loop")

    try {
      cast.emit(fg("1;36", "═══ Zingg interactive labelling demo ═══") + "\r\n", 0.0)
      cast.emit("\r\n", 0.4)
      cast.emit("First pair is drawn at random (cold start).\r\n", 0.5)
      cast.emit("As soon as we have one match and one non-match, a retrain runs in\r\n", 0.5)
      cast.emit("the background and the next pair becomes the most uncertain one\r\n", 0.5)
      cast.emit("under the freshly trained model.\r\n", 0.6)
      cast.emit("\r\n", 0.8)

      val maxLabels      = 14
      var labelCount     = 0
      var lastModelId    = Option.empty[Int]
      var firstSwapShown = false
      var more           = true

      while (more && labelCount < maxLabels) {
        session.nextPair() match {
          case None => more = false
          case Some(pair) =>
            val curId = session.currentModel.map(System.identityHashCode)
            if (curId.isDefined && curId != lastModelId) {
              if (!firstSwapShown) {
                cast.emit(fg("1;33", "[bg] first retrain complete — switching from random to uncertainty sampling.") + "\r\n\r\n", 0.3)
                firstSwapShown = true
              } else {
                cast.emit(fg("1;33", "[bg] retrain done — new uncertainty ranking active.") + "\r\n\r\n", 0.3)
              }
            }
            lastModelId = curId

            renderPair(cast, pair, labelCount + 1)
            cast.advance(1.8)  // simulated think-time

            oracle.decide(pair, cfg) match {
              case RowLabeller.Match =>
                cast.emit("y\r\n", 0.06)
                session.submitLabel(pair, 1.0)
                labelCount += 1
              case RowLabeller.NonMatch =>
                cast.emit("n\r\n", 0.06)
                session.submitLabel(pair, 0.0)
                labelCount += 1
              case _ =>
                cast.emit("s\r\n", 0.06)
                session.skip(pair)
            }
            cast.advance(0.4)
        }
      }

      cast.emit("\r\n" + fg("1;36", "═══ finalising ═══") + "\r\n", 0.6)
      val (model, tree) = session.finalModel()
      cast.emit(s"Trained final model on ${fg("1", labelCount.toString)} labels.\r\n", 0.8)

      val clusters = z.cluster(df, model, tree)
      val pred = clusters.select(cfg.idCol, cfg.clusterCol).collect()
        .map(r => r.getLong(0) -> r.getAs[Any](cfg.clusterCol)).toMap
      val (precision, recall, f1) = Evaluator.pairwiseF1(pred, plan.truth)
      cast.emit(
        f"Clustering on full dataset: " +
        f"precision=${fg("32", f"$precision%.3f")}  " +
        f"recall=${fg("32", f"$recall%.3f")}  " +
        f"f1=${fg("32", f"$f1%.3f")}\r\n",
        0.8
      )
      cast.advance(1.2)

      // Mill's test sandbox makes `os.pwd` a fresh dir under the gitignored
      // workspace `out/` (`testSandboxWorkingDir: true` in package.mill.yaml).
      val outPath = os.pwd / "interactive-session.cast"
      os.write.over(outPath, cast.toCast)
      System.out.println(s"[demo] asciicast written to $outPath")
      System.out.println(s"[demo] play with: asciinema play $outPath")

      assert(labelCount > 0, "expected at least one label from the oracle")
      assert(f1 >= 0.5,
        f"recorded session should still yield a usable model; got f1=$f1%.3f")
    } finally session.close()
  }

  private def renderPair(cast: AsciicastRecorder, r: Row, n: Int): Unit = {
    cast.emit(fg("1", s"━━━ Pair $n ━━━") + "\r\n", 0.25)
    cfg.fields.foreach { f =>
      val l  = Option(r.getAs[Any](s"${ZinggConf.LeftPrefix}${f.name}")).map(_.toString).getOrElse("")
      val rt = Option(r.getAs[Any](s"${ZinggConf.RightPrefix}${f.name}")).map(_.toString).getOrElse("")
      cast.emit(f"  ${fg("36", f"${f.name}%-20s")} │ $l\r\n", 0.05)
      cast.emit(f"  ${" " * 20} │ $rt\r\n",                    0.05)
    }
    cast.emit("Match? [y]es / [n]o / [s]kip / [q]uit: ", 0.3)
  }
}

/** Minimal asciicast v2 writer. Header is a single JSON object on line 1;
  * each subsequent line is a JSON triple `[t, "o", text]` where `t` is
  * seconds since the start of the recording. */
final class AsciicastRecorder(width: Int, height: Int, title: String) {
  private val mapper = new ObjectMapper()
  private val nf     = JsonNodeFactory.instance
  private val sb     = new StringBuilder
  private var t      = 0.0

  locally {
    val header = nf.objectNode()
    header.put("version",   2)
    header.put("width",     width)
    header.put("height",    height)
    header.put("timestamp", System.currentTimeMillis() / 1000L)
    header.put("title",     title)
    sb.append(mapper.writeValueAsString(header)).append('\n')
  }

  def advance(dt: Double): Unit = { t += dt }

  def emit(text: String, dt: Double = 0.0): Unit = {
    t += dt
    val arr = nf.arrayNode()
    arr.add(t)
    arr.add("o")
    arr.add(text)
    sb.append(mapper.writeValueAsString(arr)).append('\n')
  }

  def toCast: String = sb.toString
}
