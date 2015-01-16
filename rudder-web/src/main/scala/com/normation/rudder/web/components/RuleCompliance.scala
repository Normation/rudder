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

package com.normation.rudder.web.components

import com.normation.rudder.domain.policies._
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.NodeInfo
import scala.xml._
import net.liftweb.http._
import net.liftweb.common._
import com.normation.rudder.domain.reports._
import net.liftweb.util.Helpers._
import net.liftweb.util.Helpers
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.model.WBTextField
import com.normation.rudder.web.model.WBTextAreaField
import com.normation.rudder.web.model.WBSelectField
import com.normation.rudder.web.services.ComplianceData
import com.normation.rudder.rule.category.RuleCategory
import org.joda.time.DateTime
import org.joda.time.format.PeriodFormatterBuilder
import org.joda.time.Interval
import com.normation.rudder.web.services.ReportLine
import com.normation.rudder.web.services.ChangeLine
import com.normation.rudder.services.reports.NodeChanges
import net.liftweb.http.js.JsExp
import net.liftweb.http.js.JsObj



object RuleCompliance {

  private def details =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil)
    } yield {
      chooseTemplate("component", "details", xml)
    }) openOr Nil

}

/**
 *   This component display the compliance of a Rule by showing compliance of every Directive
 *   It generates all Data and put them in a DataTable
 */

class RuleCompliance (
    rule : Rule
  , directiveLib    : FullActiveTechniqueCategory
  , allNodeInfos    : Map[NodeId, NodeInfo]
  , rootRuleCategory: RuleCategory
) extends Loggable {

  private[this] val reportingService = RudderConfig.reportingService
  private[this] val recentChangesService = RudderConfig.recentChangesService
  private[this] val categoryService  = RudderConfig.ruleCategoryService

  //fresh value when refresh
  private[this] val roRuleRepository    = RudderConfig.roRuleRepository
  private[this] val getFullDirectiveLib = RudderConfig.roDirectiveRepository.getFullDirectiveLibrary _
  private[this] val getAllNodeInfos     = RudderConfig.nodeInfoService.getAll _

  import RuleCompliance._

  def display : NodeSeq = {

    (
      "#ruleName" #>   rule.name &
      "#ruleCategory" #> categoryService.shortFqdn(rootRuleCategory, rule.categoryId) &
      "#rudderID" #> rule.id.value.toUpperCase &
      "#ruleShortDescription" #> rule.shortDescription &
      "#ruleLongDescription" #>  rule.longDescription &
      "#compliancedetails" #> showCompliance
    )(details)
  }

  /*
   * For each table : the subtable is contained in td : details
   * when + is clicked: it gets the content of td details then process it
   * as a datatable
   */
  def showCompliance : NodeSeq = {

    ( for {
      reports <- reportingService.findDirectiveRuleStatusReportsByRule(rule.id)
      changesOnRule <- recentChangesService.getChangesByInterval().map(NodeChanges.changesOnRule(rule.id))
    } yield {

        val complianceByDirective = ComplianceData.getRuleByDirectivesComplianceDetails(reports, rule, allNodeInfos, directiveLib).json
        val complianceByNode = ComplianceData.getRuleByNodeComplianceDetails(directiveLib, reports, allNodeInfos).json

       // val changes = NodeChanges.changesByPeriod(changesOnRule)
        val changesData = NodeChanges.json(changesOnRule)
        val changesLine = ChangeLine.jsonByInterval(changesOnRule, Some(rule.name), directiveLib, allNodeInfos)
        val changesArray = changesLine.in.toList.flatMap{case a:JsArray => a.in.toList; case _ => Nil}
        val allChanges = JsArray(changesArray)
       <div id="directiveComplianceSection" class="unfoldedSection" onclick="$('#directiveCompliance').toggle(400); $('#directiveComplianceSection').toggleClass('foldedSection');$('#directiveComplianceSection').toggleClass('unfoldedSection');">
         <div class="section-title">Compliance by Directive</div>
       </div>
       <div id="directiveCompliance">
         <table id="reportsGrid" cellspacing="0">  </table>
      </div>
        <div id="nodeComplianceSection" class="unfoldedSection" onclick="$('#nodeCompliance').toggle(400); $('#nodeComplianceSection').toggleClass('foldedSection');$('#nodeComplianceSection').toggleClass('unfoldedSection');">
         <div class="section-title">Compliance by Node</div>
       </div>
       <div id="nodeCompliance">
         <table id="nodeReportsGrid" cellspacing="0">  </table>
        </div>
        <div id="recentChangesSection" class="unfoldedSection" onclick="$('#recentChanges').toggle(400); $('#recentChangesSection').toggleClass('foldedSection');$('#recentChangesSection').toggleClass('unfoldedSection');">
         <div class="section-title">Recent changes</div>
       </div>
       <div id="recentChanges">
         <div id="changesChart">  </div>
          <hr class="spacer" />
         <table id="changesGrid" cellspacing="0">  </table>
        </div> ++
        Script(After(0,(JsRaw(s"""
          createDirectiveTable(true, false, "${S.contextPath}")("reportsGrid",${complianceByDirective.toJsCmd},${refresh().toJsCmd});
          createNodeComplianceTable("nodeReportsGrid",${complianceByNode.toJsCmd},"${S.contextPath}", ${refresh(false).toJsCmd});
          var recentChanges = ${changesData.toJsCmd};
          var changes = ${changesLine.toJsCmd};
          var data = recentChanges.y
          data.splice(0,0,'Recent changes')
          var x = recentChanges.x
          x.splice(0,0,'x')
          createChangesTable("changesGrid",${allChanges.toJsCmd},"${S.contextPath}");
          var chart = c3.generate({
            data: {
                  x: 'x'
                , columns: [ x , data ]
                , type: 'bar'
                , onclick: function (d, element) {
                    var res = changes[d.index];
                    refreshTable("changesGrid",res);
                  }
              }
            , bar: {
                  width: {
                      ratio: 1 // this makes bar width 50% of length between ticks
                  }
              }
            , axis: {
                  x: {
                      type: 'categories'
                  }
              }
            , grid: {
                  x: { show: true }
                , y: { show: true }
              }
          } );
          $$('#changesChart').append(chart.element);
          createTooltip();""")) ))
      } ) match {
      case Full(xml) => xml
      case _ => <div class="error">Error while fetching report information</div>
    }
  }

  def refresh(refreshDirectiveData : Boolean = true) = {
     val ajaxCall = SHtml.ajaxCall(JsNull, (s) => {
        val result : Box[String] = for {
            reports <- reportingService.findDirectiveRuleStatusReportsByRule(rule.id)
            updatedRule <- roRuleRepository.get(rule.id)
            updatedNodes <- getAllNodeInfos().map(_.toMap)
            updatedDirectives <- getFullDirectiveLib()
        } yield {
          if (refreshDirectiveData) {
            ComplianceData.getRuleByDirectivesComplianceDetails(reports, updatedRule, allNodeInfos, directiveLib).json.toJsCmd
          } else {
            ComplianceData.getRuleByNodeComplianceDetails(directiveLib, reports, allNodeInfos).json.toJsCmd
          }
        }

        val id = if (refreshDirectiveData) {
          "reportsGrid"
        } else {
          "nodeReportsGrid"
        }
        JsRaw(s"""refreshTable("${id}",${result.getOrElse("[]")});
               createTooltip();""")
     })

     AnonFunc("",ajaxCall)
  }
}

