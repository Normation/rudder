/*
 *************************************************************************************
 * Copyright 2013 Normation SAS
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

package com.normation.rudder.apidata

import com.normation.cfclerk.domain._
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.inventory.domain._
import com.normation.inventory.domain.NodeId
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiAccountKind.{PublicApi => PublicApiAccount}
import com.normation.rudder.api.ApiAccountKind.System
import com.normation.rudder.api.ApiAccountKind.User
import com.normation.rudder.api.ApiAuthorization.{None => NoAccess}
import com.normation.rudder.api.ApiAuthorization.ACL
import com.normation.rudder.api.ApiAuthorization.RO
import com.normation.rudder.api.ApiAuthorization.RW
import com.normation.rudder.api.ApiVersion
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.properties._
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.servers.Srv
import com.normation.rudder.domain.workflows._
import com.normation.rudder.repository.FullActiveTechnique
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.healthcheck.HealthcheckResult
import com.normation.rudder.services.healthcheck.HealthcheckResult.Critical
import com.normation.rudder.services.healthcheck.HealthcheckResult.Ok
import com.normation.rudder.services.healthcheck.HealthcheckResult.Warning
import com.normation.rudder.services.modification.DiffService
import com.normation.utils.DateFormaterService
import net.liftweb.common._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import org.joda.time.DateTime
import zio.json.ast.Json
import zio.json.ast.Json.Str

sealed trait DetailLevel {
  def value: String
}

case object FullDetails    extends DetailLevel {
  val value = "full"
}
case object MinimalDetails extends DetailLevel {
  val value = "minimal"
}

/**
 *  Centralize all function to serialize data as valid answer for API Rest
 */
trait RestDataSerializer {

  def serializeDirective(technique: Technique, directive: Directive, crId: Option[ChangeRequestId]): JValue

  def serializeCR(changeRequest: ChangeRequest, status: WorkflowNodeId, isAcceptable: Boolean, apiVersion: ApiVersion): JValue

  def serializeGroup(group: NodeGroup, cat: Option[NodeGroupCategoryId], crId: Option[ChangeRequestId]): JValue
  def serializeGroupCategory(
      category:             FullNodeGroupCategory,
      parent:               NodeGroupCategoryId,
      detailLevel:          DetailLevel,
      apiVersion:           ApiVersion
  ): JValue

  def serializeParameter(parameter: GlobalParameter, crId: Option[ChangeRequestId]): JValue

  def serializeRule(rule: Rule, crId: Option[ChangeRequestId]): JValue
  def serializeRuleCategory(
      category:           RuleCategory,
      parent:             RuleCategoryId,
      rules:              Map[RuleCategoryId, Seq[Rule]],
      detailLevel:        DetailLevel
  ): JValue

  def serializeServerInfo(srv: Srv, status: String): JValue

  def serializeNodeInfo(nodeInfo: NodeInfo, status: String): JValue
  def serializeNode(node:         Node): JValue

  def serializeInventory(inventory: FullInventory, status: String): JValue

  def serializeInventory(
      nodeInfo:    NodeInfo,
      status:      InventoryStatus,
      optRunDate:  Option[DateTime],
      inventory:   Option[FullInventory],
      software:    Seq[Software],
      detailLevel: NodeDetailLevel
  ): JValue

  def serializeTechnique(technique: FullActiveTechnique): JValue

  def serializeTechnique(technique: Technique): Json

  def serializeHealthcheckResult(check: HealthcheckResult): JValue

}

