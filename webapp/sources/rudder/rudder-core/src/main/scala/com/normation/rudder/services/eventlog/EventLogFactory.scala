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

package com.normation.rudder.services.eventlog

import com.normation.GitVersion
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.eventlog.*
import com.normation.inventory.domain.NodeId
import com.normation.rudder.api.*
import com.normation.rudder.domain.Constants
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.eventlog.*
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.AddGlobalParameterDiff
import com.normation.rudder.domain.properties.DeleteGlobalParameterDiff
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.ModifyGlobalParameterDiff
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.secret.Secret
import com.normation.rudder.domain.workflows.WorkflowStepChange
import com.normation.rudder.ncf.eventlogs.AddEditorTechnique
import com.normation.rudder.ncf.eventlogs.AddEditorTechniqueDiff
import com.normation.rudder.ncf.eventlogs.DeleteEditorTechnique
import com.normation.rudder.ncf.eventlogs.DeleteEditorTechniqueDiff
import com.normation.rudder.ncf.eventlogs.EditorTechniqueXmlSerialisation
import com.normation.rudder.ncf.eventlogs.ModifyEditorTechnique
import com.normation.rudder.ncf.eventlogs.ModifyEditorTechniqueDiff
import com.normation.rudder.services.marshalling.*
import java.time.Instant
import net.liftweb.util.Helpers.*
import org.apache.commons.text.StringEscapeUtils
import scala.xml.*
import zio.json.*

trait EventLogFactory {

  def getAddRuleFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddRuleDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddRule

  def getDeleteRuleFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteRuleDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteRule

  def getModifyRuleFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyRuleDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyRule

  def getAddDirectiveFromDiff(
      id:                  Option[Int] = None,
      modificationId:      Option[ModificationId] = None,
      principal:           EventActor,
      addDiff:             AddDirectiveDiff,
      varsRootSectionSpec: SectionSpec,
      creationDate:        Instant = Instant.now(),
      severity:            Int = 100,
      reason:              Option[String]
  ): AddDirective

  def getDeleteDirectiveFromDiff(
      id:                  Option[Int] = None,
      modificationId:      Option[ModificationId] = None,
      principal:           EventActor,
      deleteDiff:          DeleteDirectiveDiff,
      varsRootSectionSpec: SectionSpec,
      creationDate:        Instant = Instant.now(),
      severity:            Int = 100,
      reason:              Option[String]
  ): DeleteDirective

  def getModifyDirectiveFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyDirectiveDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyDirective

  def getAddEditorTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddEditorTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddEditorTechnique

  def getDeleteEditorTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteEditorTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteEditorTechnique

  def getModifyEditorTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyEditorTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyEditorTechnique
  def getAddNodeGroupFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddNodeGroupDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddNodeGroup

  def getDeleteNodeGroupFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteNodeGroupDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteNodeGroup

  def getModifyNodeGroupFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyNodeGroupDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyNodeGroup

  def getAddTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddTechnique

  def getModifyTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyTechnique

  def getDeleteTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteTechnique

  def getAddGlobalParameterFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddGlobalParameterDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddGlobalParameter

  def getDeleteGlobalParameterFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteGlobalParameterDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteGlobalParameter

  def getModifyGlobalParameterFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyGlobalParameterDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyGlobalParameter

  def getChangeRequestFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      diff:           ChangeRequestDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ChangeRequestEventLog

  def getWorkFlowEventFromStepChange(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      step:           WorkflowStepChange,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): WorkflowStepChanged

  def getCreateApiAccountFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddApiAccountDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): CreateAPIAccountEventLog

  def getModifyApiAccountFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyApiAccountDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyAPIAccountEventLog

  def getDeleteApiAccountFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteApiAccountDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteAPIAccountEventLog

  def getModifyGlobalPropertyFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String],
      oldProperty:    RudderWebProperty,
      newProperty:    RudderWebProperty,
      eventLogType:   ModifyGlobalPropertyEventType
  ): ModifyGlobalProperty

  def getModifyNodeFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyNodeDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyNode

  def getPromoteToRelayFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      promotedNode:   NodeInfo,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): PromoteNode

  def getDemoteToNodeFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      demotedRelay:   NodeInfo,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DemoteRelay

  def getAddSecretFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      secret:         Secret,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddSecret

  def getDeleteSecretFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      secret:         Secret,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteSecret

  def getModifySecretFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      oldSecret:      Secret,
      newSecret:      Secret,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifySecret

}

