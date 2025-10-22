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

import bootstrap.liftweb.RudderConfig
import com.normation.box.*
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.logger.TimingDebugLogger
import com.normation.rudder.domain.policies.*
import com.normation.rudder.facts.nodes.CoreNodeFact
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.repository.*
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.reports.NodeChanges
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.web.ChooseTemplate
import com.normation.rudder.web.services.ComputePolicyMode
import com.normation.rudder.web.services.JsTableData
import com.normation.rudder.web.services.JsTableLine
import com.normation.utils.Control.traverse
import com.normation.utils.SimpleStatus
import com.normation.zio.*
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import net.liftweb.json.*
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.Interval
import scala.collection.MapView
import scala.concurrent.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.xml.*

/**
 * An ADT to denote if a column should be display or not,
 * or if is should be decided from config service.
 */
sealed trait DisplayColumn
object DisplayColumn {
  final case class Force(display: Boolean) extends DisplayColumn
  object FromConfig                        extends DisplayColumn
}

class RuleGrid(
    htmlId_rulesGridZone: String, // JS callback to call when clicking on a line
    detailsCallbackLink:  Option[(Rule, String) => JsCmd],
    showCheckboxColumn:   Boolean,
    directiveApplication: Option[DirectiveApplicationManagement],
    columnCompliance:     DisplayColumn,
    graphRecentChanges:   DisplayColumn
) extends DispatchSnippet with Loggable {

  import RuleGrid.*

  private val getFullNodeGroupLib      = () => RudderConfig.roNodeGroupRepository.getFullGroupLibrary()
  private val getFullDirectiveLib      = () => RudderConfig.roDirectiveRepository.getFullDirectiveLibrary()
  private val getRuleApplicationStatus = RudderConfig.ruleApplicationStatus.isApplied
  private val getRootRuleCategory      = () => RudderConfig.roRuleCategoryRepository.getRootCategory()

  private val recentChanges          = RudderConfig.recentChangesService
  private val techniqueRepository    = RudderConfig.techniqueRepository
  private val categoryService        = RudderConfig.ruleCategoryService
  private val asyncComplianceService = RudderConfig.asyncComplianceService
  private val configService          = RudderConfig.configService

  // used to error tempering
  private val roRuleRepository = RudderConfig.roRuleRepository
  private val woRuleRepository = RudderConfig.woRuleRepository
  private val uuidGen          = RudderConfig.stringUuidGenerator
  private val nodeFactRepo     = RudderConfig.nodeFactRepository

  /////  local variables /////
  private val htmlId_rulesGridId       = "grid_" + htmlId_rulesGridZone
  private val htmlId_reportsPopup      = "popup_" + htmlId_rulesGridZone
  private val htmlId_modalReportsPopup = "modal_" + htmlId_rulesGridZone

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
  import DisplayColumn.*
  private val showComplianceAndChangesColumn = columnCompliance match {
    case Force(display) => display
    case FromConfig     => configService.rudder_ui_display_ruleComplianceColumns().toBox.openOr(true)
  }
  private val showChangesGraph               = showComplianceAndChangesColumn && (graphRecentChanges match {
    case Force(display) => display
    case FromConfig     => configService.display_changes_graph().toBox.openOr(true)
  })

  def reportTemplate: NodeSeq = ChooseTemplate(
    List("templates-hidden", "reports_grid"),
    "reports-report"
  )

  def dispatch: PartialFunction[String, NodeSeq => NodeSeq] = {
    case "rulesGrid" => {
      implicit val qc: QueryContext = CurrentUser.queryContext // bug https://issues.rudder.io/issues/26605

      (_: NodeSeq) => rulesGridWithUpdatedInfo(None, showActionsColumn = true, isPopup = false)
    }
  }

  /**
   * Display all the rules. All data are charged asynchronously.
   */
  def asyncDisplayAllRules(onlyRules: Option[Set[RuleId]])(implicit qc: QueryContext): AnonFunc = {
    AnonFunc(
      SHtml.ajaxCall(
        JsNull,
        (s) => {

          val start = System.currentTimeMillis

          (for {
            rules     <- roRuleRepository.getAll(false).toBox.map { allRules =>
                           onlyRules match {
                             case None      => allRules
                             case Some(ids) => allRules.filter(rule => ids.contains(rule.id))
                           }
                         }
            afterRules = System.currentTimeMillis
            _          = TimingDebugLogger.debug(s"Rule grid: fetching all Rules took ${afterRules - start}ms")

            // we skip request only if the column is not displayed - we need it even to display text info
            futureChanges = if (showComplianceAndChangesColumn) ajaxChanges(changesFuture(rules)) else Noop

            nodeFacts     <- nodeFactRepo.getAll().toBox
            afterNodeInfos = System.currentTimeMillis
            _              = TimingDebugLogger.debug(s"Rule grid: fetching all Nodes informations took ${afterNodeInfos - afterRules}ms")

            // we have all the data we need to start our future
            futureCompliance = if (showComplianceAndChangesColumn) {
                                 asyncComplianceService
                                   .complianceByRule(nodeFacts.keys.toSet, rules.map(_.id).toSet, htmlId_rulesGridId)
                               } else {
                                 Noop
                               }

            groupLib   <- getFullNodeGroupLib().toBox
            afterGroups = System.currentTimeMillis
            _           = TimingDebugLogger.debug(s"Rule grid: fetching all Groups took ${afterGroups - afterNodeInfos}ms")

            directiveLib   <- getFullDirectiveLib().toBox
            afterDirectives = System.currentTimeMillis
            _               = TimingDebugLogger.debug(s"Rule grid: fetching all Directives took ${afterDirectives - afterGroups}ms")

            rootRuleCat <- getRootRuleCategory().toBox
            globalMode  <- configService.rudder_global_policy_mode().toBox
            newData      =
              getRulesTableData(rules, nodeFacts, groupLib, directiveLib, rootRuleCat, globalMode)
            afterData    = System.currentTimeMillis
            _            = TimingDebugLogger.debug(s"Rule grid: transforming into data took ${afterData - afterDirectives}ms")
            _            = TimingDebugLogger.debug(s"Rule grid: computing whole data for rule grid took ${afterData - start}ms")
          } yield {

            // Reset rule compliances stored in JS, so we get new ones from the future
            JsRaw(s"""
              ruleCompliances = {};
              recentChanges = {};
              recentGraphs = {};
              refreshTable("${htmlId_rulesGridId}", ${newData.toJson.toJsCmd});
              ${futureCompliance.toJsCmd}
              ${futureChanges.toJsCmd}
          """) // JsRaw ok, escaped
          }) match {
            case Full(cmd) =>
              cmd
            case eb: EmptyBox =>
              val fail = eb ?~! ("an error occured during data update")
              logger.error(s"Could not refresh Rule table data cause is: ${fail.msg}")
              JsRaw(
                s"""$$("#ruleTableError").text("Could not refresh Rule table data cause is: ${fail.msg}");"""
              ) // JsRaw ok, no user inputs
          }
        }
      )
    )
  }

  /**
   * Display the selected set of rules.
   */
  def rulesGridWithUpdatedInfo(rules: Option[Seq[Rule]], showActionsColumn: Boolean, isPopup: Boolean)(implicit
      qc: QueryContext
  ): NodeSeq = {

    (for {
      nodeFacts    <- nodeFactRepo.getAll()
      groupLib     <- getFullNodeGroupLib()
      directiveLib <- getFullDirectiveLib()
      ruleCat      <- getRootRuleCategory()
      globalMode   <- configService.rudder_global_policy_mode()
    } yield {
      getRulesTableData(
        rules.getOrElse(Seq()),
        nodeFacts,
        groupLib,
        directiveLib,
        ruleCat,
        globalMode
      )
    }).either.runNow match {
      case Left(err) =>
        val e = s"Error when trying to get information about rules: ${err.fullMsg}"
        logger.error(e)

        <div id={htmlId_rulesGridZone}>
          <div id={htmlId_modalReportsPopup} class="d-none">
            <div id={htmlId_reportsPopup} ></div>
          </div>
          <span class="error">{e}</span>
        </div>

      case Right(tableData) =>
        val allcheckboxCallback =
          AnonFunc("checked", SHtml.ajaxCall(JsVar("checked"), (in: String) => selectAllVisibleRules(in.toBoolean)))
        val onLoad              = {
          s"""createRuleTable (
                  "${htmlId_rulesGridId}"
                , ${tableData.toJson.toJsCmd}
                , ${showCheckboxColumn}
                , ${showActionsColumn}
                , ${showComplianceAndChangesColumn}
                , ${showChangesGraph}
                , ${allcheckboxCallback.toJsCmd}
                , "${S.contextPath}"
                , ${asyncDisplayAllRules(rules.map(_.map(_.id).toSet)).toJsCmd}
                , ${isPopup}
              );
              initBsTooltips()
          """
        }
        <div id={htmlId_rulesGridZone}>
          <div id={htmlId_modalReportsPopup} class="d-none">
            <div id={htmlId_reportsPopup} ></div>
          </div>
          <table id={htmlId_rulesGridId} class="display" cellspacing="0"> </table>
          <div class={htmlId_rulesGridId + "_pagination, paginatescala"} >
            <div id={htmlId_rulesGridId + "_paginate_area"}></div>
          </div>
        </div> ++
        Script(OnLoad(JsRaw(onLoad))) // JsRaw ok, escaped
    }
  }

  //////////////////////////////// utility methods ////////////////////////////////

  private def selectAllVisibleRules(status: Boolean): JsCmd = {
    directiveApplication match {
      case Some(directiveApp) =>
        def moveCategory(arg: String): JsCmd = {
          // parse arg, which have to  be json object with sourceGroupId, destCatId
          try {
            (for {
              case JObject(JField("rules", JArray(childs)) :: Nil) <- JsonParser.parse(arg)
              case JString(ruleId) <- childs
            } yield {
              RuleId(RuleUid(ruleId))
            }) match {
              case ruleIds =>
                directiveApp.checkRules(ruleIds, status) match {
                  case DirectiveApplicationResult(rules, completeCategories, indeterminate) =>
                    def toId(s: String): String = StringEscapeUtils.escapeEcmaScript(s) + "Checkbox"
                    After(
                      TimeSpan(50),
                      JsRaw(s"""
                    ${rules.map(c => s"""$$('#${toId(c.serialize)}').prop("checked",${status}); """).mkString("\n")}
                    ${completeCategories.map(c => s"""$$('#${toId(c.value)}').prop("indeterminate",false); """).mkString("\n")}
                    ${completeCategories.map(c => s"""$$('#${toId(c.value)}').prop("checked",${status}); """).mkString("\n")}
                    ${indeterminate.map(c => s"""$$('#${toId(c.value)}').prop("indeterminate",true); """).mkString("\n")}
                  """) // JsRaw ok, escaped
                    )
                }
            }
          } catch {
            case e: Exception =>
              val msg = s"Error while trying to apply directive ${directiveApp.directive.id.uid.value} on visible Rules"
              logger.error(s"$msg, cause is: ${e.getMessage()}")
              logger.debug(s"Error details:", e)
              Alert(msg)
          }
        }

        JsRaw(s"""
            var data = $$("#${htmlId_rulesGridId}").dataTable()._('tr', {"filter":"applied", "page":"current"});
            var rules = $$.map(data, function(e,i) { return e.id })
            var rulesIds = JSON.stringify({ "rules" : rules });
            ${SHtml.ajaxCall(JsVar("rulesIds"), moveCategory)};
        """) // JsRaw ok, no user inputs
      case None               => Noop
    }
  }

  private def changesFuture(rules: Seq[Rule]): Future[Box[Map[RuleId, Map[Interval, Int]]]] = {
    Future {
      if (rules.isEmpty) {
        Full(Map())
      } else {
        val default = recentChanges.getCurrentValidIntervals(None).map((_, 0)).toMap
        val start   = System.currentTimeMillis
        for {
          changes <- recentChanges.countChangesByRuleByInterval()
        } yield {
          val nodeChanges = rules.map(rule => (rule.id, changes._2.getOrElse(rule.id, default)))
          val after       = System.currentTimeMillis
          TimingDebugLogger.debug(s"computing recent changes in Future took ${after - start}ms")
          nodeChanges.toMap
        }
      }
    }
  }

  // Ajax call back to get recent changes
  private def ajaxChanges(future: Future[Box[Map[RuleId, Map[Interval, Int]]]]): JsCmd = {
    SHtml.ajaxInvoke(() => {
      // Is my future completed ?
      if (future.isCompleted) {
        // Yes wait/get for result
        Await.result(future, scala.concurrent.duration.Duration.Inf) match {
          case Full(changes) =>
            val computeGraphs = for {
              (ruleId, change) <- changes
            } yield {
              val changeCount = change.values.sum
              val data        = NodeChanges.json(change, recentChanges.getCurrentValidIntervals(None))
              s"""computeChangeGraph(${data.toJsCmd},"${StringEscapeUtils.escapeEcmaScript(
                  ruleId.serialize
                )}",currentPageIds, ${changeCount}, ${showChangesGraph})"""
            }

            JsRaw(s"""
            var ruleTable = $$('#'+"${htmlId_rulesGridId}").dataTable();
            var currentPageRow = ruleTable._('tr', {"page":"current"});
            var currentPageIds = $$.map( currentPageRow , function(val) { return val.id});
            ${computeGraphs.mkString(";")}
            resortTable("${htmlId_rulesGridId}")
          """) // JsRaw ok, escaped

          case eb: EmptyBox =>
            val error = eb ?~! "error while fetching compliances"
            logger.error(error.messageChain)
            Alert(error.messageChain)
        }
      } else {
        After(TimeSpan(500), ajaxChanges(future))
      }
    })

  }

  private def getRulePolicyMode(
      rule:         Rule,
      nodes:        Set[NodeId],
      nodeFacts:    MapView[NodeId, CoreNodeFact],
      directiveLib: FullActiveTechniqueCategory,
      globalMode:   GlobalPolicyMode
  ) = {
    val nodeModes = nodes.flatMap(id => nodeFacts.get(id).map(_.rudderSettings.policyMode))
    // when building policy mode explanation we look into every directive applied by the rule.
    // But some directive may be missing so we do 'get', we them skip the missing directive and only use existing one ( thanks to flatmap)
    // Rule will be disabled somewhere else, stating that some objects are missing and you should enable it again to fix it
    ComputePolicyMode.ruleMode(
      globalMode,
      rule.directiveIds.map(directiveLib.allDirectives.get(_)).flatMap(_.map(_._2)),
      nodeModes
    )

  }

  /*
   * Get Data to use in the datatable for all Rules
   * First: transform all Rules to Lines
   * Second: Transform all of those lines into javascript datas to send in the function
   */
  private def getRulesTableData(
      rules:            Seq[Rule],
      nodeFacts:        MapView[NodeId, CoreNodeFact],
      groupLib:         FullNodeGroupCategory,
      directiveLib:     FullActiveTechniqueCategory,
      rootRuleCategory: RuleCategory,
      globalMode:       GlobalPolicyMode
  ): JsTableData[RuleLine] = {

    val t0        = System.currentTimeMillis
    val converted = convertRulesToLines(
      directiveLib,
      groupLib,
      nodeFacts,
      rules.toList,
      globalMode
    )
    val t1        = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: convert to lines: ${t1 - t0}ms")

    var tData = System.currentTimeMillis

    val lines = getRulesData(converted, directiveApplication, callback, getCategoryName(rootRuleCategory))

    tData = System.currentTimeMillis - tData

    val size = converted.size
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data ${tData}ms for ${size} lines ${if (size == 0) ""
      else s"(by line: ${tData / size}ms)"}")

    lines
  }

  /*
   * Convert Rules to Data used in Datatables Lines
   */
  private def convertRulesToLines(
      directivesLib: FullActiveTechniqueCategory,
      groupsLib:     FullNodeGroupCategory,
      nodeFacts:     MapView[NodeId, CoreNodeFact],
      rules:         List[Rule],
      globalMode:    GlobalPolicyMode
  ): List[Line] = {

    val arePolicyServers = nodeFacts.mapValues(_.rudderSettings.isPolicyServer).toMap

    // we compute beforehand the compliance, so that we have a single big query
    // to the database

    rules.map { rule =>
      val nodes                     = groupsLib.getNodeIds(rule.targets, arePolicyServers)
      val (policyMode, explanation) = getRulePolicyMode(rule, nodes, nodeFacts, directivesLib, globalMode).tuple

      val trackerVariables: Box[Seq[DirectiveStatus]] = {
        traverse(rule.directiveIds.toSeq) { id =>
          directivesLib.allDirectives.get(id) match {
            case Some((activeTechnique, directive)) =>
              techniqueRepository.getLastTechniqueByName(activeTechnique.techniqueName) match {
                case None    =>
                  Failure(
                    s"Can not find Technique for activeTechnique with name ${activeTechnique.techniqueName.value} referenced in Rule with ID ${rule.id.serialize}"
                  )
                case Some(_) =>
                  Full(
                    DirectiveStatus(
                      directive.name,
                      SimpleStatus.fromBool(directive.isEnabled),
                      SimpleStatus.fromBool(activeTechnique.isEnabled)
                    )
                  )
              }
            case None                               => // it's an error if the directive ID is defined and found but it is not attached to an activeTechnique
              val error =
                Failure(s"Can not find Directive with ID '${id.debugString}' referenced in Rule with ID '${rule.id.serialize}'")
              logger.debug(error.messageChain, error)
              error
          }
        }
      }

      val targetsInfo = traverse(rule.targets.toSeq) {
        case json: CompositeRuleTarget =>
          val ruleTargetInfo = RuleTargetInfo(json, name = "", description = "", isEnabled = true, isSystem = false)
          Full(ruleTargetInfo)
        case target =>
          groupsLib.allTargets.get(target) match {
            case Some(t) =>
              Full(t.toTargetInfo)
            case None    =>
              Failure(
                s"Can not find full information for target '${target.target}' referenced in Rule with ID '${rule.id.serialize}'"
              )
          }
      }.map(x => x.toSet)

      (trackerVariables, targetsInfo) match {
        case (Full(seq), Full(targets)) =>
          val applicationStatus = {
            getRuleApplicationStatus(
              rule,
              groupsLib,
              directivesLib,
              arePolicyServers,
              None
            )
          }

          OKLine(rule, policyMode, explanation, nodes.isEmpty, applicationStatus, seq, targets)

        case (x, y) =>
          if (rule.isEnabledStatus) {
            // the Rule has some error, try to disable it
            // and be sure to not get a Rules from a modification pop-up, because we don't want to commit changes along
            // with the disable.
            // it's only a try, so it may fails, we won't try again
            (for {
              r <- roRuleRepository.get(rule.id)
              _ <- woRuleRepository.update(
                     r.copy(isEnabledStatus = false),
                     ModificationId(uuidGen.newUuid),
                     RudderEventActor,
                     Some("Rule automatically disabled because it contains error (bad target or bad directives)")
                   )
            } yield {
              logger.warn(
                s"Disabling rule '${rule.name}' (ID: '${rule.id.serialize}') because it refers missing objects. Go to rule's details and save, then enable it back to correct the problem."
              )
              x match {
                case f: Failure =>
                  logger.warn(s"Rule '${rule.name}' (ID: '${rule.id.serialize}' directive problem: " + f.messageChain)
                case _ => // Directive Ok!
              }
              y match {
                case f: Failure =>
                  logger.warn(s"Rule '${rule.name}' (ID: '${rule.id.serialize}' target problem: " + f.messageChain)
                case _ => // Group Ok!
              }
            }).toBox match {
              case eb: EmptyBox =>
                val e =
                  eb ?~! s"Error when to trying to disable the rule '${rule.name}' (ID: '${rule.id.serialize}') because it's data are inconsistant."
                logger.warn(e.messageChain)
                e.rootExceptionCause.foreach(ex => logger.warn("Exception was: ", ex))
              case _ => // ok
            }
          }
          ErrorLine(rule, policyMode, explanation, nodes.isEmpty)
      }
    }
  }

  def callback(rule: Rule): Option[AnonFunc] = for {
    cb  <- detailsCallbackLink
    ajax = SHtml.ajaxCall(JsVar("action"), (tab: String) => cb(rule, tab))
  } yield {
    AnonFunc("action", ajax)
  }

  def getCategoryName(rootRuleCategory: RuleCategory)(id: RuleCategoryId): String = {
    categoryService.shortFqdn(rootRuleCategory, id).getOrElse("Error")
  }

}

