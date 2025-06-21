/*
 *************************************************************************************
 * Copyright 2018 Normation SAS
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

package com.normation.rudder.hooks

import com.normation.errors.*
import com.normation.zio.*
import java.io.File
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*
import zio.{System as _, *}

/**
 * Test properties about NuProcess command, especially about
 * the process context (environment variable, file descriptors..)
 */

@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class RunNuCommandTest() extends Specification {

  "A command" should {

    val PATH = System
      .getenv()
      .asScala
      .getOrElse("PATH", throw new RuntimeException(s"PATH environment variable must be defined to run process tests."))

    "has only the environment variable explicitly defined" in {
      val prog = {
        for {
          p <- RunNuCommand.run(Cmd("env", Nil, Map("PATH" -> PATH, "foo" -> "bar"), None))
          c <- p.await.timeout(500.millis).notOptional("oups, timed out")
        } yield {
          s"return code=${c.code}\n" ++
          c.stdout ++
          c.stderr
        }
      }

      prog.runNow must beMatching("return code=0\nPATH=.*\nfoo=bar\n".r)
    }

    "has only the 3 stdin, stdout, stderr file descriptors" in {
      // we only keep the return code and the number of lines in the ls output
      // (we have one more line because last line ends with a "\n"
      val prog = {
        for {
          p <- RunNuCommand.run(Cmd("ls", "-1" :: "/proc/self/fd" :: Nil, Map("PATH" -> PATH), None))
          c <- p.await.timeout(500.millis).notOptional("oups, timed out")
        } yield {
          (c.code, c.stdout.split("\n").size)
        }
      }

      prog.runNow must beEqualTo((0, 4))
    }

    "can actually modify the file system" in {
      val date = DateTime.now(DateTimeZone.UTC).toString(ISODateTimeFormat.dateTime())
      val file = new File(s"/tmp/rudder-test/test-nucmd-$date")
      file.getParentFile.deleteOnExit()
      file.deleteOnExit()

      val prog = {
        for {
          p <- RunNuCommand.run(Cmd("mkdir", "-p" :: file.getPath :: Nil, Map("PATH" -> PATH), None))
          c <- p.await.timeout(500.millis).notOptional("oups, timed out")
        } yield {
          c.code
        }
      }

      (file.exists() must beFalse) and
      (prog.runNow must beEqualTo(0)) and
      (file.exists() must beTrue)
    }
  }
}
