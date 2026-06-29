import sbt._

/**
 * Central place for every external dependency version and ModuleID.
 * Replaces the Maven parent-pom <properties> + <dependencyManagement> sections.
 *
 * Conventions:
 *   - `%%` appends the Scala 3 suffix (`_3`) to the dependency artifactId, matching the
 *     `_${scala-binary-version}` Maven artifactIds.
 *   - Maven <exclusions> are reproduced with `.exclude(...)` / `.excludeAll(...)`.
 */
object Dependencies {

  object V {
    val servlet          = "6.1.0"
    val scala            = "3.8.4"
    val scalaXml         = "2.4.0"
    val scalaParserComb  = "2.4.0"
    val lift             = "4.0.0"
    val slf4j            = "2.0.18"
    val logback          = "1.5.34"
    val janino           = "3.1.12"
    val jodatime         = "2.14.2"
    val jodaconvert      = "3.0.1"
    val commonsIo        = "2.22.0"
    val commonsLang      = "3.20.0"
    val commonsText      = "1.15.0"
    val commonsCodec     = "1.22.0"
    val commonsFileupload = "2.0.0-M5" // keep M5 because https://issues.rudder.io/issues/28527
    val commonsCsv       = "1.14.1"
    val jgit             = "7.7.0.202606012155-r"
    val spring           = "7.0.8"
    val springSecurity   = "7.1.0"
    val asm              = "9.10.1"
    val bouncycastleCompat = "jdk18on"
    val bouncycastle     = "1.84"
    val betterFiles      = "3.9.2"
    val sourcecode       = "0.4.4"
    val quicklens        = "1.9.12"
    val hikaricp         = "7.0.2"
    val nuprocess        = "3.0.0"
    val postgresql       = "42.7.11"
    val jsonPath         = "3.0.0"
    val jsonSmart        = "2.6.0"
    val scalaj           = "2.5.0"
    val unboundid        = "7.0.4"
    val fastparse        = "3.1.1"
    val config           = "1.4.9"
    val caffeine         = "3.2.4"
    val jgrapht          = "1.5.3"
    val reflections      = "0.10.2"
    val graalvm          = "25.0.3"
    val chimney          = "1.10.0"
    val cron4s           = "0.8.2"
    val ipaddress        = "5.6.2"
    val snakeyaml        = "2.6"
    val xerces           = "2.12.2"
    val rhino            = "1.9.1"
    val jolCore          = "0.17"
    val jsr305           = "3.0.2"
    val stringtemplate   = "3.2.1"
    val scopt            = "4.1.0"
    val asyncProfiler    = "4.4"

    // test stack — versions that must work together
    val specs2           = "4.23.0"
    val junit            = "4.13.2"
    val cats             = "2.13.0"
    val ip4s             = "3.8.0"
    val doobie           = "1.0.0-RC12"
    val catsEffect       = "3.7.0"
    val zio              = "2.1.26"
    val zioCats          = "23.1.0.13"
    val zioJson          = "0.9.2"
    val izumi            = "3.0.9"
    val magnolia         = "1.3.20"
    val difflicious      = "0.6.0"
    val enumeratum       = "1.9.7"
  }

  // ---- exclusion helpers (reproduce Maven <exclusions>) ----
  private val noCommonsLogging = ExclusionRule("commons-logging", "commons-logging")
  private val noLog4j12        = ExclusionRule("org.slf4j", "slf4j-log4j12")
  private val noLog4j          = ExclusionRule("log4j", "log4j")

  // ---- lift-web ----
  val liftCommon  = ("net.liftweb" %% "lift-common" % V.lift).excludeAll(noLog4j12, noLog4j)
  val liftUtil    = ("net.liftweb"  %% "lift-util"   % V.lift).exclude("javax.mail", "mail")
  val liftWebkit  = ("net.liftweb"  %% "lift-webkit" % V.lift)
    .excludeAll(noLog4j12)
    .exclude("commons-fileupload", "commons-fileupload")
  val liftTestkit = "net.liftweb" %% "lift-testkit" % V.lift

  // ---- spring (all exclude commons-logging, we use jcl-over-slf4j) ----
  def spring(a: String)         = ("org.springframework" % a % V.spring).excludeAll(noCommonsLogging)
  def springSecurity(a: String) = "org.springframework.security" % a % V.springSecurity
  val springSecurityWeb = springSecurity("spring-security-web")
    .exclude("org.springframework", "spring-core")
    .exclude("org.springframework", "spring-beans")

