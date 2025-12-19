/*
 *************************************************************************************
 * Copyright 2014 Normation SAS
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

package com.normation.rudder.reports

import enumeratum.*
import net.liftweb.common.*
import org.joda.time.Duration
import zio.json.*

/**
 * Class that contains all relevant information about the reporting configuration:
 * * agent run interval
 * * reporting protocol
 *
 * Everything is option because that information is at the node level and
 * may not be defined (for example if the node only use default global values)
 *
 * For the node "resolved reportingconfiguration", see:
 */
final case class ReportingConfiguration(
    agentRunInterval:       Option[AgentRunInterval],
    agentReportingProtocol: Option[AgentReportingProtocol]
)

final case class AgentRunInterval(
    overrides:   Option[Boolean],
    interval:    Int, // in minute
    startMinute: Int,
    startHour:   Int,
    splaytime:   Int
)

object AgentRunInterval {
  implicit val codecAgentRunInterval: JsonCodec[AgentRunInterval] = DeriveJsonCodec.gen
}

final case class ResolvedAgentRunInterval(interval: Duration)

trait AgentRunIntervalService {
  def getGlobalAgentRun(): Box[AgentRunInterval]
}

class AgentRunIntervalServiceImpl(
    readGlobalInterval:    () => Box[Int],
    readGlobalStartHour:   () => Box[Int],
    readGlobalStartMinute: () => Box[Int],
    readGlobalSplaytime:   () => Box[Int]
) extends AgentRunIntervalService {

  override def getGlobalAgentRun(): Box[AgentRunInterval] = {
    for {
      interval    <- readGlobalInterval()
      startHour   <- readGlobalStartHour()
      startMinute <- readGlobalStartMinute()
      splaytime   <- readGlobalSplaytime()
    } yield {
      AgentRunInterval(
        None,
        interval,
        startMinute,
        startHour,
        splaytime
      )
    }
  }
}

sealed trait AgentReportingProtocol extends EnumEntry {
  def value: String
}

case object AgentReportingHTTPS extends AgentReportingProtocol {
  val value = "HTTPS"
}

object AgentReportingProtocol extends Enum[AgentReportingProtocol] {
  val values:               IndexedSeq[AgentReportingProtocol]     = findValues
  def parse(value: String): Either[String, AgentReportingProtocol] = {
    values.find(_.value == value.toUpperCase()) match {
      case None           =>
        Left(
          s"Unable to parse reporting protocol mame '${value}'. was expecting ${values.map(_.value).mkString("'", "' or '", "'")}."
        )
      case Some(protocol) =>
        Right(protocol)
    }
  }
}
