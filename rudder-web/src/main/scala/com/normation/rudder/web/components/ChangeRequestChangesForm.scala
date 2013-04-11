/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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

import scala.xml._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.workflows._
import com.normation.rudder.web.model._
import bootstrap.liftweb._
import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.Helpers._
import net.liftweb.util._
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyToNodeGroupDiff
import com.normation.cfclerk.domain.TechniqueId
import net.liftweb.util.CanBind
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.SectionSpec
import com.normation.eventlog.EventActor
import org.joda.time.DateTime
import com.normation.rudder.web.model.JsInitContextLinkUtil._
import com.normation.rudder.domain.eventlog.AddChangeRequest
import com.normation.rudder.domain.eventlog.ModifyChangeRequest
import com.normation.rudder.domain.eventlog.DeleteChangeRequest
import com.normation.rudder.domain.eventlog.ChangeRequestEventLog
import com.normation.rudder.domain.eventlog.WorkflowStepChanged
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyNodeGroupDiff


object ChangeRequestChangesForm {
  def form =
    (for {
      xml <- Templates("templates-hidden" :: "components" :: "ComponentChangeRequest" :: Nil)
    } yield {
      chooseTemplate("component", "changes", xml)
    } ) openOr Nil
}


class ChangeRequestChangesForm(
  changeRequest:ChangeRequest
) extends DispatchSnippet with Loggable {
  import ChangeRequestChangesForm._

  private[this] val roDirectiveRepo = RudderConfig.roDirectiveRepository
  private[this] val techniqueRepo = RudderConfig.techniqueRepository
  private[this] val roGroupRepo = RudderConfig.roNodeGroupRepository
  private[this] val roRuleRepo = RudderConfig.roRuleRepository
  private[this] val changeRequestEventLogService =  RudderConfig.changeRequestEventLogService
  private[this] val workFlowEventLogService = RudderConfig.workflowEventLogService
  private[this] val eventLogDetailsService =  RudderConfig.eventLogDetailsService
  private[this] val diffService =  RudderConfig.diffService

  def dispatch = {
    case "changes" =>
      _ =>
        changeRequest match {
          case cr: ConfigurationChangeRequest =>
            ( "#changeTree ul *" #>  treeNode(cr).toXml &
              "#history *" #> displayHistory (cr.directives.values.map(_.changes).toList,cr.nodeGroups.values.map(_.changes).toList,cr.rules.values.map(_.changes).toList) &
              "#diff *" #> diff(cr.directives.values.map(_.changes).toList,cr.nodeGroups.values.map(_.changes).toList,cr.rules.values.map(_.changes).toList)
            ) (form) ++
            Script(JsRaw(s"""buildChangesTree("#changeTree","${S.contextPath}");
                             $$( "#changeDisplay" ).tabs();""") )
          case _ => Text("not implemented :(")
        }
  }

 def treeNode(changeRequest:ConfigurationChangeRequest) = new JsTreeNode{

  def directiveChild(directiveId:DirectiveId) = new JsTreeNode{
    val changes = changeRequest.directives(directiveId).changes
    val directiveName = changes.initialState.map(_._2.name).getOrElse(changes.firstChange.diff.directive.name)

    val body = SHtml.a(
        () => SetHtml("history",displayHistory(List(changes)))
      , <span>{directiveName}</span>
    )

    val children = Nil
  }


  val directivesChild = new JsTreeNode{
    val changes = changeRequest.directives.values.map(_.changes).toList
    val body = SHtml.a(
        () => SetHtml("history",displayHistory(changes) )
      , <span>Directives</span>
    )
    val children = changeRequest.directives.keys.map(directiveChild(_)).toList

    override val attrs = List(( "rel" -> { "changeType" } ),("id" -> { "directives"}))
  }

  def ruleChild(ruleId:RuleId) = new JsTreeNode{
    val changes = changeRequest.rules(ruleId).changes
    val ruleName = changes.initialState.map(_.name).getOrElse(changes.firstChange.diff.rule.name)
    val body = SHtml.a(
        () => SetHtml("history",displayHistory(Nil,Nil,List(changes)))
      , <span>{ruleName}</span>
    )

    val children = Nil
  }

  val rulesChild = new JsTreeNode{
    val changes = changeRequest.rules.values.map(_.changes).toList
    val body = SHtml.a(
        () => SetHtml("history",displayHistory(Nil,Nil,changes) )
      , <span>Rules</span>
    )
    val children = changeRequest.rules.keys.map(ruleChild(_)).toList
    override val attrs = List(( "rel" -> { "changeType" } ),("id" -> { "rules"}))
  }

  def groupChild(groupId:NodeGroupId) = new JsTreeNode{
    val changes = changeRequest.nodeGroups(groupId).changes
    val groupeName = changes.initialState.map(_.name).getOrElse(changes.firstChange.diff match{
           case a :AddNodeGroupDiff => a.group.name
           case d :DeleteNodeGroupDiff => d.group.name
           case modTo : ModifyToNodeGroupDiff => modTo.group.name
    } )
    val body = SHtml.a(
        () => SetHtml("history",displayHistory(Nil,List(changes)))
      , <span>{groupeName}</span>
    )

    val children = Nil
  }

  val groupsChild = new JsTreeNode{
    val changes =  changeRequest.nodeGroups.values.map(_.changes).toList
    val body = SHtml.a(
        () => SetHtml("history",displayHistory(Nil,changes) )
      , <span>Groups</span>
    )
    val children = Nil
    override val attrs = List(( "rel" -> { "changeType" } ),("id" -> { "groups"}))
  }

  val body = SHtml.a(
     () => SetHtml("history",displayHistory (
           changeRequest.directives.values.map(_.changes).toList
         , changeRequest.nodeGroups.values.map(_.changes).toList
         , changeRequest.rules.values.map(_.changes).toList
         ))
   , <span>Changes</span>
  )

  val children = directivesChild :: rulesChild :: groupsChild ::  Nil

  override val attrs = List(( "rel" -> { "changeType" } ),("id" -> { "changes"}))

}

  def displayHistory (
      directives : List[DirectiveChange] = Nil
    , groups     : List[NodeGroupChange] = Nil
    , rules      : List[RuleChange]      = Nil
  ) = {
    val crLogs = changeRequestEventLogService.getChangeRequestHistory(changeRequest.id).getOrElse(Seq())
    val wfLogs = workFlowEventLogService.getChangeRequestHistory(changeRequest.id).getOrElse(Seq())
    val lines =
      wfLogs.flatMap(displayWorkflowEvent(_)) ++
      crLogs.flatMap(displayChangeRequestEvent(_)) ++
      directives.flatMap(displayDirectiveChange(_)) ++
      groups.flatMap(displayNodeGroupChange(_)) ++
      rules.flatMap(displayRuleChange(_))


    val initDatatable = JsRaw(s"""
        $$('#changeHistory').dataTable( {
          "asStripeClasses": [ 'color1', 'color2' ],
          "bAutoWidth": false,
          "bFilter" : true,
          "bPaginate" : true,
          "bLengthChange": true,
          "sPaginationType": "full_numbers",
          "bJQueryUI": true,
          "aaSorting": [[ 2, "desc" ]],
          "oLanguage": {
          "sSearch": ""
          },
          "sDom": '<"dataTables_wrapper_top"fl>rt<"dataTables_wrapper_bottom"ip>',
          "aoColumns": [
            { "sWidth": "120px" },
            { "sWidth": "40px" },
            { "sWidth": "40px" },
            { "sWidth": "100px" }
          ],
        } );
        $$('.dataTables_filter input').attr("placeholder", "Search"); """)

    ( "#crBody" #> lines).apply(CRTable) ++
    Script(
      SetHtml("diff",diff(directives,groups,rules) ) &
      initDatatable
    )
  }
    val CRTable =
    <table id="changeHistory">
      <thead>
       <tr class="head tablewidth">
        <th>Action</th>
        <th>Actor</th>
        <th>Date</th>
        <th>Reason</th>
      </tr>
      </thead>
      <tbody >
      <div id="crBody"/>
      </tbody>
    </table>

  private[this] val xmlPretty = new scala.xml.PrettyPrinter(80, 2)

  private[this] val DirectiveXML =
    <div>
      <h4>Directive overview:</h4>
      <ul class="evlogviewpad">
        <li><b>ID:&nbsp;</b><value id="directiveID"/></li>
        <li><b>Name:&nbsp;</b><value id="directiveName"/></li>
        <li><b>Short description:&nbsp;</b><value id="shortDescription"/></li>
        <li><b>Technique name:&nbsp;</b><value id="techniqueName"/></li>
        <li><b>Technique version:&nbsp;</b><value id="techniqueVersion"/></li>
        <li><b>Priority:&nbsp;</b><value id="priority"/></li>
        <li><b>Enabled:&nbsp;</b><value id="isEnabled"/></li>
        <li><b>System:&nbsp;</b><value id="isSystem"/></li>
        <li><b>Long description:&nbsp;</b><value id="longDescription"/></li>
        <li><b>Parameters:&nbsp;</b><value id="parameters"/></li>
      </ul>
    </div>

  private[this] val RuleXML =
    <div>
      <h4>Rule overview:</h4>
      <ul class="evlogviewpad">
        <li><b>ID:&nbsp;</b><value id="ruleID"/></li>
        <li><b>Name:&nbsp;</b><value id="ruleName"/></li>
        <li><b>Short description:&nbsp;</b><value id="shortDescription"/></li>
        <li><b>Target:&nbsp;</b><value id="target"/></li>
        <li><b>Directives:&nbsp;</b><value id="policy"/></li>
        <li><b>Enabled:&nbsp;</b><value id="isEnabled"/></li>
        <li><b>System:&nbsp;</b><value id="isSystem"/></li>
        <li><b>Long description:&nbsp;</b><value id="longDescription"/></li>
      </ul>
    </div>

  private[this] def displaySimpleDiff[T] (
      diff    : Option[SimpleDiff[T]]
    , name    : String
    , default : NodeSeq
  ) = diff.map(value => displayFormDiff(value, name)).getOrElse(default)

  private[this] def displayRule(rule:Rule) = {
    def groupTargetDetails(groups: Set[RuleTarget]) = {
      <ul>
      {groups.map(group => <li>{group.target}</li>)}
      </ul>
    }

    def directiveTargetDetails(directives: Set[DirectiveId]) = {
      <ul>
      {directives.map{directive =>
        val directiveName = roDirectiveRepo.getDirective(directive).map(_.name).openOr("Unknown Directive")
        <li>{SHtml.a(() => S.redirectTo(directiveLink(directive)),Text(directiveName))}</li>}}
      </ul>
    }
    ( "#ruleID" #> rule.id.value.toUpperCase &
      "#ruleName" #> rule.name &
      "#target" #> groupTargetDetails(rule.targets) &
      "#policy" #> directiveTargetDetails(rule.directiveIds) &
      "#isEnabled" #> rule.isEnabled &
      "#isSystem" #> rule.isSystem &
      "#shortDescription" #> rule.shortDescription &
      "#longDescription" #> rule.longDescription
    ) (RuleXML)
  }


  private[this] def displayRuleDiff (
      diff : ModifyRuleDiff
    , rule : Rule
  ) = {
    def groupTargetDetails(groups: Set[RuleTarget]) = {
      <ul>
      {groups.map(group => <li>{group.target}</li>)}
      </ul>
    }

    def directiveTargetDetails(directives: Set[DirectiveId]) = {
      <ul>
      {directives.map{directive =>
        val directiveName = roDirectiveRepo.getDirective(directive).map(_.name).openOr("Unknown Directive")
        <li>{SHtml.a(() => S.redirectTo(directiveLink(directive)),Text(directiveName))}</li>}}
      </ul>
    }

    def directiveinfoDiff(directiveIds:Set[DirectiveId]) = directiveIds.map{ id =>
        val directive = roDirectiveRepo.getDirective(id)
        directive.map(
          directive => s" ${directive.name} (ID: ${id})"
        ).openOr("Unknown Directive")
    }.toSeq.sortBy(s => s).mkString("\n")


    ( "#ruleID" #> rule.id.value.toUpperCase &
      "#ruleName" #> displaySimpleDiff(diff.modName, "name", Text(rule.name)) &
      "#target" #> diff.modTarget.map(displayFormDiff(_, "target")(t => t.map(_.target).mkString("\n"))).getOrElse(groupTargetDetails(rule.targets)) &
      "#policy" #> diff.modDirectiveIds.map(displayFormDiff(_, "directive")(directiveinfoDiff)).getOrElse(directiveTargetDetails(rule.directiveIds)) &
      "#isEnabled"        #> displaySimpleDiff(diff.modIsActivatedStatus, "active", Text(rule.isEnabled.toString)) &
      "#shortDescription" #> displaySimpleDiff(diff.modShortDescription, "short", Text(rule.shortDescription)) &
      "#longDescription"  #> displaySimpleDiff(diff.modLongDescription, "long", Text(rule.longDescription))
    ) (RuleXML)
  }

  private[this] def displayDirective(directive:Directive, techniqueName:TechniqueName) = {
    val techniqueId = TechniqueId(techniqueName,directive.techniqueVersion)
    val parameters = techniqueRepo.get(techniqueId).map(_.rootSection) match {
      case Some(rs) =>
        xmlPretty.format(SectionVal.toXml(SectionVal.directiveValToSectionVal(rs,directive.parameters)))
      case None =>
        logger.error(s"Could not find rootSection for technique ${techniqueName.value} version ${directive.techniqueVersion}" )
        <div> directive.parameters </div>
      }

    ( "#directiveID" #> directive.id.value.toUpperCase &
      "#directiveName" #> directive.name &
      "#techniqueVersion" #> directive.techniqueVersion.toString &
      "#techniqueName" #> techniqueName.value &
      "#techniqueVersion" #> directive.techniqueVersion.toString &
      "#techniqueName" #> techniqueName.value &
      "#priority" #> directive.priority &
      "#isEnabled" #> directive.isEnabled &
      "#isSystem" #> directive.isSystem &
      "#shortDescription" #> directive.shortDescription &
      "#longDescription" #> directive.longDescription &
      "#parameters" #> <pre>{parameters}</pre>
    ) (DirectiveXML)
  }

  private[this] def displayDirectiveDiff (
        diff          : ModifyDirectiveDiff
      , directive     : Directive
      , techniqueName : TechniqueName
      , rootSection   : SectionSpec
  ) = {

    ( "#directiveID"      #> directive.id.value.toUpperCase &
      "#techniqueName"    #> techniqueName.value &
      "#isSystem"         #> directive.isSystem &
      "#directiveName"    #> displaySimpleDiff(diff.modName, "name", Text(directive.name)) &
      "#techniqueVersion" #> displaySimpleDiff(diff.modTechniqueVersion, "techniqueVersion", Text(directive.techniqueVersion.toString)) &
      "#priority"         #> displaySimpleDiff(diff.modPriority, "priority", Text(directive.priority.toString)) &
      "#isEnabled"        #> displaySimpleDiff(diff.modIsActivated, "active", Text(directive.isEnabled.toString)) &
      "#shortDescription" #> displaySimpleDiff(diff.modShortDescription, "short", Text(directive.shortDescription)) &
      "#longDescription"  #> displaySimpleDiff(diff.modLongDescription, "long", Text(directive.longDescription)) &
      "#parameters"       #> {
        implicit val fun = (section:SectionVal) => xmlPretty.format(SectionVal.toXml(section))
        val parameters = <pre>{fun(SectionVal.directiveValToSectionVal(rootSection,directive.parameters))}</pre>
        diff.modParameters.map(displayFormDiff(_,"parameters")).getOrElse(parameters)
      }
    ) (DirectiveXML)
  }

  def diff(directives : List[DirectiveChange],groups : List[NodeGroupChange], rules : List[RuleChange]) = <ul> {
      directives.flatMap(directiveChange =>
        <li>{
          directiveChange.change.map(_.diff match {
            case e @(_:AddDirectiveDiff|_:DeleteDirectiveDiff) =>
              val techniqueName = e.techniqueName
              val directive = e.directive
              displayDirective(directive, techniqueName)
            case ModifyToDirectiveDiff(techniqueName,directive,rootSection) =>
              directiveChange.initialState.map(init => (init._2,init._3)) match {
                case Some((initialDirective,initialRS)) =>
                  val techniqueId = TechniqueId(techniqueName,directive.techniqueVersion)
                  val diff = diffService.diffDirective(initialDirective, initialRS, directive, rootSection)
                  displayDirectiveDiff(diff,directive,techniqueName,rootSection)
                case None =>
                  val msg = s"Could not display diff for ${directive.name} (${directive.id.value.toUpperCase})"
                  logger.error(msg)
                  <div>msg</div>
              }
          } ).getOrElse(<div>Error</div>)
        }</li>

      ) ++
      groups.map(a => <li>a group change></li>)++
      rules.flatMap(ruleChange =>
        <li>{
          ruleChange.change.map{_.diff match {

            case ModifyToRuleDiff(rule) =>
              ruleChange.initialState match {
                case Some(initialRule) =>
                  val diff = diffService.diffRule(initialRule, rule)
                  displayRuleDiff(diff, rule)
                case None =>
                  val msg = s"Could not display diff for ${rule.name} (${rule.id.value.toUpperCase})"
                  logger.error(msg)
                  <div>msg</div>
              }
           case diff =>
              val rule = diff.rule
              displayRule(rule)
          } }.getOrElse(<div>Error</div>)
        }</li>
          )

  }</ul>

  private[this] def displayFormDiff[T](diff: SimpleDiff[T], name:String)(implicit fun: T => String = (t:T) => t.toString) = {
    <pre style="width:200px;" id={s"before${name}"}
    class="nodisplay">{fun(diff.oldValue)}</pre>
    <pre style="width:200px;" id={s"after${name}"}
    class="nodisplay">{fun(diff.newValue)}</pre>
    <pre id={s"result${name}"} ></pre>  ++
    Script(
      OnLoad(
        JsRaw(
          s"""
            var before = "before${name}";
            var after  = "after${name}";
            var result = "result${name}";
            makeDiff(before,after,result);"""
        )
      )
    )
  }

  val CRLine =
    <tr>
      <td id="action"/>
      <td id="actor"/>
      <td id="date"/>
      <td id="reason"/>
   </tr>

  def displayEvent(action:NodeSeq, actor:EventActor, date:DateTime, changeMessage:String ) =
   ( "#action *" #> {action } &
     "#actor *" #> actor.name &
     "#reason *" #> changeMessage &
     "#date *"  #> DateFormaterService.getFormatedDate(date)
   ).apply(CRLine)

  def displayChangeRequestEvent(crEvent:ChangeRequestEventLog) = {
    val action = Text(crEvent match {
           case AddChangeRequest(_) => "Change request created"
           case ModifyChangeRequest(_) => "Change request details modified"
           case DeleteChangeRequest(_) => "Change request deleted"
    })
    displayEvent(action,crEvent.principal,crEvent.creationDate,crEvent.eventDetails.reason.getOrElse(""))
  }

  def displayWorkflowEvent(wfEvent: WorkflowStepChanged)= {
    val step = eventLogDetailsService.getWorkflotStepChange(wfEvent.details)
    val action = step.map(step => Text(s"State changed from ${step.from} to ${step.to}")).getOrElse(Text("State changed"))
    displayEvent(action,wfEvent.principal,wfEvent.creationDate,wfEvent.eventDetails.reason.getOrElse(""))
   }

  def displayRuleChange(ruleChange: RuleChange) = {
    val action = ruleChange.firstChange.diff match {
           case AddRuleDiff(rule) => Text(s"Create rule ${rule.name}")
           case DeleteRuleDiff(rule) => <span>Delete rule {<a href={ruleLink(rule.id)} onclick="noBubble(event);">{rule.name}</a>}</span>
           case ModifyToRuleDiff(rule) => <span>Modify rule {<a href={ruleLink(rule.id)} onclick="noBubble(event);">{rule.name}</a>}</span>
         }
   displayEvent(action,ruleChange.firstChange.actor,ruleChange.firstChange.creationDate, ruleChange.firstChange.reason.getOrElse(""))
  }

  def displayNodeGroupChange(groupChange: NodeGroupChange) = {
    val action = groupChange.firstChange.diff match {
           case AddNodeGroupDiff(group) => Text(s"Create group ${group.name}")
           case DeleteNodeGroupDiff(group) => <span>Delete group {<a href={groupLink(group.id)} onclick="noBubble(event);">{group.name}</a>}</span>
           case ModifyToNodeGroupDiff(group) => <span>Modify group {<a href={groupLink(group.id)} onclick="noBubble(event);">{group.name}</a>}</span>
         }
   displayEvent(action,groupChange.firstChange.actor,groupChange.firstChange.creationDate, groupChange.firstChange.reason.getOrElse(""))
  }


  def displayDirectiveChange(directiveChange: DirectiveChange) = {
    val action = directiveChange.firstChange.diff match {
           case a : AddDirectiveDiff => Text(s"Create Directive ${a.directive.name}")
           case d : DeleteDirectiveDiff => <span>Delete Directive {<a href={directiveLink(d.directive.id)} onclick="noBubble(event);">{d.directive.name}</a>}</span>
           case m : ModifyToDirectiveDiff => <span>Modify Directive {<a href={directiveLink(m.directive.id)} onclick="noBubble(event);">{m.directive.name}</a>}</span>
         }
   displayEvent(action,directiveChange.firstChange.actor,directiveChange.firstChange.creationDate, directiveChange.firstChange.reason.getOrElse(""))
  }

}