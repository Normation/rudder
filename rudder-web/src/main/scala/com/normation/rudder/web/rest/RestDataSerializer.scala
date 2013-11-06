/*
*************************************************************************************
* Copyright 2013 Normation SAS
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

package com.normation.rudder.web.rest

import com.normation.rudder.domain.workflows._
import net.liftweb.common._
import com.normation.rudder.domain.policies._
import net.liftweb.json._
import com.normation.cfclerk.domain._
import com.normation.rudder.services.modification.DiffService
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.queries.Query
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.parameters._
import com.normation.inventory.domain._
import com.normation.rudder.domain.servers.Srv



/**
 *  Centralize all function to serialize datas as valid answer for API Rest
 */
trait RestDataSerializer {

  def serializeDirective(technique:Technique, directive : Directive, crId: Option[ChangeRequestId]): JValue

  def serializeCR(changeRequest:ChangeRequest , status : WorkflowNodeId, isAcceptable : Boolean) : JValue

  def serializeGroup (group : NodeGroup, crId: Option[ChangeRequestId]): JValue

  def serializeParameter (parameter:Parameter , crId: Option[ChangeRequestId]): JValue

  def serializeRule (rule:Rule , crId: Option[ChangeRequestId]): JValue

  def serializeServerInfo (srv : Srv, status : String) : JValue

  def serializeNodeInfo(nodeInfo : NodeInfo, status : String) : JValue

  def serializeInventory (inventory : FullInventory, status : String) : JValue

}