final case class RestDataSerializerImpl(
    readTechnique: TechniqueRepository,
    diffService:   DiffService
) extends RestDataSerializer with Loggable {

  private[this] def serializeMachineType(machine: Option[MachineType]): JValue = {
    machine match {
      case None                           => "No machine Inventory"
      case Some(UnknownMachineType)       => "Unknown"
      case Some(PhysicalMachineType)      => "Physical"
      case Some(VirtualMachineType(kind)) => "Virtual"
    }
  }

  def serializeNodeInfo(nodeInfo: NodeInfo, status: String): JValue = {
    (("id"           -> nodeInfo.id.value)
    ~ ("status"      -> status)
    ~ ("hostname"    -> nodeInfo.hostname)
    ~ ("osName"      -> nodeInfo.osDetails.os.name)
    ~ ("osVersion"   -> nodeInfo.osDetails.version.value)
    ~ ("machineType" -> serializeMachineType(nodeInfo.machine.map(_.machineType))))
  }

  def serializeNode(node: Node): JValue = {
    (("id"          -> node.id.value)
    ~ ("properties" -> node.properties.sortBy(_.name).toApiJson)
    ~ ("policyMode" -> node.policyMode.map(_.name).getOrElse("default"))
    ~ ("state"      -> node.state.name))
  }

  def serializeInventory(
      nodeInfo:    NodeInfo,
      status:      InventoryStatus,
      optRunDate:  Option[DateTime],
      inventory:   Option[FullInventory],
      software:    Seq[Software],
      detailLevel: NodeDetailLevel
  ): JValue = {
    detailLevel.toJson(nodeInfo, status, optRunDate, inventory, software)
  }

  def serializeInventory(inventory: FullInventory, status: String): JValue = {

    (("id"           -> inventory.node.main.id.value)
    ~ ("status"      -> status)
    ~ ("hostname"    -> inventory.node.main.hostname)
    ~ ("osName"      -> inventory.node.main.osDetails.os.name)
    ~ ("osVersion"   -> inventory.node.main.osDetails.version.toString)
    ~ ("machineType" -> serializeMachineType(inventory.machine.map(_.machineType))))
  }

  // Not accepted node
  def serializeServerInfo(srv: Srv, status: String): JValue = {

    (("id"        -> srv.id.value)
    ~ ("status"   -> status)
    ~ ("hostname" -> srv.hostname)
    ~ ("osName"   -> srv.osName))

  }

  def serializeTags(tags: Tags): JValue = {
    JArray(tags.tags.toList.sortBy(_.name.value).map(t => JObject(JField(t.name.value, t.value.value) :: Nil)).toList)
  }

  def serializeRule(rule: Rule, crId: Option[ChangeRequestId]): JValue = {
    (("changeRequestId"   -> crId.map(_.value.toString))
    ~ ("id"               -> rule.id.uid.value)
    ~ ("revision"         -> rule.id.rev.toOptionString)
    ~ ("displayName"      -> rule.name)
    ~ ("categoryId"       -> rule.categoryId.value)
    ~ ("shortDescription" -> rule.shortDescription)
    ~ ("longDescription"  -> rule.longDescription)
    ~ ("directives"       -> rule.directiveIds.toList.map(_.serialize).sorted)
    ~ ("targets"          -> rule.targets.map(_.toJson))
    ~ ("enabled"          -> rule.isEnabledStatus)
    ~ ("system"           -> rule.isSystem)
    ~ ("tags"             -> serializeTags(rule.tags)))
  }

  def serializeRuleCategory(
      category:    RuleCategory,
      parent:      RuleCategoryId,
      rulesMap:    Map[RuleCategoryId, Seq[Rule]],
      detailLevel: DetailLevel
  ): JValue = {

    val ruleList = rulesMap.get(category.id).getOrElse(Nil).sortBy(_.id.serialize)
    val children = category.childs.sortBy(_.id.value)
    val (rules, categories): (Seq[JValue], Seq[JValue]) = detailLevel match {
      case FullDetails    =>
        (ruleList.map(serializeRule(_, None)), children.map(serializeRuleCategory(_, category.id, rulesMap, detailLevel)))
      case MinimalDetails =>
        (ruleList.map(rule => JString(rule.id.serialize)), children.map(cat => JString(cat.id.value)))
    }
    (("id" -> category.id.value)
    ~ ("name"        -> category.name)
    ~ ("description" -> category.description)
    ~ ("parent"      -> parent.value)
    ~ ("categories"  -> categories)
    ~ ("rules"       -> rules))
  }

  def serializeParameter(parameter: GlobalParameter, crId: Option[ChangeRequestId]): JValue = {
    (("changeRequestId" -> crId.map(_.value.toString))
    ~ ("id"             -> parameter.name)
    ~ ("value"          -> parameter.jsonValue)
    ~ ("description"    -> parameter.description)
    ~ ("inheritMode"    -> parameter.inheritMode.flatMap(x => if (x == InheritMode.Default) None else Some(x.value)))
    ~ ("provider"       -> parameter.provider.flatMap(x => if (x == PropertyProvider.defaultPropertyProvider) None else Some(x.value))))
  }

  override def serializeGroup(group: NodeGroup, cat: Option[NodeGroupCategoryId], crId: Option[ChangeRequestId]): JValue = {
    val query = group.query.map(query => query.toJSON)
    (
      ("changeRequestId" -> crId.map(_.value.toString))
      ~ ("id"            -> group.id.serialize)
      ~ ("displayName"   -> group.name)
      ~ ("description"   -> group.description)
      ~ ("category"      -> cat.map(_.value))
      ~ ("query"         -> query)
      ~ ("nodeIds"       -> group.serverList.toSeq.map(_.value).sorted)
      ~ ("dynamic"       -> group.isDynamic)
      ~ ("enabled"       -> group.isEnabled)
      ~ ("groupClass"    -> List(group.id.serialize, group.name).map(RuleTarget.toCFEngineClassName _).sorted)
      ~ ("properties"    -> group.properties.sortBy(_.name).toApiJson)
      ~ ("system"        -> group.isSystem)
      ~ ("target"        -> GroupTarget(group.id).target)
    )
  }

  override def serializeGroupCategory(
      category:    FullNodeGroupCategory,
      parent:      NodeGroupCategoryId,
      detailLevel: DetailLevel,
      apiVersion:  ApiVersion
  ): JValue = {
    val groupList = category.ownGroups.values.toSeq.sortBy(_.nodeGroup.id.serialize)
    val subCat    = category.subCategories.sortBy(_.id.value)

    def serializeTarget(target: FullRuleTargetInfo): JValue = {
      (("id"           -> target.target.target.target)
      ~ ("displayName" -> target.name)
      ~ ("description" -> target.description)
      ~ ("enabled"     -> target.isEnabled)
      ~ ("target"      -> target.target.target.target))
    }
    val otherTargets = category.allTargets.collect {
      case (AllTargetExceptPolicyServers | AllPolicyServers | PolicyServerTarget(_) | AllTarget, v) => serializeTarget(v)
    }

    val (groups, categories): (Seq[JValue], Seq[JValue]) = detailLevel match {
      case FullDetails    =>
        (
          groupList.map(fullGroup => serializeGroup(fullGroup.nodeGroup, Some(category.id), None)),
          subCat.map(serializeGroupCategory(_, category.id, detailLevel, apiVersion))
        )
      case MinimalDetails =>
        (groupList.map(g => JString(g.nodeGroup.id.serialize)), subCat.map(cat => JString(cat.id.value)))
    }
    (("id" -> category.id.value)
    ~ ("name"        -> category.name)
    ~ ("description" -> category.description)
    ~ ("parent"      -> parent.value)
    ~ ("categories"  -> categories)
    ~ ("groups"      -> groups)
    ~ ("targets"     -> otherTargets))
  }

  def serializeSectionVal(sv: SectionVal, sectionName: String = SectionVal.ROOT_SECTION_NAME): JValue = {
    val variables = {
      sv.variables.toSeq.sortBy(_._1).map {
        case (variable, value) =>
          ("var"     ->
          ("name"    -> variable)
          ~ ("value" -> value))
      }
    }
    val section   = {
      for {
        (sectionName, sectionIterations) <- sv.sections.toSeq.sortBy(_._1)
        sectionValue                     <- sectionIterations
      } yield {
        serializeSectionVal(sectionValue, sectionName)
      }
    }

    ("section"    ->
    ("name"       -> sectionName)
    ~ ("vars"     -> (if (variables.isEmpty) None else Some(variables)))
    ~ ("sections" -> (if (section.isEmpty) None else Some(section))))
  }

  def serializeDirective(technique: Technique, directive: Directive, crId: Option[ChangeRequestId]): JValue = {
    val sectionVal = serializeSectionVal(SectionVal.directiveValToSectionVal(technique.rootSection, directive.parameters))
    (("changeRequestId"   -> crId.map(_.value.toString))
    ~ ("id"               -> directive.id.uid.value)
    ~ ("revision"         -> directive.id.rev.toOptionString)
    ~ ("displayName"      -> directive.name)
    ~ ("shortDescription" -> directive.shortDescription)
    ~ ("longDescription"  -> directive.longDescription)
    ~ ("techniqueName"    -> technique.id.name.value)
    // we want to keep technique version and revision together to have meaning full diff
    ~ ("techniqueVersion" -> directive.techniqueVersion.version.toVersionString)
    ~ ("parameters"       -> sectionVal)
    ~ ("priority"         -> directive.priority)
    ~ ("enabled"          -> directive.isEnabled)
    ~ ("system"           -> directive.isSystem)
    ~ ("policyMode"       -> directive.policyMode.map(_.name).getOrElse("default"))
    ~ ("tags"             -> JArray(
      directive.tags.tags.toList.sortBy(_.name.value).map(t => JObject(JField(t.name.value, t.value.value) :: Nil)).toList
    )))
  }

  def displaySimpleDiff[T](diff: SimpleDiff[T])(implicit convert: T => JValue): JValue = {
    (("from" -> convert(diff.oldValue))
    ~ ("to"  -> convert(diff.newValue)))
  }
  private[this] val create = "create"
  private[this] val delete = "delete"
  private[this] val modify = "modify"

  def serializeRuleChange(change: RuleChange): Box[JValue] = {

    def serializeRuleDiff(diff: ModifyRuleDiff, initialState: Rule): JValue = {
      def convertDirectives(dl: Set[DirectiveId]): JValue = dl.map(d => JString(d.serialize)).toList
      def convertTargets(t: Set[RuleTarget]):      JValue = t.map(_.target).toList

      val name:             JValue = diff.modName.map(displaySimpleDiff(_)).getOrElse(initialState.name)
      val shortDescription: JValue = diff.modShortDescription.map(displaySimpleDiff(_)).getOrElse(initialState.shortDescription)
      val longDescription:  JValue = diff.modLongDescription.map(displaySimpleDiff(_)).getOrElse(initialState.longDescription)
      val targets:          JValue =
        diff.modTarget.map(displaySimpleDiff(_)(convertTargets)).getOrElse(initialState.targets.map(_.target).toList)
      val directives:       JValue = diff.modDirectiveIds
        .map(displaySimpleDiff(_)(convertDirectives))
        .getOrElse(initialState.directiveIds.map(x => JString(x.serialize)).toList)
      val enabled:          JValue = diff.modIsActivatedStatus.map(displaySimpleDiff(_)).getOrElse(initialState.isEnabled)

      (("id"                -> initialState.id.serialize)
      ~ ("displayName"      -> name)
      ~ ("shortDescription" -> shortDescription)
      ~ ("longDescription"  -> longDescription)
      ~ ("directives"       -> directives)
      ~ ("targets"          -> targets)
      ~ ("enabled"          -> enabled))
    }

    for {
      item <- change.change
      diff  = item.diff
    } yield {
      diff match {
        case AddRuleDiff(rule) =>
          val change = serializeRule(rule, None)
          (("action"  -> create)
          ~ ("change" -> change))

        case DeleteRuleDiff(rule) =>
          val change = serializeRule(rule, None)
          (("action"  -> delete)
          ~ ("change" -> change))

        case ModifyToRuleDiff(rule) =>
          val result = change.initialState match {
            case Some(init) =>
              val diff = diffService.diffRule(init, rule)
              serializeRuleDiff(diff, init)
            case None       => JString(s"Error while fetching initial state of change request.")

          }
          (("action"  -> modify)
          ~ ("change" -> result))

      }
    }
  }

  def serializeGlobalParameterChange(change: GlobalParameterChange): Box[JValue] = {

    def serializeGlobalParameterDiff(diff: ModifyGlobalParameterDiff, initialState: GlobalParameter): JValue = {
      val description: JValue = diff.modDescription.map(displaySimpleDiff(_)).getOrElse(initialState.description)
      val value:       JValue = diff.modValue
        .map(displaySimpleDiff(_)(x => JString(GenericProperty.serializeToHocon(x))))
        .getOrElse(GenericProperty.serializeToHocon(initialState.value))

      (("name"         -> initialState.name)
      ~ ("value"       -> value)
      ~ ("description" -> description))
    }

    for {
      item <- change.change
      diff  = item.diff
    } yield {
      diff match {
        case AddGlobalParameterDiff(parameter) =>
          val change = serializeParameter(parameter, None)
          (("action"  -> create)
          ~ ("change" -> change))

        case DeleteGlobalParameterDiff(parameter) =>
          val change = serializeParameter(parameter, None)
          (("action"  -> delete)
          ~ ("change" -> change))

        case ModifyToGlobalParameterDiff(parameter) =>
          val result = change.initialState match {
            case Some(init) =>
              val diff = diffService.diffGlobalParameter(init, parameter)
              serializeGlobalParameterDiff(diff, init)
            case None       => JString(s"Error while fetching initial state of change request.")

          }
          (("action"  -> modify)
          ~ ("change" -> result))

      }
    }
  }

  def serializeGroupChange(change: NodeGroupChange, apiVersion: ApiVersion): Box[JValue] = {

    def serializeNodeGroupDiff(diff: ModifyNodeGroupDiff, initialState: NodeGroup): JValue = {
      implicit def convert[T](value: GroupProperty): JValue = value.toJson
      def convertNodeList(nl: Set[NodeId]):          JValue = nl.map(_.value).toList
      def convertQuery(q: Option[Query]):            JValue = q.map(_.toString)

      val name:        JValue = diff.modName.map(displaySimpleDiff(_)).getOrElse(initialState.name)
      val description: JValue = diff.modDescription.map(displaySimpleDiff(_)).getOrElse(initialState.description)
      val query:       JValue = diff.modQuery.map(displaySimpleDiff(_)(convertQuery)).getOrElse(initialState.query.map(_.toString))
      val serverList:  JValue =
        diff.modNodeList.map(displaySimpleDiff(_)(convertNodeList)).getOrElse(initialState.serverList.map(v => (v.value)).toList)
      val dynamic:     JValue = diff.modIsDynamic.map(displaySimpleDiff(_)).getOrElse(initialState.isDynamic)
      val enabled:     JValue = diff.modIsActivated.map(displaySimpleDiff(_)).getOrElse(initialState.isEnabled)
      val properties:  JValue = diff.modProperties.map(displaySimpleDiff(_)).getOrElse(initialState.properties.toApiJson)
      val category:    JValue = diff.modCategory.map(displaySimpleDiff(_)(_.value))
      (("id"           -> initialState.id.serialize)
      ~ ("displayName" -> name)
      ~ ("description" -> description)
      ~ ("category"    -> category)
      ~ ("query"       -> query)
      ~ ("properties"  -> properties)
      ~ ("nodeIds"     -> serverList)
      ~ ("dynamic"     -> dynamic)
      ~ ("enabled"     -> enabled))
    }

    for {
      item <- change.change
      diff  = item.diff
    } yield {
      diff match {
        case AddNodeGroupDiff(group)    =>
          val change = serializeGroup(group, None, None)
          (("action"  -> create)
          ~ ("change" -> change))
        case DeleteNodeGroupDiff(group) =>
          val change = serializeGroup(group, None, None)
          (("action"  -> delete)
          ~ ("change" -> change))

        case ModifyToNodeGroupDiff(group) =>
          val result = change.initialState match {
            case Some(init) =>
              val diff = diffService.diffNodeGroup(init, group)
              serializeNodeGroupDiff(diff, init)
            case None       => JString(s"Error while fetching initial state of change request.")
          }
          logger.info(result)
          (("action" -> modify)
          ~ ("change" -> result))

      }
    }
  }

  def serializeDirectiveChange(change: DirectiveChange): Box[JValue] = {
    def serializeDirectiveDiff(
        diff:               ModifyDirectiveDiff,
        initialState:       Directive,
        technique:          Technique,
        initialRootSection: SectionSpec
    ): Box[JValue] = {
      // This is in a try/catch because directiveValToSectionVal may fail (it can throw exceptions, so we need to catch them)
      try {
        def convertParameters(sv: SectionVal): JValue = serializeSectionVal(sv)
        val name:                              JValue = diff.modName.map(displaySimpleDiff(_)).getOrElse(initialState.name)
        val shortDescription:                  JValue = diff.modShortDescription.map(displaySimpleDiff(_)).getOrElse(initialState.shortDescription)
        val longDescription:                   JValue = diff.modLongDescription.map(displaySimpleDiff(_)).getOrElse(initialState.longDescription)
        val techniqueVersion:                  JValue =
          diff.modTechniqueVersion.map(displaySimpleDiff(_)(_.serialize)).getOrElse(initialState.techniqueVersion.serialize)
        val priority:                          JValue = diff.modPriority.map(displaySimpleDiff(_)).getOrElse(initialState.priority)
        val enabled:                           JValue = diff.modIsActivated.map(displaySimpleDiff(_)).getOrElse(initialState.isEnabled)
        val initialParams:                     JValue = serializeSectionVal(
          SectionVal.directiveValToSectionVal(initialRootSection, initialState.parameters)
        )
        val parameters:                        JValue = diff.modParameters.map(displaySimpleDiff(_)(convertParameters)).getOrElse(initialParams)
        Full(
          ("id"                 -> initialState.id.serialize)
          ~ ("displayName"      -> name)
          ~ ("shortDescription" -> shortDescription)
          ~ ("longDescription"  -> longDescription)
          ~ ("techniqueName"    -> technique.id.name.value)
          ~ ("techniqueVersion" -> techniqueVersion)
          ~ ("parameters"       -> parameters)
          ~ ("priority"         -> priority)
          ~ ("enabled"          -> enabled)
          ~ ("system"           -> initialState.isSystem)
        )
      } catch {
        case e: Exception =>
          val errorMsg = "Error while trying to serialize Directive Diff. cause is: " + e.getMessage()
          logger.error(errorMsg)
          Failure(errorMsg)
      }
    }

    for {
      item <- change.change
      diff  = item.diff
    } yield {
      diff match {
        case AddDirectiveDiff(techniqueName, directive) =>
          val technique = readTechnique.get(TechniqueId(techniqueName, directive.techniqueVersion))
          val change    = technique
            .map(serializeDirective(_, directive, None))
            .getOrElse(JString(s"Error while fetchg technique ${techniqueName.value}"))
          (("action"  -> create)
          ~ ("change" -> change))

        case DeleteDirectiveDiff(techniqueName, directive)                =>
          val technique = readTechnique.get(TechniqueId(techniqueName, directive.techniqueVersion))
          val change    = technique
            .map(serializeDirective(_, directive, None))
            .getOrElse(JString(s"Error while fetchg technique ${techniqueName.value}"))
          (("action"  -> delete)
          ~ ("change" -> change))
        case ModifyToDirectiveDiff(techniqueName, directive, rootSection) =>
          val technique = readTechnique.get(TechniqueId(techniqueName, directive.techniqueVersion))

          val result = change.initialState match {
            case Some((techniqueName, initialState, initialRootSection)) =>
              val diff = diffService.diffDirective(initialState, initialRootSection, directive, rootSection, techniqueName)
              technique
                .flatMap(t =>
                  initialRootSection.flatMap(rootSection => serializeDirectiveDiff(diff, initialState, t, rootSection))
                )
                .getOrElse(JString("Error while fetching technique"))
            case None                                                    => JString(s"Error while fetching initial state of change request.")
          }
          (("action" -> modify)
          ~ ("change" -> result))

      }
    }
  }

  override def serializeCR(
      changeRequest: ChangeRequest,
      status:        WorkflowNodeId,
      isAcceptable:  Boolean,
      apiVersion:    ApiVersion
  ) = {

    val changes: JValue = changeRequest match {
      case cr: ConfigurationChangeRequest =>
        val directives = cr.directives.values.map(ch => {
          serializeDirectiveChange(ch.changes).getOrElse(
            JString(s"Error while serializing directives from CR ${changeRequest.id.value}")
          )
        })
        val groups     = cr.nodeGroups.values.map(ch => {
          serializeGroupChange(ch.changes, apiVersion).getOrElse(
            JString(s"Error while serializing groups from CR ${changeRequest.id.value}")
          )
        })
        val parameters = cr.globalParams.values.map(ch => {
          serializeGlobalParameterChange(ch.changes).getOrElse(
            JString(s"Error while serializing Parameters from CR ${changeRequest.id.value}")
          )
        })
        val rules      = cr.rules.values.map(ch =>
          serializeRuleChange(ch.changes).getOrElse(JString(s"Error while serializing Rules from CR ${changeRequest.id.value}"))
        )
        (("directives"  -> directives)
        ~ ("rules"      -> rules)
        ~ ("groups"     -> groups)
        ~ ("parameters" -> parameters))
      case _ => JNothing
    }
    (("id" -> changeRequest.id.value)
    ~ ("displayName" -> changeRequest.info.name)
    ~ ("status"      -> status.value)
    ~ ("created by"  -> changeRequest.owner)
    ~ ("acceptable"  -> isAcceptable)
    ~ ("description" -> changeRequest.info.description)
    ~ ("changes"     -> changes))
  }

  def serializeTechnique(technique: FullActiveTechnique): JValue = {
    (("name"      -> technique.techniqueName.value)
    ~ ("versions" -> technique.techniques.map(_._1.serialize)))
  }
  def serializeTechnique(technique: Technique) = {
    zio.json.ast.Json(
      ("name"    -> Str(technique.name)),
      ("id"      -> Str(technique.id.name.value)),
      ("version" -> Str(technique.id.version.serialize)),
      ("source"  -> Str("built-in"))
    )
  }

  def serializeHealthcheckResult(check: HealthcheckResult): JValue = {
    val status = check match {
      case _: Critical => "Critical"
      case _: Ok       => "Ok"
      case _: Warning  => "Warning"
    }
    (("name" -> check.name.value)
    ~ ("msg"    -> check.msg)
    ~ ("status" -> status))
  }
}

