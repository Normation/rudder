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

package com.normation.rudder.utils

import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full

object Utils {
  // Either[String, T] => Box[T] with the obvious semantic
  implicit def eitherToBox[A](res: Either[String, A]): Box[A] = res match {
    case Left(s)  => Failure(s, Empty, Empty)
    case Right(a) => Full(a)
  }
}

/**
 * An object to transform parse a number into a parallelism number, which accept either an
 * integer or a multiplicator based on the number of cores.
 * This method is effectful.
 */
object ParseMaxParallelism {
  /*
   * value: the string to transform in to a number, either a positive Int ("1", etc)
   *        or a mutiplicator like "x0.5", "x2", etc (where what is after the 'x' is a double.
   * defaultValue: the value to use if the string is not parsable or result is < 1
   * propertyName, loggerWarn: used to log message if there is an error with the parsing.
   */
  def apply(value: String, defaultValue: Int, propertyName: String, loggerWarn: String => Unit): Int = {
    def threadForProc(mult: Double): Int = {
      Math.max(1, (java.lang.Runtime.getRuntime.availableProcessors * mult).ceil.toInt)
    }

    val t = {
      try {
        value match {
          case s if s.charAt(0) == 'x' => threadForProc(s.substring(1).toDouble)
          case other                   => other.toInt
        }
      } catch {
        case ex: IllegalArgumentException =>
          loggerWarn(s"Error when trying to parse '${value}' for '${propertyName}', defaulting to '${defaultValue}'.")
          defaultValue
      }
    }
    if (t < 1) {
      loggerWarn(s"You can't set '${propertyName}' to ${t} (parsed from '${value}'. Defaulting to '${defaultValue}''")
      defaultValue
    } else {
      t
    }
  }
}