object RuleGrid {
  sealed private[components] trait Line {
    def rule:                  Rule
    def policyMode:            String
    def policyModeExplanation: String
    def nodeIsEmpty:           Boolean // true if rule defining this line currently targets zero node
  }

  private[components] case class DirectiveStatus(
      directiveName:    String,
      directiveEnabled: SimpleStatus,
      techniqueEnabled: SimpleStatus
  )

  private[components] case class OKLine(
      rule:                  Rule,
      policyMode:            String,
      policyModeExplanation: String,
      nodeIsEmpty:           Boolean, // true if rule defining this line currently targets zero node
      applicationStatus:     ApplicationStatus,
      trackerVariables:      Seq[DirectiveStatus],
      targets:               Set[RuleTargetInfo]
  ) extends Line

  private[components] case class ErrorLine(
      rule:                  Rule,
      policyMode:            String,
      policyModeExplanation: String,
      nodeIsEmpty:           Boolean // true if rule defining this line currently targets zero node
  ) extends Line

  private[components] def getRulesData(
      lines:                List[Line],
      directiveApplication: Option[DirectiveApplicationManagement],
      callback:             Rule => Option[AnonFunc],
      getCategoryName:      RuleCategoryId => String
  ): JsTableData[RuleLine] = {
    JsTableData(lines.map(l => getRuleData(l, directiveApplication, callback, getCategoryName)))
  }

