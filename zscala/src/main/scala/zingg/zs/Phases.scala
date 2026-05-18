package zingg.zs

import org.apache.spark.ml.PipelineModel
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

import ConfigLoader.{IO, Loaded}

/** Dispatch table from a parsed [[Phase]] to the zscala pipeline. Phases the
  * pipeline doesn't yet implement throw with a clear message — they parse,
  * they just don't run.
  */
object Phases {

  private val ModelFile        = "model"
  private val TreeFile         = "blockingTree.json"
  private val MarkedTrainData  = "marked"
  private val UnmarkedTrainData = "unmarked"

  def run(
      phase: Phase,
      loaded: Loaded,
      modelDir: os.Path,
      exportLocation: Option[os.Path],
      spark: SparkSession
  ): Unit = phase match {
    case Phase.FindTrainingData => findTrainingData(loaded, modelDir, spark)
    case Phase.Label            => label(loaded, modelDir, spark)
    case Phase.FindAndLabel     => findAndLabel(loaded, modelDir, spark)
    case Phase.Train            => train(loaded, modelDir, spark)
    case Phase.Match            => matchPhase(loaded, modelDir, spark)
    case Phase.Link             => matchPhase(loaded, modelDir, spark)
    case Phase.TrainMatch       =>
      train(loaded, modelDir, spark)
      matchPhase(loaded, modelDir, spark)
    case other                  =>
      throw new NotImplementedError(
        s"phase '${other.name}' is not yet wired through to the zscala pipeline"
      )
  }

  private def findTrainingData(loaded: Loaded, modelDir: os.Path, spark: SparkSession): Unit = {
    val df         = read(loaded, spark)
    val z          = new Zingg(loaded.cfg, link = loaded.link)
    val candidates = z.findTrainingData(df)
    os.makeDir.all(modelDir)
    candidates.write.mode(SaveMode.Overwrite).parquet((modelDir / UnmarkedTrainData).toString)
  }

  private def label(loaded: Loaded, modelDir: os.Path, spark: SparkSession): Unit = {
    val unmarked = spark.read.parquet((modelDir / UnmarkedTrainData).toString)
    val labelled = new CliLabeller().label(unmarked, loaded.cfg)
    labelled.write.mode(SaveMode.Append).parquet((modelDir / MarkedTrainData).toString)
  }

  /** Adaptive, one-pair-at-a-time labelling.
    *
    * Unlike the split `findTrainingData` + `label` phases — which fix a whole
    * batch of candidates up front from a single, never-updated posterior — this
    * drives [[InteractiveSession]]: the Bayesian posterior is re-fit after every
    * answer and the next pair is re-selected against it (BALD). Once the model
    * has learned the obvious non-match region, information gain there drops to
    * zero and selection moves to genuinely ambiguous pairs, so the human stops
    * being shown long runs of obvious "no"s. */
  private def findAndLabel(loaded: Loaded, modelDir: os.Path, spark: SparkSession): Unit = {
    val df      = read(loaded, spark)
    val z       = new Zingg(loaded.cfg, link = loaded.link)
    val session = z.interactiveSession(df)
    val cli     = new CliLabeller()
    try {
      var labelled = 0
      var more     = true
      while (more) {
        session.nextPair() match {
          case None => more = false
          case Some(pair) =>
            cli.ask(pair, loaded.cfg, s"━━━ Pair ${labelled + 1} ━━━") match {
              case CliLabeller.Quit   => more = false
              case CliLabeller.Skip   => session.skip(pair)
              case CliLabeller.Lab(v) => session.submitLabel(pair, v); labelled += 1
            }
        }
      }
      os.makeDir.all(modelDir)
      session.labeled.write.mode(SaveMode.Append).parquet((modelDir / MarkedTrainData).toString)
    } finally session.close()
  }

  private def train(loaded: Loaded, modelDir: os.Path, spark: SparkSession): Unit = {
    val marked        = spark.read.parquet((modelDir / MarkedTrainData).toString)
    val z             = new Zingg(loaded.cfg, link = loaded.link)
    val (model, tree) = z.train(marked)
    os.makeDir.all(modelDir)
    model.write.overwrite().save((modelDir / ModelFile).toString)
    os.write.over(modelDir / TreeFile, BlockingTree.toJson(tree))
  }

  private def matchPhase(loaded: Loaded, modelDir: os.Path, spark: SparkSession): Unit = {
    val df       = read(loaded, spark)
    val model    = PipelineModel.load((modelDir / ModelFile).toString)
    val z        = new Zingg(loaded.cfg, link = loaded.link)
    val tree     = BlockingTree.fromJson(os.read(modelDir / TreeFile))
    val clusters = z.cluster(df, model, tree)
    writeOutputs(clusters, loaded.outputs)
  }

  /** Read all input sources into a single DataFrame. In link mode each source
    * is canonicalised onto the logical schema (and tagged with its origin)
    * before union; in dedup mode the raw rows are unioned directly. */
  private def read(loaded: Loaded, spark: SparkSession): DataFrame = {
    val inputs = loaded.inputs
    require(inputs.nonEmpty, "config has no `data` entries to read")
    val dfs =
      if (loaded.link) inputs.map(io => Canonicalizer.canonicalize(readOne(io, spark), io, loaded.cfg))
      else inputs.map(io => readOne(io, spark))
    dfs.tail.foldLeft(dfs.head)(_ unionByName _)
  }

  private def readOne(io: IO, spark: SparkSession): DataFrame =
    spark.read
      .format(io.format)
      .option("header", io.header.toString)
      .option("inferSchema", "true")
      .options(io.options)
      .load(io.path.toString)

  private def writeOutputs(df: DataFrame, outputs: Seq[IO]): Unit =
    outputs.foreach { io =>
      df.write
        .mode(SaveMode.Overwrite)
        .format(io.format)
        .option("header", io.header.toString)
        .options(io.options)
        .save(io.path.toString)
    }
}
