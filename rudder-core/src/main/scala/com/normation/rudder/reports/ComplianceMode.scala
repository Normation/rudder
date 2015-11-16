/*
*************************************************************************************
* Copyright 2014 Normation SAS
*************************************************************************************
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Affero General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU Affero GPL v3, the copyright holders add the following
* Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU Affero GPL v3
* licence, when you create a Related Module, this Related Module is
* not considered as a part of the work and may be distributed under the
* license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Affero General Public License for more details.
*
* You should have received a copy of the GNU Affero General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/agpl.html>.
*
*************************************************************************************
*/

package com.normation.rudder.reports

import net.liftweb.common._
import net.liftweb.json._

/**
 * Define level of compliance:
 *
 * - compliance: full compliance, check success report (historical Rudder way)
 * - error_only: only report for repaired and error reports.
 */

sealed trait ComplianceModeName {
  val name : String
}

case object FullCompliance extends ComplianceModeName {
  val name = "full-compliance"
}

case object ChangesOnly extends ComplianceModeName {
  val name = "changes-only"
}

case object ReportsDisabled extends ComplianceModeName {
  val name = "reports-disabled"
}

object ComplianceModeName {
  val allModes : List[ComplianceModeName] = FullCompliance :: ChangesOnly :: ReportsDisabled :: Nil

  def parse (value : String) : Box[ComplianceModeName] = {
    allModes.find { _.name == value } match {
      case None =>
         Failure(s"Unable to parse the compliance mode name '${value}'. was expecting ${allModes.map(_.name).mkString("'", "' or '", "'")}.")
      case Some(mode) =>
        Full(mode)
    }
  }
}

sealed trait ComplianceMode {
  def mode: ComplianceModeName
  def heartbeatPeriod : Int
  val name = mode.name
}

case class GlobalComplianceMode (
    mode : ComplianceModeName
  , heartbeatPeriod : Int
) extends ComplianceMode

case class NodeComplianceMode (
    mode : ComplianceModeName
  , heartbeatPeriod : Int
  , overrideGlobal : Boolean
) extends ComplianceMode

trait ComplianceModeService {
  def getGlobalComplianceMode : Box[GlobalComplianceMode]
}

class ComplianceModeServiceImpl (
    readComplianceMode : () => Box[String]
  , readHeartbeatFreq  : () => Box[Int]
) extends ComplianceModeService {

  def getGlobalComplianceMode : Box[GlobalComplianceMode] = {
    for {
      modeName       <- readComplianceMode()
      mode           <- ComplianceModeName.parse(modeName)
      heartbeat      <- readHeartbeatFreq()
    } yield {
      GlobalComplianceMode(
          mode
        , heartbeat
      )
    }
  }
}
