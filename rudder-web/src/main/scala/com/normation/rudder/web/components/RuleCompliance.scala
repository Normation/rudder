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
  , directiveLib : FullActiveTechniqueCategory
  , allNodeInfos : Map[NodeId, NodeInfo]
) extends Loggable {

  private[this] val reportingService = RudderConfig.reportingService
  private[this] val categoryService  = RudderConfig.ruleCategoryService

  //fresh value when refresh
  private[this] val roRuleRepository    = RudderConfig.roRuleRepository
  private[this] val getFullDirectiveLib = RudderConfig.roDirectiveRepository.getFullDirectiveLibrary _
  private[this] val getAllNodeInfos     = RudderConfig.nodeInfoService.getAll _

  import RuleCompliance._

  def display : NodeSeq = {

    (
      "#ruleName" #>   rule.name &
      "#ruleCategory" #> categoryService.shortFqdn(rule.categoryId) &
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

    reportingService.findDirectiveRuleStatusReportsByRule(rule.id) match {
      case e: EmptyBox => <div class="error">Error while fetching report information</div>
      case Full(reports) =>
        val data = ComplianceData.getRuleByDirectivesComplianceDetails(reports, rule, allNodeInfos, directiveLib).json.toJsCmd

        <div>
          <hr class="spacer" />
         <table id="reportsGrid" cellspacing="0">  </table>
        </div> ++
        Script(JsRaw(s"""
          createDirectiveTable(true, false, "${S.contextPath}")("reportsGrid",${data},${refresh().toJsCmd});
          createTooltip();
        """))
      }
  }

  def refresh() = {
     val ajaxCall = SHtml.ajaxCall(JsNull, (s) => {
        val result : Box[String] = for {
            reports <- reportingService.findDirectiveRuleStatusReportsByRule(rule.id)
            updatedRule <- roRuleRepository.get(rule.id)
            updatedNodes <- getAllNodeInfos().map(_.toMap)
            updatedDirectives <- getFullDirectiveLib()
        } yield {

          ComplianceData.getRuleByDirectivesComplianceDetails(reports, updatedRule, allNodeInfos, directiveLib).json.toJsCmd
        }

        JsRaw(s"""refreshTable("reportsGrid",${result.getOrElse("[]")});
               createTooltip();""")
     })

     AnonFunc("",ajaxCall)
  }
}

