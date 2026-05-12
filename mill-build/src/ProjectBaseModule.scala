package millbuild

import mill.*
import mill.javalib.*
import mill.javalib.publish.*
import mill.scalalib.*

trait ProjectBaseModule extends SbtModule, PublishModule {

  def scalaVersion = "2.13.17"
  def jvmVersion = "17"

  def repositories =
    Seq("https://repo1.maven.org/maven2/", "https://repos.spark-packages.org/")
  def mvnDeps = Seq(
    mvn"commons-logging:commons-logging:1.1.1",
    mvn"com.fasterxml.jackson.core:jackson-annotations:2.15.2",
    mvn"com.fasterxml.jackson.module:jackson-module-scala_2.13:2.15.2"
  )
  def javacOptions = Seq("-source", "17", "-target", "17")
  def scalacOptions = Seq(
    "-J--add-opens", "-Jjava.base/java.lang=ALL-UNNAMED",
    "-J--add-opens", "-Jjava.base/java.lang.reflect=ALL-UNNAMED",
    "-J--add-opens", "-Jjava.base/java.io=ALL-UNNAMED",
    "-J--add-opens", "-Jjava.base/java.util=ALL-UNNAMED"
  )
  def pomSettings = Task {
    PomSettings(
      "",
      "",
      "",
      Seq(),
      VersionControl(None, None, None, None),
      Seq()
    )
  }

  trait ProjectBaseTests extends SbtTests, TestModule.Junit5 {

    def forkWorkingDir = moduleDir
    def mvnDeps = Seq(
      mvn"org.mockito:mockito-inline:5.2.0",
      mvn"org.mockito:mockito-core:5.2.0",
      mvn"com.opencsv:opencsv:5.12.0;exclude=commons-beanutils:commons-beanutils",
      mvn"org.junit.jupiter:junit-jupiter-engine:5.8.1",
      mvn"org.junit.jupiter:junit-jupiter-api:5.8.1",
      mvn"org.junit.jupiter:junit-jupiter-params:5.8.1",
      mvn"org.hamcrest:hamcrest-all:1.3"
    )
    def runMvnDeps = Seq(mvn"org.junit.platform:junit-platform-launcher")
    def bomMvnDeps = super.bomMvnDeps() ++ Seq(mvn"org.junit:junit-bom:5.8.1")
    def testParallelism = true
    def testSandboxWorkingDir = false
    def forkArgs = Seq(
      "--add-opens", "java.base/java.lang=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens", "java.base/java.io=ALL-UNNAMED",
      "--add-opens", "java.base/java.net=ALL-UNNAMED",
      "--add-opens", "java.base/java.nio=ALL-UNNAMED",
      "--add-opens", "java.base/java.util=ALL-UNNAMED",
      "--add-opens", "java.base/java.util.concurrent=ALL-UNNAMED",
      "--add-opens", "java.base/java.util.concurrent.atomic=ALL-UNNAMED",
      "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens", "java.base/sun.nio.cs=ALL-UNNAMED",
      "--add-opens", "java.base/sun.security.action=ALL-UNNAMED",
      "--add-opens", "java.base/sun.util.calendar=ALL-UNNAMED"
    )

  }

}
