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

package com.normation.rudder.web.components

import com.normation.cfclerk.domain.Technique
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.repository._
import net.liftweb.http.js._
import JsCmds._
import com.normation.inventory.domain.NodeId
import JE._
import net.liftweb.common._
import net.liftweb.http._
import scala.xml._
import net.liftweb.util.Helpers._
import com.normation.utils.Control.sequence
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig
import net.liftweb.json.JArray
import net.liftweb.json.JsonParser
import net.liftweb.json.JString
import net.liftweb.json.JObject
import net.liftweb.json.JField
import com.normation.rudder.web.services.JsTableLine
import com.normation.rudder.web.services.JsTableData
import net.liftweb.http.js.JE.AnonFunc
import org.joda.time.Interval
import com.normation.rudder.services.reports.NodeChanges
import com.normation.rudder.domain.logger.TimingDebugLogger
import scala.concurrent._
import ExecutionContext.Implicits.global
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.web.services.ComputePolicyMode
import com.normation.rudder.web.ChooseTemplate

import com.normation.box._

object RuleGrid {
  def staticInit =
    <head>
      <style type="text/css">
        #actions_zone , .dataTables_length , .dataTables_filter {{ display: inline-block; }}
        .greenCompliance {{ background-color: #5cb85c }}
        .orangeCompliance  {{ background-color: #FF6600 }}
        .redCompliance  {{ background-color: #c9302c }}
        .noCompliance   {{ background-color:#b4b4b4; }}
        .applyingCompliance {{ background-color:#5bc0de; }}
        .compliance {{ text-align: center; }}
      </style>
    </head>
}

/**
 * An ADT to denote if a column should be display or not,
 * or if is should be decided from config service.
 */
sealed trait DisplayColumn
final object DisplayColumn {
  final case class Force(display: Boolean) extends DisplayColumn
  final object FromConfig                  extends DisplayColumn
}

class RuleGrid(
    htmlId_rulesGridZone: String
   //JS callback to call when clicking on a line
  , detailsCallbackLink : Option[(Rule,String) => JsCmd]
  , showCheckboxColumn  : Boolean
  , directiveApplication: Option[DirectiveApplicationManagement]
  , columnCompliance    : DisplayColumn
  , graphRecentChanges  : DisplayColumn
) extends DispatchSnippet with Loggable {

  private[this] sealed trait Line { val rule:Rule }

  private[this] case class OKLine(
      rule             : Rule
    , applicationStatus: ApplicationStatus
    , trackerVariables : Seq[(Directive,ActiveTechnique,Technique)]
    , targets          : Set[RuleTargetInfo]
  ) extends Line with HashcodeCaching

  private[this] case class ErrorLine(
      rule:Rule
    , trackerVariables: Box[Seq[(Directive,ActiveTechnique,Technique)]]
    , targets:Box[Set[RuleTargetInfo]]
  ) extends Line with HashcodeCaching

  private[this] val getFullNodeGroupLib      = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _
  private[this] val getFullDirectiveLib      = RudderConfig.roDirectiveRepository.getFullDirectiveLibrary _
  private[this] val getRuleApplicationStatus = RudderConfig.ruleApplicationStatus.isApplied _
  private[this] val getAllNodeInfos          = RudderConfig.nodeInfoService.getAll _
  private[this] val getRootRuleCategory      = RudderConfig.roRuleCategoryRepository.getRootCategory _

  private[this] val recentChanges            = RudderConfig.recentChangesService
  private[this] val techniqueRepository      = RudderConfig.techniqueRepository
  private[this] val categoryService          = RudderConfig.ruleCategoryService
  private[this] val asyncComplianceService   = RudderConfig.asyncComplianceService
  private[this] val configService            = RudderConfig.configService

  //used to error tempering
  private[this] val roRuleRepository         = RudderConfig.roRuleRepository
  private[this] val woRuleRepository         = RudderConfig.woRuleRepository
  private[this] val uuidGen                  = RudderConfig.stringUuidGenerator

  /////  local variables /////
  private[this] val htmlId_rulesGridId = "grid_" + htmlId_rulesGridZone
  private[this] val htmlId_reportsPopup = "popup_" + htmlId_rulesGridZone
  private[this] val htmlId_modalReportsPopup = "modal_" + htmlId_rulesGridZone

  /*
   * Compliance and recent changes columns (not forced):
   * - we display compliance if rudder_ui_display_ruleComplianceColumns is true,
   * - we display recent changes only if compliance is displayed ; we
   *   display the number in place of graphe if diplay_recent_changes is false
   *
   * If the configService is not available, display
   *
   * The will is to have the following result: (C = compliance column,
   * R = recent changes columns; 0 = missing, N = number, G = graph)
   *
   * Screen \ config | C=1 R=1 | C=1 R=0 | C=0 R=1 | C=0 R=0 |
   * ---------------------------------------------------------
   * Rules           | C=1 R=G | C=1 R=N | C=1 R=1 | C=1 R=N |
   * ---------------------------------------------------------
   * Directives      | C=1 R=G | C=1 R=N | C=0 R=0 | C=0 R=0 |
   * ---------------------------------------------------------
   * Accept Node     |                                       |
   * -----------------               C=0  R=0                |
   * Validate Change |                                       |
   * ---------------------------------------------------------
   */
  import DisplayColumn._
  private[this] val showComplianceAndChangesColumn = columnCompliance match {
    case Force(display) => display
    case FromConfig     => configService.rudder_ui_display_ruleComplianceColumns.toBox.openOr(true)
  }
  private[this] val showChangesGraph = showComplianceAndChangesColumn && (graphRecentChanges match {
    case Force(display) => display
    case FromConfig => configService.display_changes_graph.toBox.openOr(true)
  })

  def reportTemplate = ChooseTemplate(
      List("templates-hidden", "reports_grid")
    , "reports-report"
  )

  def dispatch = {
    case "rulesGrid" => { _:NodeSeq => rulesGridWithUpdatedInfo(None, true, false)}
  }

  /**
   * Display all the rules. All data are charged asynchronously.
   */
  def asyncDisplayAllRules(onlyRules: Option[Set[RuleId]]) =  {
    AnonFunc(SHtml.ajaxCall(JsNull, (s) => {

      val start = System.currentTimeMillis

      ( for {
          rules           <- roRuleRepository.getAll(false).toBox.map { allRules => onlyRules match {
                               case None => allRules
                               case Some(ids) => allRules.filter(rule => ids.contains(rule.id) )
                             } }
          afterRules      =  System.currentTimeMillis
          _               =  TimingDebugLogger.debug(s"Rule grid: fetching all Rules took ${afterRules - start}ms" )

                             //we skip request only if the column is not displayed - we need it even to display text info
          futureChanges   =  if(showComplianceAndChangesColumn) ajaxChanges(changesFuture(rules)) else Noop

          nodeInfo        <- getAllNodeInfos()
          afterNodeInfos  =  System.currentTimeMillis
          _               =  TimingDebugLogger.debug(s"Rule grid: fetching all Nodes informations took ${afterNodeInfos - afterRules}ms" )

          // we have all the data we need to start our future
          futureCompliance =  if(showComplianceAndChangesColumn) {
                                asyncComplianceService.complianceByRule(nodeInfo.keys.toSet, rules.map(_.id).toSet, htmlId_rulesGridId)
                              } else {
                                Noop
                              }

          groupLib        <- getFullNodeGroupLib().toBox
          afterGroups     =  System.currentTimeMillis
          _               =  TimingDebugLogger.debug(s"Rule grid: fetching all Groups took ${afterGroups - afterNodeInfos}ms" )

          directiveLib    <- getFullDirectiveLib().toBox
          afterDirectives =  System.currentTimeMillis
          _               =  TimingDebugLogger.debug(s"Rule grid: fetching all Directives took ${afterDirectives - afterGroups}ms" )

          rootRuleCat     <- getRootRuleCategory().toBox
          globalMode      <- configService.rudder_global_policy_mode().toBox
          newData         =  getRulesTableData(rules, nodeInfo, groupLib, directiveLib, rootRuleCat, globalMode)
          afterData       =  System.currentTimeMillis
          _               =  TimingDebugLogger.debug(s"Rule grid: transforming into data took ${afterData - afterDirectives}ms" )

          _ = TimingDebugLogger.debug(s"Rule grid: computing whole data for rule grid took ${afterData - start}ms" )
        } yield {

          // Reset rule compliances stored in JS, so we get new ones from the future
          JsRaw(s"""
              ruleCompliances = {};
              recentChanges = {};
              recentGraphs = {};
              refreshTable("${htmlId_rulesGridId}", ${newData.json.toJsCmd});
              ${futureCompliance.toJsCmd}
              ${futureChanges.toJsCmd}
          """)
        }
      ) match {
        case Full(cmd) =>
          cmd
        case eb:EmptyBox =>
          val fail = eb ?~! ("an error occured during data update")
          logger.error(s"Could not refresh Rule table data cause is: ${fail.msg}")
          JsRaw(s"""$$("#ruleTableError").text("Could not refresh Rule table data cause is: ${fail.msg}");""")
      }
    } ) )
  }

  /**
   * Display the selected set of rules.
   */
  def rulesGridWithUpdatedInfo(rules: Option[Seq[Rule]], showActionsColumn: Boolean, isPopup: Boolean): NodeSeq = {

    (for {
      allNodeInfos <- getAllNodeInfos()
      groupLib     <- getFullNodeGroupLib().toBox
      directiveLib <- getFullDirectiveLib().toBox
      ruleCat      <- getRootRuleCategory().toBox
      globalMode   <- configService.rudder_global_policy_mode().toBox
    } yield {
      getRulesTableData(rules.getOrElse(Seq()), allNodeInfos, groupLib, directiveLib, ruleCat, globalMode)
    }) match {
      case eb:EmptyBox =>
        val e = eb ?~! "Error when trying to get information about rules"
        logger.error(e.messageChain)
        e.rootExceptionCause.foreach { ex =>
          logger.error("Root exception was:", ex)
        }

        <div id={htmlId_rulesGridZone}>
          <div id={htmlId_modalReportsPopup} class="nodisplay">
            <div id={htmlId_reportsPopup} ></div>
          </div>
          <span class="error">{e.messageChain}</span>
        </div>

      case Full(tableData) =>

        val allcheckboxCallback = AnonFunc("checked",SHtml.ajaxCall(JsVar("checked"), (in : String) => selectAllVisibleRules(in.toBoolean)))
        val onLoad =
          s"""createRuleTable (
                  "${htmlId_rulesGridId}"
                , ${tableData.json.toJsCmd}
                , ${showCheckboxColumn}
                , ${showActionsColumn}
                , ${showComplianceAndChangesColumn}
                , ${showChangesGraph}
                , ${allcheckboxCallback.toJsCmd}
                , "${S.contextPath}"
                , ${asyncDisplayAllRules(rules.map(_.map(_.id).toSet)).toJsCmd}
                , ${isPopup}
              );
              createTooltip();
              createTooltiptr();
              var scopeElmnt = '#showFiltersRules'
              if(angular.element(scopeElmnt).length && !angular.element(scopeElmnt).scope()){
                angular.bootstrap(scopeElmnt, ['filters']);
              }
          """
        <div id={htmlId_rulesGridZone}>
          <div id={htmlId_modalReportsPopup} class="nodisplay">
            <div id={htmlId_reportsPopup} ></div>
          </div>
          <table id={htmlId_rulesGridId} class="display" cellspacing="0"> </table>
          <div class={htmlId_rulesGridId +"_pagination, paginatescala"} >
            <div id={htmlId_rulesGridId +"_paginate_area"}></div>
          </div>
        </div> ++
        Script(OnLoad(JsRaw(onLoad)))
    }
  }

  //////////////////////////////// utility methods ////////////////////////////////

  private[this] def selectAllVisibleRules(status : Boolean) : JsCmd= {
    directiveApplication match {
      case Some(directiveApp) =>
        def moveCategory(arg: String) : JsCmd = {
          //parse arg, which have to  be json object with sourceGroupId, destCatId
          try {
            (for {
               JObject(JField("rules",JArray(childs)) :: Nil) <- JsonParser.parse(arg)
               JString(ruleId) <- childs
             } yield {
               RuleId(ruleId)
             }) match {
              case ruleIds =>
                directiveApp.checkRules(ruleIds,status)  match {
                case DirectiveApplicationResult(rules,completeCategories,indeterminate) =>
                  After(TimeSpan(50),JsRaw(s"""
                    ${rules.map(c => s"""$$('#${c.value}Checkbox').prop("checked",${status}); """).mkString("\n")}
                    ${completeCategories.map(c => s"""$$('#${c.value}Checkbox').prop("indeterminate",false); """).mkString("\n")}
                    ${completeCategories.map(c => s"""$$('#${c.value}Checkbox').prop("checked",${status}); """).mkString("\n")}
                    ${indeterminate.map(c => s"""$$('#${c.value}Checkbox').prop("indeterminate",true); """).mkString("\n")}
                  """))
                }
            }
          } catch {
            case e:Exception =>
              val msg = s"Error while trying to apply directive ${directiveApp.directive.id.value} on visible Rules"
              logger.error(s"$msg, cause is: ${e.getMessage()}")
              logger.debug(s"Error details:", e)
              Alert(msg)
          }
        }

        JsRaw(s"""
            var data = $$("#${htmlId_rulesGridId}").dataTable()._('tr', {"filter":"applied", "page":"current"});
            var rules = $$.map(data, function(e,i) { return e.id })
            var rulesIds = JSON.stringify({ "rules" : rules });
            ${SHtml.ajaxCall(JsVar("rulesIds"), moveCategory _)};
        """)
      case None => Noop
    }
  }

  private[this] def changesFuture (rules: Seq[Rule]): Future[Box[Map[RuleId, Map[Interval, Int]]]] = {
    Future {
      if(rules.isEmpty) {
        Full(Map())
      } else {
        val default = recentChanges.getCurrentValidIntervals(None).map((_, 0)).toMap
        val start = System.currentTimeMillis
        for {
          changes <- recentChanges.countChangesByRuleByInterval()
        } yield {
          val nodeChanges = rules.map(rule => (rule.id, changes.getOrElse(rule.id, default)))
          val after       = System.currentTimeMillis
          TimingDebugLogger.debug(s"computing recent changes in Future took ${after - start}ms" )
          nodeChanges.toMap
        }
      }
    }
  }

  // Ajax call back to get recent changes
  private[this] def ajaxChanges(future : Future[Box[Map[RuleId,Map[Interval, Int]]]]) : JsCmd = {
    SHtml.ajaxInvoke( () => {
      // Is my future completed ?
      if( future.isCompleted ) {
        // Yes wait/get for result
        Await.result(future,scala.concurrent.duration.Duration.Inf) match {
          case Full(changes) =>
            val computeGraphs = for {
              (ruleId,change) <- changes
            } yield {
              val changeCount = change.values.sum
              val data = NodeChanges.json(change, recentChanges.getCurrentValidIntervals(None))
              s"""computeChangeGraph(${data.toJsCmd},"${ruleId.value}",currentPageIds, ${changeCount}, ${showChangesGraph})"""
           }

          JsRaw(s"""
            var ruleTable = $$('#'+"${htmlId_rulesGridId}").dataTable();
            var currentPageRow = ruleTable._('tr', {"page":"current"});
            var currentPageIds = $$.map( currentPageRow , function(val) { return val.id});
            ${computeGraphs.mkString(";")}
            resortTable("${htmlId_rulesGridId}")
          """)

          case eb : EmptyBox =>
            val error = eb ?~! "error while fetching compliances"
            logger.error(error.messageChain)
            Alert(error.messageChain)
        }
      } else {
        After(TimeSpan(500),ajaxChanges(future))
      }
    } )

  }

  /*
   * Get Data to use in the datatable for all Rules
   * First: transform all Rules to Lines
   * Second: Transform all of those lines into javascript datas to send in the function
   */
  private[this] def getRulesTableData(
      rules:Seq[Rule]
    , allNodeInfos : Map[NodeId, NodeInfo]
    , groupLib     : FullNodeGroupCategory
    , directiveLib : FullActiveTechniqueCategory
    , rootRuleCategory   : RuleCategory
    , globalMode : GlobalPolicyMode
  ) : JsTableData[RuleLine] = {

      val t0 = System.currentTimeMillis
      val converted = convertRulesToLines(directiveLib, groupLib, allNodeInfos, rules.toList, rootRuleCategory)
      val t1 = System.currentTimeMillis
      TimingDebugLogger.trace(s"Rule grid: transforming into data: convert to lines: ${t1-t0}ms")

      var tData = 0l

      val lines = for {
         line <- converted
      } yield {
        val tf0 = System.currentTimeMillis
        val res = getRuleData(line, groupLib, allNodeInfos, rootRuleCategory, directiveLib, globalMode)
        val tf1 = System.currentTimeMillis
        tData = tData + tf1 - tf0
        res
      }

      val size = converted.size
      TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data ${tData}ms for ${size} lines ${if(size == 0) "" else s"(by line: ${tData/size}ms)"}")

      val t2 = System.currentTimeMillis
      val res = JsTableData(lines)
      val t3 = System.currentTimeMillis
      TimingDebugLogger.trace(s"Rule grid: transforming into data: jstable: ${t3-t2}ms")
      res
  }

  /*
   * Convert Rules to Data used in Datatables Lines
   */
  private[this] def convertRulesToLines (
      directivesLib: FullActiveTechniqueCategory
    , groupsLib    : FullNodeGroupCategory
    , nodes        : Map[NodeId, NodeInfo]
    , rules        : List[Rule]
    , rootRuleCategory: RuleCategory
  ) : List[Line] = {

    // we compute beforehand the compliance, so that we have a single big query
    // to the database

    rules.map { rule =>

      val trackerVariables: Box[Seq[(Directive, ActiveTechnique, Technique)]] = {
        sequence(rule.directiveIds.toSeq) { id =>
          directivesLib.allDirectives.get(id) match {
            case Some((activeTechnique, directive)) =>
              techniqueRepository.getLastTechniqueByName(activeTechnique.techniqueName) match {
                case None =>
                  Failure(s"Can not find Technique for activeTechnique with name ${activeTechnique.techniqueName} referenced in Rule with ID ${rule.id.value}")
                case Some(technique) =>
                  Full((directive, activeTechnique.toActiveTechnique, technique))
              }
            case None => //it's an error if the directive ID is defined and found but it is not attached to an activeTechnique
              val error = Failure(s"Can not find Directive with ID '${id.value}' referenced in Rule with ID '${rule.id.value}'")
              logger.debug(error.messageChain, error)
              error
          }
        }
      }

      val targetsInfo = sequence(rule.targets.toSeq) {
        case json:CompositeRuleTarget =>
          val ruleTargetInfo = RuleTargetInfo(json,"","",true,false)
          Full(ruleTargetInfo)
        case target =>
          groupsLib.allTargets.get(target) match {
            case Some(t) =>
              Full(t.toTargetInfo)
            case None =>
              Failure(s"Can not find full information for target '${target}' referenced in Rule with ID '${rule.id.value}'")
          }
       }.map(x => x.toSet)

       (trackerVariables, targetsInfo) match {
         case (Full(seq), Full(targets)) =>
           val applicationStatus = getRuleApplicationStatus(rule, groupsLib, directivesLib, nodes)

           OKLine(rule, applicationStatus, seq, targets)

         case (x,y) =>
           if(rule.isEnabledStatus) {
             //the Rule has some error, try to disable it
             //and be sure to not get a Rules from a modification pop-up, because we don't want to commit changes along
             //with the disable.
             //it's only a try, so it may fails, we won't try again
             ( for {
               r <- roRuleRepository.get(rule.id)
               _ <- woRuleRepository.update(
                        r.copy(isEnabledStatus=false)
                      , ModificationId(uuidGen.newUuid)
                      , RudderEventActor
                      , Some("Rule automatically disabled because it contains error (bad target or bad directives)")
                    )
             } yield {
               logger.warn(s"Disabling rule '${rule.name}' (ID: '${rule.id.value}') because it refers missing objects. Go to rule's details and save, then enable it back to correct the problem.")
               x match {
                 case f: Failure =>
                   logger.warn(s"Rule '${rule.name}' (ID: '${rule.id.value}' directive problem: " + f.messageChain)
                 case _ => // Directive Ok!
               }
               y match {
                    case f: Failure =>
                      logger.warn(s"Rule '${rule.name}' (ID: '${rule.id.value}' target problem: " + f.messageChain)
                    case _ => // Group Ok!
               }
             } ) match {
               case eb: EmptyBox =>
                 val e = eb ?~! s"Error when to trying to disable the rule '${rule.name}' (ID: '${rule.id.value}') because it's data are unconsistant."
                 logger.warn(e.messageChain)
                 e.rootExceptionCause.foreach { ex =>
                   logger.warn("Exception was: ", ex)
                 }
               case _ => //ok
             }
           }
           ErrorLine(rule, x, y)
      }
    }
  }

  /*
   * Generates Data for a line of the table
   */
  private[this] def getRuleData (
      line:Line
    , groupsLib: FullNodeGroupCategory
    , nodesInfo: Map[NodeId, NodeInfo]
    , rootRuleCategory: RuleCategory
    , directiveLib: FullActiveTechniqueCategory
    , globalMode  : GlobalPolicyMode
  ) : RuleLine = {

    val t0 = System.currentTimeMillis

    val nodes = groupsLib.getNodeIds(line.rule.targets, nodesInfo)
    // when building policy mode explanation we look into all directives every directive applied by the rule
    // But some directive may be missing so we do 'get', we them skip the missing directive and only use existing one ( thanks to flatmap)
    // Rule will be disabled somewhere else, stating that some object are missing and you should enable it again to fix it
    val (policyMode,explanation) = ComputePolicyMode.ruleMode(globalMode, line.rule.directiveIds.map(directiveLib.allDirectives.get(_)).flatMap(_.map(_._2)))

    // Status is the state of the Rule, defined as a string
    // reasons are the the reasons why a Rule is disabled
    val (status,reasons) : (String,Option[String]) =
      line match {
        case line : OKLine =>
          line.applicationStatus match {
            case FullyApplied => ("In application",None)
            case PartiallyApplied(seq) =>
              val why = seq.map { case (at, d) => "Directive " + d.name + " disabled" }.mkString(", ")
              ("Partially applied", Some(why))
            case x:NotAppliedStatus =>
              val isAllTargetsEnabled = line.targets.filter(t => !t.isEnabled).isEmpty

              val conditions = {
                Seq( ( !line.rule.isEnabledStatus, "Rule disabled" )
                   , ( line.rule.isEnabledStatus && !line.rule.isEnabled , "Rule unapplied" )
                   , ( line.trackerVariables.size <= 0 , "No policy defined")
                   , ( !isAllTargetsEnabled            , "Group disabled")
                   , ( nodes.size<=0                   , "Empty groups")
                ) ++
                line.trackerVariables.flatMap {
                  case (directive, activeTechnique,_) =>
                    Seq( ( directive.isEnabled , "Directive " + directive.name + " disabled")
                       , ( activeTechnique.isEnabled, "Technique for '" + directive.name + "' disabled")
                    )
                }
              }
              val why =  conditions.collect { case (ok, label) if(ok) => label }.mkString(", ")
              ("Not applied", Some(why))
          }
        case _ : ErrorLine => ("N/A",None)
      }

    val t1 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: line status: ${t1-t0}ms")

    // Is the ruple applying a Directive and callback associated to the checkbox
    val (applying, checkboxCallback) = {
      directiveApplication match {
        case Some(directiveApplication) =>
          def check(value : Boolean) : JsCmd= {
            directiveApplication.checkRule(line.rule.id, value) match {
              case DirectiveApplicationResult(rules,completeCategories,indeterminate) =>
              JsRaw(s"""
                ${completeCategories.map(c => s"""$$('#${c.value}Checkbox').prop("indeterminate",false); """).mkString("\n")}
                ${completeCategories.map(c => s"""$$('#${c.value}Checkbox').prop("checked",${value}); """).mkString("\n")}
                ${indeterminate.map(c => s"""$$('#${c.value}Checkbox').prop("indeterminate",true); """).mkString("\n")}
              """)
            }
          }
          //We check the rule status in the directive application object instead of looking directly in the rule.
          val isApplying = directiveApplication.ruleStatus(line.rule)
          val ajax = SHtml.ajaxCall(JsVar("checked"), bool => check (bool.toBoolean))
          val callback = AnonFunc("checked",ajax)
          (isApplying,Some(callback))
        case None => (false,None)
      }
    }

    val t2 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: checkbox callback: ${t2-t1}ms")
    // Css to add to the whole line
    val cssClass = {
      val disabled = if (line.rule.isEnabled) {
        ""
      } else if(line.rule.isEnabledStatus){
        "unappliedRule"
      }else{
        "disabledRule"
      }

      val error = line match {
        case _:ErrorLine => " error"
        case _ => ""
      }

      s"tooltipabletr ${disabled} ${error}"
    }

    val t3 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: css class: ${t3-t2}ms")

    val category = categoryService.shortFqdn(rootRuleCategory, line.rule.categoryId).getOrElse("Error")

    val t4 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: category: ${t4-t3}ms")

    // Callback to use on links
    val callback = for {
      callback <- detailsCallbackLink
      ajax = SHtml.ajaxCall(JsVar("action"), (tab: String) => callback(line.rule,tab))
    } yield {
      AnonFunc("action",ajax)
    }

    val t5 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: callback: ${t5-t4}ms")

    val tags = JsObj(line.rule.tags.map(tag => (tag.name.value, Str(tag.value.value))).toList:_*).toJsCmd

    RuleLine (
        line.rule.name
      , line.rule.id
      , line.rule.shortDescription
      , applying
      , category
      , status
      , cssClass
      , callback
      , checkboxCallback
      , reasons
      , policyMode
      , explanation
      , tags
   )
  }
}

  /*
   *   Javascript object containing all data to create a line in the DataTable
   *   { "name" : Rule name [String]
   *   , "id" : Rule id [String]
   *   , "description" : Rule (short) description [String]
   *   , "applying": Is the rule applying the Directive, used in Directive page [Boolean]
   *   , "category" : Rule category [String]
   *   , "status" : Status of the Rule, "enabled", "disabled" or "N/A" [String]
   *   , "recentChanges" : Array of changes to build the sparkline [Array[String]]
   *   , "trClass" : Class to apply on the whole line (disabled ?) [String]
   *   , "callback" : Function to use when clicking on one of the line link, takes a parameter to define which tab to open, not always present[ Function ]
   *   , "checkboxCallback": Function used when clicking on the checkbox to apply/not apply the Rule to the directive, not always present [ Function ]
   *   , "reasons": Reasons why a Rule is a not applied, empty if there is no reason [ String ]
   *   , "policyMode": Policy mode applied by this Rule [ String ]
   *   }
   */
case class RuleLine (
    name             : String
  , id               : RuleId
  , description      : String
  , applying         : Boolean
  , category         : String
  , status           : String
  , trClass          : String
  , callback         : Option[AnonFunc]
  , checkboxCallback : Option[AnonFunc]
  , reasons          : Option[String]
  , policyMode       : String
  , explanation      : String
  , tags             : String
) extends JsTableLine {

  /* Would love to have a reflexive way to generate that map ...  */
  override val json  = {

      val reasonField =  reasons.map(r => ( "reasons" -> escapeHTML(r)))

      val cbCallbackField = checkboxCallback.map(cb => ( "checkboxCallback" -> cb))

      val callbackField = callback.map(cb => ( "callback" -> cb))

      val optFields : Seq[(String,JsExp)]= reasonField.toSeq ++ cbCallbackField ++ callbackField

      val base = JsObj(
          ( "name", name )
        , ( "id", id.value )
        , ( "description", description )
        , ( "applying",  applying )
        , ( "category", category )
        , ( "status", status )
        , ( "trClass", trClass )
        , ( "policyMode", policyMode )
        , ( "explanation", explanation )
        , ( "tags", tags)
      )

      base +* JsObj(optFields:_*)
    }
}
