/*
 *************************************************************************************
 * Copyright 2016 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.templates.cli

import com.normation.zio.*
import java.io.File
import java.io.FileInputStream
import java.io.PrintStream
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.junit.runner.RunWith
import org.specs2.matcher.ContentMatchers
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll

@RunWith(classOf[JUnitRunner])
class TemplateCliTest extends Specification with ContentMatchers with AfterAll {

  sequential

  val testDir = new File("/tmp/test-template-cli-" + DateTime.now(DateTimeZone.UTC).toString())

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      FileUtils.deleteDirectory(testDir)
    }
  }

  def dir(path: String): String = (new File(testDir, path)).getAbsolutePath

  "The main program" should {
    "correctly replace variable in template" in {

      FileUtils.copyDirectory(new File("src/test/resources/templates1"), testDir)

      TemplateCli.main(
        Array(
          "--outdir",
          dir("out1"),
          "-p",
          dir("variables.json"),
          dir("cf-served.st"),
          dir("promises.st")
        )
      )

      new File(testDir, "out1") must haveSameFilesAs(new File(testDir, "expected"))
    }

    "write to stdout ignoring other things when --stdout is given" in {
      val out  = new File(testDir, "out2")
      out.mkdirs()
      val dest = new File(out, "cf-served.stdout")

      val orig = System.out
      // redirect stdout to the file
      System.setOut(new PrintStream(dest))

      TemplateCli.main(
        Array(
          "--params",
          dir("variables.json"),
          "--stdout",
          dir("cf-served.st")
        )
      )

      // restore std out
      System.setOut(orig)

      dest must haveSameLinesAs(new File(testDir, "expected/cf-served"))

    }

    "failed when there is not input at all" in {

      val res = TemplateCli.process(Config(variables = new File(testDir, "variables.json"))).either.runNow
      res match {
        case Right(_)  => ko(s"It should be a failure but we get: ${res}")
        case Left(err) => err.fullMsg === "Inconsistency: Can not get template content from stdin and no template file given"
      }
    }

    "read from stdin when there is content available in stdin" in {
      val out  = new File(testDir, "out3")
      out.mkdirs()
      val dest = new File(out, "cf-served.stdout")

      val origStdout = System.out
      val origStdin  = System.in

      // redirect stdout to the file
      System.setOut(new PrintStream(dest))

      System.setIn(new FileInputStream(new File(testDir, "cf-served.st")))

      TemplateCli.main(
        Array(
          "--params",
          dir("variables.json")
        )
      )

      // restore std out
      System.setOut(origStdout)
      System.setIn(origStdin)

      dest must haveSameLinesAs(new File(testDir, "expected/cf-served"))
    }

//    "try to read from stdin when no template name is given" in {
//
//      FileUtils.copyDirectory(new File("src/test/resources/templates1"), testDir)
//
//      TemplateCli.main(Array(
//          "--outdir", dir("out")
//        , "-p", dir("variables.json")
//        , dir("cf-served.st")
//        , dir("promises.st")
//     ))
//
//     new File(testDir, "out") must haveSameFilesAs(new File(testDir, "expected"))
//    }
  }
}
