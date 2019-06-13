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

package com.normation.rudder.javascript


import com.normation.cfclerk.domain.Variable
import com.normation.rudder.services.policies.JsEngine
import javax.script.SimpleBindings
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import org.junit.runner.RunWith
import org.specs2.matcher.Matcher
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import scala.util.matching.Regex


@RunWith(classOf[JUnitRunner])
class CalculateComplianceTest extends Specification {


  /**
   * A failure matcher utility that pattern matches the result message
   */
  def beFailure[T](regex: Regex): Matcher[Box[T]] = { b: Box[T] =>
    (
      b match {
        case Failure(m, _, _) if( regex.pattern.matcher(m).matches() ) => true
        case _ => false
      }
    , s"${b} is not a Failure whose message matches ${regex.toString}"
    )
  }

  def beVariableValue[T](cond: String => Boolean): Matcher[Box[Variable]] = { b: Box[Variable] =>
    (
      b match {
        case Full(v) if( v.values.size == 1 && cond(v.values(0)) ) => true
        case Full(v) => false
        case eb: EmptyBox =>
          val e = eb ?~! "Error with test"
          e.rootExceptionCause.foreach { ex => ex.printStackTrace() }
          false
      }
    , s"${b} is not a Full(InputVariable) that matches condition ${cond} but a '${b}'"
    )
  }


  val emptyJsBindings = new SimpleBindings()
  def js(js: String): Box[String] = {
    JsEngine.SandboxedJsEngine.sandboxed(this.getClass.getClassLoader.getResource("rudder-js.policy")) { box =>
      for {
        x <- box.singleEval(js, emptyJsBindings)
      } yield x
    }
  }


  "Test a js expression" should {
    "be ok" in {
      js("1+1") must beEqualTo(Full("2"))
    }

    "be not ok in " in {
      js("throw 'plop'") must beFailure(".*plop.*".r)
    }
  }

}
