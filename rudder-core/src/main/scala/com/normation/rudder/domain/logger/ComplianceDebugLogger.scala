/*
*************************************************************************************
* Copyright 2015 Normation SAS
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

package com.normation.rudder.domain.logger

import org.slf4j.LoggerFactory
import net.liftweb.common.Logger
import com.normation.rudder.services.reports._
import com.normation.rudder.repository.NodeConfigIdInfo
import com.normation.inventory.domain.NodeId
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ResolvedAgentRunInterval
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.ResolvedAgentRunInterval
import com.normation.rudder.reports.ChangesOnly
import com.google.common.cache.CacheBuilder
import java.util.concurrent.TimeUnit
import com.google.common.cache.CacheLoader


/**
 * Log information about compliance.
 * This log is intended to be used in debug & trace level.
 * It allows to get trace explaining why the compliance is what it
 * is.
 */
object ComplianceDebugLogger extends Logger {
  override protected def _logger = LoggerFactory.getLogger("explain_compliance")


  //we have one logger defined by node.
  //they automatically expires after some time.

  val nodeCache = CacheBuilder.newBuilder().maximumSize(1000).expireAfterWrite(10, TimeUnit.MINUTES).build(
                    new CacheLoader[String, Logger]() {
                      def load(key: String) = {
                        new Logger() {
                          override protected def _logger = LoggerFactory.getLogger(s"explain_compliance.${key}")
                        }
                      }
                    }
                  )

  def node(id: NodeId) : Logger = nodeCache.get(id.value)

  implicit class NodeConfigIdInfoToLog(n: NodeConfigIdInfo) {
    val toLog: String = s"${n.configId.value}/[${n.creation}-${n.endOfLife.fold("now")(_.toString)}]"
  }

  implicit class RunAndConfigInfoToLog(c: RunAndConfigInfo) {
    val toLog: String = c match {
      case NoRunNoInit =>
        "[NoRunNoInit: expected NodeConfigId: not found | last run: not found]"

      case VersionNotFound(lastRunDateTime, lastRunConfigId) =>
         s"[VersionNotFound: expected NodeConfigId: not found |"+
         s" last run: nodeConfigId: ${lastRunConfigId.fold("none")(_.value)} received at ${lastRunDateTime}]"

      case NoReportInInterval(expectedConfigId) =>
         s"[NoReportInInterval: expected NodeConfigId: ${expectedConfigId.toLog}|"+
         s" last run: none available (or too old)]"

      case Pending(expectedConfigId, optLastRun, expirationDateTime, missingStatus) =>
        s"[Pending: until ${expirationDateTime} expected NodeConfigId: ${expectedConfigId.toLog} |"+
        s" last run: ${optLastRun.fold("none (or too old)")(x => s"nodeConfigId: ${x._2.toLog} received at ${x._1}")}]"

      case UnexpectedVersion(lastRunDateTime, lastRunConfigId, lastRunExpiration, expectedConfigId, expectedExpiration) =>
        s"[UnexpectedVersion: expected NodeConfigId: ${expectedConfigId.toLog} |"+
        s" last run: nodeConfigId: ${lastRunConfigId.toLog} received at ${lastRunDateTime} |"+
        s" expire at ${lastRunExpiration}]"

      case ComputeCompliance(lastRunDateTime, lastRunConfigId, expectedConfigId, expirationDateTime, missingStatus) =>
        s"[CheckChanges: expected NodeConfigId: ${expectedConfigId.toLog} |"+
        s" last run: nodeConfigId: ${lastRunConfigId.toLog} received at ${lastRunDateTime} |"+
        s" expire at ${expirationDateTime}]"

    }

    val logName =  c match {
      case    NoRunNoInit       => "NoRunNoInit"
      case _: VersionNotFound   => "VersionNotFound"
      case _: NoReportInInterval=> "NoReportInInterval"
      case _: Pending           => "Pending"
      case _: UnexpectedVersion => "UnexpectedVersion"
      case _: ComputeCompliance => "ComputeCompliance"
    }
  }

  implicit class AgentRunConfigurationToLog(info: (NodeId, ComplianceMode, ResolvedAgentRunInterval)) {

    private[this] def log(c: ComplianceMode, r: ResolvedAgentRunInterval): String = {
      val h = c match {
        case FullCompliance => ""
        case ChangesOnly(_) => s", hearbeat every ${r.heartbeatPeriod} run(s)"
      }
      s"run interval: ${r.interval.toStandardMinutes.getMinutes} min${h}"
    }

    val (id, c, r) = info

    val toLog: String = {
      s"[${id.value}:${c.name}, ${log(c, r)}]"
    }
  }


}


