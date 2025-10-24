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

import com.normation.GitVersion.ParseRev
import com.normation.box.*
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.Certificate
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.SecurityToken
import com.normation.rudder.api.*
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.domain.Constants.*
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.appconfig.RudderWebPropertyName
import com.normation.rudder.domain.eventlog.*
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.AddGlobalParameterDiff
import com.normation.rudder.domain.properties.DeleteGlobalParameterDiff
import com.normation.rudder.domain.properties.GenericProperty
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.ModifyGlobalParameterDiff
import com.normation.rudder.domain.properties.NodeProperty
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.queries.Query
import com.normation.rudder.domain.secret.Secret
import com.normation.rudder.domain.workflows.*
import com.normation.rudder.git.GitArchiveId
import com.normation.rudder.git.GitCommitId
import com.normation.rudder.git.GitPath
import com.normation.rudder.reports.*
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.marshalling.*
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.utils.Control.traverse
import com.normation.utils.DateFormaterService
import com.typesafe.config.ConfigValue
import net.liftweb.common.*
import net.liftweb.common.Box.*
import org.eclipse.jgit.lib.PersonIdent
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import scala.xml.*

/**
 * A service that helps mapping event log details to there structured data model.
 * Details should always be in the format: <entry>{more details here}</entry>
 */
trait EventLogDetailsService {

  /**
   * From a given nodeSeq, retrieve the real details, that should be in a <entry/> tag.
   * The result, if Full, is trimed so that pattern matching can be made without
   * taking care of empty texts.
   */
  def getEntryContent(xml: NodeSeq): Box[Elem]

  ///// rule /////

  def getRuleAddDetails(xml: NodeSeq): Box[AddRuleDiff]

  def getRuleDeleteDetails(xml: NodeSeq): Box[DeleteRuleDiff]

  def getRuleModifyDetails(xml: NodeSeq): Box[ModifyRuleDiff]

  ///// directive /////

  def getDirectiveAddDetails(xml: NodeSeq): Box[(AddDirectiveDiff, SectionVal)]

  def getDirectiveDeleteDetails(xml: NodeSeq): Box[(DeleteDirectiveDiff, SectionVal)]

  def getDirectiveModifyDetails(xml: NodeSeq): Box[ModifyDirectiveDiff]

  ///// node group /////

  def getNodeGroupAddDetails(xml: NodeSeq): Box[AddNodeGroupDiff]

  def getNodeGroupDeleteDetails(xml: NodeSeq): Box[DeleteNodeGroupDiff]

  def getNodeGroupModifyDetails(xml: NodeSeq): Box[ModifyNodeGroupDiff]

  ///// node /////

  def getAcceptNodeLogDetails(xml: NodeSeq): Box[InventoryLogDetails]

  def getRefuseNodeLogDetails(xml: NodeSeq): Box[InventoryLogDetails]

  def getDeleteNodeLogDetails(xml: NodeSeq): Box[InventoryLogDetails]

  ///// other /////

  def getDeploymentStatusDetails(xml: NodeSeq): Box[CurrentDeploymentStatus]

  def getUpdatePolicyServerDetails(xml: NodeSeq): Box[AuthorizedNetworkModification]

  def getTechniqueLibraryReloadDetails(xml: NodeSeq): Box[Seq[TechniqueId]]

  def getTechniqueModifyDetails(xml: NodeSeq): Box[ModifyTechniqueDiff]

  def getTechniqueDeleteDetails(xml: NodeSeq): Box[DeleteTechniqueDiff]

  ///// archiving & restoration /////

  def getNewArchiveDetails[T <: ExportEventLog](xml: NodeSeq, archive: T): Box[GitArchiveId]

  def getRestoreArchiveDetails[T <: ImportEventLog](xml: NodeSeq, archive: T): Box[GitCommitId]

  def getRollbackDetails(xml: NodeSeq): Box[RollbackInfo]

  def getChangeRequestDetails(xml: NodeSeq): Box[ChangeRequestDiff]

  def getWorkflotStepChange(xml: NodeSeq): Box[WorkflowStepChange]

  // Parameters
  def getGlobalParameterAddDetails(xml: NodeSeq): Box[AddGlobalParameterDiff]

  def getGlobalParameterDeleteDetails(xml: NodeSeq): Box[DeleteGlobalParameterDiff]

  def getGlobalParameterModifyDetails(xml: NodeSeq): Box[ModifyGlobalParameterDiff]

  // API Account
  def getApiAccountAddDetails(xml: NodeSeq): Box[AddApiAccountDiff]

  def getApiAccountDeleteDetails(xml: NodeSeq): Box[DeleteApiAccountDiff]

  def getApiAccountModifyDetails(xml: NodeSeq): Box[ModifyApiAccountDiff]

  // Global properties
  def getModifyGlobalPropertyDetails(xml: NodeSeq): Box[(RudderWebProperty, RudderWebProperty)]

  // Node modification
  def getModifyNodeDetails(xml: NodeSeq): Box[ModifyNodeDiff]

  def getPromotedNodeToRelayDetails(xml: NodeSeq): Box[(NodeId, String)]

  // Secrets variables
  def getSecretAddDetails(xml: NodeSeq): Box[AddSecretDiff]

  def getSecretDeleteDetails(xml: NodeSeq): Box[DeleteSecretDiff]

  def getSecretModifyDetails(xml: NodeSeq): Box[ModifySecretDiff]

}

/**
 * Details should always be in the format: <entry>{more details here}</entry>
 */