  // ---- jgit (no apache httpclient / commons-logging) ----
  val jgit = ("org.eclipse.jgit" % "org.eclipse.jgit" % V.jgit)
    .exclude("org.apache.httpcomponents", "httpclient")

  // ---- bouncycastle (compat suffix) ----
  val bcpkix = "org.bouncycastle" % s"bcpkix-${V.bouncycastleCompat}" % V.bouncycastle
  val bcprov = "org.bouncycastle" % s"bcprov-${V.bouncycastleCompat}" % V.bouncycastle
  val bcpg   = "org.bouncycastle" % s"bcpg-${V.bouncycastleCompat}"   % V.bouncycastle

  // ---- common to ALL modules (parent-pom <dependencies>) ----
  val common: Seq[ModuleID] = Seq(
    "com.github.pathikrit"        %% "better-files"      % V.betterFiles,
    "com.beachape"                %% "enumeratum"        % V.enumeratum,
    "joda-time"                    % "joda-time"         % V.jodatime,
    "com.softwaremill.quicklens"  %% "quicklens"         % V.quicklens,
    "org.typelevel"               %% "cats-core"         % V.cats,
    "dev.zio"                     %% "zio"               % V.zio,
    "dev.zio"                     %% "zio-stacktracer"   % V.zio,
    "dev.zio"                     %% "zio-concurrent"    % V.zio,
    "dev.zio"                     %% "zio-streams"       % V.zio,
    "dev.zio"                     %% "zio-json"          % V.zioJson,
    "dev.zio"                     %% "zio-json-yaml"     % V.zioJson,
    "dev.zio"                     %% "izumi-reflect"     % V.izumi,
    "com.softwaremill.magnolia1_3" %% "magnolia"         % V.magnolia,
    "org.yaml"                     % "snakeyaml"         % V.snakeyaml,
    "io.scalaland"                %% "chimney"           % V.chimney,
    "io.scalaland"                %% "chimney-cats"      % V.chimney,
    "dev.zio"                     %% "zio-interop-cats"  % V.zioCats,
    "org.typelevel"               %% "cats-effect"       % V.catsEffect,
    "org.jgrapht"                  % "jgrapht-core"      % V.jgrapht,
    // slf4j native backend (logback)
    "ch.qos.logback"               % "logback-core"      % V.logback,
    "ch.qos.logback"               % "logback-classic"   % V.logback,
    "org.codehaus.janino"          % "janino"            % V.janino,
    // tests
    "junit"                        % "junit"                 % V.junit       % Test,
    "org.specs2"                  %% "specs2-core"            % V.specs2      % Test,
    "org.specs2"                  %% "specs2-matcher-extra"   % V.specs2      % Test,
    "org.specs2"                  %% "specs2-junit"           % V.specs2      % Test,
    "org.tpolecat"                %% "doobie-specs2"          % V.doobie      % Test,
    "dev.zio"                     %% "zio-test"               % V.zio         % Test,
    "dev.zio"                     %% "zio-test-magnolia"      % V.zio         % Test,
    "dev.zio"                     %% "zio-test-junit"         % V.zio         % Test,
    "com.github.jatcwang"         %% "difflicious-core"       % V.difflicious % Test
  )

  // ---- transitive version pins (Maven <dependencyManagement> that only fix versions) ----
  val overrides: Seq[ModuleID] = Seq(
    "org.scala-lang.modules" %% "scala-xml"                 % V.scalaXml,
    "org.scala-lang.modules" %% "scala-parser-combinators"  % V.scalaParserComb,
    "xerces"                  % "xercesImpl"                 % V.xerces,
    "org.mozilla"             % "rhino"                      % V.rhino,
    "org.ow2.asm"             % "asm"                        % V.asm,
    "org.slf4j"               % "slf4j-api"                  % V.slf4j,
    "org.slf4j"               % "jcl-over-slf4j"             % V.slf4j,
    "org.joda"                % "joda-convert"               % V.jodaconvert,
    "org.typelevel"          %% "cats-effect-std"            % V.catsEffect,
    "com.lihaoyi"            %% "sourcecode"                 % V.sourcecode,
    "com.zaxxer"              % "HikariCP"                   % V.hikaricp,
    "org.postgresql"          % "postgresql"                 % V.postgresql,
    bcprov,
    bcpg
  )
}
