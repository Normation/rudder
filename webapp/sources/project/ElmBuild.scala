import sbt._
import sbt.Keys._
import scala.sys.process._

/**
 * Frontend (Elm) build glue for rudder-web, replacing the maven exec-maven-plugin
 * + maven-resources-plugin executions:
 *   - generate-sources : src/main/build.sh --release  (builds the elm apps)
 *   - process-resources: copy the elm 'generated' css/js into toserve/
 *   - test             : `npx elm-test` and `npx elm-review`
 */
object ElmBuild {

  val elmBuild  = taskKey[Seq[File]]("Build the Elm frontend and stage generated css/js under toserve/")
  val elmTest   = taskKey[Unit]("Run elm-test")
  val elmReview = taskKey[Unit]("Run elm-review")

  /** Settings to add to the rudder-web project. */
  def settings: Seq[Setting[?]] = Seq(
    // File outputs aren't cacheable in sbt 2.0; this task has side effects (runs the
    // frontend build), so opt out of caching explicitly.
    elmBuild := Def.uncached {
      val log     = streams.value.log
      val baseDir = baseDirectory.value
      val mainDir = baseDir / "src" / "main"
      val outBase = (Compile / resourceManaged).value / "toserve"

      log.info("Building Elm frontend (build.sh --release)")
      val rc = Process(Seq("./build.sh", "--release"), mainDir).!
      if (rc != 0) sys.error(s"Elm build.sh failed with exit code $rc")

      // Stage every elm 'generated' css/js file under toserve/
      // (mirrors the maven-resources copy-elm-toserve execution, whose ${destDirectory}
      //  was empty -> flat toserve/. Validate against the full frontend checkout, which
      //  is not present in this sandbox.)
      val elmDir = mainDir / "elm"
      val generated = (elmDir ** "generated").get().flatMap { gen =>
        val files = (gen * ("*.css" | "*.js")).get()
        files.map { src =>
          val target = outBase / src.getName
          IO.copyFile(src, target)
          target
        }
      }
      log.info(s"Staged ${generated.size} generated Elm asset(s) under $outBase")
      generated
    },
    elmTest := {
      val _   = elmBuild.value // build.sh first (npm ci + elm-git-install populate node_modules & gitdeps)
      val log = streams.value.log
      val cwd = baseDirectory.value / "src" / "main" / "elm"
      log.info("Running elm-test")
      val rc = Process(Seq("npx", "elm-test", "sources/**/tests"), cwd).!
      if (rc != 0) sys.error(s"elm-test failed with exit code $rc")
    },
    elmReview := {
      val _   = elmBuild.value // ditto: elm-review needs node_modules + the rudder-elm-library gitdep
      val log = streams.value.log
      val cwd = baseDirectory.value / "src" / "main" / "elm"
      log.info("Running elm-review")
      val rc = Process(Seq("npx", "elm-review"), cwd).!
      if (rc != 0) sys.error(s"elm-review failed with exit code $rc")
    },
    // wire elmBuild into the resource pipeline (was process-resources / generate-sources)
    Compile / resourceGenerators += elmBuild.taskValue,
    // run elm-test + elm-review as part of `test` (was the two `test`-phase exec executions).
    // In sbt 2.0 `test` is an InputTask, so use `.evaluated` rather than `.value`.
    Test / test := (Test / test).dependsOn(elmTest, elmReview).evaluated
  )
}