class EventLogDetailsServiceImpl(
    cmdbQueryParser:                 CmdbQueryParser,
    piUnserialiser:                  DirectiveUnserialisation,
    groupUnserialiser:               NodeGroupUnserialisation,
    crUnserialiser:                  RuleUnserialisation,
    techniqueUnserialiser:           ActiveTechniqueUnserialisation,
    deploymentStatusUnserialisation: DeploymentStatusUnserialisation,
    globalParameterUnserialisation:  GlobalParameterUnserialisation,
    apiAccountUnserialisation:       ApiAccountUnserialisation,
    secretUnserialisation:           SecretUnserialisation
) extends EventLogDetailsService {

  /**
   * An utility method that is able the parse a <X<from>....</from><to>...</to></X>
   * attribute and extract it into a SimpleDiff class.
   */
  private def getFromTo[T](opt: Option[NodeSeq], f: NodeSeq => Box[T]): Box[Option[SimpleDiff[T]]] = {
    opt match {
      case None    => Full(None)
      case Some(x) =>
        for {
          fromS <- (x \ "from").headOption ?~! ("Missing required tag 'from'")
          toS   <- (x \ "to").headOption ?~! ("Missing required tag 'to'")
          from  <- f(fromS)
          to    <- f(toS)
        } yield {
          Some(SimpleDiff(from, to))
        }
    }
  }

  /**
   * Special case of getFromTo for strings.
   */
  private def getFromToString(opt: Option[NodeSeq]) = getFromTo[String](opt, (s: NodeSeq) => Full(s.text))

  def getEntryContent(xml: NodeSeq): Box[Elem] = {
    if (xml.size == 1) {
      val node = Utility.trim(xml.head)
      node match {
        case e: Elem => Full(e)
        case _ => Failure("Given node is not an XML element: " + node.toString)
      }
    } else {
      Failure("Bad details format. We were expected an unique node <entry/>, and we get: " + xml.toString)
    }
  }

  def getSecretFromXML(xml: NodeSeq, changeType: String): Box[Secret] = {
    for {
      entry           <- getEntryContent(xml)
      s               <- (entry \ "secret").headOption ?~! (s"Entry type is not a secret: ${entry}")
      changeTypeAddOk <- {
        if (s.attribute("changeType").map(_.text) == Some(changeType)) Full("OK")
        else Failure(s"Secret attribute does not have changeType=${changeType} in ${entry}")
      }
      secret          <- secretUnserialisation.unserialise(s)
    } yield {
      secret
    }
  }

  /**
   * Version 2:
     <rule changeType="add" fileFormat="2">
        <id>{rule.id.value}</id>
        <name>{rule.name}</name>
        <serial>{rule.serial}</serial>
        <target>{ rule.target.map( _.target).getOrElse("") }</target>
        <directiveIds>{
          rule.directiveIds.map { id => <id>{id.value}</id> }
        }</directiveIds>
        <shortDescription>{rule.shortDescription}</shortDescription>
        <longDescription>{rule.longDescription}</longDescription>
        <isEnabled>{rule.isEnabledStatus}</isEnabled>
        <isSystem>{rule.isSystem}</isSystem>
      </rule>
   */
  override def getRuleAddDetails(xml: NodeSeq): Box[AddRuleDiff] = {
    getRuleFromXML(xml, "add").map(rule => AddRuleDiff(rule))
  }

  /**
   * Version 2:
     <rule changeType="delete" fileFormat="2">
        <id>{rule.id.value}</id>
        <name>{rule.name}</name>
        <serial>{rule.serial}</serial>
        <target>{ rule.target.map( _.target).getOrElse("") }</target>
        <directiveIds>{
          rule.directiveIds.map { id => <id>{id.value}</id> }
        }</directiveIds>
        <shortDescription>{rule.shortDescription}</shortDescription>
        <longDescription>{rule.longDescription}</longDescription>
        <isEnabled>{rule.isEnabledStatus}</isEnabled>
        <isSystem>{rule.isSystem}</isSystem>
      </rule>
   */
  override def getRuleDeleteDetails(xml: NodeSeq): Box[DeleteRuleDiff] = {
    getRuleFromXML(xml, "delete").map(rule => DeleteRuleDiff(rule))
  }

  /**
   * <rule changeType="modify">
     <id>012f3064-d392-43a3-bec9-b0f75950a7ea</id>
     <displayName>cr1</displayName>
     <name><from>cr1</from><to>cr1-x</to></name>
     <category><from>oldId</from><to>newId</to></category>
     <target><from>....</from><to>....</to></target>
     <directiveIds><from><id>...</id><id>...</id></from><to><id>...</id></to></directiveIds>
     <shortDescription><from>...</from><to>...</to></shortDescription>
     <longDescription><from>...</from><to>...</to></longDescription>
     </rule>
   */
  override def getRuleModifyDetails(xml: NodeSeq): Box[ModifyRuleDiff] = for {
    entry            <- getEntryContent(xml)
    rule             <- (entry \ "rule").headOption ?~! ("Entry type is not rule : " + entry.toString())
    changeTypeAddOk  <- {
      if (rule.attribute("changeType").map(_.text) == Some("modify"))
        Full("OK")
      else
        Failure("Rule attribute does not have changeType=modify: " + entry.toString())
    }
    fileFormatOk     <- TestFileFormat(rule)
    sid              <- (rule \ "id").headOption.map(_.text) ?~!
                        ("Missing attribute 'id' in entry type rule : " + entry.toString())
    id               <- RuleId.parse(sid).toBox
    displayName      <- (rule \ "displayName").headOption.map(_.text) ?~!
                        ("Missing attribute 'displayName' in entry type rule : " + entry.toString())
    name             <- getFromToString((rule \ "name").headOption)
    category         <- getFromTo[RuleCategoryId](
                          (rule \ "category").headOption,
                          s => Full(RuleCategoryId(s.text))
                        )
    serial           <- getFromTo[Int]((rule \ "serial").headOption, x => tryo(x.text.toInt))
    targets          <- getFromTo[Set[RuleTarget]](
                          (rule \ "targets").headOption,
                          (x: NodeSeq) => Full((x \ "target").toSet.flatMap((y: NodeSeq) => RuleTarget.unser(y.text).toOption))
                        )
    shortDescription <- getFromToString((rule \ "shortDescription").headOption)
    longDescription  <- getFromToString((rule \ "longDescription").headOption)
    isEnabled        <- getFromTo[Boolean]((rule \ "isEnabled").headOption, s => tryo(s.text.toBoolean))
    isSystem         <- getFromTo[Boolean]((rule \ "isSystem").headOption, s => tryo(s.text.toBoolean))
    directiveIds     <- getFromTo[Set[DirectiveId]](
                          (rule \ "directiveIds").headOption,
                          { (x: NodeSeq) =>
                            Full((x \ "id").toSet.map { (y: NodeSeq) =>
                              DirectiveId(DirectiveUid(y.text), ParseRev((y \ "@revision").text))
                            })
                          }
                        )
  } yield {
    ModifyRuleDiff(
      id = id,
      name = displayName,
      modName = name,
      modSerial = serial,
      modTarget = targets,
      modDirectiveIds = directiveIds,
      modShortDescription = shortDescription,
      modLongDescription = longDescription,
      modIsActivatedStatus = isEnabled,
      modIsSystem = isSystem,
      modCategory = category
    )
  }

  /**
   * Map XML into a rule
   */
  private def getRuleFromXML(xml: NodeSeq, changeType: String): Box[Rule] = {
    for {
      entry           <- getEntryContent(xml)
      crXml           <- (entry \ "rule").headOption ?~! ("Entry type is not a rule: " + entry.toString())
      changeTypeAddOk <- {
        if (crXml.attribute("changeType").map(_.text) == Some(changeType)) Full("OK")
        else Failure("Rule attribute does not have changeType=%s: ".format(changeType) + entry)
      }
      rule            <- crUnserialiser.unserialise(crXml).toBox
    } yield {
      rule
    }
  }

  ///// directives /////

  /**
   * Map XML into a directive
   */
  private def getDirectiveFromXML(xml: NodeSeq, changeType: String): Box[(TechniqueName, Directive, SectionVal)] = {
    for {
      entry           <- getEntryContent(xml)
      directiveXml    <- (entry \ "directive").headOption ?~! ("Entry type is not a directive: " + entry.toString())
      changeTypeAddOk <- {
        if (directiveXml.attribute("changeType").map(_.text) == Some(changeType)) Full("OK")
        else Failure("Directive attribute does not have changeType=%s: ".format(changeType) + entry)
      }
      unserialised    <- piUnserialiser.unserialise(directiveXml).toBox
    } yield {
      unserialised
    }
  }

  def getDirectiveAddDetails(xml: NodeSeq): Box[(AddDirectiveDiff, SectionVal)] = {
    getDirectiveFromXML(xml, "add").map {
      case (ptName, directive, sectionVal) =>
        (AddDirectiveDiff(ptName, directive), sectionVal)
    }
  }

  def getDirectiveDeleteDetails(xml: NodeSeq): Box[(DeleteDirectiveDiff, SectionVal)] = {
    getDirectiveFromXML(xml, "delete").map {
      case (ptName, directive, sectionVal) =>
        (DeleteDirectiveDiff(ptName, directive), sectionVal)
    }
  }

  def getDirectiveModifyDetails(xml: NodeSeq): Box[ModifyDirectiveDiff] = {
    for {
      entry            <- getEntryContent(xml)
      directive        <- (entry \ "directive").headOption ?~! ("Entry type is not directive : " + entry.toString())
      changeTypeAddOk  <- {
        if (directive.attribute("changeType").map(_.text) == Some("modify")) Full("OK")
        else Failure("Directive attribute does not have changeType=modify: " + entry.toString())
      }
      fileFormatOk     <- TestFileFormat(directive)
      sid              <-
        (directive \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type directive : " + entry.toString())
      id               <- DirectiveId.parse(sid).toBox
      ptName           <- (directive \ "techniqueName").headOption.map(
                            _.text
                          ) ?~! ("Missing attribute 'techniqueName' in entry type directive : " + entry.toString())
      displayName      <- (directive \ "displayName").headOption.map(
                            _.text
                          ) ?~! ("Missing attribute 'displayName' in entry type directive : " + entry.toString())
      name             <- getFromToString((directive \ "name").headOption)
      techniqueVersion <-
        getFromTo[TechniqueVersion]((directive \ "techniqueVersion").headOption, v => TechniqueVersion.parse(v.text).toBox)
      parameters       <-
        getFromTo[SectionVal]((directive \ "parameters").headOption, parameter => piUnserialiser.parseSectionVal(parameter).toBox)
      shortDescription <- getFromToString((directive \ "shortDescription").headOption)
      longDescription  <- getFromToString((directive \ "longDescription").headOption)
      priority         <- getFromTo[Int]((directive \ "priority").headOption, x => tryo(x.text.toInt))
      isEnabled        <- getFromTo[Boolean]((directive \ "isEnabled").headOption, s => tryo(s.text.toBoolean))
      isSystem         <- getFromTo[Boolean]((directive \ "isSystem").headOption, s => tryo(s.text.toBoolean))
      policyMode       <-
        getFromTo[Option[PolicyMode]]((directive \ "policyMode").headOption, x => PolicyMode.parseDefault(x.text).toBox)
      tags             <-
        getFromTo[Tags]((directive \ "tags").headOption, extractTags(entryType = "directive"))
    } yield {
      ModifyDirectiveDiff(
        techniqueName = TechniqueName(ptName),
        id,
        displayName,
        name,
        techniqueVersion,
        parameters,
        shortDescription,
        longDescription,
        priority,
        isEnabled,
        isSystem,
        policyMode,
        tags
      )
    }
  }

  ///// node group /////

  override def getNodeGroupAddDetails(xml: NodeSeq): Box[AddNodeGroupDiff] = {
    getNodeGroupFromXML(xml, "add").map(group => AddNodeGroupDiff(group))
  }

  override def getNodeGroupDeleteDetails(xml: NodeSeq): Box[DeleteNodeGroupDiff] = {
    getNodeGroupFromXML(xml, "delete").map(group => DeleteNodeGroupDiff(group))
  }

  override def getNodeGroupModifyDetails(xml: NodeSeq): Box[ModifyNodeGroupDiff] = {
    for {
      entry           <- getEntryContent(xml)
      group           <- (entry \ "nodeGroup").headOption ?~! ("Entry type is not nodeGroup : " + entry.toString())
      changeTypeAddOk <- {
        if (group.attribute("changeType").map(_.text) == Some("modify")) Full("OK")
        else Failure("NodeGroup attribute does not have changeType=modify: " + entry.toString())
      }
      fileFormatOk    <- TestFileFormat(group)
      sid             <- (group \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type nodeGroup : " + entry.toString())
      id              <- NodeGroupId.parse(sid).toBox
      displayName     <- (group \ "displayName").headOption.map(
                           _.text
                         ) ?~! ("Missing attribute 'displayName' in entry type nodeGroup : " + entry.toString())
      name            <- getFromToString((group \ "name").headOption)
      description     <- getFromToString((group \ "description").headOption)
      query           <- getFromTo[Option[Query]](
                           (group \ "query").headOption,
                           { s =>
                             // check for <from><none></none></from> or the same with <to>, <none/>, etc
                             if ((s \ "none").isEmpty) cmdbQueryParser(s.text).map(Some(_))
                             else Full(None)
                           }
                         )
      isDynamic       <- getFromTo[Boolean]((group \ "isDynamic").headOption, s => tryo(s.text.toBoolean))
      serverList      <- getFromTo[Set[NodeId]](
                           (group \ "nodeIds").headOption,
                           (x: NodeSeq) => Full((x \ "id").toSet.map((y: NodeSeq) => NodeId(y.text)))
                         )
      isEnabled       <- getFromTo[Boolean]((group \ "isEnabled").headOption, s => tryo(s.text.toBoolean))
      isSystem        <- getFromTo[Boolean]((group \ "isSystem").headOption, s => tryo(s.text.toBoolean))
    } yield {
      ModifyNodeGroupDiff(
        id = id,
        name = displayName,
        modName = name,
        modDescription = description,
        modQuery = query,
        modIsDynamic = isDynamic,
        modNodeList = serverList,
        modIsActivated = isEnabled,
        modIsSystem = isSystem
      )
    }
  }

  /**
   * Map XML into a node group
   */
  private def getNodeGroupFromXML(xml: NodeSeq, changeType: String): Box[NodeGroup] = {
    for {
      entry           <- getEntryContent(xml)
      groupXml        <- (entry \ "nodeGroup").headOption ?~! ("Entry type is not a nodeGroup: " + entry.toString())
      changeTypeAddOk <- {
        if (groupXml.attribute("changeType").map(_.text) == Some(changeType)) Full("OK")
        else Failure("nodeGroup attribute does not have changeType=%s: ".format(changeType) + entry)
      }
      group           <- groupUnserialiser.unserialise(groupXml).toBox
    } yield {
      group
    }
  }

  def getAcceptNodeLogDetails(xml: NodeSeq): Box[InventoryLogDetails] = {
    getInventoryLogDetails(xml, "accept")
  }

  def getRefuseNodeLogDetails(xml: NodeSeq): Box[InventoryLogDetails] = {
    getInventoryLogDetails(xml, "refuse")
  }

  /**
   * Get inventory details
   */
  private def getInventoryLogDetails(xml: NodeSeq, action: String): Box[InventoryLogDetails] = {
    for {
      entry        <- getEntryContent(xml)
      details      <- (entry \ "node").headOption ?~! ("Entry type is not a node: " + entry.toString())
      actionOk     <- {
        if (details.attribute("action").map(_.text) == Some(action)) Full("OK")
        else Failure("node attribute does not have action=%s: ".format(action) + entry)
      }
      fileFormatOk <- TestFileFormat(details)
      nodeId       <- (details \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type node: " + entry.toString())
      version      <- (details \ "inventoryVersion").headOption.map(
                        _.text
                      ) ?~! ("Missing attribute 'inventoryVersion' in entry type node : " + entry.toString())
      hostname     <- (details \ "hostname").headOption
                        .map(_.text) ?~! ("Missing attribute 'hostname' in entry type node : " + entry.toString())
      os           <- (details \ "fullOsName").headOption
                        .map(_.text) ?~! ("Missing attribute 'fullOsName' in entry type node : " + entry.toString())
      actorIp      <-
        (details \ "actorIp").headOption.map(_.text) ?~! ("Missing attribute 'actorIp' in entry type node : " + entry.toString())
    } yield {
      InventoryLogDetails(
        nodeId = NodeId(nodeId),
        inventoryVersion = DateFormaterService.toInstant(ISODateTimeFormat.dateTimeParser.parseDateTime(version)),
        hostname = hostname,
        fullOsName = os,
        actorIp = actorIp
      )
    }
  }

  def getDeleteNodeLogDetails(xml: NodeSeq): Box[InventoryLogDetails] = {
    getInventoryLogDetails(xml, "delete")
  }

  def getDeploymentStatusDetails(xml: NodeSeq): Box[CurrentDeploymentStatus] = {
    for {
      entry            <- getEntryContent(xml)
      details          <- (entry \ "deploymentStatus").headOption ?~! ("Entry type is not a deploymentStatus: " + entry.toString())
      deploymentStatus <- deploymentStatusUnserialisation.unserialise(details)
    } yield {
      deploymentStatus
    }
  }

  /**
   *  <changeAuthorizedNetworks fileFormat="2">
   *  <oldAuthorizedNetworks>
        <net>XXXXX</net>
        <net>SSSSS</net>
      </oldAuthorizedNetworks>
      <newAuthorizedNetworks>
        <net>XXXXX</net>
        <net>SSSSS</net>
        <net>PPPPP</net>
      </newAuthorizedNetworks>
      </changeAuthorizedNetworks>
   */
  def getUpdatePolicyServerDetails(xml: NodeSeq): Box[AuthorizedNetworkModification] = {
    for {
      entry        <- getEntryContent(xml)
      details      <-
        (entry \ "changeAuthorizedNetworks").headOption ?~! ("Entry type is not a changeAuthorizedNetworks: " + entry.toString())
      fileFormatOk <- TestFileFormat(details)
      oldsXml      <- (entry \\ "oldAuthorizedNetworks").headOption ?~! ("Missing attribute 'oldAuthorizedNetworks' in entry: " + entry
                        .toString())
      newsXml      <- (entry \\ "newAuthorizedNetworks").headOption ?~! ("Missing attribute 'newAuthorizedNetworks' in entry: " + entry
                        .toString())
    } yield {
      AuthorizedNetworkModification(
        oldNetworks = (oldsXml \ "net").map(_.text),
        newNetworks = (newsXml \ "net").map(_.text)
      )
    }
  }

  /**
   * <techniqueReloaded fileFormat="2">
       <modifiedTechnique>
         <name>{name.value}</name>
         <version>{version.toString}</version>
       </modifiedTechnique>
       <modifiedTechnique>
         <name>{name.value}</name>
         <version>{version.toString}</version>
       </modifiedTechnique>
       ....
     </techniqueReloaded>
   */
  def getTechniqueLibraryReloadDetails(xml: NodeSeq): Box[Seq[TechniqueId]] = {
    for {
      entry              <- getEntryContent(xml)
      details            <- (entry \ "reloadTechniqueLibrary").headOption ?~! ("Entry type is not a techniqueReloaded: " + entry.toString())
      fileFormatOk       <- TestFileFormat(details)
      activeTechniqueIds <- traverse((details \ "modifiedTechnique")) { technique =>
                              for {
                                name    <- (technique \ "name").headOption.map(
                                             _.text
                                           ) ?~! ("Missing attribute 'name' in entry type techniqueReloaded : " + entry.toString())
                                version <-
                                  (technique \ "version").headOption.map(
                                    _.text
                                  ) ?~! ("Missing attribute 'version' in entry type techniqueReloaded : " + entry.toString())
                                v       <- TechniqueVersion.parse(version).toBox
                              } yield {
                                TechniqueId(TechniqueName(name), v)
                              }
                            }
    } yield {
      activeTechniqueIds
    }
  }

  def getTechniqueModifyDetails(xml: NodeSeq): Box[ModifyTechniqueDiff] = {
    for {
      entry        <- getEntryContent(xml)
      technique    <- (entry \ "activeTechnique").headOption ?~!
                      ("Entry type is not a technique: " + entry.toString())
      id           <- (technique \ "id").headOption.map(_.text) ?~!
                      ("Missing attribute 'id' in entry type technique : " + entry.toString())
      displayName  <- (technique \ "techniqueName").headOption.map(_.text) ?~!
                      ("Missing attribute 'displayName' in entry type rule : " + entry.toString())
      isEnabled    <- getFromTo[Boolean]((technique \ "isEnabled").headOption, s => tryo(s.text.toBoolean))
      fileFormatOk <- TestFileFormat(technique)
    } yield {
      ModifyTechniqueDiff(
        id = ActiveTechniqueId(id),
        name = TechniqueName(displayName),
        modIsEnabled = isEnabled
      )
    }
  }

  override def getTechniqueDeleteDetails(xml: NodeSeq): Box[DeleteTechniqueDiff] = {
    getTechniqueFromXML(xml, "delete").map(technique => DeleteTechniqueDiff(technique))
  }

  /**
   * Map XML into a technique
   */
  private def getTechniqueFromXML(xml: NodeSeq, changeType: String): Box[ActiveTechnique] = {
    for {
      entry           <- getEntryContent(xml)
      techniqueXml    <- (entry \ "activeTechnique").headOption ?~! ("Entry type is not a technique: " + entry.toString())
      changeTypeAddOk <- if (techniqueXml.attribute("changeType").map(_.text) == Some(changeType))
                           Full("OK")
                         else
                           Failure("Technique attribute does not have changeType=%s: ".format(changeType) + entry)
      technique       <- techniqueUnserialiser.unserialise(techniqueXml)
    } yield {
      technique
    }
  }

  def getNewArchiveDetails[T <: ExportEventLog](xml: NodeSeq, archive: T): Box[GitArchiveId] = {
    def getCommitInfo(xml: NodeSeq, tagName: String) = {
      for {
        entry        <- getEntryContent(xml)
        details      <- (entry \ tagName).headOption ?~! ("Entry type is not a '%s': %s".format(tagName, entry))
        fileFormatOk <- TestFileFormat(details)
        path         <- (details \ "path").headOption.map(_.text) ?~! ("Missing attribute 'path' in entry: " + xml)
        commitId     <- (details \ "commit").headOption.map(_.text) ?~! ("Missing attribute 'commit' in entry: " + xml)
        name         <- (details \ "commiterName").headOption.map(_.text) ?~! ("Missing attribute 'commiterName' in entry: " + xml)
        email        <- (details \ "commiterEmail").headOption.map(_.text) ?~! ("Missing attribute 'commiterEmail' in entry: " + xml)
      } yield {
        GitArchiveId(GitPath(path), GitCommitId(commitId), new PersonIdent(name, email))
      }
    }

    archive match {
      case x: ExportGroupsArchive           => getCommitInfo(xml, ExportGroupsArchive.tagName)
      case x: ExportTechniqueLibraryArchive => getCommitInfo(xml, ExportTechniqueLibraryArchive.tagName)
      case x: ExportRulesArchive            => getCommitInfo(xml, ExportRulesArchive.tagName)
      case x: ExportParametersArchive       => getCommitInfo(xml, ExportParametersArchive.tagName)
      case x: ExportFullArchive             => getCommitInfo(xml, ExportFullArchive.tagName)
    }
  }

  def getRestoreArchiveDetails[T <: ImportEventLog](xml: NodeSeq, archive: T): Box[GitCommitId] = {
    def getCommitInfo(xml: NodeSeq, tagName: String) = {
      for {
        entry        <- getEntryContent(xml)
        details      <- (entry \ tagName).headOption ?~! ("Entry type is not a '%s': %s".format(tagName, entry))
        fileFormatOk <- TestFileFormat(details)
        commitId     <- (details \ "commit").headOption.map(_.text) ?~! ("Missing attribute 'commit' in entry: " + xml)
      } yield {
        GitCommitId(commitId)
      }
    }

    archive match {
      case x: ImportGroupsArchive           => getCommitInfo(xml, ImportGroupsArchive.tagName)
      case x: ImportTechniqueLibraryArchive => getCommitInfo(xml, ImportTechniqueLibraryArchive.tagName)
      case x: ImportRulesArchive            => getCommitInfo(xml, ImportRulesArchive.tagName)
      case x: ImportParametersArchive       => getCommitInfo(xml, ImportParametersArchive.tagName)
      case x: ImportFullArchive             => getCommitInfo(xml, ImportFullArchive.tagName)
      case x: Rollback                      => getCommitInfo(xml, Rollback.tagName)
    }
  }

  def getChangeRequestDetails(xml: NodeSeq): Box[ChangeRequestDiff] = {
    for {
      entry         <- getEntryContent(xml)
      changeRequest <- (entry \ "changeRequest").headOption ?~! s"Entry type is not a 'changeRequest': ${entry}"
      kind           =
        (changeRequest \ "@changeType").headOption.map(_.text)
      crId          <- (changeRequest \ "id").headOption.map(id =>
                         ChangeRequestId(id.text.toInt)
                       ) ?~! s"change request does not have any Id: ${changeRequest}"
      modId          = (changeRequest \ "modId").headOption.map(modId => ModificationId(modId.text))
      name          <- (changeRequest \ "name").headOption.map(_.text) ?~! s"change request does not have any name: ${changeRequest}"
      description   <- (changeRequest \ "description").headOption.map(
                         _.text
                       ) ?~! s"change request does not have any description: ${changeRequest}"
      diffName      <- getFromToString((changeRequest \ "diffName").headOption)
      diffDesc      <- getFromToString((changeRequest \ "diffDescription").headOption)
      changeRequest  =
        ConfigurationChangeRequest(crId, modId, ChangeRequestInfo(name, description), Map(), Map(), Map(), Map())
      res           <-
        kind match {
          case Some("add")           => Full(AddChangeRequestDiff(changeRequest))
          case Some("delete")        => Full(DeleteChangeRequestDiff(changeRequest))
          // None for compat : previously the "modify" was not serialized in the event log xml
          case Some("modify") | None => Full(ModifyToChangeRequestDiff(changeRequest, diffName, diffDesc))
          case Some(_)               => Failure(s"diff is not a valid changeRequest diff: ${changeRequest}")
        }
    } yield {
      res
    }

  }

  def getWorkflotStepChange(xml: NodeSeq): Box[WorkflowStepChange] = {
    for {
      entry        <- getEntryContent(xml)
      workflowStep <- (entry \ "workflowStep").headOption ?~! s"Entry type is not a 'workflowStep': ${entry}"
      crId         <- (workflowStep \ "changeRequestId").headOption.map(id =>
                        ChangeRequestId(id.text.toInt)
                      ) ?~! s"Workflow event does not target any change request: ${workflowStep}"
      from         <- (workflowStep \ "from").headOption.map(from =>
                        WorkflowNodeId(from.text)
                      ) ?~! s"Workflow event does not have any from step: ${workflowStep}"
      to           <- (workflowStep \ "to").headOption.map(to =>
                        WorkflowNodeId(to.text)
                      ) ?~! s"workflow step does not have any to step: ${workflowStep}"
    } yield {
      WorkflowStepChange(crId, from, to)
    }

  }

  def getRollbackDetails(xml: NodeSeq): Box[RollbackInfo] = {
    def getEvents(xml: Elem): Box[Seq[RollbackedEvent]] = {
      traverse(xml \ "rollbackedEvent") { entry =>
        for {
          id      <- (entry \ "id").headOption.map(_.text.toInt) ?~! s"rollbacked event details does not have an id: ${entry}"
          evtType <- (entry \ "type").headOption.map(_.text) ?~! s"rollbacked event details does not have a type: ${entry}"
          author  <- (entry \ "author").headOption.map(_.text) ?~! s"rollbacked event details does not have an author: ${entry}"
          date    <- (entry \ "date").headOption.map(_.text) ?~! s"rollbacked event details does not have a date: ${entry}"
        } yield {
          RollbackedEvent(id, date, evtType, author)
        }
      }
    }

    for {
      entry        <- getEntryContent(xml)
      rollback     <- (entry \ "main").headOption ?~! s"Entry type is not a 'rollback' event: ${entry}"
      id           <- (rollback \ "id").headOption.map(_.text.toInt) ?~! s"rollback event does not have an id: ${entry}"
      evtType      <- (rollback \ "type").headOption.map(_.text) ?~! s"rollback event does not have a type: ${entry}"
      author       <- (rollback \ "author").headOption.map(_.text) ?~! s"rollback event does not have an author: ${entry}"
      date         <- (rollback \ "date").headOption.map(_.text) ?~! s"rollback event does not have a date: ${entry}"
      rollbackType <-
        (rollback \ "rollbackType").headOption.map(_.text) ?~! s"rollback event does not have a rollback type: ${entry}"
      events       <- getEvents(entry)
    } yield {
      val target = RollbackedEvent(id, date, evtType, author)
      RollbackInfo(target, rollbackType, events)
    }
  }

  // Parameters
  def getGlobalParameterFromXML(xml: NodeSeq, changeType: String): Box[GlobalParameter] = {
    for {
      entry           <- getEntryContent(xml)
      globalParam     <- (entry \ "globalParameter").headOption ?~! (s"Entry type is not a globalParameter: ${entry}")
      changeTypeAddOk <- {
        if (globalParam.attribute("changeType").map(_.text) == Some(changeType)) Full("OK")
        else Failure(s"Global Parameter attribute does not have changeType=${changeType} in ${entry}")
      }
      globalParameter <- globalParameterUnserialisation.unserialise(globalParam).toBox
    } yield {
      globalParameter
    }
  }

  def getGlobalParameterAddDetails(xml: NodeSeq): Box[AddGlobalParameterDiff] = {
    getGlobalParameterFromXML(xml, "add").map(globalParam => AddGlobalParameterDiff(globalParam))
  }

  def getGlobalParameterDeleteDetails(xml: NodeSeq): Box[DeleteGlobalParameterDiff] = {
    getGlobalParameterFromXML(xml, "delete").map(globalParam => DeleteGlobalParameterDiff(globalParam))
  }

  def getGlobalParameterModifyDetails(xml: NodeSeq): Box[ModifyGlobalParameterDiff] = {
    for {
      entry          <- getEntryContent(xml)
      globalParam    <- (entry \ "globalParameter").headOption ?~!
                        (s"Entry type is not a Global Parameter: ${entry}")
      name           <- (globalParam \ "name").headOption.map(_.text) ?~!
                        ("Missing attribute 'name' in entry type Global Parameter: ${entry}")
      modValue       <- getFromTo[ConfigValue]((globalParam \ "value").headOption, s => GenericProperty.parseValue(s.text).toBox)
      modInheritMode <- getFromTo[Option[InheritMode]](
                          (globalParam \ "inheritMode").headOption,
                          s => Full(InheritMode.parseString(s.text).toOption)
                        )
      modDescription <- getFromToString((globalParam \ "description").headOption)
      modOverridable <- getFromTo[Boolean]((globalParam \ "overridable").headOption, s => tryo(s.text.toBoolean))
      fileFormatOk   <- TestFileFormat(globalParam)
    } yield {
      ModifyGlobalParameterDiff(
        name = name,
        modValue = (modValue),
        modDescription = modDescription,
        modInheritMode = modInheritMode
      )
    }
  }

  // API Account
  def getApiAccountFromXML(xml: NodeSeq, changeType: String): Box[ApiAccount] = {
    for {
      entry           <- getEntryContent(xml)
      account         <- (entry \ XML_TAG_API_ACCOUNT).headOption ?~! (s"Entry type is not an API Account: ${entry}")
      changeTypeAddOk <- {
        if (account.attribute("changeType").map(_.text) == Some(changeType)) Full("OK")
        else Failure(s"API Account attribute does not have changeType=${changeType} in ${entry}")
      }
      apiAccount      <- apiAccountUnserialisation.unserialise(account)
    } yield {
      apiAccount
    }
  }

  def getApiAccountAddDetails(xml: NodeSeq): Box[AddApiAccountDiff] = {
    getApiAccountFromXML(xml, "add").map(account => AddApiAccountDiff(account))
  }

  def getApiAccountDeleteDetails(xml: NodeSeq): Box[DeleteApiAccountDiff] = {
    getApiAccountFromXML(xml, "delete").map(account => DeleteApiAccountDiff(account))
  }

  def getApiAccountModifyDetails(xml: NodeSeq): Box[ModifyApiAccountDiff] = {
    import cats.implicits.*

    for {
      entry             <- getEntryContent(xml)
      apiAccount        <- (entry \ XML_TAG_API_ACCOUNT).headOption ?~!
                           (s"Entry type is not a Api Account: ${entry}")
      id                <-
        (apiAccount \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type API Account : " + entry.toString())
      modName           <- getFromToString((apiAccount \ "name").headOption)
      modToken          <- getFromToString((apiAccount \ "token").headOption)
      modDescription    <- getFromToString((apiAccount \ "description").headOption)
      modIsEnabled      <- getFromTo[Boolean]((apiAccount \ "enabled").headOption, s => tryo(s.text.toBoolean))
      modTokenGenDate   <- getFromTo[DateTime](
                             (apiAccount \ "tokenGenerationDate").headOption,
                             s => tryo(ISODateTimeFormat.dateTimeParser().parseDateTime(s.text))
                           )
      modExpirationDate <- getFromTo[Option[DateTime]](
                             (apiAccount \ "expirationDate").headOption,
                             s => Full(tryo(ISODateTimeFormat.dateTimeParser().parseDateTime(s.text)).toOption)
                           )
      modAccountKind    <- getFromToString((apiAccount \ "accountKind").headOption)
      modAcls           <- getFromTo[List[ApiAclElement]](
                             (apiAccount \ "acls").headOption,
                             { s =>
                               ((s \ "acl").toList.traverse { x =>
                                 for {
                                   path    <- AclPath.parse((x \ "@path").head.text)
                                   actions <- (x \ "@actions").head.text.split(",").toList.traverse(HttpAction.parse)
                                 } yield {
                                   ApiAclElement(path, actions.toSet)
                                 }
                               }) match {
                                 case Left(e)  => Failure(e)
                                 case Right(x) => Full(x)
                               }
                             }
                           )
      fileFormatOk      <- TestFileFormat(apiAccount)
    } yield {
      ModifyApiAccountDiff(
        id = ApiAccountId(id),
        modName = modName,
        modToken = modToken,
        modDescription = modDescription,
        modIsEnabled = modIsEnabled,
        modTokenGenerationDate = modTokenGenDate,
        modExpirationDate = modExpirationDate,
        modAccountKind = modAccountKind,
        modAccountAcl = modAcls
      )
    }
  }

  // global properties
  def getModifyGlobalPropertyDetails(xml: NodeSeq): Box[(RudderWebProperty, RudderWebProperty)] = {
    for {
      entry        <- getEntryContent(xml)
      globalParam  <- (entry \ "globalPropertyUpdate").headOption ?~! (s"Entry type is not a Global Property: ${entry}")
      name         <- (globalParam \ "name").headOption.map(xml =>
                        RudderWebPropertyName(xml.text)
                      ) ?~! ("Missing attribute 'name' in entry type Global Parameter: ${entry}")
      modValue     <- getFromToString((globalParam \ "value").headOption)
      values       <- modValue
      fileFormatOk <- TestFileFormat(globalParam)
    } yield {
      val oldValue = RudderWebProperty(name, values.oldValue, "")
      val newValue = RudderWebProperty(name, values.newValue, "")
      (oldValue, newValue)
    }
  }

  private def extractAgentRun(xml: NodeSeq)(details: NodeSeq) = {
    if ((details \ "_").isEmpty) { // no children
      Full(None)
    } else {
      for {
        overrides   <- (details \ "override").headOption match {
                         case None      => Full(None)
                         case Some(elt) => if (elt.child.isEmpty) Full(None) else tryo(Some(elt.text.toBoolean))
                       }
        interval    <- (details \ "interval").headOption.flatMap(x =>
                         tryo(x.text.toInt)
                       ) ?~! s"Missing attribute 'interval' in entry type node : '${xml}'"
        startMinute <- (details \ "startMinute").headOption.flatMap(x =>
                         tryo(x.text.toInt)
                       ) ?~! s"Missing attribute 'startMinute' in entry type node : '${xml}'"
        startHour   <- (details \ "startHour").headOption.flatMap(x =>
                         tryo(x.text.toInt)
                       ) ?~! s"Missing attribute 'startHour' in entry type node : '${xml}'"
        splaytime   <- (details \ "splaytime").headOption.flatMap(x =>
                         tryo(x.text.toInt)
                       ) ?~! s"Missing attribute 'splaytime' in entry type node : '${xml}'"
      } yield {
        Some(
          AgentRunInterval(
            overrides,
            interval,
            startMinute,
            startHour,
            splaytime
          )
        )
      }
    }
  }

  private def extractHeartbeatConfiguration(xml: NodeSeq)(details: NodeSeq) = {
    if ((details \ "_").isEmpty) { // no children
      Full(None)
    } else {
      for {
        overrides <- (details \ "override").headOption.flatMap(x =>
                       tryo(x.text.toBoolean)
                     ) ?~! s"Missing attribute 'override' in entry type node : '${xml}'"
        period    <- (details \ "period").headOption.flatMap(x =>
                       tryo(x.text.toInt)
                     ) ?~! s"Missing attribute 'period' in entry type node : '${xml}'"
      } yield {
        Some(HeartbeatConfiguration(overrides, period))
      }
    }
  }

  private def extractNodeProperties(xml: NodeSeq)(details: NodeSeq): Box[Seq[NodeProperty]] = {
    if (details.isEmpty) Full(Seq())
    else {
      for {
        properties <- traverse((details \ "property").toSeq) { prop =>
                        for {
                          name    <-
                            (prop \ "name").headOption.map(_.text) ?~! s"Missing attribute 'name' in entry type node : '${xml}'"
                          value   <-
                            (prop \ "value").headOption.map(_.text) ?~! s"Missing attribute 'value' in entry type node : '${xml}'"
                          // 'provider' is optionnal, default to "default"
                          provider = (prop \ "provider").headOption.map(p => PropertyProvider(p.text))
                          mode     = (prop \ "inheritMode").headOption.flatMap(p => InheritMode.parseString(p.text).toOption)
                          prop    <- NodeProperty.parse(name, value, mode, provider).toBox
                        } yield {
                          prop
                        }
                      }
      } yield {
        properties
      }
    }
  }

  // extract tags, with mention to the entryType when reporting errors
  private def extractTags(entryType: String)(details: NodeSeq): Box[Tags] = {
    if (details.isEmpty) Full(Tags(Set()))
    else {
      val tags = traverse(details \\ "tag") { prop =>
        for {
          name  <-
            (prop \ "@name").headOption.map(_.text) ?~! s"Missing attribute 'name' in entry type $entryType : '$details'"
          value <-
            (prop \ "@value").headOption.map(_.text) ?~! s"Missing attribute 'value' in entry type $entryType : '$details'"
          tag    = Tag(TagName(name), TagValue(value))
        } yield tag
      }

      tags.map(l => Tags(l.toSet))
    }
  }

  def getPromotedNodeToRelayDetails(xml: NodeSeq): Box[(NodeId, String)] = {
    for {
      entry        <- getEntryContent(xml)
      node         <- (entry \ "node").headOption ?~! ("Entry type is not node : " + entry.toString())
      fileFormatOk <- TestFileFormat(node)
      changeTypeOk <- {
        if (node.attribute("changeType").map(_.text) == Some("modify")) Full("OK")
        else Failure(s"'Node promotion' entry does not have attribute 'changeType' with value 'modify', entry is: ${entry}")
      }
      id           <- (node \ "id").headOption.map(x => NodeId(x.text)) ?~! ("Missing element 'id' in entry type Node: " + entry.toString())
      name         <-
        (node \ "hostname").headOption.map(x => x.text) ?~! ("Missing element 'hostname' in entry type Node: " + entry.toString())
    } yield {
      (id, name)
    }
  }

  def getModifyNodeDetails(xml: NodeSeq): Box[ModifyNodeDiff] = {
    for {
      entry         <- getEntryContent(xml)
      node          <- (entry \ "node").headOption ?~! ("Entry type is not node : " + entry.toString())
      fileFormatOk  <- TestFileFormat(node)
      changeTypeOk  <- {
        if (node.attribute("changeType").map(_.text) == Some("modify")) Full("OK")
        else Failure(s"'Node modification' entry does not have attribute 'changeType' with value 'modify', entry is: ${entry}")
      }
      id            <- (node \ "id").headOption.map(x => NodeId(x.text)) ?~! ("Missing element 'id' in entry type Node: " + entry.toString())
      policyMode    <- getFromTo[Option[PolicyMode]]((node \ "policyMode").headOption, x => PolicyMode.parseDefault(x.text).toBox)
      agentRun      <- getFromTo[Option[AgentRunInterval]]((node \ "agentRun").headOption, x => extractAgentRun(xml)(x))
      heartbeat     <-
        getFromTo[Option[HeartbeatConfiguration]]((node \ "heartbeat").headOption, x => extractHeartbeatConfiguration(xml)(x))
      properties    <-
        getFromTo[List[NodeProperty]]((node \ "properties").headOption, x => extractNodeProperties(xml)(x).map(_.toList))
      agentKey      <- getFromTo[SecurityToken](
                         (node \ "agentKey").headOption,
                         { x =>
                           val s = x.text;
                           if (s.contains("BEGIN CERTIFICATE")) Full(Certificate(s)) else Failure(s"Unrecognized security token")
                         }
                       )
      keyStatus     <- getFromTo[KeyStatus](
                         (node \ "keyStatus").headOption,
                         x => KeyStatus.apply(x.text).map(Full(_)).getOrElse(Failure(s"Unrecognized agent key status '${x.text}'"))
                       )
      nodeState     <- getFromTo[NodeState]((node \ "nodeState").headOption, x => NodeState.parse(x.text).toBox)
      documentation <- getFromTo[String]((node \ "documentation").headOption, x => Full(x.text))
    } yield {
      ModifyNodeDiff(
        id,
        heartbeat,
        agentRun,
        properties,
        policyMode,
        agentKey,
        keyStatus,
        nodeState,
        documentation
      )
    }
  }

  def getSecretAddDetails(xml: NodeSeq): Box[AddSecretDiff] = {
    getSecretFromXML(xml, "add").map(secret => AddSecretDiff(secret))
  }

  def getSecretDeleteDetails(xml: NodeSeq): Box[DeleteSecretDiff] = {
    getSecretFromXML(xml, "delete").map(secret => DeleteSecretDiff(secret))
  }

  def getSecretModifyDetails(xml: NodeSeq): Box[ModifySecretDiff] = {
    for {
      entry          <- getEntryContent(xml)
      secret         <- (entry \ "secret").headOption ?~!
                        (s"Entry type is not a Secret: ${entry}")
      name           <- (secret \ "name").headOption.map(_.text) ?~!
                        (s"Missing attribute 'name' in entry type Secret: ${entry}")
      description    <- (secret \ "description").headOption.map(_.text) ?~!
                        (s"Missing attribute 'description' in entry type Secret: ${entry}")
      modValue       <- tryo((secret \ "valueHasChanged").text.toBoolean)
      modDescription <- getFromToString((secret \ "diffDescription").headOption)
      modName        <- getFromToString((secret \ "diffName").headOption)
      fileFormatOk   <- TestFileFormat(secret)
    } yield {
      ModifySecretDiff(
        name = name,
        description = description,
        modValue = (modValue),
        modDescription = (modDescription)
      )
    }
  }

}

final case class RollbackInfo(
    target:       RollbackedEvent,
    rollbackType: String,
    rollbacked:   Seq[RollbackedEvent]
)

final case class RollbackedEvent(
    id:        Int,
    date:      String,
    eventType: String,
    author:    String
)
