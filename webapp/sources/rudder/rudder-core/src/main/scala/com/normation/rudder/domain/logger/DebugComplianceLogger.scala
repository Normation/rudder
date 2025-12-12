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

import com.github.benmanes.caffeine.cache.CacheLoader
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import com.normation.NamedZioLogger
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.NodeConfigIdInfo
import com.normation.rudder.domain.reports.NodeExpectedReports
import com.normation.rudder.domain.reports.RunAnalysis
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.ResolvedAgentRunInterval
import com.normation.rudder.services.reports.*
import com.normation.utils.Utils.DateToIsoString
import java.util.concurrent.TimeUnit
import net.liftweb.common.Logger
import org.slf4j
import org.slf4j.LoggerFactory

/**
 * Log information about compliance.
 * This log is intended to be used in debug & trace level.
 * It allows to get trace explaining why the compliance is what it
 * is.
 */
object ComplianceDebugLoggerPure extends NamedZioLogger {
  override def loggerName: String = "explain_compliance"
}

object ComplianceDebugLogger extends Logger {
  override protected def _logger: slf4j.Logger = LoggerFactory.getLogger("explain_compliance")

  // we have one logger defined by node.
  // they automatically expires after some time.

  val nodeCache: LoadingCache[String, Logger] = Caffeine
    .newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build(
      new CacheLoader[String, Logger]() {
        def load(key: String): Logger = {
          new Logger() {
            override protected def _logger = LoggerFactory.getLogger(s"explain_compliance.${key}")
          }
        }
      }
    )

  def node(id: NodeId): Logger = nodeCache.get(id.value)

  implicit class RunAnalysisInfoToLog(val c: RunAnalysis) extends AnyVal {
    import com.normation.rudder.domain.reports.RunAnalysisKind.*

    def logId:  String = {
      val configId = c.expectedConfigId.fold("unknown id")(_.value)
      val start    = c.expectedConfigStart.fold("unknown start time")(_.toIsoStringNoMillis)
      val end      = c.expirationDateTime.fold("unknown expiration date")(_.toIsoStringNoMillis)

      s"${configId}/[${start}-${end}]"
    }
    def logRun: String = {
      val configId = c.lastRunConfigId.fold("none or too old")(i => s"configId: ${i.value}")
      val date     = c.lastRunDateTime.fold("unknown")(_.toIsoStringNoMillis)
      val exp      = c.lastRunExpiration.fold("")(r => s" | expired at ${r.toIsoStringNoMillis}")

      s"${configId} received at: ${date}${exp}"
    }

    def logDetails: String = c.kind match {
      case NoRunNoExpectedReport =>
        "expected NodeConfigId: not found | last run: not found"

      case NoExpectedReport =>
        s"expected NodeConfigId: not found |" +
        s" last run: nodeConfigId: ${c.lastRunConfigId.fold("none")(_.value)} received at ${c.lastRunDateTime.fold("undef")(_.toIsoStringNoMillis)}"

      case NoReportInInterval =>
        s"expected NodeConfigId: ${c.logId} |" +
        s" last run: none available (or too old)"

      case KeepLastCompliance =>
        s"expected NodeConfigId: ${c.logId} |" +
        s" last: ${c.logRun} ; expired but will be kept until ${c.expirationDateTime.fold("unknown")(_.toIsoStringNoMillis)}"

      case ReportsDisabledInInterval =>
        s"expected NodeConfigId: ${c.logId} |" +
        s" last run: none available (compliance mode is reports-disabled)]"

      case _ =>
        s"expected NodeConfigId: ${c.logId} |" +
        s" last run: ${c.logRun}"
    }

    def toLog: String = s"${c.kind.entryName}: ${logDetails}"
  }

  implicit class NodeConfigIdInfoToLog(val n: NodeConfigIdInfo)      extends AnyVal {
    def toLog: String =
      s"${n.configId.value}/[${n.creation.toIsoStringNoMillis}-${n.endOfLife.fold("now")(_.toIsoStringNoMillis)}]"
  }
  implicit class NodeExpectedConfigToLog(val n: NodeExpectedReports) extends AnyVal {
    def toLog: String =
      s"${n.nodeConfigId.value}/[${n.beginDate.toIsoStringNoMillis}-${n.endDate.fold("now")(_.toIsoStringNoMillis)}]"
  }

