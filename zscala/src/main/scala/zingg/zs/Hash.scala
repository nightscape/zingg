package zingg.zs

trait Hash extends Serializable {
  def name: String
  def apply(v: Any): String
}

object Hash {

  /** Stringify a value, treating null *and* blank/whitespace-only as "no
    * value" (null). A blank field carries no blocking signal, so it must not
    * produce a hash key — otherwise every blank-on-this-field record collapses
    * into one shared `""` block and gets paired with everything in it. */
  private def s(v: Any): String =
    if (v == null) null
    else { val t = v.toString; if (t.trim.isEmpty) null else t }

  case object IdentityString extends Hash {
    val name = "identityString"
    def apply(v: Any): String = s(v)
  }

  case class FirstChars(n: Int) extends Hash {
    val name = s"firstChars($n)"
    def apply(v: Any): String = {
      val t = s(v); if (t == null) null else t.take(n)
    }
  }

  case class LastChars(n: Int) extends Hash {
    val name = s"lastChars($n)"
    def apply(v: Any): String = {
      val t = s(v); if (t == null) null else t.takeRight(n)
    }
  }

  case object LastWord extends Hash {
    val name = "lastWord"
    def apply(v: Any): String = {
      val t = s(v)
      if (t == null) null
      else {
        val parts = t.split("\\s+").filter(_.nonEmpty)
        if (parts.isEmpty) null else parts.last
      }
    }
  }

  case class RangeLong(bucket: Long) extends Hash {
    val name = s"rangeLong($bucket)"
    def apply(v: Any): String = v match {
      case null => null
      case n: Number => (n.longValue / bucket).toString
      case _ => null
    }
  }

  case class RegexExtract(pattern: String) extends Hash {
    val name = s"regex($pattern)"
    private val re = pattern.r
    def apply(v: Any): String = {
      val t = s(v); if (t == null) null
      else re.findFirstIn(t).map(_.toUpperCase).orNull
    }
  }

  /** Candidate hashes the blocking-tree *learner* may try for a match type. A
    * superset of [[coldStartHashes]]; a configurable [[MatchType.Regex]] simply
    * contributes its own extractor. */
  def hashesFor(mt: MatchType): Seq[Hash] = mt match {
    case MatchType.Exact     => Seq(IdentityString)
    case MatchType.Email     => Seq(IdentityString, FirstChars(3), LastChars(3))
    case MatchType.Numeric   => Seq(RangeLong(10L), RangeLong(100L), RangeLong(1000L))
    case MatchType.Fuzzy     => Seq(FirstChars(2), FirstChars(3), FirstChars(5),
                                    LastChars(2), LastChars(3), LastWord)
    case MatchType.Text      => Seq(LastWord, RegexExtract(MatchType.CvePattern))
    case MatchType.Regex(p)  => Seq(RegexExtract(p))
    case MatchType.Custom    => Seq(IdentityString)
  }

  /** High-recall hashes for cold-start (pre-labels) canopy blocking — the subset
    * of [[hashesFor]] worth fanning out over before a tree has been learned. The
    * regex extractor is preferred for id-bearing text because full-string
    * identity has near-zero recall on long descriptions. */
  def coldStartHashes(mt: MatchType): Seq[Hash] = mt match {
    case MatchType.Exact     => Seq(IdentityString)
    case MatchType.Email     => Seq(IdentityString)
    case MatchType.Numeric   => Seq(RangeLong(100L))
    case MatchType.Fuzzy     => Seq(FirstChars(3))
    case MatchType.Text      => Seq(RegexExtract(MatchType.CvePattern), LastWord)
    case MatchType.Regex(p)  => Seq(RegexExtract(p))
    case MatchType.Custom    => Seq(IdentityString)
  }

  /** Every (blockable field, candidate hash) the learner may pick from. */
  def candidatesFor(fields: Seq[FieldDef]): Seq[(FieldDef, Hash)] =
    (for {
      f  <- fields if f.blockable
      mt <- f.matchTypes
      h  <- hashesFor(mt)
    } yield (f, h)).distinct

  /** Fixed hashes that can appear in a serialized tree by a stable name.
    * Parametric regex hashes round-trip through [[byName]]'s parse fallback
    * instead, since their pattern is open-ended. */
  private def registry: Seq[Hash] = Seq(
    IdentityString,
    FirstChars(2), FirstChars(3), FirstChars(5),
    LastChars(2), LastChars(3),
    LastWord,
    RangeLong(10L), RangeLong(100L), RangeLong(1000L),
    RegexExtract(MatchType.CvePattern)
  )

  private lazy val byNameMap: Map[String, Hash] = registry.map(h => h.name -> h).toMap

  /** Resolve a hash from its [[Hash.name]]. Fixed hashes come from [[registry]];
    * any `regex(<pattern>)` name is reconstructed directly, so a learned tree
    * that keyed on a configured regex round-trips through JSON. */
  def byName(name: String): Hash =
    byNameMap.getOrElse(name,
      parseRegex(name).getOrElse(
        throw new IllegalArgumentException(s"unknown hash '$name'")))

  private def parseRegex(name: String): Option[Hash] =
    if (name.startsWith("regex(") && name.endsWith(")"))
      Some(RegexExtract(name.substring("regex(".length, name.length - 1)))
    else None
}
