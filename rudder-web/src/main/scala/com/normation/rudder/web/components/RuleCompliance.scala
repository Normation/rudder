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
    <div id="recentChanges" class="tw-bs">
      <div class="alert alert-info " style="font-size: 14px">
        <div style="width:90%;display:inline-block;">
        <span class="glyphicon glyphicon-info-sign"></span>
        Details of changes for each period are displayed below the graph. Click to change the selected period.
        </div>
        <div class="recentChange_refresh">
          {SHtml.ajaxButton(<img src='/images/icRefresh.png'/>, () => refresh() , ("class","recentChangeGraph refreshButton") , ("title","Refresh"))}
        </div>
      </div>

      <div id="changesChart">  </div>
      </div>
      <hr class="spacer" />
      <span >Changes during period <b id="selectedPeriod"> --- </b> (selected in graph above)</span>

      <table id="changesGrid" cellspacing="0">  </table>  ++
    Script(After(0,JsRaw(s"""
      function refresh() {${refresh().toJsCmd}};
      createDirectiveTable(true, false, "${S.contextPath}")("reportsGrid",[],refresh);
      createNodeComplianceTable("nodeReportsGrid",[],"${S.contextPath}", refresh);
      createChangesTable("changesGrid",[],"${S.contextPath}");
      correctButtons();
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
        var data = recentChanges.y
        data.splice(0,0,'Recent changes')
        var x = recentChanges.x
        x.splice(0,0,'x')
        var selectedIndex = x.length-2;
        //recentChart variable has to be global because we need it to refresh the graph clicking on compliance tab.
        recentChart = c3.generate({
          data: {
                x: 'x'
              , columns: [ x , data ]
              , type: 'bar'
              , onclick: function (d, element) {
                  selectedIndex = d.index;
                  ${SHtml.ajaxCall(JsRaw("recentChanges.t[selectedIndex]"),  s => refreshTableChanges(Some(s.toLong)))}
                  selectInterval(x[selectedIndex+1],element);
                }
              , onmouseover : function (element) {
                changeCursor(element.value);
              }
              , onmouseout : function (element) {
                changeCursor(element.value);
              }
            }
          , legend : {
              show : false
            }
          , bindto : '#changesChart'
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
          , onrendered: function () {
              var element = document.getElementsByClassName('c3-bar-'+(selectedIndex).toString())[0];
              selectInterval(x[selectedIndex+1],element);
          }
        } );
        createTooltip();
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
          "RAM (see: http://www.rudder-project.org/doc-3.2/_performance_tuning.html#_java_out_of_memory_error) or that the number of recent changes is " +
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
      directiveLib    <- getFullDirectiveLib()
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
          "RAM (see: http://www.rudder-project.org/doc-3.2/_performance_tuning.html#_java_out_of_memory_error) or that the number of recent changes is " +
          "overwhelming, and you hit: http://www.rudder-project.org/redmine/issues/7735. Look here for workaround"
        logger.error(msg)
        Noop
    }
  }

  def refreshCompliance() : JsCmd = {
    ( for {
        reports      <- reportingService.findDirectiveRuleStatusReportsByRule(rule.id)
        updatedRule  <- roRuleRepository.get(rule.id)
        directiveLib <- getFullDirectiveLib()
        allNodeInfos <- getAllNodeInfos()
      } yield {

        val directiveData = ComplianceData.getRuleByDirectivesComplianceDetails(reports, updatedRule, allNodeInfos, directiveLib).json.toJsCmd
        val nodeData = ComplianceData.getRuleByNodeComplianceDetails(directiveLib, reports, allNodeInfos).json.toJsCmd
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