  implicit class RunAndConfigInfoToLog(val c: RunAndConfigInfo) extends AnyVal {

    def logDetails: String = c match {
      case NoRunNoExpectedReport =>
        "expected NodeConfigId: not found | last run: not found"

      case NoExpectedReport(lastRunDateTime, lastRunConfigId) =>
        s"expected NodeConfigId: not found |" +
        s" last run: nodeConfigId: ${lastRunConfigId.fold("none")(_.value)} received at ${lastRunDateTime.toIsoStringNoMillis}"

      case NoReportInInterval(expectedConfigId, _) =>
        s"expected NodeConfigId: ${expectedConfigId.toLog}|" +
        s" last run: none available (or too old)"

      case KeepLastCompliance(expectedConfigId, expirationDateTime, keepUntil, optLastRun) =>
        val run = optLastRun match {
          case Some(r) => s"nodeConfigId: ${r._2.nodeConfigId.value} received at ${r._1.toIsoStringNoMillis}"
          case None    => "unknown"
        }
        s"expected NodeConfigId: ${expectedConfigId.toLog}|" +
        s" last: ${run} ; expired at ${expirationDateTime.toIsoStringNoMillis} but will be kept until ${keepUntil.toIsoStringNoMillis}"

      case ReportsDisabledInInterval(expectedConfigId, _) =>
        s"expected NodeConfigId: ${expectedConfigId.toLog}|" +
        s" last run: none available (compliance mode is reports-disabled)]"

      case Pending(expectedConfigId, optLastRun, expirationDateTime) =>
        s"until ${expirationDateTime.toIsoStringNoMillis} expected NodeConfigId: ${expectedConfigId.toLog} |" +
        s" last run: ${optLastRun
            .fold("none (or too old)")(x => s"nodeConfigId: ${x._2.toLog} received at ${x._1.toIsoStringNoMillis}")}"

      case UnexpectedVersion(lastRunDateTime, Some(lastRunConfigInfo), lastRunExpiration, expectedConfigId, _, _) =>
        s"expected NodeConfigId: ${expectedConfigId.toLog} |" +
        s" last run: nodeConfigInfo: ${lastRunConfigInfo.toLog} received at ${lastRunDateTime.toIsoStringNoMillis} |" +
        s" expired at ${lastRunExpiration.toIsoStringNoMillis}"

      case UnexpectedUnknownVersion(lastRunDateTime, lastRunConfigId, expectedConfig, expectedExpiration, _) =>
        s"expected NodeConfigId: ${expectedConfig.toLog} |" +
        s" last run: nodeConfigId: ${lastRunConfigId.value} received at ${lastRunDateTime.toIsoStringNoMillis} |" +
        s" expired at ${expectedExpiration.toIsoStringNoMillis}"

      case NoUserRulesDefined(lastRunDateTime, expectedConfig, lastRunConfigId, lastRunConfigInfo, _) =>
        s"expected NodeConfigId: ${expectedConfig.toLog} |" +
        s" last run: nodeConfigId: ${lastRunConfigId.value} received at ${lastRunDateTime.toIsoStringNoMillis} |" +
        s" last run config: ${lastRunConfigInfo.fold("none")(_.toLog)}"

      case UnexpectedNoVersion(lastRunDateTime, _, lastRunExpiration, expectedConfig, _, _) =>
        s"expected NodeConfigId: ${expectedConfig.toLog} |" +
        s" last run: no configId, received at ${lastRunDateTime.toIsoStringNoMillis} |" +
        s" expired at ${lastRunExpiration.toIsoStringNoMillis}"

      case x @ ComputeCompliance(lastRunDateTime, expectedConfig, expirationDateTime) =>
        s"expected NodeConfigId: ${expectedConfig.toLog} |" +
        s" last run: nodeConfigId: ${x.lastRunConfigId.value} received at ${lastRunDateTime.toIsoStringNoMillis} |" +
        s" expired at ${expirationDateTime.toIsoStringNoMillis}"
    }

    def logName: String = c match {
      case NoRunNoExpectedReport => "NoRunNoExpectedReport"
      case _: NoExpectedReport          => "NoRunNoExpectedReport"
      case _: NoReportInInterval        => "NoReportInInterval"
      case _: UnexpectedVersion         => "UnexpectedVersion"
      case _: UnexpectedNoVersion       => "UnexpectedNoVersion"
      case _: UnexpectedUnknownVersion  => "UnexpectedUnknowVersion"
      case _: ReportsDisabledInInterval => "ReportsDisabledInInterval"
      case _: Pending                   => "Pending"
      case _: ComputeCompliance         => "ComputeCompliance"
      case _: NoUserRulesDefined        => "NoUserRulesDefined"
      case _: KeepLastCompliance        => "KeepLastCompliance"
    }

    def toLog: String = logName + ": " + logDetails
  }

  implicit class AgentRunConfigurationToLog(val info: (NodeId, ComplianceMode, ResolvedAgentRunInterval)) extends AnyVal {

    private def log(c: ComplianceMode, r: ResolvedAgentRunInterval): String = {
      s"run interval: ${r.interval.toStandardMinutes.getMinutes} min"
    }

    def toLog: String = {
      val (id, c, r) = info
      s"[${id.value}:${c.name}, ${log(c, r)}]"
    }
  }

}
