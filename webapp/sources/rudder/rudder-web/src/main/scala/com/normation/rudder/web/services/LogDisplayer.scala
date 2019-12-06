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

import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.web.model._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.reports.Reports
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js._
import JsCmds._
import JE._

import scala.collection._
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.repository.ReportsRepository
import com.normation.rudder.repository.RoDirectiveRepository
import com.normation.rudder.repository.RoRuleRepository
import org.joda.time.DateTime
import com.normation.cfclerk.xmlparsers.CfclerkXmlConstants.DEFAULT_COMPONENT_KEY
import net.liftweb.json.JsonAST.JString
import org.joda.time.format.DateTimeFormat
import com.normation.rudder.web.ChooseTemplate
import com.normation.box._
import com.normation.rudder.web.components.DateFormaterService

/**
 * Show the reports from cfengine (raw data)
 */
class LogDisplayer(
    reportRepository   : ReportsRepository
  , directiveRepository: RoDirectiveRepository
  , ruleRepository     : RoRuleRepository
) extends Loggable {

  private def content = ChooseTemplate(
      List("templates-hidden", "node_logs_tabs")
    , "logs-content"
  )

  def ajaxRefresh(nodeId : NodeId, runDate : Option[DateTime], tableId : String)  = {
    runDate match {
      case Some(runDate) => SHtml.ajaxInvoke(() => refreshData(nodeId, reportRepository.findReportsByNodeByRun(nodeId, runDate).filter(_.severity.startsWith("log")), tableId))
      case None => SHtml.ajaxInvoke(() => refreshData(nodeId, reportRepository.findReportsByNode(nodeId), tableId))
    }
  }

  def asyncDisplay(nodeId : NodeId, runDate : Option[DateTime], tableId :String)  = {
    val id = JsNodeId(nodeId)
    val refresh = ajaxRefresh(nodeId,runDate, tableId)
    def getEventsInterval(jsonInterval: String): JsCmd = {
      import net.liftweb.util.Helpers.tryo
      import net.liftweb.json.parse

      val format = DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss")

      (for {
        parsed   <- tryo(parse(jsonInterval)) ?~! s"Error when trying to parse '${jsonInterval}' as a JSON datastructure with fields 'start' and 'end'"
        startStr <- parsed \ "start" match {
                      case JString("")       => Full(None)
                      case JString(startStr) => tryo(Some(DateTime.parse(startStr, format))) ?~! s"Error when trying to parse start date '${startStr}"
                      case _                 => Failure("Invalid value for start date")
                    }
        endStr   <- parsed \ "end" match {
                      case JString("")     => Full(None)
                      case JString(endStr) => tryo(Some(DateTime.parse(endStr, format))) ?~! s"Error when trying to parse end date '${endStr}"
                      case _               => Failure("Invalid value for end date")
                    }
      } yield {
        (startStr, endStr) match {
          case (Some(startValue),Some(endValue)) if startValue.isAfter(endValue) =>
            reportRepository.findReportsByNodeOnInterval(nodeId, endStr, startStr)
          case _ =>
            reportRepository.findReportsByNodeOnInterval(nodeId, startStr, endStr)
        }
      }) match {
        case Full(reports) =>
          refreshData(nodeId, reports, tableId)
        case eb : EmptyBox =>
          val fail = eb ?~! "Could not get latest event logs"
          logger.error(fail.messageChain)
          val xml = <div class="error">Error when trying to get last event logs. Error message was: {fail.messageChain}</div>
          SetHtml("eventLogsError",xml)
      }
    }

    ( if (runDate.isEmpty) {
      // set static content
      SetHtml("logsDetails", content)
    } else {
      Noop
    } ) &
      // Create empty table
      JsRaw(s"""
    ${if (runDate.isEmpty) {
        s"""$$("#details_${id}").on( "tabsactivate", function(event, ui) {
        if(ui.newPanel.attr('id')== 'node_logs') {
          ${refresh.toJsCmd}
        }
      });
      initDatePickers("#filterLogs", ${AnonFunc("param",SHtml.ajaxCall(JsVar("param"), getEventsInterval)._2).toJsCmd});
      """} else ""
      }
    createTechnicalLogsTable("${tableId}",[], "${S.contextPath}",function() {${refresh.toJsCmd}}, ${runDate.isEmpty});
    """
      )
  }

  /**
   * find all reports for node passed as parameter and transform them into table data
   */
  def getReportsLineForNode (nodeId : NodeId, reports: Seq[Reports]) = {
    val directiveMap = mutable.Map[DirectiveId, String]()
    val ruleMap = mutable.Map[RuleId, String]()

    def getDirectiveName(directiveId : DirectiveId) : String = {
      directiveMap.getOrElse(directiveId, {
        val result = directiveRepository.getDirective(directiveId).map(_.map(_.name).getOrElse(directiveId.value) ).toBox.openOr(directiveId.value)
        directiveMap += ( directiveId -> result)
        result
      })
    }

    def getRuleName(ruleId : RuleId) : String = {
      ruleMap.get(ruleId).getOrElse({val result = ruleRepository.get(ruleId).map(x => x.name).toBox.openOr(ruleId.value); ruleMap += ( ruleId -> result); result } )
    }

    val lines = {
      for {
        report <- reports
      } yield {

        val ruleName = getRuleName(report.ruleId)

        val directiveName = getDirectiveName(report.directiveId)

        val value = if (DEFAULT_COMPONENT_KEY == report.keyValue) "-" else report.keyValue

        ReportLine (
            report.executionDate
          , report.executionTimestamp
          , report.severity
          , ruleName
          , directiveName
          , report.component
          , value
          , report.message
        )
      }
    }

    JsTableData(lines.toList)

  }

  def refreshData(nodeId : NodeId, reports: => Seq[Reports], tableId : String) : JsCmd = {

    val data = getReportsLineForNode(nodeId, reports).json.toJsCmd

    OnLoad(JsRaw(s"""refreshTable("${tableId}",${data});""")
    )
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
final case class ReportLine (
    executionDate  : DateTime
  , runDate        : DateTime
  , severity       : String
  , ruleName       : String
  , directiveName  : String
  , component      : String
  , value          : String
  , message        : String
) extends JsTableLine {

    override val json  = {

      JsObj(
          ( "executionDate", DateFormaterService.getDisplayDate(executionDate) )
        , ( "runDate"      , DateFormaterService.getDisplayDate(runDate) )
        , ( "severity"     , severity )
        , ( "ruleName"     , escapeHTML(ruleName) )
        , ( "directiveName", escapeHTML(directiveName) )
        , ( "component"    , escapeHTML(component) )
        , ( "value"        , escapeHTML(value) )
        , ( "message"      , escapeHTML(message) )
      )

    }
}
