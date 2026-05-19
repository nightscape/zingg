package zingg.zs

trait Hash extends Serializable {
  def name: String
  def applicableTo: Set[MatchType]
  def apply(v: Any): String
}

object Hash {

  private def s(v: Any): String = if (v == null) null else v.toString

  case object IdentityString extends Hash {
    val name = "identityString"
    val applicableTo = Set[MatchType](MatchType.Exact, MatchType.Email,
                                       MatchType.CveId, MatchType.Custom)
    def apply(v: Any): String = s(v)
  }

  case class FirstChars(n: Int) extends Hash {
    val name = s"firstChars($n)"
    val applicableTo = Set[MatchType](MatchType.Fuzzy, MatchType.Email)
    def apply(v: Any): String = {
      val t = s(v); if (t == null) null else t.take(n)
    }
  }

  case class LastChars(n: Int) extends Hash {
    val name = s"lastChars($n)"
    val applicableTo = Set[MatchType](MatchType.Fuzzy, MatchType.Email)
    def apply(v: Any): String = {
      val t = s(v); if (t == null) null else t.takeRight(n)
    }
  }

  case object LastWord extends Hash {
    val name = "lastWord"
    val applicableTo = Set[MatchType](MatchType.Fuzzy, MatchType.Text)
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
    val applicableTo = Set[MatchType](MatchType.Numeric)
    def apply(v: Any): String = v match {
      case null => null
      case n: Number => (n.longValue / bucket).toString
      case _ => null
    }
  }

  case class RegexExtract(pattern: String) extends Hash {
    val name = s"regex($pattern)"
    val applicableTo = Set[MatchType](MatchType.CveId, MatchType.Text, MatchType.Fuzzy)
    private val re = pattern.r
    def apply(v: Any): String = {
      val t = s(v); if (t == null) null
      else re.findFirstIn(t).map(_.toUpperCase).orNull
    }
  }

  def registry: Seq[Hash] = Seq(
    IdentityString,
    FirstChars(2), FirstChars(3), FirstChars(5),
    LastChars(2), LastChars(3),
    LastWord,
    RangeLong(10L), RangeLong(100L), RangeLong(1000L),
    RegexExtract("(?i)CVE-\\d{4}-\\d{4,7}")
  )

  def candidatesFor(fields: Seq[FieldDef]): Seq[(FieldDef, Hash)] =
    for {
      f <- fields if f.blockable
      h <- registry if h.applicableTo.contains(f.matchType)
    } yield (f, h)

  private lazy val byNameMap: Map[String, Hash] = registry.map(h => h.name -> h).toMap

  /** Resolve a hash from its [[Hash.name]]. Every hash that can appear in a
    * learned or seeded blocking tree is drawn from [[registry]], so persisted
    * trees round-trip through this lookup. */
  def byName(name: String): Hash =
    byNameMap.getOrElse(name, throw new IllegalArgumentException(s"unknown hash '$name'"))
}