case class RestDataSerializerImpl (
    readTechnique : TechniqueRepository
  , diffService   : DiffService
) extends RestDataSerializer with Loggable {

  import net.liftweb.json.JsonDSL._




  def serializeNodeInfo(nodeInfo : NodeInfo, status : String) : JValue ={
    (   ("id"          -> nodeInfo.id.value)
      ~ ("status"      -> status)
      ~ ("hostname"    -> nodeInfo.hostname)
      ~ ("osName"      -> nodeInfo.osName)
      ~ ("osVersion"   -> nodeInfo.osVersion)
      ~ ("machyneType" -> nodeInfo.machineType)
    )

  }

  def serializeInventory (inventory : FullInventory, status : String) : JValue ={

    val machineType = inventory.machine.map(_.machineType match {
      case PhysicalMachineType => "Physical"
      case VirtualMachineType(kind) => "Virtual"
    }).getOrElse("No machine Inventory")

    (   ("id"          -> inventory.node.main.id.value)
      ~ ("status"      -> status)
      ~ ("hostname"    -> inventory.node.main.hostname)
      ~ ("osName"      -> inventory.node.main.osDetails.os.name)
      ~ ("osVersion"   -> inventory.node.main.osDetails.version.toString)
      ~ ("machyneType" -> machineType)
    )
  }

  // Not accepted node
  def serializeServerInfo (srv : Srv, status : String) : JValue ={

    (   ("id"          -> srv.id.value)
      ~ ("status"      -> status)
      ~ ("hostname"    -> srv.hostname)
      ~ ("osName"      -> srv.osName)
    )

  }


  def serializeRule (rule:Rule , crId: Option[ChangeRequestId]): JValue = {

   (   ( "changeRequestId"  -> crId.map(_.value.toString))
     ~ ( "id"               -> rule.id.value )
     ~ ( "displayName"      -> rule.name )
     ~ ( "shortDescription" -> rule.shortDescription )
     ~ ( "longDescription"  -> rule.longDescription )
     ~ ( "directives"       -> rule.directiveIds.map(_.value) )
     ~ ( "targets"          -> rule.targets.map(_.target) )
     ~ ( "enabled"          -> rule.isEnabledStatus )
     ~ ( "system"           -> rule.isSystem )
   )
  }

  def serializeParameter (parameter:Parameter , crId: Option[ChangeRequestId]): JValue = {
   (   ( "changeRequestId" -> crId.map(_.value.toString))
     ~ ( "id"              -> parameter.name.value )
     ~ ( "value"           -> parameter.value )
     ~ ( "description"     -> parameter.description )
     ~ ( "overridable"     -> parameter.overridable )
   )
  }

  def serializeGroup (group : NodeGroup, crId: Option[ChangeRequestId]): JValue = {
  val query = group.query.map(query => query.toJSON)
   (   ("changeRequestId" -> crId.map(_.value.toString))
     ~ ("id"              -> group.id.value)
     ~ ("displayName"     -> group.name)
     ~ ("description"     -> group.description)
     ~ ("query"           -> query)
     ~ ("nodeIds"         -> group.serverList.map(_.value))
     ~ ("isDynamic"       -> group.isDynamic)
     ~ ("isEnabled"       -> group.isEnabled )
   )
  }

  def serializeSectionVal(sv:SectionVal, sectionName:String = SectionVal.ROOT_SECTION_NAME): JValue = {
    val variables =
      sv.variables.toSeq.sortBy(_._1).map {
        case (variable,value) =>
          ("var" ->
              ("name" -> variable)
            ~ ("value" -> value)
         ) }
    val section =
      for {
        (sectionName, sectionIterations) <- sv.sections.toSeq.sortBy(_._1)
        sectionValue <- sectionIterations
      } yield {
        serializeSectionVal(sectionValue,sectionName)
      }

      ("section" ->
          ("name" -> sectionName)
        ~ ("vars" ->  (if (variables.isEmpty) None else Some(variables)))
        ~ ("sections" -> (if (section.isEmpty) None else Some(section)))
      )
    }

  def serializeDirective(technique:Technique, directive : Directive, crId: Option[ChangeRequestId]): JValue = {
    val sectionVal = serializeSectionVal(SectionVal.directiveValToSectionVal(technique.rootSection, directive.parameters))
    (   ("changeRequestId" -> crId.map(_.value.toString))
      ~ ("id"               -> directive.id.value)
      ~ ("displayName"      -> directive.name)
      ~ ("shortDescription" -> directive.shortDescription)
      ~ ("longDescription"  -> directive.longDescription)
      ~ ("techniqueName"    -> technique.id.name.value)
      ~ ("techniqueVersion" -> directive.techniqueVersion.toString)
      ~ ("parameters"       -> sectionVal )
      ~ ("priority"         -> directive.priority)
      ~ ("isEnabled"        -> directive.isEnabled )
      ~ ("isSystem"         -> directive.isSystem )
    )
  }

  def displaySimpleDiff[T](diff: SimpleDiff[T])( implicit convert : T => JValue) : JValue = {
   (   ("from" -> convert(diff.oldValue))
     ~ ("to"   -> convert(diff.newValue))
   )
  }
  private[this] val create = "create"
  private[this] val delete = "delete"
  private[this] val modify = "modify"

  def serializeRuleChange(change : RuleChange): Box[JValue] = {

    def serializeRuleDiff(diff:ModifyRuleDiff,initialState:Rule) : JValue= {
      implicit def convert[T] (value : T) : JValue = value
      def convertDirectives(dl : Set[DirectiveId]) : JValue = dl.map(_.value).toList
      def convertTargets(t : Set[RuleTarget]) : JValue = t.map(_.target).toList

      val name :JValue             = diff.modName.map(displaySimpleDiff(_) ).getOrElse(initialState.name)
      val shortDescription :JValue = diff.modShortDescription.map(displaySimpleDiff(_)).getOrElse(initialState.shortDescription)
      val longDescription  :JValue = diff.modLongDescription.map(displaySimpleDiff(_)).getOrElse(initialState.longDescription)
      val targets :JValue          = diff.modTarget.map(displaySimpleDiff(_)(convertTargets)).getOrElse(initialState.targets.map(_.target).toList)
      val directives :JValue       = diff.modDirectiveIds.map(displaySimpleDiff(_)(convertDirectives)).getOrElse(initialState.directiveIds.map(_.value).toList)
      val enabled :JValue          = diff.modIsActivatedStatus.map(displaySimpleDiff(_)).getOrElse(initialState.isEnabled)

      (   ( "id"               -> initialState.id.value)
        ~ ( "displayName"      -> name )
        ~ ( "shortDescription" -> shortDescription )
        ~ ( "longDescription"  -> longDescription )
        ~ ( "directives"       -> directives )
        ~ ( "targets"          -> targets )
        ~ ( "enabled"          -> enabled )
      )
    }

    for {
      item <- change.change
      diff = item.diff
    } yield {
      diff match {
        case AddRuleDiff(rule) =>
          val change = serializeRule(rule,None)
          (   ("action" -> create)
            ~ ("change" -> change)
          )

        case DeleteRuleDiff(rule) =>
          val change = serializeRule(rule,None)
          (   ("action" -> delete)
            ~ ("change" -> change)
          )

        case ModifyToRuleDiff(rule) =>
          val result = change.initialState match {
            case Some(init) =>
              val diff = diffService.diffRule(init, rule)
              serializeRuleDiff(diff, init)
            case None => JString(s"Error while fetching initial state of change request.")

          }
          (   ("action" -> modify)
            ~ ("change" -> result)
          )

      }
    }
  }


  def serializeGlobalParameterChange(change : GlobalParameterChange): Box[JValue] = {

    def serializeGlobalParameterDiff(diff:ModifyGlobalParameterDiff,initialState:GlobalParameter) : JValue= {
      implicit def convert[T] (value : T) : JValue = value.toString

      val description :JValue = diff.modDescription.map(displaySimpleDiff(_)).getOrElse(initialState.description)
      val value :JValue       = diff.modValue.map(displaySimpleDiff(_)).getOrElse(initialState.value)
      val overridable :JValue = diff.modOverridable.map(displaySimpleDiff(_)).getOrElse(initialState.overridable)

      (   ("name"        -> initialState.name)
        ~ ("value"       -> value)
        ~ ("description" -> description)
        ~ ("overridable" -> overridable)
      )
    }

    for {
      item <- change.change
      diff = item.diff
    } yield {
      diff match {
        case AddGlobalParameterDiff(parameter) =>
          val change = serializeParameter(parameter,None)
          (   ("action" -> create)
            ~ ("change" -> change)
          )

        case DeleteGlobalParameterDiff(parameter) =>
          val change = serializeParameter(parameter,None)
          (   ("action" -> delete)
            ~ ("change" -> change)
          )

        case ModifyToGlobalParameterDiff(parameter) =>

          val result = change.initialState match {
            case Some(init) =>
              val diff = diffService.diffGlobalParameter(init, parameter)
              serializeGlobalParameterDiff(diff, init)
            case None => JString(s"Error while fetching initial state of change request.")

          }
          (   ("action" -> modify)
            ~ ("change" -> result)
          )

      }
    }
  }


  def serializeGroupChange(change : NodeGroupChange): Box[JValue] = {

    def serializeNodeGroupDiff(diff:ModifyNodeGroupDiff,initialState:NodeGroup) : JValue= {
      implicit def convert[T] (value : T) : JValue = value
      def convertNodeList(nl : Set[NodeId]) : JValue = nl.map(_.value).toList
      def convertQuery(q : Option[Query]) : JValue = q.map(_.toString)

      val name :JValue        = diff.modName.map(displaySimpleDiff(_) ).getOrElse(initialState.name)
      val description :JValue = diff.modDescription.map(displaySimpleDiff(_)).getOrElse(initialState.description)
      val query :JValue       = diff.modQuery.map(displaySimpleDiff(_)(convertQuery)).getOrElse(initialState.query.map(_.toString))
      val serverList :JValue  = diff.modNodeList.map(displaySimpleDiff(_)(convertNodeList)).getOrElse(initialState.serverList.map(v => (v.value)).toList)
      val dynamic :JValue     = diff.modIsDynamic.map(displaySimpleDiff(_)).getOrElse(initialState.isDynamic)
      val enabled :JValue     = diff.modIsActivated.map(displaySimpleDiff(_)).getOrElse(initialState.isEnabled)

      (    ("id"          -> initialState.id.value)
         ~ ("displayName" -> name)
         ~ ("description" -> description)
         ~ ("query"       -> query)
         ~ ("nodeIds"     -> serverList)
         ~ ("isDynamic"   -> dynamic)
         ~ ("isEnabled"   -> enabled )
       )
    }

    for {
      item <- change.change
      diff = item.diff
    } yield {
      diff match {
        case AddNodeGroupDiff(group) =>
          val change = serializeGroup(group,None)
          (   ("action" -> create)
            ~ ("change" -> change)
          )
        case DeleteNodeGroupDiff(group) =>
          val change = serializeGroup(group,None)
          (   ("action" -> delete)
            ~ ("change" -> change)
          )

        case ModifyToNodeGroupDiff(group) =>
          val result = change.initialState match {
            case Some(init) =>
              val diff = diffService.diffNodeGroup(init, group)
              serializeNodeGroupDiff(diff, init)
            case None => JString(s"Error while fetching initial state of change request.")
          }
          logger.info(result)
          (   ("action" -> modify)
            ~ ("change" -> result)
          )

      }
    }
  }

  def serializeDirectiveChange(change : DirectiveChange): Box[JValue] = {
    def serializeDirectiveDiff(diff:ModifyDirectiveDiff,initialState:Directive, technique:Technique) : JValue= {
      implicit def convert[T] (value : T) : JValue =  value
      def convertParameters(sv : SectionVal) : JValue = serializeSectionVal(sv)
      val name :JValue             = diff.modName.map(displaySimpleDiff(_) ).getOrElse(initialState.name)
      val shortDescription :JValue = diff.modShortDescription.map(displaySimpleDiff(_)).getOrElse(initialState.shortDescription)
      val longDescription :JValue  = diff.modLongDescription.map(displaySimpleDiff(_)).getOrElse(initialState.longDescription)
      val techniqueVersion :JValue = diff.modTechniqueVersion.map(displaySimpleDiff(_)(_.toString)).getOrElse(initialState.techniqueVersion.toString)
      val priority :JValue         = diff.modPriority.map(displaySimpleDiff(_)).getOrElse(initialState.priority)
      val enabled :JValue          = diff.modIsActivated.map(displaySimpleDiff(_)).getOrElse(initialState.isEnabled)
      val initialParams :JValue    = serializeSectionVal(SectionVal.directiveValToSectionVal(technique.rootSection, initialState.parameters))
      val parameters :JValue       = diff.modParameters.map(displaySimpleDiff(_)(convertParameters)).getOrElse(initialParams)

      (   ("id"               -> initialState.id.value)
        ~ ("displayName"      -> name )
        ~ ("shortDescription" -> shortDescription)
        ~ ("longDescription"  -> longDescription)
        ~ ("techniqueName"    -> technique.id.name.value)
        ~ ("techniqueVersion" -> techniqueVersion)
        ~ ("parameters"       -> parameters )
        ~ ("priority"         -> priority)
        ~ ("isEnabled"        -> enabled )
        ~ ("isSystem"         -> initialState.isSystem )
      )
    }

    for {
      item <- change.change
      diff = item.diff
    } yield {
      diff match {
        case AddDirectiveDiff(techniqueName,directive) =>
          val technique =  readTechnique.get(TechniqueId(techniqueName,directive.techniqueVersion))
          val change = technique.map(serializeDirective(_,directive,None)).getOrElse(JString(s"Error while fetchg technique ${techniqueName.value}"))
          (   ("action" -> create)
            ~ ("change" -> change)
          )

        case DeleteDirectiveDiff(techniqueName,directive) =>
          val technique =  readTechnique.get(TechniqueId(techniqueName,directive.techniqueVersion))
          val change = technique.map(serializeDirective(_,directive,None)).getOrElse(JString(s"Error while fetchg technique ${techniqueName.value}"))
          (   ("action" -> delete)
            ~ ("change" -> change)
          )
        case ModifyToDirectiveDiff(techniqueName,directive,rootSection) =>
          val technique =  readTechnique.get(TechniqueId(techniqueName,directive.techniqueVersion))

          val result = change.initialState match {
            case Some((techniqueName,init,rs)) =>
              val diff = diffService.diffDirective(init, rs, directive, rootSection,techniqueName)
              technique.map(t => serializeDirectiveDiff(diff, init, t)).getOrElse(JString("Error while fetching technique"))
            case None => JString(s"Error while fetching initial state of change request.")
          }
          (   ("action" -> modify)
            ~ ("change" -> result)
          )


      }
    }
  }


  def serializeCR(changeRequest:ChangeRequest , status : WorkflowNodeId, isAcceptable : Boolean) = {

    val changes : JValue = changeRequest match {
      case cr : ConfigurationChangeRequest =>
      val directives = cr.directives.values.map(ch => serializeDirectiveChange(ch.changes).getOrElse(JString(s"Error while serializing directives from CR ${changeRequest.id}")))
      val groups     = cr.nodeGroups.values.map(ch => serializeGroupChange(ch.changes).getOrElse(JString(s"Error while serializing groups from CR ${changeRequest.id}")))
      val parameters = cr.globalParams.values.map(ch => serializeGlobalParameterChange(ch.changes).getOrElse(JString(s"Error while serializing Parameters from CR ${changeRequest.id}")))
      val rules = cr.rules.values.map(ch => serializeRuleChange(ch.changes).getOrElse(JString(s"Error while serializing Rules from CR ${changeRequest.id}")))
      (   ("directives" -> directives)
        ~ ("rules"      -> rules)
        ~ ("groups"     -> groups)
        ~ ("parameters" -> parameters)
      )
      case _ => JNothing
    }
    (   ("id"           -> changeRequest.id.value)
      ~ ("displayName"  -> changeRequest.info.name)
      ~ ("status"       -> status.value)
      ~ ("created by"   -> changeRequest.owner)
      ~ ("isAcceptable" -> isAcceptable)
      ~ ("description"  -> changeRequest.info.description)
      ~ ("changes"      -> changes)
    )
  }

}