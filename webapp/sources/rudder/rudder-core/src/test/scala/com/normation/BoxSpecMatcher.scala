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

package com.normation

import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.specs2.matcher.Expectable
import org.specs2.matcher.Matcher
import org.specs2.matcher.MatchResult
import org.specs2.matcher.NoShouldExpectations
import org.specs2.mutable.Specification
import scala.annotation.nowarn

/**
 * Here we manage all the initialisation of services and database
 * state for the full example (after/before class).
 */
trait BoxSpecMatcher extends Specification with Loggable with NoShouldExpectations {

  /*
   * helpers for Box
   */
  implicit class BoxMustFails[T](t: Box[T]) {
    def mustFails(): MatchResult[Any] = t match {
      case f: Failure => ok(s"Got a Failure as expected: ${f.messageChain}")
      case x => ko(s"I was expecting a Failure box and got ${x}")
    }
  }

  implicit class BoxMustEmpty[T](t: Box[T]) {
    def mustEmpty(): MatchResult[Any] = t match {
      case Empty => ok(s"Got Empty as expected")
      case x     => ko(s"I was expecting an Empty box and got ${x}")
    }
  }

  implicit class BoxMustEquals[T](t: Box[T]) {
    private def matchRes(f: T => MatchResult[Any]) = t match {
      case failure: Failure =>
        val msg = s"I wasn't expecting the failure: ${failure.messageChain}"
        failure.rootExceptionCause.foreach { ex =>
          logger.error(msg)
          ex.printStackTrace()
        }
        ko(msg)
      case Empty => ko(s"How can I get an Empty!")
      case Full(x) => f(x)
    }

    infix def mustFullEq(res: T): MatchResult[Any] = matchRes((x: T) => x === res)

    infix def mustMatch(m: Matcher[T], name: String): MatchResult[Any] = matchRes((x: T) => (x aka name) must m)

    infix def mustFull: MatchResult[Any] = matchRes((x: T) => ok(s"Got a ${x}"))

  }

}

import org.specs2.matcher.describe.Diffable

trait BoxMatchers {
  @nowarn
  infix def beFull[T: Diffable]: BoxMatcher[T] = BoxMatcher()
}

case class BoxMatcher[T]() extends Matcher[Box[T]] {
  override def apply[S <: Box[T]](value: Expectable[S]): MatchResult[S] = {
    result(
      test = value.value.isInstanceOf[Full[T]],
      okMessage = s"${value.description} is Full",
      koMessage = s"${value.description} is not Full",
      value = value
    )
  }

}
