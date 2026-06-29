import Dependencies._
import java.time.{ZonedDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter

// ============================================================================
// Rudder webapp build — sbt port of the Maven reactor (rudder/webapp/sources).
// See the Maven POMs (now removed) and project/Dependencies.scala for context.
// ============================================================================

// ---- versions surfaced into resources / artifacts ----
val rudderVersion      = "9.2.0~alpha1-SNAPSHOT"
val rudderMajorVersion = "9.2"
val currentYear        = "2026"

// ---- shared settings for every module ----------------------------------------------------------
ThisBuild / scalaVersion       := V.scala
ThisBuild / version            := rudderVersion
// keep published artifactIds unsuffixed (rudder-core, not rudder-core_3) so that
// rudder-plugins / rudder-plugins-private keep resolving the exact Maven coordinates.
ThisBuild / crossPaths         := false
ThisBuild / autoScalaLibrary   := true
ThisBuild / publishMavenStyle  := true

ThisBuild / resolvers ++= Seq(
  // local repo is the Maven local repo (~/.m2), not the Ivy local repo, so the build
  // and the plugins ecosystem share a single local repository.
  Resolver.mavenLocal,
  "rudder-release"   at "https://repository.rudder.io/maven/releases/",
  "rudder-snapshot"  at "https://repository.rudder.io/maven/snapshots/",
  "sonatype-snapshot" at "https://oss.sonatype.org/content/groups/public"
)

ThisBuild / publishTo := {
  val nexus = "https://nexus.normation.com/nexus/content/repositories/"
  if (isSnapshot.value) Some("snapshots" at nexus + "snapshots")
  else                  Some("releases"  at nexus + "releases")
}

// scalac options — copied verbatim from the Maven scala-maven-plugin <args>.
ThisBuild / scalacOptions ++= Seq(
  "-release:17",
  "-deprecation",
  "-explain-types",
  "-feature",
  "-unchecked",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-Xmax-inlines", "100",
  "-Wconf:msg=An existential type that came from a Scala-2 classfile for trait BoxTrait:s",
  "-Werror",
  "-Wunused:imports",
  "-Wunused:locals",
  "-Wunused:implicits",
  "-Wunused:privates",
  "-Ycheck-all-patmat",
  "-Ysemanticdb"
)

// javac options — Maven's maven-compiler-plugin used <release>17</release>. Without this,
// javac compiles against the host JDK API (21+), where java.lang.StringTemplate exists and
// collides with org.antlr.stringtemplate.StringTemplate in the generated ANTLR parser.
ThisBuild / javacOptions ++= Seq("--release", "17", "-encoding", "UTF-8")

// pin transitive versions that Maven enforced via <dependencyManagement>
ThisBuild / dependencyOverrides ++= Dependencies.overrides

// ---- settings reused by all code modules -------------------------------------------------------
lazy val commonSettings = Seq(
  libraryDependencies ++= Dependencies.common,
  // specs2 console reporter (was surefire argLine -Dspecs2.commandline=console)
  Test / javaOptions += "-Dspecs2.commandline=console",
  Test / fork := true,
  // Forked test JVMs don't inherit the sbt launcher's -D properties (Maven's non-forked
  // surefire did). Forward any -Dtest.* / -Dtests.* so e.g. `sbt -Dtest.postgres=true test`
  // enables the Postgres tests (DBCommon: test.postgres in {true,1}), like Maven did.
  Test / javaOptions ++= sys.props.collect {
    case (k, v) if k.startsWith("test.") || k.startsWith("tests.") => s"-D$k=$v"
  }.toSeq,
  // Run test classes sequentially, like maven-surefire (forkCount=1, parallel=none). sbt
  // parallelizes by default, which races SLF4J's init window: a concurrent getLogger returns
  // an org.slf4j.helpers.SubstituteLogger, breaking NodeConfigData's cast to logback's Logger
  // (-> NoClassDefFoundError: Could not initialize class NodeConfigData$).
  Test / parallelExecution := false,
  // Tests run against class DIRECTORIES, like Maven & sbt 1.x (sbt 2.0 defaults exportJars := true).
  // Needed because some tests read resources as files (getResource(...).toURI.getPath, e.g.
  // ldap/rudder.schema in CheckNormationOidTest and the LDIF/schema in the LDAP helpers) and the
  // logback-test.xml we strip from the test-jar must still be found on disk. (Making that test code
  // jar-safe is a separate, later cleanup.) The war's WEB-INF/lib jar layout is handled explicitly
  // in the rudderWeb project instead of via Runtime/exportJars (which sbt-war resolves inconsistently).
  exportJars := false,
  // exclude logback-test.xml from packaged (test-)jars, like the maven-jar-plugin config
  Compile / packageBin / mappings ~= { _.filterNot { case (_, n) => n == "logback-test.xml" } },
  // Maven published a sources jar but no scaladoc; match that (and avoid DottyDoc choking
  // on cross-compiled dependency sources like cron4s' JSExport annotations).
  Compile / packageDoc / publishArtifact := false,
  // redirect `publishLocal` to the Maven local repo (~/.m2) instead of Ivy local (~/.ivy2),
  // so `sbt publishLocal` and `sbt publishM2` are equivalent and only ~/.m2 is used locally.
  publishLocal := publishM2.value
)

// modules whose test classes are published as a `-tests.jar` (Maven test-jar) and consumed
// either internally (utils -> inventory-api) or by external plugins (rudder-*).
lazy val publishTestJar = Seq(
  Test / packageBin / publishArtifact := true,
  Test / packageBin / mappings ~= { _.filterNot { case (_, n) => n == "logback-test.xml" } }
)

// ---- version.properties generator (replaces maven resource filtering) --------------------------
def versionPropertiesGen = Def.task {
  val src = (Compile / sourceDirectory).value / "resources" / "version.properties"
  val out = (Compile / resourceManaged).value / "version.properties"
  if (src.exists) {
    val ts = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"))
    val filtered = IO.read(src)
      .replace("${rudder-major-version}", rudderMajorVersion)
      .replace("${version}", rudderVersion)
      .replace("${current-year}", currentYear)
      .replace("${build-timestamp}", ts)
    IO.write(out, filtered)
    Seq(out)
  } else Seq.empty
}

// don't also copy the unfiltered template / sample
lazy val versionPropertiesSettings = Seq(
  Compile / resourceGenerators += versionPropertiesGen.taskValue,
  Compile / unmanagedResources / excludeFilter :=
    (Compile / unmanagedResources / excludeFilter).value ||
      new SimpleFileFilter(f => f.getName == "configuration.properties.sample"),
  // both the raw src template and the generated (filtered) copy map to "version.properties";
  // keep only the generated one (under resourceManaged) so the jar/sources-jar has no duplicate entry.
  Compile / packageBin / mappings := dedupVersionProperties(
    fileConverter.value, (Compile / resourceManaged).value, (Compile / packageBin / mappings).value),
  Compile / packageSrc / mappings := dedupVersionProperties(
    fileConverter.value, (Compile / resourceManaged).value, (Compile / packageSrc / mappings).value)
)

def dedupVersionProperties(
    conv:     xsbti.FileConverter,
    managed:  File,
    mappings: Seq[(xsbti.HashedVirtualFileRef, String)]
): Seq[(xsbti.HashedVirtualFileRef, String)] = {
  val managedPath = managed.toPath
  val (vps, rest) = mappings.partition(_._2 == "version.properties")
  rest ++ vps.find { case (ref, _) => conv.toPath(ref).startsWith(managedPath) }.orElse(vps.headOption).toList
}

// ================================================================================================
// Modules
// ================================================================================================

lazy val utils = (project in file("utils"))
  .settings(commonSettings, publishTestJar)
  .settings(
    organization := "com.normation",
    name         := "utils",
    libraryDependencies ++= Seq(
      "commons-io"        % "commons-io"   % V.commonsIo,
      "org.apache.commons" % "commons-csv" % V.commonsCsv,
      "org.apache.commons" % "commons-lang3" % V.commonsLang,
      liftCommon,
      "com.lihaoyi" %% "fastparse" % V.fastparse,
      "com.github.alonsodomin.cron4s" %% "cron4s-core" % V.cron4s
    )
  )

lazy val scalaLdap = (project in file("scala-ldap"))
  .dependsOn(utils)
  .settings(commonSettings)
  .settings(
    organization := "com.normation",
    name         := "scala-ldap",
    libraryDependencies += "com.unboundid" % "unboundid-ldapsdk" % V.unboundid
  )

lazy val inventoryApi = (project in file("ldap-inventory/inventory-api"))
  // utils test-jar is a *compile*-scope dep here (Maven <type>test-jar</type> at compile)
  .dependsOn(utils, utils % "compile->test")
  .settings(commonSettings)
  .settings(
    organization := "com.normation.inventory",
    name         := "inventory-api",
    libraryDependencies ++= Seq(
      bcpkix,
      "com.comcast" %% "ip4s-core" % V.ip4s
    )
  )

lazy val inventoryFusion = (project in file("ldap-inventory/inventory-fusion"))
  .dependsOn(inventoryApi)
  .settings(commonSettings)
  .settings(
    organization := "com.normation.inventory",
    name         := "inventory-fusion"
  )

lazy val inventoryRepository = (project in file("ldap-inventory/inventory-repository"))
  .dependsOn(inventoryApi, utils, scalaLdap)
  .settings(commonSettings)
  .settings(
    organization := "com.normation.inventory",
    name         := "inventory-repository",
    libraryDependencies += liftUtil
  )

lazy val inventoryProvisioningCore = (project in file("ldap-inventory/inventory-provisioning-core"))
  .dependsOn(inventoryRepository)
  .settings(commonSettings)
  .settings(
    organization := "com.normation.inventory",
    name         := "inventory-provisioning-core"
  )

lazy val rudderTemplates = (project in file("rudder/rudder-templates"))
  .dependsOn(utils)
  .settings(commonSettings)
  .settings(
    organization := "com.normation.rudder",
    name         := "rudder-templates",
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-lang3"  % V.commonsLang,
      "org.antlr"          % "stringtemplate" % V.stringtemplate
    )
  )

