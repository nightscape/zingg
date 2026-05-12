package zingg.zs

import org.apache.commons.text.similarity.{JaroWinklerSimilarity, LevenshteinDistance}

object Similarity {

  private val jw = new JaroWinklerSimilarity()
  private val lev = LevenshteinDistance.getDefaultInstance

  def jaroWinkler(a: String, b: String): Double =
    if (a == null || b == null) 0.0 else jw.apply(a, b)

  def normalizedLevenshtein(a: String, b: String): Double =
    if (a == null || b == null) 0.0
    else {
      val maxLen = math.max(a.length, b.length)
      if (maxLen == 0) 1.0
      else 1.0 - (lev.apply(a, b).toDouble / maxLen)
    }

  def jaccard(a: String, b: String): Double =
    if (a == null || b == null) 0.0
    else {
      val sa = a.toLowerCase.split("\\W+").filter(_.nonEmpty).toSet
      val sb = b.toLowerCase.split("\\W+").filter(_.nonEmpty).toSet
      if (sa.isEmpty && sb.isEmpty) 1.0
      else sa.intersect(sb).size.toDouble / sa.union(sb).size
    }

  def bigramJaccard(a: String, b: String): Double =
    if (a == null || b == null) 0.0
    else {
      val ga = bigrams(a)
      val gb = bigrams(b)
      if (ga.isEmpty && gb.isEmpty) 1.0
      else if (ga.isEmpty || gb.isEmpty) 0.0
      else ga.intersect(gb).size.toDouble / ga.union(gb).size
    }

  def exact(a: String, b: String): Double =
    if (a == null || b == null) 0.0 else if (a == b) 1.0 else 0.0

  def numericClose(a: Any, b: Any): Double = (a, b) match {
    case (null, _) | (_, null) => 0.0
    case (x: Number, y: Number) =>
      val d = math.abs(x.doubleValue - y.doubleValue)
      val m = math.max(math.abs(x.doubleValue), math.abs(y.doubleValue))
      if (m == 0.0) { if (d == 0.0) 1.0 else 0.0 }
      else math.max(0.0, 1.0 - d / m)
    case _ => 0.0
  }

  private def bigrams(s: String): Set[String] = {
    val t = s.toLowerCase
    if (t.length < 2) Set.empty
    else (0 until t.length - 1).map(i => t.substring(i, i + 2)).toSet
  }

  def features(mt: MatchType, a: Any, b: Any): Array[Double] = mt match {
    case MatchType.Exact   => Array(exact(s(a), s(b)))
    case MatchType.Fuzzy   => Array(jaroWinkler(s(a), s(b)),
                                    normalizedLevenshtein(s(a), s(b)),
                                    jaccard(s(a), s(b)))
    case MatchType.Numeric => Array(numericClose(a, b))
    case MatchType.Email   => Array(exact(s(a), s(b)), jaroWinkler(s(a), s(b)))
    case MatchType.Text    => Array(bigramJaccard(s(a), s(b)), jaccard(s(a), s(b)))
    case MatchType.CveId   => Array(exact(extractCve(s(a)), extractCve(s(b))))
    case MatchType.Custom  => Array(exact(s(a), s(b)))
  }

  def featureWidth(mt: MatchType): Int = mt match {
    case MatchType.Exact | MatchType.Numeric |
         MatchType.CveId | MatchType.Custom => 1
    case MatchType.Email | MatchType.Text   => 2
    case MatchType.Fuzzy                    => 3
  }

  private def s(a: Any): String = if (a == null) null else a.toString

  private val cveRe = "(?i)CVE-\\d{4}-\\d{4,7}".r

  def extractCve(s: String): String =
    if (s == null) null else cveRe.findFirstIn(s).map(_.toUpperCase).orNull
}
