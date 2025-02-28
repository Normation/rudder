/*
 *************************************************************************************
 * Copyright 2019 Normation SAS
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

import better.files.*
import com.normation.rudder.hooks.HookReturnCode.Ok
import com.normation.rudder.hooks.HookReturnCode.ScriptError
import com.normation.rudder.hooks.HookReturnCode.SystemError
import com.normation.rudder.hooks.HookReturnCode.Warning
import java.nio.file.attribute.PosixFilePermissions
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.AfterAll
import scala.jdk.CollectionConverters.*
import zio.{System as _, *}

/**
 * Test properties about NuProcess command, especially about
 * the process context (environment variable, file descriptors..)
 */

@RunWith(classOf[JUnitRunner])
class HooksTest() extends Specification with AfterAll {

  val tmp: File = File(s"/tmp/rudder-test-hook/${DateTime.now.toString(ISODateTimeFormat.dateTime())}")
  tmp.createDirectoryIfNotExists(true)

  List("error10.sh", "success.sh", "warning50.sh", "echoCODE.sh", "timeout.sh", "timeout_ok.sh").foreach { i =>
    val f = File(tmp, i)
    f.write(Resource.getAsString(s"hooks.d/test/$i"))
    f.setPermissions(PosixFilePermissions.fromString("rwxr--r--").asScala.toSet)
  }

  def runHooks(hooks: List[String], params: List[HookEnvPair]): HookReturnCode = {
    RunHooks.syncRun(
      Hooks(tmp.pathAsString, hooks.map(f => (f, HookTimeout(None, None)))),
      HookEnvPairs(params),
      HookEnvPairs(Nil),
      1.second,
      500.millis,
      5.seconds
    )
  }

  override def afterAll(): Unit = {
    if (System.getProperty("tests.clean.tmp") != "false") {
      File("/tmp/rudder-test-hook/").delete()
    }
  }

  // there is a lot of error/Warning level log in these tests, so we are disabling them. Reenable by changing level to debug
  org.slf4j.LoggerFactory
    .getLogger("hooks")
    .asInstanceOf[ch.qos.logback.classic.Logger]
    .setLevel(ch.qos.logback.classic.Level.OFF)

  "A successful hook should be a success" >> {
    val res = runHooks(List("success.sh"), Nil)
    res must beEqualTo(Ok("", ""))
  }

  "A success, then a warning should keep the warning" >> {
    val res = runHooks(List("success.sh", "warning50.sh"), Nil)
    res must beEqualTo(Warning(50, "", "", s"Exit code=50 for hook: '${tmp.pathAsString}/warning50.sh'."))
  }

  "A warning shouldn't stop execution" >> {
    val res = runHooks(List("warning50.sh", "success.sh"), Nil)
    res must beEqualTo(Ok("", ""))
  }

  "An error should stop the execution" >> {
    val res = runHooks(List("error10.sh", "warning50.sh"), Nil)
    res must beEqualTo(ScriptError(10, "", "", s"Exit code=10 for hook: '${tmp.pathAsString}/error10.sh'."))
  }

  "A hook should be able to read env variables as parameter" >> {
    val res = runHooks(List("echoCODE.sh"), HookEnvPair("CODE", "0") :: Nil)
    res must beEqualTo(Ok("", ""))
  }

  "A hook should be killed after time-out duration is reached and message contains what script failed" >> {
    val res = runHooks(List("timeout.sh"), HookEnvPair("CODE", "0") :: Nil)
    res must beLike { case SystemError(msg) if (msg.contains("/timeout.sh") && msg.contains("timed out after")) => ok }
  }

  "A hook should warn after time-out duration is reached" >> {
    val name    = "timeout_ok.sh"
    val timeout = RunHooks.effectfulGetHookTimeout(File(tmp.pathAsString, name).toJava)
    val res     = RunHooks.syncRun(
      Hooks(tmp.pathAsString, List((name, timeout))),
      HookEnvPairs(HookEnvPair("CODE", "0") :: Nil),
      HookEnvPairs(Nil),
      1.second,
      1.second,
      500.millis
    )
    timeout must beEqualTo(HookTimeout(Some(6.seconds), Some(10.seconds)))
    res must beEqualTo(Ok("", ""))
  }

// This one is more for testing performance. I don't want to make it a test, because in
// a CI it can be exptremelly different. But at least the code is here for anybody to check.
//  "Running a hook 1000 times should be extremely fast" >> {
//
//    //org.slf4j.LoggerFactory.getLogger("hooks").asInstanceOf[ch.qos.logback.classic.Logger].setLevel(ch.qos.logback.classic.Level.DEBUG)
//
//    import zio._
//    import zio.syntax._
//    import com.normation.zio._
//
//    val runOne = RunHooks.asyncRun(Hooks(tmp.pathAsString, List("echoCODE.sh")), HookEnvPairs(HookEnvPair("CODE", "0") :: Nil), HookEnvPairs(Nil), 1.second, 1.second)
//
//    val many = (ZIO.foreach(0 until 1000) { i =>
//      runOne
//    }).timed.map(_._1.toMillis)
//
//    /* Typical results in a dell xps 8 cores:
//     *  2838 ms
//     *  2253 ms
//     *  1902 ms
//     *  1700 ms
//     *  1698 ms
//     *  1819 ms
//     *  1708 ms
//     *  1649 ms
//     *  1619 ms
//     *  1624 ms
//     */
//    val res = println((0 until 10).map(_ => ZioRuntime.unsafeRun(many)).sum)
//
//    1 === 1
//
//
//  }
}
