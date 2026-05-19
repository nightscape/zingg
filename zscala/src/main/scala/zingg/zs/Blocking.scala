package zingg.zs

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.{col, struct, udf}

import scala.jdk.CollectionConverters._

sealed trait BlockingTree extends Serializable {

  /** Block key for a row, or `null` when the row carries no blocking signal —
    * i.e. it hashed to null on every field along its path. Such rows must not
    * be paired: a null block key never equi-joins (not even with another null),
    * so they drop out of [[PairBuilder.selfPairs]] instead of all collapsing
    * into one giant "null" block and being compared against everything. */
  def keyFor(row: Row): String = this match {
    case BlockingTree.Leaf(id) => id
    case n: BlockingTree.Node =>
      val segs = n.segments(row)
      if (segs.isEmpty) null else segs.mkString("|")
  }

  /** Non-null `field=hash` contributions along this row's path. A bare [[Leaf]]
    * adds nothing; a [[Node]] adds its segment only when the value hashes to a
    * non-null key. */
  private[zs] def segments(row: Row): List[String]
}

object BlockingTree {

  final case class Leaf(id: String) extends BlockingTree {
    private[zs] def segments(row: Row): List[String] = Nil
  }

  final case class Node(
      field: String,
      hash: Hash,
      children: Map[String, BlockingTree],
      fallback: BlockingTree
  ) extends BlockingTree {
    private[zs] def segments(row: Row): List[String] = {
      val v: Any =
        try row.getAs[Any](field)
        catch { case _: IllegalArgumentException => null }
      val k = hash(v)
      val sub = if (k == null) fallback else children.getOrElse(k, fallback)
      if (k == null) sub.segments(row)
      else s"${hash.name}:$field=$k" :: sub.segments(row)
    }
  }

  /** Serialize a tree to JSON. Hashes are stored by [[Hash.name]] and resolved
    * via [[Hash.byName]] on read, so the artifact stays inspectable and does not
    * depend on Java serialization. */
  def toJson(tree: BlockingTree): String = {
    val mapper = new ObjectMapper()
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(toNode(mapper, tree))
  }

  def fromJson(s: String): BlockingTree =
    fromNode(new ObjectMapper().readTree(s))

  private def toNode(m: ObjectMapper, t: BlockingTree): JsonNode = t match {
    case Leaf(id) =>
      m.createObjectNode().put("type", "leaf").put("id", id)
    case Node(field, hash, children, fallback) =>
      val n = m.createObjectNode()
      n.put("type", "node").put("field", field).put("hash", hash.name)
      val ch = n.putObject("children")
      children.foreach { case (k, v) => ch.set[JsonNode](k, toNode(m, v)) }
      n.set[JsonNode]("fallback", toNode(m, fallback))
      n
  }

  private def fromNode(n: JsonNode): BlockingTree = n.path("type").asText match {
    case "leaf" => Leaf(n.path("id").asText)
    case "node" =>
      val children = n.path("children")
      Node(
        field    = n.path("field").asText,
        hash     = Hash.byName(n.path("hash").asText),
        children = children.fieldNames().asScala.map(k => k -> fromNode(children.path(k))).toMap,
        fallback = fromNode(n.path("fallback"))
      )
    case other =>
      throw new IllegalArgumentException(s"unknown blocking tree node type '$other'")
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
