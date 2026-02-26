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

package com.normation.rudder.web.services

import com.normation.box.*
import com.normation.errors.IOResult
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.ComplianceLevel
import com.normation.rudder.domain.reports.ComplianceLevelSerialisation.*
import com.normation.rudder.domain.reports.RuleNodeStatusReport
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.services.reports.ReportingService
import net.liftweb.common.*
import net.liftweb.http.SHtml
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds.*
import net.liftweb.util.Helpers.TimeSpan
import org.apache.commons.text.StringEscapeUtils
import scala.concurrent.*
import scala.concurrent.ExecutionContext.Implicits.global
import zio.json.EncoderOps

class AsyncComplianceService(
    reportingService: ReportingService
) extends Loggable {

  // Trait containing all functions to compute compliance asynchronously
  // Compliance a grouped  by a Type
  private trait ComplianceBy[Kind] {
    // Rules and Nodes we are computing compliances on
    val nodeIds: Set[NodeId]
    val ruleIds: Set[RuleId]

    // get value from kind (id)
    def value(key: Kind): String

    // javascript data container, available in rudder-datatable.js
    val jsContainer: String

    // Compute compliance
    def computeCompliance(): IOResult[Map[Kind, Option[ComplianceLevel]]]

    final protected def toCompliance(id: Kind, reports: Iterable[RuleNodeStatusReport]): (Kind, Some[ComplianceLevel]) = {
      // BE CAREFUL: reports may be a SET - and it's likely that
      // some compliance will be equals. So change to seq.
      val compliance = {
        val c = ComplianceLevel.sum(reports.toSeq.map(_.compliance))
        // if compliance is exactly 0, we want to display a bar of "unknown"
        if (c.total == 0) c.copy(missing = 1) else c
      }
      (id, Some(compliance))
    }

    final protected def toComplianceWithMissing(compliance: ComplianceLevel): Some[ComplianceLevel] = {
      if (compliance.total == 0) {
        Some(compliance.copy(missing = 1))
      } else {
        Some(compliance)
      }
    }

    // Is the compliance empty (No nodes? no Rules ? )
    def empty: Boolean

    // Compute compliance level for all rules in  a future so it will be displayed asynchronously
    val futureCompliance: Future[Box[Map[Kind, Option[ComplianceLevel]]]] = {
      Future {
        if (empty) {
          Full(Map())
        } else {
          val start = System.currentTimeMillis
          for {
            compliances <- computeCompliance().toBox
            after        = System.currentTimeMillis
            _            = TimingDebugLogger.debug(s"computing compliance in Future took ${after - start}ms")
          } yield {
            compliances.withDefault(_ => None)
          }
        }
      }
    }
  }

  private class RuleCompliance(
      val nodeIds: Set[NodeId],
      val ruleIds: Set[RuleId]
  )(using qc: QueryContext)
      extends ComplianceBy[RuleId] {
    def value(key: RuleId): String = key.serialize
    val jsContainer: String  = "ruleCompliances"
    def empty:       Boolean = ruleIds.isEmpty

    // Compute compliance
    def computeCompliance(): IOResult[Map[RuleId, Option[ComplianceLevel]]] = {
      for {
        reports <- reportingService.findRuleNodeStatusReports(nodeIds, ruleIds)
      } yield {
        // We need to take values before flatmap, because we are using a Map here and will lose values on first level because keys are policyTypes
        // Using values at each level ensure that we still have all our data at the end
        val found      = reports.values.flatMap(_.reports.values).flatMap(_.reports).groupBy(_.ruleId).map {
          case (ruleId, reports) =>
            toCompliance(ruleId, reports)
        }
        // add missing elements with "None" compliance, see #7281, #8030, #8141, #11842
        val missingIds = ruleIds -- found.keySet
        found ++ (missingIds.map(id => (id, None)))
      }
    }
  }

  // Compute compliance from a defined kind
  private def compliance[Kind](kind: ComplianceBy[Kind], tableId: String): JsCmd = {
    SHtml.ajaxInvoke(() => {
      // Is my future completed ?
      if (kind.futureCompliance.isCompleted) {
        // Yes wait for result
        Await.result(kind.futureCompliance, scala.concurrent.duration.Duration.Inf) match {
          case Full(compliances) =>
            val bars = {
              for { (key, optCompliance) <- compliances } yield {
                val value             = kind.value(key)
                val displayCompliance = optCompliance
                  .map(_.toJsArray.toJson)
                  .getOrElse("""'<div class="text-muted text-center">no data available</div>'""")
                s"${kind.jsContainer}['${value}'] = ${displayCompliance};"
              }
            }
            JsRaw(
              s"""
              ${bars.mkString(";")}
              resortTable("${StringEscapeUtils.escapeEcmaScript(tableId)}")
              """
            ) // JsRaw ok, escaped

          case eb: EmptyBox =>
            val error = eb ?~! "error while fetching compliances"
            logger.error(error.messageChain)
            Alert(error.messageChain)
        }
      } else {
        After(TimeSpan(500), compliance(kind, tableId))
      }
    })
  }

  def complianceByRule(nodeIds: Set[NodeId], ruleIds: Set[RuleId], tableId: String)(using qc: QueryContext): JsCmd = {
    val kind = new RuleCompliance(nodeIds, ruleIds)
    compliance(kind, tableId)

  }
}
