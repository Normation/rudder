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

package com.normation.rudder.web.components

import com.normation.rudder.domain.policies._
import scala.xml._
import net.liftweb.http._
import net.liftweb.common._
import net.liftweb.util.Helpers._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmd
import bootstrap.liftweb.RudderConfig
import com.normation.rudder.web.services.ComplianceData
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.web.services.ChangeLine
import com.normation.rudder.services.reports.NodeChanges
import com.normation.rudder.web.ChooseTemplate

import com.normation.box._

object RuleCompliance {
  private def details = ChooseTemplate(
      "templates-hidden" :: "components" :: "ComponentRuleEditForm" :: Nil
    , "component-details"
  )
}

/**
 *   This component display the compliance of a Rule by showing compliance of every Directive
 *   It generates all Data and put them in a DataTable
 */

class RuleCompliance (
    rule : Rule
  , rootRuleCategory: RuleCategory
) extends Loggable {

  private[this] val reportingService = RudderConfig.reportingService
  private[this] val recentChangesService = RudderConfig.recentChangesService
  private[this] val categoryService  = RudderConfig.ruleCategoryService
  private[this] val configService  = RudderConfig.configService

  //fresh value when refresh
  private[this] val roRuleRepository    = RudderConfig.roRuleRepository
  private[this] val getFullDirectiveLib = RudderConfig.roDirectiveRepository.getFullDirectiveLibrary _
  private[this] val getAllNodeInfos     = RudderConfig.nodeInfoService.getAll _

  import RuleCompliance._

  def tagsEditForm = new TagsEditForm(rule.tags)
  def display : NodeSeq = {

    (
      "#ruleName" #>   rule.name &
      "#ruleCategory" #> categoryService.shortFqdn(rootRuleCategory, rule.categoryId) &
      "#tagField *" #> tagsEditForm.viewTags("viewRuleTags", "ruleViewTagsApp", true) &
      "#rudderID" #> rule.id.value &
      "#ruleShortDescription" #> rule.shortDescription &
      "#ruleLongDescription" #>  rule.longDescription &
      "#compliancedetails" #> showCompliance
    )(details)
  }

  /*
   * For each table : the subtable is contained in td : details
   * when + is clicked: it gets the content of td details then process it
   * as a datatable
   *
   * The table are empty when the page is displayed, then there is a JS call
   * to refresh() that fill them.
   */
  def showCompliance : NodeSeq = {
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
      <div class="alert alert-info">
        <div>
          <span class="glyphicon glyphicon-info-sign"></span>
          Details of changes for each period are displayed below the graph. Click to change the selected period.
        </div>
          <div class="recentChange_refresh">
            {SHtml.ajaxButton(<i class="fa fa-refresh"></i>, () => refresh() , ("class","btn btn-primary btn-refresh") , ("title","Refresh"))}
          </div>
      </div>

      <div id="changesChartContainer">
      <canvas id="changesChart" >  </canvas>
      </div>
      </div>
      <div><h5>Changes during period <b id="selectedPeriod"> --- </b> (selected in graph above)</h5></div>

      <table id="changesGrid" cellspacing="0">  </table>  ++
    Script(After(TimeSpan(0), JsRaw(s"""
      function refresh() {${refresh().toJsCmd}};
      createDirectiveTable(true, false, "${S.contextPath}")("reportsGrid",[],refresh);
      createNodeComplianceTable("nodeReportsGrid",[],"${S.contextPath}", refresh);
      createChangesTable("changesGrid",[],"${S.contextPath}", refresh);
      refresh();
    """)))
  }

  def refresh() = {
    //we want to be able to see at least one if the other fails
    SHtml.ajaxInvoke(() => refreshCompliance()) &
    SHtml.ajaxInvoke(() => refreshGraphChanges()) &
    SHtml.ajaxInvoke(() => refreshTableChanges(None))
  }

  def refreshGraphChanges() : JsCmd = {
    try {
    ( for {
      changesOnRule <- recentChangesService.countChangesByRuleByInterval().map( _.getOrElse(rule.id, Map()))
    } yield {
      JsRaw(s"""
        var recentChanges = ${NodeChanges.json(changesOnRule, recentChangesService.getCurrentValidIntervals(None)).toJsCmd};
        var lastLabel = recentChanges.labels[recentChanges.labels.length -1].join(" ")
        $$("#selectedPeriod").text(lastLabel);
        var chart = recentChangesGraph(recentChanges,"changesChart",true);
        $$("#changesChart").click (function(evt){
          var activePoints = chart.getElementAtEvent(evt);
          if (activePoints.length > 0) {
            var label = activePoints[0]._model.label.join(" ")
            selectedIndex = activePoints[0]._index;
            $$("#selectedPeriod").text(label);
            ${SHtml.ajaxCall(JsRaw("recentChanges.t[selectedIndex]"),  s => refreshTableChanges(Some(s.toLong)))}
          }
        });
      """)
    }) match  {
      case Full(cmd)   => cmd
      case eb:EmptyBox =>
        val fail = eb ?~! "Could not refresh recent changes"
        logger.error(fail.messageChain)
        Noop
    }
    } catch {
      case oom: OutOfMemoryError =>
        val msg = "NodeChanges can not be retrieved du to OutOfMemory error. That mean that either your installation is missing " +
          "RAM (see: https://docs.rudder.io/reference/current/administration/performance.html#_java_out_of_memory_error) or that the number of recent changes is " +
          "overwhelming, and you hit: http://www.rudder-project.org/redmine/issues/7735. Look here for workaround"
        logger.error(msg)
        Noop
    }
  }

  /*
   * Refresh the tables with details on events.
   * The argument is the starting timestamp of the interval
   * to check. If None is provided, the last current interval
   * is used.
   * We set a hard limit of 10 000 events by interval of 6 hours.
   */
  def refreshTableChanges(intervalStartTimestamp: Option[Long]) : JsCmd = {
    val intervals = recentChangesService.getCurrentValidIntervals(None).sortBy(_.getStartMillis)
    val failure = Failure("No interval defined. It's likelly a bug, please contact report it to rudder-project.org/redmine")
    val int = intervalStartTimestamp.orElse(intervals.lastOption.map(_.getStartMillis)) match {
      case Some(t) => intervals.find { i => t == i.getStartMillis } match {
        case Some(i) => Full(i)
        case None    => failure
      }
      case None    => failure
    }

    try {
    ( for {
      currentInterval <- int
      changesOnRule   <- recentChangesService.getChangesForInterval(rule.id, currentInterval, Some(10000))
      directiveLib    <- getFullDirectiveLib().toBox
      allNodeInfos    <- getAllNodeInfos()
    } yield {
      val changesLine = ChangeLine.jsonByInterval(Map((currentInterval, changesOnRule)), Some(rule.name), directiveLib, allNodeInfos)
      val changesArray = changesLine.in.toList.flatMap{case a:JsArray => a.in.toList; case _ => Nil}

      JsRaw(s"""
        refreshTable("changesGrid", ${JsArray(changesArray).toJsCmd});
      """)
    }) match  {
      case Full(cmd)   => cmd
      case eb:EmptyBox =>
        val fail = eb ?~! "Could not refresh recent changes"
        logger.error(fail.messageChain)
        Noop
    }
    } catch {
      case oom: OutOfMemoryError =>
        val msg = "NodeChanges can not be retrieved du to OutOfMemory error. That mean that either your installation is missing " +
          "RAM (see: https://docs.rudder.io/reference/current/administration/performance.html#_java_out_of_memory_error) or that the number of recent changes is " +
          "overwhelming, and you hit: http://www.rudder-project.org/redmine/issues/7735. Look here for workaround"
        logger.error(msg)
        Noop
    }
  }

  def refreshCompliance() : JsCmd = {
    ( for {
        reports      <- reportingService.findDirectiveRuleStatusReportsByRule(rule.id)
        updatedRule  <- roRuleRepository.get(rule.id).toBox
        directiveLib <- getFullDirectiveLib().toBox
        allNodeInfos <- getAllNodeInfos()
        globalMode   <- configService.rudder_global_policy_mode().toBox
      } yield {

        val directiveData = ComplianceData.getRuleByDirectivesComplianceDetails(reports, updatedRule, allNodeInfos, directiveLib, globalMode).json.toJsCmd
        val nodeData = ComplianceData.getRuleByNodeComplianceDetails(directiveLib, reports, allNodeInfos, globalMode).json.toJsCmd
        JsRaw(s"""
          refreshTable("reportsGrid", ${directiveData});
          refreshTable("nodeReportsGrid", ${nodeData});
          createTooltip();
        """)
      }
    ) match {
        case Full(cmd) => cmd
        case eb : EmptyBox =>
          val fail = eb ?~! s"Error while computing Rule ${rule.name} (${rule.id.value})"
          logger.error(fail.messageChain)
          Noop
      }
  }

}
