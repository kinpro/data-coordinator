import sbt._
import Keys._

object CoordinatorLib {
  lazy val settings: Seq[Setting[_]] = BuildSettings.projectSettings(protobuf=true) ++ Seq(
    libraryDependencies ++= Seq(
      "com.socrata" %% "soql-brita" % "[1.2.1,2.0.0)",
      "com.socrata" %% "soql-environment" % "0.0.16-SNAPSHOT",
      "com.rojoma" %% "rojoma-json" % "[2.4.0,3.0.0)",
      "com.rojoma" %% "simple-arm" % "[1.1.10,2.0.0)",
      "commons-codec" % "commons-codec" % "1.8",
      "joda-time" % "joda-time" % "2.1",
      "org.joda" % "joda-convert" % "1.2",
      "org.xerial.snappy" % "snappy-java" % "1.1.0-M3",
      "org.iq80.snappy" % "snappy" % "0.3",
      "postgresql" % "postgresql" % "9.1-901-1.jdbc4", // we do use postgres-specific features some places
      "com.mchange" % "c3p0" % "0.9.2.1" % "optional",
      "com.google.protobuf" % "protobuf-java" % "2.4.1",
      "com.h2database" % "h2" % "1.3.166" % "test,it",
      "org.scalacheck" %% "scalacheck" % "1.10.0" % "test,it",
      "org.slf4j" % "slf4j-simple" % BuildSettings.slf4jVersion % "test,it",
      "org.scalatest" %% "scalatest" % "1.9.1" % "it",
      "org.liquibase" % "liquibase-core" % "2.0.0",
      "org.liquibase" % "liquibase-plugin" % "1.9.5.0"
    )
  )


  lazy val configs: Seq[Configuration] = BuildSettings.projectConfigs
}
