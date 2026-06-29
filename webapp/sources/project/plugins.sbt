// Fat-jar for rudder-templates-cli (replaces maven-assembly-plugin jar-with-dependencies).
// 2.3.1 is cross-published for sbt 2.x (sbt-assembly_sbt2_3).
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")

// Scalafix (replaces scalafix-maven-plugin); scalafmt is built into sbt.
// 0.14.7 is published for sbt 2.x (sbt-scalafix_sbt2_3).
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")

// War packaging for rudder-web (replaces maven-war-plugin). sbt-war (formerly xsbt-web-plugin)
// 5.x is cross-published for sbt 2.x (sbt-war_sbt2_3).
addSbtPlugin("com.earldouglas" % "sbt-war" % "5.2.1")
