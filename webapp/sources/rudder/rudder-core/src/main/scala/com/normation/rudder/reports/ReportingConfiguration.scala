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

import ca.mrvisser.sealerate.values
import com.normation.errors.RudderError
import com.normation.errors.Unexpected
import com.normation.utils.HashcodeCaching
import net.liftweb.common._
import org.joda.time.Duration
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.domain.Constants

/**
 * Class that contains all relevant information about the reporting configuration:
 * * agent run interval
 * * compliance mode (soon)
 * * heartbeat configuration
 *
 * Everything is option because that information is at the node level and
 * may not be defined (for example if the node only use default global values)
 *
 * For the node "resolved reportingconfiguration", see:
 */
final case class ReportingConfiguration(
    agentRunInterval      : Option[AgentRunInterval]
  , heartbeatConfiguration: Option[HeartbeatConfiguration]
  , agentReportingProtocol: Option[AgentReportingProtocol]
) extends HashcodeCaching

final case class HeartbeatConfiguration(
    overrides      : Boolean
  , heartbeatPeriod: Int
)

final case class AgentRunInterval(
    overrides  : Option[Boolean]
  , interval   : Int //in minute
  , startMinute: Int
  , startHour  : Int
  , splaytime  : Int)
extends HashcodeCaching {

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

final case class ResolvedAgentRunInterval(
  interval: Duration, heartbeatPeriod: Int)

trait AgentRunIntervalService {
  def getGlobalAgentRun(): Box[AgentRunInterval]

  /**
   * For each node Id passed as argument, find the corresponding
   * run interval and heartbeat value (using global default if needed)
   */
  def getNodeReportingConfigurations(nodeIds: Set[NodeId]): Box[Map[NodeId, ResolvedAgentRunInterval]]

}

class AgentRunIntervalServiceImpl (
    nodeInfoService: NodeInfoService
  , readGlobalInterval: () => Box[Int]
  , readGlobalStartHour: () => Box[Int]
  , readGlobalStartMinute: () => Box[Int]
  , readGlobalSplaytime: () => Box[Int]
  , readGlobalHeartbeat: () => Box[Int]
) extends AgentRunIntervalService {

  override def getGlobalAgentRun(): Box[AgentRunInterval] = {
    for {
      interval <- readGlobalInterval()
      startHour <- readGlobalStartHour()
      startMinute <- readGlobalStartMinute()
      splaytime <- readGlobalSplaytime()
    } yield {
      AgentRunInterval(
        None, interval, startHour, startMinute, splaytime
      )
    }
  }

  override def getNodeReportingConfigurations(nodeIds: Set[NodeId]): Box[Map[NodeId, ResolvedAgentRunInterval]] = {
    for {
      gInterval  <- readGlobalInterval()
      gHeartbeat <- readGlobalHeartbeat()
      nodeInfos  <- nodeInfoService.getAll()
    } yield {
      nodeIds.map { nodeId =>

        if (nodeId == Constants.ROOT_POLICY_SERVER_ID) {
          //special case. The root policy server always run each 5 minutes
          (nodeId, ResolvedAgentRunInterval(Duration.standardMinutes(5), 1))
        } else {
          val node = nodeInfos.get(nodeId)
          val run: Int = node.flatMap {
            _.nodeReportingConfiguration.agentRunInterval.flatMap(x =>
              if (x.overrides.getOrElse(false)) Some(x.interval) else None)
          }.getOrElse(gInterval)
          val heartbeat = node.flatMap {
            _.nodeReportingConfiguration.heartbeatConfiguration.flatMap(x =>
              if (x.overrides) Some(x.heartbeatPeriod) else None)
          }.getOrElse(gHeartbeat)

          (nodeId, ResolvedAgentRunInterval(Duration.standardMinutes(run.toLong), heartbeat))
        }
      }.toMap
    }
  }

}

import ca.mrvisser.sealerate.values

sealed trait SyslogProtocol {
  def value : String
}

object SyslogProtocol {
  def apply(value: String): Box[SyslogProtocol] = {
    value match {
      case SyslogTCP.value => Full(SyslogTCP)
      case SyslogUDP.value => Full(SyslogUDP)
      case _ => Failure(s"Invalid syslog protocol: *{value}")
    }
  }

  def allProtocols: Set[SyslogProtocol] = values[SyslogProtocol]

  def parse(value: String): Either[RudderError, SyslogProtocol] = {
    allProtocols.find {
      _.value == value.toUpperCase()
    } match {
      case None =>
        Left(Unexpected(s"Unable to parse syslog protocol mame '${value}'. was expecting ${allProtocols.map(_.value).mkString("'", "' or '", "'")}."))
      case Some(protocol) =>
        Right(protocol)
    }
  }

}

final case object SyslogTCP extends SyslogProtocol {
  val value = "TCP"
}

final case object SyslogUDP extends SyslogProtocol {
  val value = "UDP"
}

sealed trait AgentReportingProtocol {
  def value : String
}

final case object AgentReportingHTTPS extends AgentReportingProtocol {
  val value = "HTTPS"
}

final case  object AgentReportingSyslog extends AgentReportingProtocol {
  val value = "SYSLOG"
}

object AgentReportingProtocol {
  def apply(value: String): Box[AgentReportingProtocol] = {
    value match {
      case AgentReportingHTTPS.value  => Full(AgentReportingHTTPS)
      case AgentReportingSyslog.value => Full(AgentReportingSyslog)
      case _                          => Failure(s"Invalid reporting protocol: *{value}")
    }
  }

  def allProtocols: Set[AgentReportingProtocol] = values[AgentReportingProtocol]
  def parse(value: String) : Either[RudderError, AgentReportingProtocol] = {
    allProtocols.find { _.value == value.toUpperCase() } match {
      case None =>
        Left(Unexpected(s"Unable to parse reporting protocol mame '${value}'. was expecting ${allProtocols.map(_.value).mkString("'", "' or '", "'")}."))
      case Some(protocol) =>
        Right(protocol)
    }
  }
}