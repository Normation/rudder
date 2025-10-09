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

package com.normation.rudder.services.policies

import com.normation.cfclerk.domain.InputVariable
import com.normation.cfclerk.domain.InputVariableSpec
import com.normation.cfclerk.domain.Variable
import com.normation.errors.IOResult
import com.normation.errors.RudderError
import com.normation.rudder.domain.appconfig.FeatureSwitch
import com.normation.rudder.services.policies.JsEngine.SandboxedJsEngine
import com.normation.zio.*
import org.junit.runner.*
import org.specs2.matcher.Matcher
import org.specs2.matcher.MatchResult
import org.specs2.mutable.*
import org.specs2.runner.*
import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex
import zio.*
import zio.syntax.*

/*
 * This class test the JsEngine.
 * It must work identically on Java 7 and Java 8.
 *
 */

@nowarn("msg=a type was inferred to be `\\w+`; this may indicate a programming error.")
@RunWith(classOf[JUnitRunner])
class TestJsEngine extends Specification {

  val hashPrefix = "test"
  val variableSpec: InputVariableSpec = InputVariableSpec(hashPrefix, "", None, id = None)

  val noscriptVariable:     InputVariable = variableSpec.toVariable(Seq("simple ${rudder} value"))
  val get4scriptVariable:   InputVariable = variableSpec.toVariable(Seq(s"${JsEngine.EVALJS} 2+2"))
  val infiniteloopVariable: InputVariable = variableSpec.toVariable(Seq(s"${JsEngine.EVALJS}while(true){}"))

  /**
   * A failure matcher utility that pattern matches the result message
   */
  def beFailure[T](regex: Regex): Matcher[Either[RudderError, T]] = { (b: Either[RudderError, T]) =>
    (
      b match {
        case Left(err) if (regex.pattern.matcher(err.fullMsg).matches()) => true
        case _                                                           => false
      },
      s"${b} is not a Failure whose message matches ${regex.toString}"
    )
  }

  def beVariableValue[T](cond: String => Boolean): Matcher[Either[RudderError, Variable]] = {
    (b: Either[RudderError, Variable]) =>
      (
        b match {
          case Right(v) if (v.values.size == 1 && cond(v.values(0))) => true
          case Right(v)                                              => false
          case Left(err)                                             =>
            println(err.fullMsg)
            false
        },
        s"${b} is not a Full(InputVariable) that matches condition ${cond} but a '${b}'"
      )
  }

  def runSandboxed[T](maxThread: Int = 1)(script: SandboxedJsEngine => IOResult[T]): Either[RudderError, T] =
    JsEngine.SandboxedJsEngine.sandboxed(maxThread)(script).either.runNow

  def contextEnabled[T](script: JsEngine => IOResult[T]):  Either[RudderError, T] =
    JsEngineProvider.withNewEngine[T](FeatureSwitch.Enabled, 1, FiniteDuration(1, "second"))(script).either.runNow
  def contextDisabled[T](script: JsEngine => IOResult[T]): Either[RudderError, T] =
    JsEngineProvider.withNewEngine[T](FeatureSwitch.Disabled, 1, FiniteDuration(1, "second"))(script).either.runNow

  /**
   * This is needed, because we set the security manager and then
   * remove it. So we don't want two thread to do it concurrently.
   *
   * Also not that any safe use of that method mustn't be done in
   * a concurrent env where several thread create (and destroy)
   * sandboxed env.
   */
  sequential

  "Getting the scripting engine" should {
    "correctly get the engine instance" in {
      (JsEngine.SandboxedJsEngine.getJsEngine(1).either.runNow match {
        case Right(engine) =>
          ok("ok")
        case Left(err)     =>
          ko(err.fullMsg)
      }): MatchResult[Any]
    }

    "let test have direct access to the engine" in {
      val res = JsEngine.SandboxedJsEngine
        .getJsEngine(1)
        .flatMap(c => ZIO.scoped(c.buildContext.flatMap(_.eval("js", "1 + 1").asInt().succeed)))
        .runNow
      res === 2
    }
  }

  "When feature is disabled, one " should {

    "have an identical results without eval" in {
      contextDisabled(engine => true.succeed) must beEqualTo(Right(true))
    }

    "have an identical result when variable isn't a script" in {
      contextDisabled(_.eval(noscriptVariable, JsRudderLibBinding.Crypt)) must beEqualTo(Right(noscriptVariable))
    }

    "failed with a message when the variable is a script" in {
      contextDisabled(_.eval(get4scriptVariable, JsRudderLibBinding.Crypt)) must beFailure("(?s).*starts with the evaljs:.*".r)
    }
  }

