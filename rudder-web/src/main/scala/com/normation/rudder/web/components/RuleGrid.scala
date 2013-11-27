/*
*************************************************************************************
* Copyright 2011 Normation SAS
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

import com.normation.cfclerk.domain.Technique
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.reports.bean._
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.repository._
import com.normation.rudder.services.reports.ReportingService
import com.normation.rudder.services.nodes.NodeInfoService
import net.liftweb.http.js._
import JsCmds._
import com.normation.inventory.domain.NodeId
import JE._
import net.liftweb.common._
import net.liftweb.http._
import scala.xml._
import net.liftweb.util._
import net.liftweb.util.Helpers._
import com.normation.rudder.web.model._
import com.normation.utils.StringUuidGenerator
import com.normation.exceptions.TechnicalException
import com.normation.utils.Control.sequence
import com.normation.utils.HashcodeCaching
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig



object RuleGrid {
  def staticInit =
    <head>
      <script type="text/javascript" language="javascript" src="/javascript/datatables/js/jquery.dataTables.js"></script>
      <style type="text/css">
        #actions_zone , .dataTables_length , .dataTables_filter {{ display: inline-block; }}
        .greenCompliance {{ background-color: #CCFFCC }}
        .orangeCompliance  {{ background-color: #FFBB66 }}
        .redCompliance  {{ background-color: #FF6655 }}
        .noCompliance   {{ background-color:#BBAAAA; }}
        .applyingCompliance {{ background-color:#CCCCCC; }}
        .compliance {{ text-align: center; }}
      </style>
    </head>
}

class RuleGrid(
    htmlId_rulesGridZone : String
  , rules : Seq[Rule]
   //JS callback to call when clicking on a line
  , detailsCallbackLink : Option[(Rule,String) => JsCmd]
  , showCheckboxColumn:Boolean = true
  , directiveApplication : Option[DirectiveApplicationManagement] = None
) extends DispatchSnippet with Loggable {

  private[this] val getFullNodeGroupLib = RudderConfig.roNodeGroupRepository.getFullGroupLibrary _
  private[this] val getFullDirectiveLib = RudderConfig.roDirectiveRepository.getFullDirectiveLibrary _
  private[this] val getRuleApplicationStatus = RudderConfig.ruleApplicationStatus.isApplied _

  private[this] val reportingService = RudderConfig.reportingService
  private[this] val getAllNodeInfos  = RudderConfig.nodeInfoService.getAll _
  private[this] val techniqueRepository = RudderConfig.techniqueRepository
  private[this] val categoryRepository  = RudderConfig.roRuleCategoryRepository
  private[this] val categoryService     = RudderConfig.ruleCategoryService


  //used to error tempering
  private[this] val roRuleRepository    = RudderConfig.roRuleRepository
  private[this] val woRuleRepository    = RudderConfig.woRuleRepository
  private[this] val uuidGen             = RudderConfig.stringUuidGenerator


  /////  local variables /////
  private[this] val htmlId_rulesGridId = "grid_" + htmlId_rulesGridZone
  private[this] val htmlId_reportsPopup = "popup_" + htmlId_rulesGridZone
  private[this] val htmlId_modalReportsPopup = "modal_" + htmlId_rulesGridZone
  private[this] val tableId_reportsPopup = "popupReportsGrid"



  def templatePath = List("templates-hidden", "reports_grid")
  def template() =  Templates(templatePath) match {
    case Empty | Failure(_,_,_) =>
      throw new TechnicalException("Template for report grid not found. I was looking for %s.html".format(templatePath.mkString("/")))
    case Full(n) => n
  }
  def reportTemplate = chooseTemplate("reports", "report", template)

  def dispatch = {
    case "rulesGrid" => { _:NodeSeq => rulesGrid(getAllNodeInfos(), getFullNodeGroupLib(), getFullDirectiveLib()) }
  }

  def jsVarNameForId(tableId:String) = "oTable" + tableId

  def rulesGridWithUpdatedInfo(popup: Boolean = false, linkCompliancePopup:Boolean = true) = {
    rulesGrid(getAllNodeInfos(), getFullNodeGroupLib(), getFullDirectiveLib(), popup, linkCompliancePopup)
  }

  def rulesGrid(
      allNodeInfos: Box[Set[NodeInfo]]
    , groupLib    : Box[FullNodeGroupCategory]
    , directiveLib: Box[FullActiveTechniqueCategory]
    , popup       : Boolean = false
    , linkCompliancePopup:Boolean = true
  ) : NodeSeq = {
    showRulesDetails(popup,rules,linkCompliancePopup, allNodeInfos, groupLib, directiveLib) match {
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


      case Full(xml) =>
        val tableVar = jsVarNameForId(htmlId_rulesGridId)
        val onLoad =
          s"""
      /* Event handler function */
       ${tableVar} = $$('#${htmlId_rulesGridId}').dataTable({
        "asStripeClasses": [ 'color1', 'color2' ],
        "bAutoWidth": false,
        "bFilter" : true,
        "bPaginate" : true,
        "bLengthChange": true,
        "sPaginationType": "full_numbers",
        "bJQueryUI": true,
        "oLanguage": {
          "sZeroRecords": "No matching rules!",
          "sSearch": ""
        },
        "aaSorting": [[ 0, "asc" ]],
        "aoColumns": [${ if(showCheckboxColumn) """
          { "sWidth": "30px" , "bSortable" : false},""" else "" }
          { "sWidth": "90px" },
          { "sWidth": "120px"  },
          { "sWidth": "60px" },
          { "sWidth": "40px"  }${ if(!popup) """,
          { "sWidth": "20px", "bSortable" : false }""" else "" }
        ],
        "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>'
      });
      $$('.dataTables_filter input').attr("placeholder", "Search");
      createTooltip();
          """


    (
        <div id={htmlId_rulesGridZone}>
          <div id={htmlId_modalReportsPopup} class="nodisplay">
            <div id={htmlId_reportsPopup} ></div>
          </div>
          <table id={htmlId_rulesGridId} class="display" cellspacing="0">
            <thead>
              <tr class="head">
                { if(showCheckboxColumn) <th></th> else NodeSeq.Empty }
                <th>Name</th>
                <th>Category</th>
                <th>Status</th>
                <th>Compliance</th>
                { if (!popup) <th/> else NodeSeq.Empty }
              </tr>
            </thead>
            <tbody>
            {xml}
            </tbody>
          </table>
          <div class={htmlId_rulesGridId +"_pagination, paginatescala"} >
            <div id={htmlId_rulesGridId +"_paginate_area"}></div>
          </div>
        </div>
    ) ++
     Script(JsRaw(s"var ${tableVar};") &     //pop-ups for multiple Directives
      JsRaw( """var openMultiPiPopup = function(popupid) {
          createPopup(popupid);
          createTooltip();
     }""") &
 OnLoad(JsRaw(onLoad)))

  }
  }


  /////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private[this] def showRulesDetails(
      popup:Boolean
    , rules:Seq[Rule]
    , linkCompliancePopup:Boolean
    , allNodeInfos: Box[Set[NodeInfo]]
    , groupLib    : Box[FullNodeGroupCategory]
    , directiveLib: Box[FullActiveTechniqueCategory]) : Box[NodeSeq] = {
    sealed trait Line { val rule:Rule }

    // we compute beforehand the compliance, so that we have a single big query
    // to the database
    val complianceMap = computeCompliances(rules.toSet)

    case class OKLine(
        rule             : Rule
      , compliance       : Option[ComplianceLevel]
      , applicationStatus: ApplicationStatus
      , trackerVariables : Seq[(Directive,ActiveTechnique,Technique)]
      , targets          : Set[RuleTargetInfo]
    ) extends Line with HashcodeCaching

    case class ErrorLine(
        rule:Rule,
        trackerVariables: Box[Seq[(Directive,ActiveTechnique,Technique)]],
        targets:Box[Set[RuleTargetInfo]]
    ) extends Line with HashcodeCaching



    ////////////////////////////////////////////////////////////////////////////////////////////
    ///////////////////// actual start of the logic of the grid displaying /////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////



    def displayGridLines(directivesLib: FullActiveTechniqueCategory, groupsLib: FullNodeGroupCategory, nodes: Set[NodeInfo]) : NodeSeq = {
    //for each rule, get all the required info and display them
    val lines:Seq[Line] = rules.map { rule =>

      val trackerVariables: Box[Seq[(Directive, ActiveTechnique, Technique)]] =
        sequence(rule.directiveIds.toSeq) { id =>
          directivesLib.allDirectives.get(id) match {
            case Some((activeTechnique, directive)) =>
              techniqueRepository.getLastTechniqueByName(activeTechnique.techniqueName) match {
                case None => Failure("Can not find Technique for activeTechnique with name %s referenced in Rule with ID %s".format(activeTechnique.techniqueName, rule.id))
                case Some(technique) => Full((directive, activeTechnique.toActiveTechnique, technique))
              }
            case None => //it's an error if the directive ID is defined and found but it is not attached to an activeTechnique
                val error = Failure(s"Can not find Directive with ID '${id.value}' referenced in Rule with ID '${rule.id.value}'")
                logger.debug(error.messageChain, error)
                error
            }
        }

      val targetsInfo = sequence(rule.targets.toSeq) { target =>
        groupsLib.allTargets.get(target) match {
          case Some(t) => Full(t.toTargetInfo)
          case None => Failure(s"Can not find full information for target '${target}' referenced in Rule with ID '${rule.id.value}'")
        }
      }.map(x => x.toSet)

      (trackerVariables, targetsInfo) match {
        case (Full(seq), Full(targets)) =>
          val applicationStatus = getRuleApplicationStatus(rule, groupsLib, directivesLib, nodes)
          val compliance =  applicationStatus match {
            case _:NotAppliedStatus => Full(None)
            case _ => complianceMap.getOrElse(rule.id, Failure(s"Error when getting compliance for Rule ${rule.name}"))
          }
          compliance match {
            case e:EmptyBox => ErrorLine(rule, trackerVariables, targetsInfo)
            case Full(value) =>  OKLine(rule, value, applicationStatus, seq, targets)
          }
        case (x,y) =>
          if(rule.isEnabledStatus) {
          //the Rule has some error, try to disactivate it
            //and be sure to not get a Rules from a modification pop-up, because we don't want to commit changes along
            //with the disable.
            //it's only a try, so it may fails, we won't try again
            (for {
              r <- roRuleRepository.get(rule.id)
              _ <- woRuleRepository.update(r.copy(isEnabledStatus=false), ModificationId(uuidGen.newUuid), RudderEventActor,
            Some("Rule automatically disabled because it contains error (bad target or bad directives)"))
            } yield {
              logger.warn(s"Disabling rule '${rule.name}' (ID: '${rule.id.value}') because it refers missing objects. Go to rule's details and save, then enable it back to correct the problem.")
              x match {
                case f: Failure => logger.warn(s"Rule '${rule.name}' (ID: '${rule.id.value}' directive problem: " + f.messageChain)
                case _ => //
              }
              y match {
                case f: Failure => logger.warn(s"Rule '${rule.name}' (ID: '${rule.id.value}' target problem: " + f.messageChain)
                case _ => //
              }
            }) match {
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


    //now, build html lines
      lines.flatMap { l =>
        l match {
      case line:OKLine =>
        val tooltipId = Helpers.nextFuncName

        <div>{
        if(line.rule.shortDescription.size > 0){


         <div class="tooltipContent" id={tooltipId}>
           <h3>{line.rule.name}</h3>
           <div>{line.rule.shortDescription}</div>
         </div>
           }
        }
        <tr tooltipid={tooltipId} class="tooltipable" title="">
          { // CHECKBOX
            if(showCheckboxColumn) <td>{
              val isApplying = directiveApplication.map(d => line.rule.directiveIds.contains(d.directive.id)).getOrElse(false)
              SHtml.ajaxCheckbox(isApplying, value => directiveApplication.foreach(_.checkRule(line.rule.id, value)))}</td> else NodeSeq.Empty
          }
          <td>
         { // NAME

            if(popup) <a href={"""/secure/configurationManager/ruleManagement#{"ruleId":"%s"}""".format(line.rule.id.value)}>{detailsLink(line.rule, line.rule.name)}</a> else detailsLink(line.rule, line.rule.name)
          }</td>
          <td>{ // Category
            categoryService.shortFqdn(line.rule.category).getOrElse("Error")
          }</td>

          <td><b>{ // EFFECTIVE STATUS
            /*          <td>{ // OWN STATUS
            if (line.rule.isEnabledStatus) "Enabled" else "Disabled"
          }</td>*/
            line.applicationStatus match {
              case FullyApplied => Text("In application")
              case PartiallyApplied(seq) =>
                  val tooltipId = Helpers.nextFuncName
                  val why = seq.map { case (at, d) => "Policy " + d.name + " disabled" }.mkString(", ")

                 <span class="tooltip tooltipable" title="" tooltipid={tooltipId}>Partially applied</span>
                 <div class="tooltipContent" id={tooltipId}><h3>Reason(s)</h3><div>{why}</div></div>
              case x:NotAppliedStatus =>
                val isAllTargetsEnabled = line.targets.filter(t => !t.isEnabled).isEmpty
                val nodeSize = groupsLib.getNodeIds(line.rule.targets, nodes).size
                val conditions = Seq(
                    ( line.rule.isEnabled, "rule disabled" ),
                    ( line.trackerVariables.size > 0, "No policy defined"),
                    ( isAllTargetsEnabled, "Group disabled"),
                    ( nodeSize!=0, "Empty groups")
                 ) ++ line.trackerVariables.flatMap { case (pi, upt,pt) => Seq(
                    ( pi.isEnabled, "Policy " + pi.name + " disabled") ,
                    ( upt.isEnabled, "Technique for '" + pi.name + "' disabled")
                 )}

                val why =  conditions.collect { case (ok, label) if(!ok) => label }.mkString(", ")
                <span class="tooltip tooltipable" title="" tooltipid={line.rule.id.value}>Not applied</span>
                 <div class="tooltipContent" id={line.rule.id.value}><h3>Reason(s)</h3><div>{why}</div></div>
            }
          }</b></td>
          { //  COMPLIANCE
            buildComplianceChart(line.compliance, line.rule, linkCompliancePopup, nodes)
          }
          { if (!popup)
            <td class="parametersTd">{ //  RULE PARAMETERS
              detailsCallbackLink match {
                case None => Text("No parameters")
                case Some(callback) =>  SHtml.ajaxButton("Edit", {
                  () =>  callback(line.rule,"showEditForm")
                  }, ("class", "smallButton"))
                }
              }
            </td>
            else NodeSeq.Empty
          }
        </tr>
          </div>
      case line:ErrorLine =>
        <tr class="error">

            {// CHECKBOX
              if(showCheckboxColumn) {
              <td><input type="checkbox" name={line.rule.id.value} /></td>
            } else {
              NodeSeq.Empty
            } }
          <td>{ // NAME
            if(popup) <a href={"""/secure/configurationManager/ruleManagement#{"ruleId":"%s"}""".format(line.rule.id.value)}>{detailsLink(line.rule, line.rule.name)}</a> else detailsLink(line.rule, line.rule.name)
          }</td>
          <td>{ // Category
            categoryService.shortFqdn(line.rule.category).map(_.name).getOrElse("Error")
          }</td>
          <td>{ // DEPLOYMENT STATUS
            "N/A"
          }</td>
          <td class="compliance noCompliance">{ //  COMPLIANCE
            "N/A"
          }</td>{
            //detail and parameter only if not in a pop-up
            val detailsAndParam = if(popup) {
              NodeSeq.Empty
            } else {
          <td class="parametersTd">{
              detailsCallbackLink match {
      case None => Text("No parameters")
      case Some(callback) =>  SHtml.ajaxButton(<img src="/images/icTools.jpg"/>, {
                      () =>  callback(line.rule,"showEditForm")
                    }, ("class", "smallButton")) }
          }</td>
            }


          }
        </tr>
      } }
    }

    for {
      directivesLib <- directiveLib
      groupsLib     <- groupLib
      nodes         <- allNodeInfos
    } yield {
      displayGridLines(directivesLib, groupsLib, nodes)
    }

  }

  private[this] def computeCompliances(rules: Set[Rule]) : Map[RuleId, Box[Option[ComplianceLevel]]] = {
    reportingService.findImmediateReportsByRules(rules.map(_.id)).map { case (ruleId, entry) =>
      (ruleId,
          entry match {
            case e:EmptyBox => e
            case Full(None) => Full(Some(Applying)) // when we have a rule but nothing in the database, it means that it is currently being deployed
            case Full(Some(x)) if (x.directivesOnNodesExpectedReports.size==0) => Full(None)
            case Full(Some(x)) if x.getNodeStatus().exists(x => x.nodeReportType == PendingReportType ) => Full(Some(Applying))
            case Full(Some(x)) =>  Full(Some(new Compliance((100 * x.getNodeStatus().filter(x => x.nodeReportType == SuccessReportType).size) / x.getNodeStatus().size)))
          }
      )
    }
  }

  private[this] def detailsLink(rule:Rule, text:String) : NodeSeq = {
    detailsCallbackLink match {
      case None => Text(text)
      case Some(callback) => SHtml.a( () => callback(rule,"showForm"), Text(text))
    }
  }

  private[this] def buildComplianceChart(level:Option[ComplianceLevel], rule: Rule, linkCompliancePopup:Boolean, allNodes: Set[NodeInfo]) : NodeSeq = {

    val (complianceClass,content) =
      level match {
        case None => ("noCompliance",Text("N/A"))
        case Some(Applying) => ("applyingCompliance",Text("Applying"))
        case Some(NoAnswer) => ("noCompliance",Text("No answer"))
        case Some(Compliance(percent)) =>
          val complianceClass =  if (percent <= 10 ) "redCompliance"
            else if(percent >= 90) "greenCompliance"
              else "orangeCompliance"
          val text = percent.toString + "%"
          val res = if(linkCompliancePopup)
            detailsLink(rule, text)
          else Text(text)
          (complianceClass,res)
      }
    ("td [class+]" #> complianceClass&
     "td *+" #> content ) apply <td class="compliance"/>
  }

/*********************************************
  Popup for the reports
 ************************************************/
  private[this] def createPopup(rule: Rule, allNodes: Set[NodeInfo]) : NodeSeq = {

    def showReportDetail(batch : Box[Option[ExecutionBatch]]) : NodeSeq = {
    ( "#reportLine" #> {batch match {
            case e:EmptyBox => <div class="error">Could not fetch reporting info from database</div>
            case Full(None) => Text("No Reports")
            case Full(Some(reports)) =>
              reports.getNodeStatus().map {
                nodeStatus =>
                   allNodes.find ( _.id == nodeStatus.nodeId) match {
                     case Some(nodeInfo)  => {
                       val tooltipid = Helpers.nextFuncName
                       val xml:NodeSeq = (
                              "#node *" #>
                                <a class="unfoldable" href={"""/secure/nodeManager/searchNodes#{"nodeId":"%s"}""".format(nodeStatus.nodeId.value)}>
                                  <span class="curspoint">
                                    {nodeInfo.hostname}
                                  </span>
                                </a> &
                              "#severity *" #> ReportType.getSeverityFromStatus(nodeStatus.nodeReportType) &
                              "#severity [class+]" #> ReportType.getSeverityFromStatus(nodeStatus.nodeReportType).replaceAll(" ", "")
                       )(nodeLineXml)
                       xml
                     }
                     case None =>
                       <div class="error">Node with ID "{nodeStatus.nodeId.value}" is invalid</div>
                   }
              }
    }}
    ).apply(reportsGridXml)
    }


    def reportsGridXml : NodeSeq = {
    <table id="popupReportsGrid" cellspacing="0">
      <thead>
        <tr class="head">
          <th>Node<span/></th>
          <th class="severityWidth">Status<span/></th>
        </tr>
      </thead>
      <tbody>
        <div id="reportLine"/>
      </tbody>
    </table>
  }


    def nodeLineXml : NodeSeq = {
    <tr class="unfoldable">
      <td id="node"></td>
      <td id="severity" class="severityWidth" style="text-align:center;"></td>
    </tr>
  }


  val batch = reportingService.findImmediateReportsByRule(rule.id)

  <div class="simplemodal-title">
    <h1>List of nodes having the {Text(rule.name)} Rule</h1>
    <hr/>
  </div>
  <div class="simplemodal-content"> { bind("lastReportGrid",reportTemplate,
        "crName" -> Text(rule.name),
        "lines" -> showReportDetail(batch)
         )
    }
  <hr class="spacer" />
  </div>
  <div class="simplemodal-bottom">
    <hr/>
    <div class="popupButton">
      <span>
        <button class="simplemodal-close" onClick="return false;">
          Close
        </button>
      </span>
    </div>
  </div>
  }
}
sealed trait ComplianceLevel


case object Applying extends ComplianceLevel
case object NoAnswer extends ComplianceLevel
case class Compliance(val percent:Int) extends ComplianceLevel with HashcodeCaching