lazy val rudderTemplatesCli = (project in file("rudder/rudder-templates-cli"))
  .enablePlugins(sbtassembly.AssemblyPlugin)
  .dependsOn(rudderTemplates)
  .settings(commonSettings)
  .settings(
    organization := "com.normation.rudder",
    name         := "rudder-templates-cli",
    libraryDependencies ++= Seq(
      liftCommon,
      "com.github.scopt" %% "scopt" % V.scopt
    ),
    assembly / mainClass     := Some("com.normation.templates.cli.TemplateCli"),
    assembly / assemblyJarName := s"rudder-templates-cli-${version.value}-jar-with-dependencies.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "module-info.class"           => MergeStrategy.discard
      case x                             => (assembly / assemblyMergeStrategy).value(x)
    }
  )

lazy val rudderCore = (project in file("rudder/rudder-core"))
  .dependsOn(
    inventoryApi, inventoryRepository, inventoryProvisioningCore, utils, rudderTemplates,
    inventoryFusion % Test
  )
  .settings(commonSettings, publishTestJar)
  .settings(
    organization := "com.normation.rudder",
    name         := "rudder-core",
    // exclude hooks.d/** from main resources (maven <resource> exclude)
    Compile / unmanagedResources / excludeFilter :=
      (Compile / unmanagedResources / excludeFilter).value ||
        new SimpleFileFilter(f => f.getAbsolutePath.contains("/resources/hooks.d/")),
    libraryDependencies ++= Seq(
      "org.openjdk.jol"      % "jol-core"   % V.jolCore,
      "com.typesafe"         % "config"     % V.config,
      "com.lihaoyi"         %% "fastparse"  % V.fastparse,
      "commons-io"           % "commons-io" % V.commonsIo,
      "org.apache.commons"   % "commons-text"  % V.commonsText,
      "commons-codec"        % "commons-codec" % V.commonsCodec,
      "org.apache.commons"   % "commons-csv"   % V.commonsCsv,
      bcpkix,
      jgit,
      "com.zaxxer"           % "HikariCP"   % V.hikaricp,
      "org.postgresql"       % "postgresql" % V.postgresql,
      "org.tpolecat"        %% "doobie-core"     % V.doobie,
      "org.tpolecat"        %% "doobie-postgres" % V.doobie,
      "org.typelevel"       %% "cats-effect-std" % V.catsEffect,
      "com.github.ben-manes.caffeine" % "caffeine" % V.caffeine,
      "com.zaxxer"           % "nuprocess"  % V.nuprocess,
      "com.jayway.jsonpath"  % "json-path"  % V.jsonPath,
      "net.minidev"          % "json-smart" % V.jsonSmart,
      "com.google.code.findbugs" % "jsr305" % V.jsr305,
      liftWebkit,
      spring("spring-context"),
      spring("spring-test") % Test,
      "org.graalvm.js"       % "js-scriptengine" % V.graalvm,
      "org.graalvm.js"       % "js-language"     % V.graalvm,
      "org.graalvm.truffle"  % "truffle-api"     % V.graalvm,
      "com.github.seancfoley" % "ipaddress"      % V.ipaddress
    )
  )

