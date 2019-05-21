/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

import com.normation.NamedZioLogger
import javax.servlet.UnavailableException
import org.joda.time.Duration
import org.joda.time.format.PeriodFormatterBuilder

/**
 *
 * Interface of the pipeline for action to execute
 * at bootstrap.
 *
 *
 * Implementation may really be executed when the application
 * is launched, so be careful with the time taken by them.
 *
 * On the other hand, for async bootstraps checks, don't expect
 * order in there execution.
 */
trait BootstrapChecks {

  def description: String

  @throws(classOf[ UnavailableException ])
  def checks() : Unit

}

object BootraspLogger extends NamedZioLogger {
  override final def loggerName: String = "bootchecks"
}

class SequentialImmediateBootStrapChecks(_checkActions:BootstrapChecks*) extends BootstrapChecks {

  private[this] var checkActions = collection.mutable.Buffer[BootstrapChecks](_checkActions:_*)

  def appendBootstrapChecks(check: BootstrapChecks): Unit = {
    checkActions.append(check)
  }

  override val description = "Sequence of bootstrap checks"
  val formater = new PeriodFormatterBuilder()
    .appendMinutes()
    .appendSuffix(" m")
    .appendSeparator(" ")
    .appendSeconds()
    .appendSuffix(" s")
    .appendSeparator(" ")
    .appendMillis()
    .appendSuffix(" ms")
    .toFormatter();

  @throws(classOf[ UnavailableException ])
  override def checks() : Unit = checkActions.zipWithIndex.foreach { case (check,i) =>
    val start = System.currentTimeMillis
    val msg = if(BootraspLogger.logEffect.isDebugEnabled) {
      s"[#${i}] ${check.description}"
    } else {
      s"${check.description}"
    }
    BootraspLogger.logEffect.info(msg)
    check.checks
    BootraspLogger.logEffect.debug(msg + s": OK in [${formater.print(new Duration(System.currentTimeMillis - start).toPeriod)}] ms")
  }

}
