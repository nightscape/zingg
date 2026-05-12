package zingg.zs

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.{col, struct, udf}

sealed trait BlockingTree extends Serializable {
  def keyFor(row: Row): String
}

object BlockingTree {

  final case class Leaf(id: String) extends BlockingTree {
    def keyFor(row: Row): String = id
  }

  final case class Node(
      field: String,
      hash: Hash,
      children: Map[String, BlockingTree],
      fallback: BlockingTree
  ) extends BlockingTree {
    def keyFor(row: Row): String = {
      val v: Any =
        try row.getAs[Any](field)
        catch { case _: IllegalArgumentException => null }
      val k = hash(v)
      val sub = if (k == null) fallback else children.getOrElse(k, fallback)
      s"${hash.name}:$field=$k|${sub.keyFor(row)}"
    }
  }

  def assignBlocks(df: DataFrame, tree: BlockingTree,
                   blockCol: String = "z_block"): DataFrame = {
    val bTree = df.sparkSession.sparkContext.broadcast(tree)
    val keyUdf = udf((r: Row) => bTree.value.keyFor(r))
    val structCols = df.columns.map(col)
    df.withColumn(blockCol, keyUdf(struct(structCols: _*)))
  }
}

object BlockingTreeLearner {

  def learn(
      posPairs: Seq[Map[String, (Any, Any)]],
      cfg: ZinggConf,
      maxDepth: Int = 4
  ): BlockingTree = {

    val candidates = Hash.candidatesFor(cfg.fields)

    def build(pairs: Seq[Map[String, (Any, Any)]], depth: Int, used: Set[String]): BlockingTree = {
      if (pairs.size <= cfg.blockSize || depth >= maxDepth || candidates.isEmpty)
        BlockingTree.Leaf(s"leaf_${depth}_${pairs.size}")
      else {
        val scored = candidates.filterNot(c => used(s"${c._1.name}|${c._2.name}")).map { case (f, h) =>
          val agree = pairs.count { row =>
            row.get(f.name) match {
              case Some((a, b)) =>
                val ha = h(a); val hb = h(b)
                ha != null && ha == hb
              case None => false
            }
          }
          val groups = pairs.groupBy { row =>
            row.get(f.name).map { case (a, _) => h(a) }.orNull
          }
          val score = agree.toDouble - 0.1 * groups.size
          ((f, h), score, agree)
        }.sortBy(-_._2)

        scored.headOption match {
          case None => BlockingTree.Leaf(s"leaf_${depth}_${pairs.size}")
          case Some((_, _, agree)) if agree == 0 =>
            BlockingTree.Leaf(s"leaf_${depth}_${pairs.size}")
          case Some(((f, h), _, _)) =>
            val grouped: Map[String, Seq[Map[String, (Any, Any)]]] = pairs.groupBy { row =>
              row.get(f.name).flatMap { case (a, b) =>
                val ha = h(a); val hb = h(b)
                if (ha != null && ha == hb) Some(ha) else None
              }.orNull
            }
            val newUsed = used + s"${f.name}|${h.name}"
            val children: Map[String, BlockingTree] = grouped.collect {
              case (k, ps) if k != null && ps.nonEmpty =>
                k -> build(ps, depth + 1, newUsed)
            }
            val fallbackPairs = grouped.getOrElse(null, Seq.empty)
            val fb =
              if (fallbackPairs.size == pairs.size) BlockingTree.Leaf(s"leaf_${depth}_fallback")
              else build(fallbackPairs, depth + 1, newUsed)
            BlockingTree.Node(f.name, h, children, fb)
        }
      }
    }

    build(posPairs, 0, Set.empty)
  }
}
