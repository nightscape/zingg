package zingg.zs

import org.apache.spark.sql.Row

import scala.annotation.tailrec

/** Chains row-level labellers: for each pair, asks each labeller in order
  * and returns the first definite answer (`Match`, `NonMatch`, or `Skip`).
  * If every labeller answers `Unknown`, the composite also returns
  * `Unknown` — which in standalone use drops the pair, just like a single
  * labeller that couldn't decide.
  *
  * Typical use: cheap oracle first, then an LLM, optionally a human
  * fallback. The first one with a real answer wins.
  */
final class CompositeLabeller(labellers: Seq[RowLabeller]) extends RowLabeller {
  require(labellers.nonEmpty, "CompositeLabeller needs at least one labeller")

  def decide(r: Row, cfg: ZinggConf): RowLabeller.Decision = {
    @tailrec def go(remaining: List[RowLabeller]): RowLabeller.Decision =
      remaining match {
        case Nil => RowLabeller.Unknown
        case head :: tail => head.decide(r, cfg) match {
          case RowLabeller.Unknown => go(tail)
          case definite            => definite
        }
      }
    go(labellers.toList)
  }
}

object CompositeLabeller {
  def apply(labellers: RowLabeller*): CompositeLabeller =
    new CompositeLabeller(labellers)
}