lazy val rudderRest = (project in file("rudder/rudder-rest"))
  .dependsOn(rudderCore, rudderCore % "test->test")
  .settings(commonSettings, publishTestJar, versionPropertiesSettings)
  .settings(
    organization := "com.normation.rudder",
    name         := "rudder-rest",
    libraryDependencies ++= Seq(
      springSecurity("spring-security-core"),
      "org.apache.commons" % "commons-fileupload2-jakarta-servlet6" % V.commonsFileupload,
      "jakarta.servlet"    % "jakarta.servlet-api" % V.servlet % Provided,
      "com.codacy"        %% "scalaj-http" % V.scalaj,
      "com.lihaoyi"       %% "sourcecode"  % V.sourcecode,
      liftTestkit % Test,
      "tools.profiler"     % "async-profiler" % V.asyncProfiler % Test
    )
  )

lazy val rudderWeb = (project in file("rudder/rudder-web"))
  .enablePlugins(SbtWar) // war packaging (replaces maven-war-plugin); `rudderWeb/package` builds the war
  .dependsOn(
    rudderRest, inventoryProvisioningCore, inventoryFusion,
    rudderCore % "test->test", rudderRest % "test->test"
  )
  .settings(commonSettings, publishTestJar, versionPropertiesSettings, ElmBuild.settings)
  .settings(
    organization := "com.normation.rudder",
    name         := "rudder-web",
    // War layout = the Maven maven-war-plugin layout. With exportJars:=false (needed for tests),
    // sbt-war would explode every internal module into WEB-INF/classes; instead set the two members
    // explicitly so dependency modules land as jars in WEB-INF/lib:
    //   - warClasses : ONLY rudder-web's own Compile products (its classes + generated toserve/ elm
    //     + filtered version.properties), not the transitive internal modules.
    //   - warLib     : the whole Runtime classpath AS JARS — internal modules (rudder-core, …)
    //     packaged on the fly + external deps. Runtime excludes Provided (servlet-api), like Maven.
    // sbt-war's `package` reads these in the Runtime scope (it defines Runtime/warLib itself), so the
    // overrides must be Runtime-scoped to take effect.
    Runtime / warClasses := {
      // rudder-web's OWN classes + resources (managed toserve/ elm + version.properties), exactly
      // what goes in its own jar — correctly relativized, no transitive-dep classes, no duplication.
      val conv = fileConverter.value
      (Compile / packageBin / mappings).value.map { case (ref, path) =>
        s"WEB-INF/classes/$path" -> conv.toPath(ref).toFile
      }.toMap
    },
    Runtime / warLib := {
      val conv = fileConverter.value
      // external deps come back from the Runtime classpath as jars; internal modules come back as
      // class DIRECTORIES (exportJars:=false) -> keep only the jars here, and add the internal
      // modules' packageBin jars explicitly (always a jar regardless of exportJars). `.toMap` keyed
      // by the WEB-INF/lib path dedups if a module appears both ways.
      val external = (Runtime / dependencyClasspathAsJars).value
        .map(a => conv.toPath(a.data).toFile).filter(_.getName.endsWith(".jar"))
      val internal = Seq(
        (utils / Compile / packageBin).value,
        (scalaLdap / Compile / packageBin).value,
        (inventoryApi / Compile / packageBin).value,
        (inventoryFusion / Compile / packageBin).value,
        (inventoryRepository / Compile / packageBin).value,
        (inventoryProvisioningCore / Compile / packageBin).value,
        (rudderTemplates / Compile / packageBin).value,
        (rudderCore / Compile / packageBin).value,
        (rudderRest / Compile / packageBin).value
      ).map(ref => conv.toPath(ref).toFile)
      (external ++ internal).map(j => s"WEB-INF/lib/${j.getName}" -> j).toMap
    },
    // Maven also attached the compiled classes as a `-classes` jar (attachClasses=true) so plugins
    // can depend on rudder-web's classes without pulling the war. Publish that jar too, with the
    // exact `classes` classifier rudder-plugins reference.
    Compile / packageBin / artifact := Artifact("rudder-web", "classes", "jar", "classes"),
    Compile / packageBin / publishArtifact := true
  )
  .settings(
    libraryDependencies ++= Seq(
      "org.reflections"    % "reflections" % V.reflections,
      "jakarta.servlet"    % "jakarta.servlet-api" % V.servlet % Provided,
      "commons-io"         % "commons-io"  % V.commonsIo,
      spring("spring-expression"),
      spring("spring-web"),
      spring("spring-tx"),
      springSecurityWeb,
      springSecurity("spring-security-config"),
      springSecurity("spring-security-ldap"),
      "org.typelevel"     %% "cats-effect-std" % V.catsEffect
    )
  )

// aggregate everything under a root project that builds nothing itself
lazy val root = (project in file("."))
  .settings(
    name           := "parent-pom",
    publish / skip := true
  )
  .aggregate(
    utils, scalaLdap,
    inventoryApi, inventoryFusion, inventoryRepository, inventoryProvisioningCore,
    rudderTemplates, rudderTemplatesCli, rudderCore, rudderRest, rudderWeb
  )
