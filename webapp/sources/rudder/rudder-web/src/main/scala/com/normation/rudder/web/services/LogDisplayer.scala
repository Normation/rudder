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

package com.normation.rudder.web.services

import com.normation.box.*
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY
import com.normation.inventory.domain.NodeId
import com.normation.rudder.configuration.ConfigurationRepository
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.reports.Reports
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.RoRuleRepository
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.model.*
import com.normation.utils.DateFormaterService
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.json.JsonAST.JString
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import scala.collection.*

/**
 * Show the reports from cfengine (raw data)
 */
class LogDisplayer(
    reportRepository: ReportsRepository,
    configRepository: ConfigurationRepository,
    ruleRepository:   RoRuleRepository
) extends Loggable {

  private def content = ChooseTemplate(
    List("templates-hidden", "node_logs_tabs"),
    "logs-content"
  )

  def ajaxRefresh(nodeId: NodeId, runDate: Option[DateTime], tableId: String): GUIDJsExp = {
    runDate match {
      case Some(runDate) =>
        SHtml.ajaxInvoke(() => {
          refreshData(
            nodeId,
            reportRepository.findReportsByNodeByRun(nodeId, runDate).filter(_.severity.startsWith("log")),
            tableId
          )
        })
      case None          => SHtml.ajaxInvoke(() => refreshData(nodeId, reportRepository.findReportsByNode(nodeId), tableId))
    }
  }

  def asyncDisplay(nodeId: NodeId, runDate: Option[DateTime], tableId: String): JsCmd = {
    val id      = JsNodeId(nodeId)
    val refresh = ajaxRefresh(nodeId, runDate, tableId)
    def getEventsInterval(jsonInterval: String): JsCmd = {
      import net.liftweb.util.Helpers.tryo
      import net.liftweb.json.parse

      val format = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")

      (for {
        parsed   <- tryo(
                      parse(jsonInterval)
                    ) ?~! s"Error when trying to parse '${jsonInterval}' as a JSON datastructure with fields 'start' and 'end'"
        startStr <- parsed \ "start" match {
                      case JString("")       => Full(None)
                      case JString(startStr) =>
                        tryo(Some(DateTime.parse(startStr, format))) ?~! s"Error when trying to parse start date '${startStr}"
                      case _                 => Failure("Invalid value for start date")
                    }
        endStr   <- parsed \ "end" match {
                      case JString("")     => Full(None)
                      case JString(endStr) =>
                        tryo(Some(DateTime.parse(endStr, format))) ?~! s"Error when trying to parse end date '${endStr}"
                      case _               => Failure("Invalid value for end date")
                    }
      } yield {
        (startStr, endStr) match {
          case (Some(startValue), Some(endValue)) if startValue.isAfter(endValue) =>
            reportRepository.findReportsByNodeOnInterval(nodeId, endStr, startStr)
          case _                                                                  =>
            reportRepository.findReportsByNodeOnInterval(nodeId, startStr, endStr)
        }
      }) match {
        case Full(reports) =>
          refreshData(nodeId, reports, tableId)
        case eb: EmptyBox =>
          val fail = eb ?~! "Could not get latest event logs"
          logger.error(fail.messageChain)
          val xml  = <div class="error">Error when trying to get last event logs. Error message was: {
            fail.msg
          }</div> // we don't want to let the user know about SQL error
          SetHtml("eventLogsError", xml)
      }
    }

    // Display 2 hours of logs before now
    val hoursBeforeNow = 2

    // Since JS need to have the same time zone as the technical logs data,
    // the date picker need an initial date in the correct timezone
    val (jsDateWithTimeZone, currentTimezone) = {
      val now = DateTime.now.withZone(DateTimeZone.getDefault)
      (now.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZ"), now.getZone.getID)
    }

    (if (runDate.isEmpty) {
       // set static content
       SetHtml("logsDetails", content)
     } else {
       Noop
     }) &
    // Create empty table
    JsRaw(s"""
    ${if (runDate.isEmpty) {
        s"""$$("#details_${id}").on( "tabsactivate", function(event, ui) {
        if(ui.newPanel.attr('id')== 'node_logs') {
          ${refresh.toJsCmd}
        }
      });
      initDatePickers(
        "#filterLogs",
        ${AnonFunc("param", SHtml.ajaxCall(JsVar("param"), getEventsInterval)._2).toJsCmd},
        changeTimezone(new Date('${jsDateWithTimeZone}'), '${currentTimezone}'),
        '${currentTimezone}',
        ${hoursBeforeNow}
      );
      """
      } else ""}
    createTechnicalLogsTable("${tableId}",[], "${S.contextPath}",function() {${refresh.toJsCmd}}, ${runDate.isEmpty});
    ${refresh.toJsCmd}
    """) // JsRaw ok, escaped
  }

  def refreshData(nodeId: NodeId, reports: => Seq[Reports], tableId: String): JsCmd = {
    def getDirectiveName(directiveId: DirectiveId): Box[String] = {
      configRepository
        .getDirective(directiveId)
        .map(_.map(_.directive.name).getOrElse(directiveId.serialize))
        .toBox
    }

    def getRuleName(ruleId: RuleId): Box[String] = {
      ruleRepository.get(ruleId).map(x => x.name).toBox
    }

    val data = LogDisplayer.getReportsLineForNode(reports, getDirectiveName, getRuleName)

    OnLoad(JsRaw(s"""refreshTable("${StringEscapeUtils.escapeEcmaScript(tableId)}",${data.toJson.toJsCmd});"""))
  }
}

object LogDisplayer {

  /**
   * find all reports for node passed as parameter and transform them into table data
   */
  def getReportsLineForNode(
      reports:       Seq[Reports],
      directiveName: DirectiveId => Box[String],
      ruleName:      RuleId => Box[String]
  ): JsTableData[ReportLine] = {
    val directiveMap = mutable.Map[DirectiveId, String]()
    val ruleMap      = mutable.Map[RuleId, String]()

    def getDirectiveName(directiveId: DirectiveId): String = {
      directiveMap.getOrElse(
        directiveId, {
          val result = directiveName(directiveId).openOr(directiveId.serialize)
          directiveMap += (directiveId -> result)
          result
        }
      )
    }

    def getRuleName(ruleId: RuleId): String = {
      ruleMap.getOrElse(
        ruleId, {
          val result = ruleName(ruleId).openOr(ruleId.serialize)
          ruleMap += (ruleId -> result)
          result
        }
      )
    }

    val lines = {
      for {
        report <- reports
      } yield {

        val ruleName = getRuleName(report.ruleId)

        val directiveName = getDirectiveName(report.directiveId)

        val value = if (DEFAULT_COMPONENT_KEY == report.keyValue) {
          "-"
        } else {
          report.keyValue
        }
        ReportLine(
          report.executionDate,
          report.executionTimestamp,
          report.severity,
          ruleName,
          directiveName,
          report.component,
          value,
          report.message
        )
      }
    }

    JsTableData(lines.toList)
  }

}

/*
 *   Javascript object containing all data to create a line in the DataTable
 *   { "executionDate" : Date report was executed [DateTime]
 *   , "severity" : Report severity [String]
 *   , "ruleName" : Rule name [String]
 *   , "directiveName": Directive name [String]
 *   , "component" : Report component [String]
 *   , "value" : Report value [String]
 *   , "message" : Report message [String]
 *   }
 */
final case class ReportLine(
    executionDate: DateTime,
    runDate:       DateTime,
    severity:      String,
    ruleName:      String,
    directiveName: String,
    component:     String,
    value:         String,
    message:       String
) extends JsTableLine {

  val (kind, status): (String, String) = {
    severity.split("_").toList match {
      case _ :: Nil     => (severity, severity)
      case head :: rest => (head, rest.mkString("_"))
      case Nil          => (severity, severity)
    }
  }

  def json(freshName: () => String): js.JsObj = {
    JsObj(
      ("executionDate", DateFormaterService.getDisplayDate(executionDate.withZone(DateTimeZone.UTC))),
      ("runDate", DateFormaterService.getDisplayDate(runDate.withZone(DateTimeZone.UTC))),
      ("kind", kind),
      ("status", status),
      ("ruleName", escapeHTML(ruleName)),
      ("directiveName", escapeHTML(directiveName)),
      ("component", escapeHTML(component)),
      ("value", escapeHTML(value)),
      ("message", escapeHTML(message))
    )
  }
}