  "When getting the sandboxed environment, one " should {

    "still be able to do dangerous things, because it's only the JsEngine which is sandboxed" in {
      runSandboxed() { box =>
        val dir = new java.io.File(s"/tmp/rudder-test-${java.lang.System.currentTimeMillis}")
        val res = IOResult.attempt((dir).createNewFile())
        // but clean tmp after all :)
        dir.delete()
        res
      } must beEqualTo(Right(true))
    }

    "be able to do simple operation with JS" in {
      runSandboxed()(box => box.singleEval("'thestring'.substring(0,3)", JsRudderLibBinding.Crypt.jsRudderLib)) must beEqualTo(
        Right("the")
      )
    }

    "can be executed in parallel, safely even with more thread than wanted" in {
      val res: IOResult[List[String]] = JsEngine.SandboxedJsEngine.sandboxed(2)(js => {
        ZIO
          .foreachPar(Range(0, 20).toList)(i => js.singleEval(s"'processing $i'", JsRudderLibBinding.Crypt.jsRudderLib))
          .withParallelism(6)
      })

      res.runNow must containTheSameElementsAs(Range(0, 20).map(i => s"processing $i"))
    }

    "get a scripting error if the script is eval to null" in {
      runSandboxed()(box => box.singleEval("null", JsRudderLibBinding.Crypt.jsRudderLib)) must beFailure("(?s).*null.*".r)
    }

    "not be able to access FS with JS" in {
      runSandboxed() { box =>
        box.singleEval(
          """(new java.io.File("/tmp/rudder-test-fromjsengine")).createNewFile();""",
          JsRudderLibBinding.Crypt.jsRudderLib
        )
      } must beFailure("(?s).*java is not defined.*".r)
    }

    "not be able to kill the system with JS" in {
      runSandboxed() { box =>
        box.singleEval("""java.lang.System.exit(0);""", JsRudderLibBinding.Crypt.jsRudderLib)
      } must beFailure("(?s).*java is not defined.*".r)
    }

    "not be able to loop for ever with JS" in {
      runSandboxed()(box => box.singleEval("""while(true){}""", JsRudderLibBinding.Crypt.jsRudderLib)) must beFailure(
        "(?s).*took more than.*, aborting.*".r
      )
    }

  }

  "When feature is enabled, one " should {

    "have an identical results without eval" in {
      contextEnabled(engine => true.succeed) must beEqualTo(Right(true))
    }

    "get the correct STRING value for simple expression" in {
      contextEnabled(_.eval(get4scriptVariable, JsRudderLibBinding.Crypt)) must beVariableValue(s =>
        (s == "4" || s == "4.0")
      ) // JS may return 4 or 4.0
    }

    "not be able to loop for ever with JS" in {
      contextEnabled(engine => engine.eval(infiniteloopVariable, JsRudderLibBinding.Crypt)) must beFailure(
        "(?s).*took more than.*, aborting.*".r
      )
    }

    "not be able to access the content of a previously setted var" in {
      val setFooVariable = variableSpec.toVariable(Seq(s"${JsEngine.EVALJS}var foo = 'some value'; foo"))
      val getFooVariable = variableSpec.toVariable(Seq(s"${JsEngine.DEFAULT_EVAL}foo"))

      // ok in same eval
      val ok  = contextEnabled(engine => engine.eval(setFooVariable, JsRudderLibBinding.Crypt))
      val ok2 = contextEnabled(engine => engine.eval(setFooVariable, JsRudderLibBinding.Crypt))

      // not ok if two eval
      val ko = contextEnabled { engine =>
        for {
          _ <- engine.eval(setFooVariable, JsRudderLibBinding.Crypt)
          r <- engine.eval(getFooVariable, JsRudderLibBinding.Crypt)
        } yield r
      }

      (ok must beVariableValue(v => v == "some value")) and
      (ok2 must beVariableValue(v => v == "some value")) and
      (ko must beFailure("(?s)Invalid script.*foo.*Variable test.*".r))
    }

  }

  "When using the Rudder JS Library, one" should {

    val sha256Variable = variableSpec.toVariable(Seq(s"${JsEngine.EVALJS}rudder.password.sha256('secret')"))
    val sha512Variable = variableSpec.toVariable(Seq(s"${JsEngine.DEFAULT_EVAL}rudder.password.sha512('secret', '01234567')"))
    val sha256Hash     = variableSpec.toVariable(Seq(s"${JsEngine.EVALJS}rudder.hash.sha256('secret')"))
    val sha512HAsh     = variableSpec.toVariable(Seq(s"${JsEngine.DEFAULT_EVAL}rudder.hash.sha512('secret')"))

    "get the correct hashed value for Linux sha256" in {
      contextEnabled(engine => engine.eval(sha256Variable, JsRudderLibBinding.Crypt)) must beVariableValue(_.startsWith("$5$"))
    }

    "get the correct hashed value for Linux sha512" in {
      contextEnabled(engine => engine.eval(sha512Variable, JsRudderLibBinding.Crypt)) must beVariableValue(_.startsWith("$6$"))
    }

    "get the correct hashed value for sha256" in {
      contextEnabled(engine => engine.eval(sha256Hash, JsRudderLibBinding.Crypt)) must beVariableValue(
        _.startsWith("2bb80d537b1da3e38bd3")
      )
    }

    "get the correct hashed value for sha512" in {
      contextEnabled(engine => engine.eval(sha512HAsh, JsRudderLibBinding.Crypt)) must beVariableValue(
        _.startsWith("bd2b1aaf7ef4f09be9f5")
      )
    }
  }

  "When using the Rudder JS Library and advertised method, one" should {

    val sha256Variable = variableSpec.toVariable(Seq(s"${JsEngine.EVALJS}rudder.password.auto('sha256', 'secret')"))
    val sha512Variable =
      variableSpec.toVariable(Seq(s"${JsEngine.DEFAULT_EVAL}rudder.password.auto('SHA-512', 'secret', '01234567')"))
    val invalidAlgo    = variableSpec.toVariable(Seq(s"${JsEngine.EVALJS}rudder.password.auto('foo', 'secret')"))

    "get the correct hashed value for Linux sha256" in {
      contextEnabled(engine => engine.eval(sha256Variable, JsRudderLibBinding.Crypt)) must beVariableValue(_.startsWith("$5$"))
    }

    "get the correct hashed value for Linux sha512" in {
      contextEnabled(engine => engine.eval(sha512Variable, JsRudderLibBinding.Crypt)) must beVariableValue(_.startsWith("$6$"))
    }

    "fail when we ask for a wrong password" in {
      contextEnabled(engine => engine.eval(invalidAlgo, JsRudderLibBinding.Crypt)) must beFailure("(?s)Invalid script.*".r)
    }
  }
}
