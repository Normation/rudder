/*
*************************************************************************************
* Copyright 2015 Normation SAS
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

package com.normation.rudder.domain.logger

import org.slf4j.LoggerFactory
import net.liftweb.common.Logger
import com.normation.rudder.services.reports._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ResolvedAgentRunInterval
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ChangesOnly
import java.util.concurrent.TimeUnit
import com.normation.rudder.domain.reports.NodeConfigIdInfo
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.CacheLoader

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

  val nodeCache = Caffeine.newBuilder().maximumSize(1000).expireAfterWrite(10, TimeUnit.MINUTES).build(
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
  implicit class NodeExpectedConfigToLog(n: NodeExpectedReports) {
    val toLog: String = s"${n.nodeConfigId.value}/[${n.beginDate}-${n.endDate.fold("now")(_.toString)}]"
  }

  implicit class RunAndConfigInfoToLog(c: RunAndConfigInfo) {

    val logDetails: String = c match {
      case NoRunNoExpectedReport(msg) =>
        "expected NodeConfigId: not found | last run: not found"

      case NoExpectedReport(msg, lastRunDateTime, lastRunConfigId) =>
         s"expected NodeConfigId: not found |"+
         s" last run: nodeConfigId: ${lastRunConfigId.fold("none")(_.value)} received at ${lastRunDateTime}"

      case NoReportInInterval(msg, expectedConfigId) =>
         s"expected NodeConfigId: ${expectedConfigId.toLog}|"+
         s" last run: none available (or too old)"

      case ReportsDisabledInInterval(msg, expectedConfigId) =>
         s"expected NodeConfigId: ${expectedConfigId.toLog}|"+
         s" last run: none available (compliance mode is reports-disabled)]"

      case Pending(msg, expectedConfigId, optLastRun, expirationDateTime) =>
        s"until ${expirationDateTime} expected NodeConfigId: ${expectedConfigId.toLog} |"+
        s" last run: ${optLastRun.fold("none (or too old)")(x => s"${x._2.fold("no configuration id for run")(y => s"nodeConfigId: ${y.toLog}")} received at ${x._1}")}"

      case UnexpectedVersion(msg, lastRunDateTime, Some(lastRunConfigInfo), lastRunExpiration, expectedConfigId, expectedExpiration) =>
        s"expected NodeConfigId: ${expectedConfigId.toLog} |"+
        s" last run: nodeConfigInfo: ${lastRunConfigInfo.toLog} received at ${lastRunDateTime} |"+
        s" expired at ${lastRunExpiration}"

      case UnexpectedUnknowVersion(msg, lastRunDateTime, lastRunConfigId, expectedConfig, expectedExpiration) =>
        s"expected NodeConfigId: ${expectedConfig.toLog} |"+
        s" last run: nodeConfigId: ${lastRunConfigId.value} received at ${lastRunDateTime} |"+
        s" expired at ${expectedExpiration}"

      case UnexpectedNoVersion(msg, lastRunDateTime, lastRunConfigId, lastRunExpiration, expectedConfig, expectedExpiration) =>
        s"expected NodeConfigId: ${expectedConfig.toLog} |"+
        s" last run: no configId, received at ${lastRunDateTime} |"+
        s" expired at ${lastRunExpiration}"

      case x@ComputeCompliance(msg, lastRunDateTime, expectedConfig, expirationDateTime) =>
        s"expected NodeConfigId: ${expectedConfig.toLog} |"+
        s" last run: nodeConfigId: ${x.lastRunConfigId.value} received at ${lastRunDateTime} |"+
        s" expired at ${expirationDateTime}"

    }

    val toLog: String = c.name + ": " + logDetails
  }

  implicit class AgentRunConfigurationToLog(info: (NodeId, ComplianceMode, ResolvedAgentRunInterval)) {

    private[this] def log(c: ComplianceMode, r: ResolvedAgentRunInterval): String = {
      val h = c.mode match {
        case ChangesOnly => s", hearbeat every ${r.heartbeatPeriod} run(s)"
        case _ => ""
      }
      s"run interval: ${r.interval.toStandardMinutes.getMinutes} min${h}"
    }

    val (id, c, r) = info

    val toLog: String = {
      s"[${id.value}:${c.name}, ${log(c, r)}]"
    }
  }

}
