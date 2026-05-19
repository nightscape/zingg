package millbuild

import mill.javalib.{Assembly, AssemblyModule}

// Concatenate META-INF/services files so Spark's DataSourceRegister entries from
// every jar (csv, json, parquet, ... plus mllib) survive the uber-jar merge.
trait SparkAssemblyModule extends AssemblyModule {
  override def assemblyRules: Seq[Assembly.Rule] =
    super.assemblyRules ++ Seq(
      Assembly.Rule.AppendPattern("META-INF/services/.*")
    )
}
