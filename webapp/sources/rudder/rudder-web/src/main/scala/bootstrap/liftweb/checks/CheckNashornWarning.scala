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

package bootstrap.liftweb.checks

import bootstrap.liftweb.BootstrapChecks
import bootstrap.liftweb.BootstrapLogger
import javax.servlet.UnavailableException

import scala.util.control.NonFatal

/**
 * for openjdk>= 11, we need to remove a nashorn warning
 */
class CheckNashornWarning() extends BootstrapChecks {

  override val description = "Check NashHorn JS engine for warning"

  val nashornProp = "nashorn.args"
  val nashornVal = "--no-deprecation-warning"

  @throws(classOf[ UnavailableException ])
  override def checks() : Unit = {
    try {
      val javaVersionElements = System.getProperty("java.version").split('.')
      val major = Integer.parseInt(javaVersionElements(0))
      if(major >= 11) {
        BootstrapLogger.logEffect.warn(s"Set '${nashornProp}=${nashornVal}' to avoid redundant deprecation warnings")
        System.getProperty(nashornProp) match {
          case null  => System.setProperty(nashornProp, nashornVal)
          case value =>
            if(!value.contains(nashornVal)) {
              System.setProperty(nashornProp, value ++ " " ++ nashornVal)
            } else {
              // nothing
            }
        }
      } else {
        // ok, let things as they are.
      }
    } catch { // in case of error, don't touch anything
      case NonFatal(ex) => // do nothing
        BootstrapLogger.logEffect.warn(s"Unable to remove NashHorn deprecation warning, exception was: ${ex.getMessage}")
    }
  }

}
