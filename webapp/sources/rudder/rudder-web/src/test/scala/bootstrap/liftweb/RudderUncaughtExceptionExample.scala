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

package bootstrap.liftweb


/*
 * Test the uncaught exception class. It's very hard to make that
 * actual unit test because we can't really test from java the value
 * of exit method, and I don't seem to be able to figure how to
 * make the UncaughtExceptionHandler do other thing than terminating
 * the thread.
 */
object RudderUncaughtException1 {
  def main(args: Array[String]): Unit = {
    FatalException.init(Set())
    throw new java.lang.Error("I'm an error")
  }
}

object RudderUncaughtException2 {
  def main(args: Array[String]): Unit = {
    FatalException.init(Set())
    val plop = new Array[Array[String]](10000000)
    var i = 0
    while(true) {
      plop(i) = Array.fill(1000000)("a")
      i += 1
    }
  }
}

object RudderUncaughtException3 {

  class FillStack(value: String) {
    val fill = new FillStack(value)
  }

  def main(args: Array[String]): Unit = {
    FatalException.init(Set())
    new FillStack("let's fill it!")
  }
}

object RudderUncaughtException4 {
  def main(args: Array[String]): Unit = {
    FatalException.init(Set())
    throw new NoClassDefFoundError("oups/I/don/t/exist/actually")
  }
}