/*
 * ACL
 * Between front and backend, we exchange a JsonAcl list, where JsonAcl are *just*
 * one path and one verb. The grouping is done in extractor
 */
final case class JsonApiAcl(path: String, verb: String)

object ApiAccountSerialisation {

  implicit val formats: Formats = DefaultFormats

  implicit class Json(val account: ApiAccount) extends AnyVal {
    def toJson: JObject = {
      val (expirationDate, authzType, acl): (Option[String], Option[String], Option[List[JsonApiAcl]]) = {
        account.kind match {
          case User | System                           => (None, None, None)
          case PublicApiAccount(authz, expirationDate) =>
            val acl = authz match {
              case NoAccess | RO | RW => None
              case ACL(acls)          => Some(acls.flatMap(x => x.actions.map(a => JsonApiAcl(x.path.value, a.name))))
            }
            (expirationDate.map(DateFormaterService.getDisplayDateTimePicker), Some(authz.kind.name), acl)
        }
      }
      ("id" -> account.id.value) ~
      ("name"                  -> account.name.value) ~
      ("token"                 -> account.token.value) ~
      ("tokenGenerationDate"   -> DateFormaterService.serialize(account.tokenGenerationDate)) ~
      ("kind"                  -> account.kind.kind.name) ~
      ("description"           -> account.description) ~
      ("creationDate"          -> DateFormaterService.serialize(account.creationDate)) ~
      ("enabled"               -> account.isEnabled) ~
      ("expirationDate"        -> expirationDate) ~
      ("expirationDateDefined" -> expirationDate.isDefined) ~
      ("authorizationType"     -> authzType) ~
      ("acl"                   -> acl.map(x => Extraction.decompose(x)))
    }
  }
}