  /*
   * Generates Data for a line of the table
   */
  private[components] def getRuleData(
      line:                 Line,
      directiveApplication: Option[DirectiveApplicationManagement],
      createCallback:       Rule => Option[AnonFunc],
      getCategoryName:      RuleCategoryId => String
  ): RuleLine = {

    val t0 = System.currentTimeMillis

    // Status is the state of the Rule, defined as a string
    // reasons are the reasons why a Rule is disabled
    val (status, reasons): (String, Option[String]) = {
      line match {
        case line: OKLine    =>
          line.applicationStatus match {
            case FullyApplied          => ("In application", None)
            case PartiallyApplied(seq) =>
              val why = seq.map { case (at, d) => "Directive " + d.name + " disabled" }.mkString(", ")
              ("Partially applied", Some(why))
            case _: NotAppliedStatus =>
              val (status, disabledMessage) = {
                if ((!line.rule.isEnabled) && (!line.rule.isEnabledStatus)) {
                  ("Disabled", Some("This rule is disabled. "))
                } else {
                  ("Not applied", None)
                }
              }
              val isAllTargetsEnabled       = line.targets.filter(t => !t.isEnabled).isEmpty

              val conditions = {
                Seq(
                  (line.rule.isEnabledStatus && !line.rule.isEnabled, "Rule unapplied"),
                  (line.trackerVariables.isEmpty, "No policy defined"),
                  (!isAllTargetsEnabled, "Group disabled"),
                  (line.nodeIsEmpty, "Empty groups")
                ) ++
                line.trackerVariables.flatMap {
                  case DirectiveStatus(directiveName, directiveEnabled, techniqueEnabled) =>
                    Seq(
                      (!directiveEnabled.asBool, "Directive " + directiveName + " disabled"),
                      (!techniqueEnabled.asBool, "Technique for '" + directiveName + "' disabled")
                    )
                }
              }
              val why        = (disabledMessage ++ (conditions.collect { case (ok, label) if (ok) => label })).mkString(", ")
              (status, Some(why))
          }
        case _:    ErrorLine => ("N/A", None)
      }
    }
    val t1 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: line status: ${t1 - t0}ms")

    // Is the rule applying a Directive and callback associated to the checkbox
    val (applying, checkboxCallback) = {
      directiveApplication match {
        case Some(directiveApplication) =>
          def check(value: Boolean): JsCmd = {
            directiveApplication.checkRule(line.rule.id, value) match {
              case DirectiveApplicationResult(rules, completeCategories, indeterminate) =>
                def cid(c: RuleCategoryId) = StringEscapeUtils.escapeEcmaScript(c.value + "Checkbox")

                JsRaw(s"""
                ${completeCategories.map(c => s"""$$('#${cid(c)}').prop("indeterminate",false); """).mkString("\n")}
                ${completeCategories.map(c => s"""$$('#${cid(c)}').prop("checked",${value}); """).mkString("\n")}
                ${indeterminate.map(c => s"""$$('#${cid(c)}').prop("indeterminate",true); """).mkString("\n")}
              """) // JsRaw ok, escaped
            }
          }
          // We check the rule status in the directive application object instead of looking directly in the rule.
          val isApplying = directiveApplication.ruleStatus(line.rule)
          val ajax     = SHtml.ajaxCall(JsVar("checked"), bool => check(bool.toBoolean))
          val callback = AnonFunc("checked", ajax)
          (isApplying, Some(callback))
        case None                       => (false, None)
      }
    }

    val t2       = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: checkbox callback: ${t2 - t1}ms")
    // Css to add to the whole line
    val cssClass = {
      val error = line match {
        case _: ErrorLine => " error"
        case _ => ""
      }
      s"${error}"
    }

    val t3 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: css class: ${t3 - t2}ms")

    val category = getCategoryName(line.rule.categoryId)

    val t4 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: category: ${t4 - t3}ms")

    // Callback to use on links
    val callback = createCallback(line.rule)

    val t5 = System.currentTimeMillis
    TimingDebugLogger.trace(s"Rule grid: transforming into data: get rule data: callback: ${t5 - t4}ms")

    val tags = JsObj(line.rule.tags.map(tag => (tag.name.value, Str(tag.value.value))).toList*).toJsCmd
    RuleLine(
      line.rule.name,
      line.rule.id,
      line.rule.shortDescription,
      applying,
      category,
      status,
      cssClass,
      callback,
      checkboxCallback,
      reasons,
      line.policyMode,
      line.policyModeExplanation,
      tags,
      line.rule.tags
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
final case class RuleLine(
    name:             String,
    id:               RuleId,
    description:      String,
    applying:         Boolean,
    category:         String,
    status:           String,
    trClass:          String,
    callback:         Option[AnonFunc],
    checkboxCallback: Option[AnonFunc],
    reasons:          Option[String],
    policyMode:       String,
    explanation:      String,
    tags:             String,
    tagsDisplayed:    Tags
) extends JsTableLine {

  private def serializeTags(tags: Tags): JValue = {
    // sort all the tags by name
    import net.liftweb.json.JsonDSL.*
    val m: JValue = JArray(
      tags.tags.toList.sortBy(_.name.value).map(t => ("key", t.name.value) ~ ("value", t.value.value))
    )
    m
  }

  /* Would love to have a reflexive way to generate that map ...  */
  override def json(freshName: () => String): JsObj = {

    val reasonField = reasons.map(r => ("reasons" -> escapeHTML(r)))

    val cbCallbackField = checkboxCallback.map(cb => ("checkboxCallback" -> cb))

    val callbackField = callback.map(cb => ("callback" -> cb))

    val optFields: Seq[(String, JsExp)] = reasonField.toSeq ++ cbCallbackField ++ callbackField

    val base = JsObj(
      ("name", name),
      ("id", id.serialize),
      ("description", description),
      ("applying", applying),
      ("category", category),
      ("status", status),
      ("trClass", trClass),
      ("policyMode", policyMode),
      ("explanation", explanation),
      ("tags", tags),
      ("tagsDisplayed", serializeTags(tagsDisplayed))
    )

    base +* JsObj(optFields*)
  }
}
