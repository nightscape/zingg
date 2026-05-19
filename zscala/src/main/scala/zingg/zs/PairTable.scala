package zingg.zs

import org.apache.spark.sql.Row

/** Renders a candidate pair as a side-by-side table: one row per logical field
  * with the two records in adjacent columns. Long values wrap within their
  * column so full values stay visible — nothing is truncated.
  *
  * {{{
  * field       │ left                      │ right
  * ────────────┼───────────────────────────┼───────────────────────────
  * name        │ Alice Smith               │ Alice Smith
  * description │ memory corruption in      │ Customer reported: memory
  *             │ grpc. Reference: CVE-...   │ in corruption grpc. ...
  * }}}
  */
object PairTable {

  private val Sep    = " │ "
  private val MinCol = 8

  /** Table lines (no trailing newline). `fieldStyle` decorates the field-name
    * cell — pass a colouring function for a TTY, identity for plain text. It is
    * applied after padding, so column alignment is preserved. */
  def render(
      cfg: ZinggConf,
      r: Row,
      width: Int = 100,
      fieldStyle: String => String = identity,
      leftLabel: String = "left",
      rightLabel: String = "right"
  ): Seq[String] = {
    val fieldWidth = ("field" +: cfg.fields.map(_.name)).map(_.length).max
    val avail      = width - fieldWidth - Sep.length * 2
    val colWidth   = math.max(MinCol, avail / 2)

    val header = fieldStyle(pad("field", fieldWidth)) + Sep +
                 pad(leftLabel, colWidth) + Sep + rightLabel
    val rule = ("─" * fieldWidth) + "─┼─" + ("─" * colWidth) + "─┼─" + ("─" * colWidth)

    val body = cfg.fields.flatMap { f =>
      val left  = wrap(value(r, ZinggConf.LeftPrefix, f.name), colWidth)
      val right = wrap(value(r, ZinggConf.RightPrefix, f.name), colWidth)
      (0 until math.max(left.length, right.length)).map { i =>
        val fieldCell =
          if (i == 0) fieldStyle(pad(f.name, fieldWidth)) else pad("", fieldWidth)
        fieldCell + Sep + pad(left.lift(i).getOrElse(""), colWidth) +
          Sep + right.lift(i).getOrElse("")
      }
    }
    header +: rule +: body
  }

  private def value(r: Row, prefix: String, name: String): String =
    Option(r.getAs[Any](s"$prefix$name")).map(_.toString).getOrElse("")

  private def pad(s: String, w: Int): String =
    if (s.length >= w) s else s + (" " * (w - s.length))

  /** Greedy word-wrap to `w` columns; tokens longer than `w` are hard-split.
    * Always returns at least one (possibly empty) line. */
  private def wrap(s: String, w: Int): Seq[String] = {
    if (s.isEmpty) return Seq("")
    val out  = scala.collection.mutable.ArrayBuffer.empty[String]
    val line = new StringBuilder
    def flush(): Unit = { out += line.toString; line.setLength(0) }
    s.split(" ").foreach { word0 =>
      var word = word0
      while (word.length > w) {
        if (line.nonEmpty) flush()
        out += word.substring(0, w)
        word = word.substring(w)
      }
      if (line.isEmpty) line.append(word)
      else if (line.length + 1 + word.length <= w) line.append(' ').append(word)
      else { flush(); line.append(word) }
    }
    flush()
    out.toSeq
  }
}