class EventLogFactoryImpl(
    ruleXmlserializer:         RuleSerialisation,
    DirectiveXmlSerializer:    DirectiveSerialisation,
    GroupXmlSerializer:        NodeGroupSerialisation,
    techniqueXmlSerializer:    ActiveTechniqueSerialisation,
    parameterXmlSerializer:    GlobalParameterSerialisation,
    apiAccountXmlSerializer:   APIAccountSerialisation,
    propertySerializer:        GlobalPropertySerialisation,
    secretXmlSerializer:       SecretSerialisation,
    editorTechniqueSerializer: EditorTechniqueXmlSerialisation
) extends EventLogFactory {

  /////
  ///// rules /////
  /////

  override def getAddRuleFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddRuleDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddRule = {
    val details = EventLog.withContent(ruleXmlserializer.serialise(addDiff.rule) % ("changeType" -> "add"))
    AddRule(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getDeleteRuleFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteRuleDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteRule = {
    val details = EventLog.withContent(ruleXmlserializer.serialise(deleteDiff.rule) % ("changeType" -> "delete"))
    DeleteRule(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getModifyRuleFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyRuleDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyRule = {
    val modCategory = modifyDiff.modCategory.map(diff => SimpleDiff(diff.oldValue.value, diff.newValue.value))
    val details     = EventLog.withContent {
      scala.xml.Utility.trim(
        <rule changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
          <id>{modifyDiff.id.serialize}</id>
          <displayName>{modifyDiff.name}</displayName>{
          modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x)) ++
          modCategory.map(x => SimpleDiff.stringToXml(<category/>, x)) ++
          modifyDiff.modSerial.map(x => SimpleDiff.intToXml(<serial/>, x)) ++
          modifyDiff.modTarget.map(x =>
            SimpleDiff.toXml[Set[RuleTarget]](<targets/>, x)(targets => targets.toSeq.map(t => <target>{t.target}</target>))
          ) ++
          modifyDiff.modDirectiveIds.map(x => {
            SimpleDiff.toXml[Set[DirectiveId]](<directiveIds/>, x) { ids =>
              ids.toSeq.map {
                case DirectiveId(uid, rev) =>
                  rev match {
                    case GitVersion.DEFAULT_REV => <id>{uid.value}</id>
                    case r                      => <id revision={r.value}>{uid.value}</id>
                  }
              }
            }
          }) ++
          modifyDiff.modShortDescription.map(x => SimpleDiff.stringToXml(<shortDescription/>, x)) ++
          modifyDiff.modLongDescription.map(x => SimpleDiff.stringToXml(<longDescription/>, x)) ++
          modifyDiff.modIsActivatedStatus.map(x => SimpleDiff.booleanToXml(<isEnabled/>, x)) ++
          modifyDiff.modIsSystem.map(x => SimpleDiff.booleanToXml(<isSystem/>, x)) ++
          modifyDiff.modTags.map(x => SimpleDiff.toXml[Set[Tag]](<tags/>, x)(tags => TagsXml.toXml(Tags(tags))))
        }
        </rule>
      )
    }
    ModifyRule(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  /////
  ///// directive /////
  /////

  override def getAddDirectiveFromDiff(
      id:                  Option[Int] = None,
      modificationId:      Option[ModificationId] = None,
      principal:           EventActor,
      addDiff:             AddDirectiveDiff,
      varsRootSectionSpec: SectionSpec,
      creationDate:        Instant = Instant.now(),
      severity:            Int = 100,
      reason:              Option[String]
  ): AddDirective = {
    val details = EventLog.withContent(
      DirectiveXmlSerializer.serialise(
        addDiff.techniqueName,
        Some(varsRootSectionSpec),
        addDiff.directive
      ) % ("changeType" -> "add")
    )
    AddDirective(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getDeleteDirectiveFromDiff(
      id:                  Option[Int] = None,
      modificationId:      Option[ModificationId] = None,
      principal:           EventActor,
      deleteDiff:          DeleteDirectiveDiff,
      varsRootSectionSpec: SectionSpec,
      creationDate:        Instant = Instant.now(),
      severity:            Int = 100,
      reason:              Option[String]
  ): DeleteDirective = {
    val details = EventLog.withContent(
      DirectiveXmlSerializer.serialise(
        deleteDiff.techniqueName,
        Some(varsRootSectionSpec),
        deleteDiff.directive
      ) % ("changeType" -> "delete")
    )
    DeleteDirective(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getModifyDirectiveFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyDirectiveDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyDirective = {
    val details = EventLog.withContent {
      scala.xml.Utility.trim(
        <directive changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
          <id>{modifyDiff.id.serialize}</id>
          <techniqueName>{modifyDiff.techniqueName.value}</techniqueName>
          <displayName>{modifyDiff.name}</displayName>{
          modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x)) ++
          modifyDiff.modTechniqueVersion.map(x =>
            SimpleDiff.toXml[TechniqueVersion](<techniqueVersion/>, x)(v => Text(v.serialize))
          ) ++
          modifyDiff.modParameters.map(x => SimpleDiff.toXml[SectionVal](<parameters/>, x)(sv => SectionVal.toXml(sv))) ++
          modifyDiff.modShortDescription.map(x => SimpleDiff.stringToXml(<shortDescription/>, x)) ++
          modifyDiff.modLongDescription.map(x => SimpleDiff.stringToXml(<longDescription/>, x)) ++
          modifyDiff.modPriority.map(x => SimpleDiff.intToXml(<priority/>, x)) ++
          modifyDiff.modPolicyMode.map(x => {
            SimpleDiff.toXml[Option[PolicyMode]](<policyMode/>, x) {
              case None    => Text(PolicyMode.defaultValue)
              case Some(y) => Text(y.name)
            }
          }) ++
          modifyDiff.modIsActivated.map(x => SimpleDiff.booleanToXml(<isEnabled/>, x)) ++
          modifyDiff.modIsSystem.map(x => SimpleDiff.booleanToXml(<isSystem/>, x)) ++
          modifyDiff.modTags.map(x => SimpleDiff.toXml[Tags](<tags/>, x)(tags => TagsXml.toXml(tags)))
        }
        </directive>
      )
    }
    ModifyDirective(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  /////
  ///// EditorTechnique /////
  /////

  override def getAddEditorTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddEditorTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddEditorTechnique = {
    val details = EventLog.withContent(
      editorTechniqueSerializer.serialise(
        addDiff.editorTechnique
      ) % ("changeType" -> "add")
    )
    AddEditorTechnique(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getDeleteEditorTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteEditorTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteEditorTechnique = {
    val details = EventLog.withContent(
      editorTechniqueSerializer.serialise(
        deleteDiff.editorTechnique
      ) % ("changeType" -> "delete")
    )
    DeleteEditorTechnique(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getModifyEditorTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyEditorTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyEditorTechnique = {
    val details = EventLog.withContent {
      editorTechniqueSerializer.serialiseDiff(modifyDiff)
    }
    ModifyEditorTechnique(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getAddNodeGroupFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddNodeGroupDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddNodeGroup = {
    val details = EventLog.withContent(GroupXmlSerializer.serialise(addDiff.group) % ("changeType" -> "add"))
    AddNodeGroup(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getDeleteNodeGroupFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteNodeGroupDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteNodeGroup = {
    val details = EventLog.withContent(GroupXmlSerializer.serialise(deleteDiff.group) % ("changeType" -> "delete"))
    DeleteNodeGroup(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getModifyNodeGroupFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyNodeGroupDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyNodeGroup = {
    val details = EventLog.withContent {
      scala.xml.Utility.trim(<nodeGroup changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <id>{modifyDiff.id.withDefaultRev.serialize}</id>
        <displayName>{modifyDiff.name}</displayName>{
        modifyDiff.modName.map(x => SimpleDiff.stringToXml(<name/>, x)) ++
        modifyDiff.modDescription.map(x => SimpleDiff.stringToXml(<description/>, x)) ++
        modifyDiff.modQuery.map(x => {
          SimpleDiff.toXml[Option[Query]](<query/>, x) { t =>
            t match {
              case None    => <none/>
              case Some(y) => Text(y.toJson)
            }
          }
        }) ++
        modifyDiff.modIsDynamic.map(x => SimpleDiff.booleanToXml(<isDynamic/>, x)) ++
        modifyDiff.modNodeList.map(x =>
          SimpleDiff.toXml[Set[NodeId]](<nodeIds/>, x)(ids => ids.toSeq.map(id => <id>{id.value}</id>))
        ) ++
        modifyDiff.modIsActivated.map(x => SimpleDiff.booleanToXml(<isEnabled/>, x)) ++
        modifyDiff.modIsSystem.map(x => SimpleDiff.booleanToXml(<isSystem/>, x)) ++
        modifyDiff.modProperties.map(x => {
          SimpleDiff.toXml[List[GroupProperty]](<properties/>, x)(props => {
            props.flatMap(p =>
              <property><name>{p.name}</name><value>{StringEscapeUtils.escapeHtml4(p.valueAsString)}</value></property>
            )
          })
        })
      }
      </nodeGroup>)
    }
    ModifyNodeGroup(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  def getAddTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddTechnique = {
    val details = EventLog.withContent {
      scala.xml.Utility.trim(<activeTechnique changeType="add" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <id>{addDiff.technique.id.value}</id>
        <techniqueName>{addDiff.technique.techniqueName.value}</techniqueName>
      </activeTechnique>)
    }
    AddTechnique(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getModifyTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyTechnique = {
    val details = EventLog.withContent {
      scala.xml.Utility.trim(<activeTechnique changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <id>{modifyDiff.id.value}</id>
        <techniqueName>{modifyDiff.name.value}</techniqueName>{
        modifyDiff.modIsEnabled.map(x => SimpleDiff.booleanToXml(<isEnabled/>, x)).toSeq
      }
      </activeTechnique>)
    }
    ModifyTechnique(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getDeleteTechniqueFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteTechniqueDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteTechnique = {
    val details = EventLog.withContent {
      scala.xml.Utility.trim(<activeTechnique changeType="delete" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <id>{deleteDiff.technique.id.value}</id>
        <techniqueName>{deleteDiff.technique.techniqueName.value}</techniqueName>
        <isSystem>{deleteDiff.technique.policyTypes.isSystem}</isSystem>
        <isEnabled>{deleteDiff.technique._isEnabled}</isEnabled>
      </activeTechnique>)
    }
    DeleteTechnique(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getAddGlobalParameterFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddGlobalParameterDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddGlobalParameter = {
    val details = EventLog.withContent(parameterXmlSerializer.serialise(addDiff.parameter) % ("changeType" -> "add"))
    AddGlobalParameter(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getDeleteGlobalParameterFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteGlobalParameterDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteGlobalParameter = {
    val details = EventLog.withContent(parameterXmlSerializer.serialise(deleteDiff.parameter) % ("changeType" -> "delete"))
    DeleteGlobalParameter(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getModifyGlobalParameterFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyGlobalParameterDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyGlobalParameter = {
    val details = EventLog.withContent {
      scala.xml.Utility.trim(<globalParameter changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <name>{modifyDiff.name}</name>{
        modifyDiff.modValue.map(x => SimpleDiff.toXml(<value/>, x)(v => Text(GenericProperty.serializeToHocon(v)))) ++
        modifyDiff.modDescription.map(x => SimpleDiff.stringToXml(<description/>, x))
      }
      </globalParameter>)
    }
    ModifyGlobalParameter(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getChangeRequestFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      diff:           ChangeRequestDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ChangeRequestEventLog = {

    def eventlogDetails(xml: Elem): EventLogDetails = {
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = xml,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    }
    val diffName = diff.diffName.map(x => SimpleDiff.stringToXml(<diffName/>, x)).getOrElse(NodeSeq.Empty)
    val diffDesc = diff.diffDescription.map(x => SimpleDiff.stringToXml(<diffDescription/>, x)).getOrElse(NodeSeq.Empty)
    val xml      = <changeRequest>
                <id>{diff.changeRequest.id}</id>
                <name>{diff.changeRequest.info.name}</name>
                <description>{diff.changeRequest.info.description}</description>
                {(diffName ++ diffDesc)}
              </changeRequest>

    diff match {
      case _:   AddChangeRequestDiff      =>
        val details = EventLog.withContent(scala.xml.Utility.trim(xml % ("changeType" -> "add")))
        AddChangeRequest(eventlogDetails(details))
      case _:   DeleteChangeRequestDiff   =>
        val details = EventLog.withContent(scala.xml.Utility.trim(xml % ("changeType" -> "delete")))
        DeleteChangeRequest(eventlogDetails(details))
      case mod: ModifyToChangeRequestDiff =>
        val details = EventLog.withContent((xml))
        ModifyChangeRequest(eventlogDetails(details))
    }
  }

  def getWorkFlowEventFromStepChange(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      step:           WorkflowStepChange,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): WorkflowStepChanged = {
    val xml     = {
      <workflowStep>
        <changeRequestId>{step.id}</changeRequestId>
        <from>{step.from}</from>
        <to>{step.to}</to>
      </workflowStep>
    }
    val details = EventLog.withContent(scala.xml.Utility.trim(xml))
    val data    = EventLogDetails(
      id = id,
      modificationId = modificationId,
      principal = principal,
      details = details,
      creationDate = creationDate,
      reason = reason,
      severity = severity
    )
    WorkflowStepChanged(data)
  }

  // API Account

  override def getCreateApiAccountFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      addDiff:        AddApiAccountDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): CreateAPIAccountEventLog = {
    val details = EventLog.withContent(apiAccountXmlSerializer.serialise(addDiff.apiAccount) % ("changeType" -> "add"))
    CreateAPIAccountEventLog(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  def getModifyApiAccountFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      diff:           ModifyApiAccountDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyAPIAccountEventLog = {
    val details = EventLog.withContent {
      scala.xml.Utility.trim(<apiAccount changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <id>{diff.id.value}</id>{
        diff.modName.map(x => SimpleDiff.stringToXml(<name/>, x)) ++
        diff.modToken.map(x => SimpleDiff.stringToXml(<token/>, x)) ++
        diff.modDescription.map(x => SimpleDiff.stringToXml(<description/>, x)) ++
        diff.modIsEnabled.map(x => SimpleDiff.booleanToXml(<enabled/>, x)) ++
        diff.modTokenGenerationDate.map(x => SimpleDiff.toXml[Instant](<tokenGenerationDate/>, x)(x => Text(x.toString))) ++
        diff.modExpirationDate.map(x => {
          SimpleDiff.toXml[Option[Instant]](<expirationDate/>, x) { x =>
            x match {
              case None    => NodeSeq.Empty
              case Some(d) => Text(d.toString)
            }
          }
        }) ++
        diff.modAccountKind.map(x => SimpleDiff.stringToXml(<accountKind/>, x)) ++
        diff.modAccountAcl.map(x => {
          SimpleDiff.toXml[List[ApiAclElement]](<acls/>, x) { x =>
            x.map(acl => <acl path={acl.path.value} actions={acl.actions.map(_.name).mkString(",")} />)
          }
        })
      }
      </apiAccount>)
    }

    ModifyAPIAccountEventLog(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getDeleteApiAccountFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      deleteDiff:     DeleteApiAccountDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteAPIAccountEventLog = {
    val details = EventLog.withContent(apiAccountXmlSerializer.serialise(deleteDiff.apiAccount) % ("changeType" -> "delete"))
    DeleteAPIAccountEventLog(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getModifyGlobalPropertyFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String],
      oldProperty:    RudderWebProperty,
      newProperty:    RudderWebProperty,
      eventLogType:   ModifyGlobalPropertyEventType
  ): ModifyGlobalProperty = {

    val details         = EventLog.withContent(propertySerializer.serializeChange(oldProperty, newProperty))
    val eventLogDetails = EventLogDetails(
      id = id,
      modificationId = modificationId,
      principal = principal,
      details = details,
      creationDate = creationDate,
      reason = reason,
      severity = severity
    )

    ModifyGlobalProperty(eventLogType, eventLogDetails)
  }

  override def getPromoteToRelayFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      promotedNode:   NodeInfo,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): PromoteNode = {

    val details = EventLog.withContent {
      scala.xml.Utility.trim(
        <node changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
         <id>{promotedNode.node.id.value}</id>
         <hostname>{promotedNode.hostname}</hostname>
       </node>
      )
    }
    PromoteNode(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getDemoteToNodeFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      promotedNode:   NodeInfo,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DemoteRelay = {

    val details = EventLog.withContent {
      scala.xml.Utility.trim(
        <node changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
         <id>{promotedNode.node.id.value}</id>
         <hostname>{promotedNode.hostname}</hostname>
       </node>
      )
    }
    DemoteRelay(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  def getModifyNodeFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      modifyDiff:     ModifyNodeDiff,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifyNode = {
    val details = EventLog.withContent {
      scala.xml.Utility.trim(<node changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <id>{modifyDiff.id.value}</id>
        {
        modifyDiff.modProperties match {
          case None    => NodeSeq.Empty
          case Some(x) =>
            SimpleDiff.toXml(<properties/>, x) { props =>
              props.flatMap(p => <property><name>{p.name}</name><value>{p.valueAsString}</value></property>)
            }
        }
      }{
        modifyDiff.modAgentRun match {
          case None    => NodeSeq.Empty
          case Some(x) =>
            SimpleDiff.toXml(<agentRun/>, x) {
              _ match {
                case None      => NodeSeq.Empty
                case Some(run) => (
                  <override>{run.overrides.map(_.toString).getOrElse("")}</override>
                                  <interval>{run.interval}</interval>
                                  <startMinute>{run.startMinute}</startMinute>
                                  <startHour>{run.startHour}</startHour>
                                  <splaytime>{run.splaytime}</splaytime>
                )
              }
            }
        }
      } {
        modifyDiff.modPolicyMode match {
          case None    => NodeSeq.Empty
          case Some(x) =>
            SimpleDiff.toXml(<policyMode/>, x) {
              _ match {
                case None       => Text("default")
                case Some(mode) => Text(mode.name)
              }
            }
        }
      } {
        modifyDiff.modKeyValue match {
          case None    => NodeSeq.Empty
          case Some(x) => SimpleDiff.toXml(<agentKey/>, x)(x => Text(x.key))
        }
      } {
        modifyDiff.modKeyStatus match {
          case None    => NodeSeq.Empty
          case Some(x) => SimpleDiff.toXml(<keyStatus/>, x)(x => Text(x.value))
        }
      }{
        modifyDiff.modNodeState match {
          case None    => NodeSeq.Empty
          case Some(x) => SimpleDiff.toXml(<nodeState/>, x)(x => Text(x.name))
        }
      }{
        modifyDiff.modDocumentation match {
          case None    => NodeSeq.Empty
          case Some(x) => SimpleDiff.toXml(<description/>, x)(x => Text(x))
        }
      }
      </node>)
    }

    ModifyNode(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getAddSecretFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      secret:         Secret,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): AddSecret = {
    val details = EventLog.withContent(secretXmlSerializer.serialise(secret) % ("changeType" -> "add"))
    AddSecret(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getDeleteSecretFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      secret:         Secret,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): DeleteSecret = {

    val details = EventLog.withContent(secretXmlSerializer.serialise(secret) % ("changeType" -> "delete"))
    DeleteSecret(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

  override def getModifySecretFromDiff(
      id:             Option[Int] = None,
      modificationId: Option[ModificationId] = None,
      principal:      EventActor,
      oldSecret:      Secret,
      newSecret:      Secret,
      creationDate:   Instant = Instant.now(),
      severity:       Int = 100,
      reason:         Option[String]
  ): ModifySecret = {

    val diffValue = {
      if (oldSecret.value != newSecret.value)
        <valueHasChanged> True </valueHasChanged>
      else
        <valueHasChanged> False </valueHasChanged>
    }

    val diffDescription = {
      if (oldSecret.description != newSecret.description)
        Some(SimpleDiff.stringToXml(<diffDescription/>, SimpleDiff(oldSecret.description, newSecret.description)))
      else
        None
    }

    val details = EventLog.withContent {
      scala.xml.Utility.trim(<secret changeType="modify" fileFormat={Constants.XML_CURRENT_FILE_FORMAT.toString}>
        <name>{oldSecret.name}</name>
        <description>{oldSecret.description}</description>
        {diffValue ++ diffDescription}
      </secret>)
    }
    ModifySecret(
      EventLogDetails(
        id = id,
        modificationId = modificationId,
        principal = principal,
        details = details,
        creationDate = creationDate,
        reason = reason,
        severity = severity
      )
    )
  }

}
