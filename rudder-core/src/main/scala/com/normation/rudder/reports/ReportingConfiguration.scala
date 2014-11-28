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

import com.normation.utils.HashcodeCaching
import net.liftweb.json.JBool
import net.liftweb.common._

/**
 * Class that contains all relevant information about the reporting configuration:
 * * agent run interval
 * * compliance mode (soon)
 * * heartbeat configuration
 */
final case class ReportingConfiguration (
    agentRunInterval : Option[AgentRunInterval]
  , heartbeatConfiguration : Option[HeartbeatConfiguration]
) extends HashcodeCaching

final case class HeartbeatConfiguration (
   overrides : Boolean
 , heartbeatPeriod : Int
)

final case class AgentRunInterval (
    overrides  : Option[Boolean]
  , interval   : Int
  , startMinute: Int
  , startHour  : Int
  , splaytime  : Int
) extends HashcodeCaching {

 def json() = {

   val overrideValue = overrides.map(_.toString).getOrElse("null")
   val splayHour = splaytime / 60
   val splayMinute = splaytime % 60

   s"""{ 'overrides'   : ${overrideValue}
       , 'interval'    : ${interval}
       , 'startHour'   : ${startHour}
       , 'startMinute' : ${startMinute}
       , 'splayHour'   : ${splayHour}
       , 'splayMinute' : ${splayMinute}
       }"""
 }
}

trait AgentRunIntervalService {
  def getAgentRun : Box[AgentRunInterval]
}

class AgentRunIntervalServiceImpl (
    readOverrides : () => Box[Option[Boolean]]
  , readInterval  : () => Box[Int]
  , readStartHour  : () => Box[Int]
  , readStartMinute  : () => Box[Int]
  , readSplaytime  : () => Box[Int]
) extends AgentRunIntervalService {

  override def getAgentRun : Box[AgentRunInterval] = {
    for {
      overrides   <- readOverrides()
      interval    <- readInterval()
      startHour   <- readStartHour()
      startMinute <- readStartMinute()
      splaytime   <- readSplaytime()
    } yield {
      AgentRunInterval(
          overrides
        , interval
        , startHour
        , startMinute
        , splaytime
      )
    }
  }
}

