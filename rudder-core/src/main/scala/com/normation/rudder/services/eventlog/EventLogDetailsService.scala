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

package com.normation.rudder.services.eventlog

import scala.xml.{Node => XNode, _}
import net.liftweb.common._
import net.liftweb.common.Box._
import net.liftweb.util.Helpers.tryo
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.domain.TechniqueName
import com.normation.utils.Control.sequence
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.domain.queries.Query
import com.normation.inventory.domain.NodeId
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.services.marshalling.RuleUnserialisation
import com.normation.rudder.services.marshalling.NodeGroupUnserialisation
import com.normation.rudder.services.marshalling.DirectiveUnserialisation
import com.normation.rudder.batch.SuccessStatus
import com.normation.rudder.batch.ErrorStatus
import com.normation.rudder.domain.servers.NodeConfiguration
import com.normation.rudder.services.marshalling.DeploymentStatusUnserialisation
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.inventory.domain.AgentType
import com.normation.rudder.domain.eventlog._
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.repository.GitPath
import com.normation.rudder.repository.GitCommitId
import com.normation.rudder.repository.GitArchiveId
import org.eclipse.jgit.lib.PersonIdent
import com.normation.rudder.domain.Constants
import com.normation.rudder.services.marshalling.TestFileFormat

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
  def getEntryContent(xml:NodeSeq) : Box[Elem]

  ///// rule /////
  
  def getRuleAddDetails(xml:NodeSeq) : Box[AddRuleDiff]
  
  def getRuleDeleteDetails(xml:NodeSeq) : Box[DeleteRuleDiff]
  
  def getRuleModifyDetails(xml:NodeSeq) : Box[ModifyRuleDiff]
  
  ///// directive /////
  
  def getDirectiveAddDetails(xml:NodeSeq) : Box[(AddDirectiveDiff, SectionVal)]
  
  def getDirectiveDeleteDetails(xml:NodeSeq) : Box[(DeleteDirectiveDiff, SectionVal)]
  
  def getDirectiveModifyDetails(xml:NodeSeq) : Box[ModifyDirectiveDiff]

  ///// node group /////
  
  def getNodeGroupAddDetails(xml:NodeSeq) : Box[AddNodeGroupDiff]
  
  def getNodeGroupDeleteDetails(xml:NodeSeq) : Box[DeleteNodeGroupDiff]
  
  def getNodeGroupModifyDetails(xml:NodeSeq) : Box[ModifyNodeGroupDiff]
  
  ///// node /////
  
  def getAcceptNodeLogDetails(xml:NodeSeq) : Box[InventoryLogDetails]
  
  def getRefuseNodeLogDetails(xml:NodeSeq) : Box[InventoryLogDetails]
    
  def getDeleteNodeLogDetails(xml:NodeSeq) : Box[NodeLogDetails]
  
  ///// other /////
  
  def getDeploymentStatusDetails(xml:NodeSeq) : Box[CurrentDeploymentStatus]

  def getUpdatePolicyServerDetails(xml:NodeSeq) : Box[AuthorizedNetworkModification]

  def getTechniqueLibraryReloadDetails(xml:NodeSeq) : Box[Seq[TechniqueId]]
  
  ///// archiving & restoration /////
  
  def getNewArchiveDetails[T <: ExportEventLog](xml:NodeSeq, archive:T) : Box[GitArchiveId]
  def getRestoreArchiveDetails[T <: ImportEventLog](xml:NodeSeq, archive:T) : Box[GitCommitId]
}


/**
 * Details should always be in the format: <entry>{more details here}</entry>
 */
class EventLogDetailsServiceImpl(
    cmdbQueryParser  : CmdbQueryParser
  , piUnserialiser   : DirectiveUnserialisation
  , groupUnserialiser: NodeGroupUnserialisation
  , crUnserialiser   : RuleUnserialisation
  , deploymentStatusUnserialisation : DeploymentStatusUnserialisation
) extends EventLogDetailsService {

  
 

  /**
   * An utility method that is able the parse a <X<from>....</from><to>...</to></X>
   * attribute and extract it into a SimpleDiff class. 
   */
  private[this] def getFromTo[T](opt:Option[NodeSeq], f:NodeSeq => Box[T] ) : Box[Option[SimpleDiff[T]]] = {
    opt match {
      case None => Full(None)
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
  private[this] def getFromToString(opt:Option[NodeSeq]) = getFromTo[String](opt,(s:NodeSeq) => Full(s.text))

    
  def getEntryContent(xml:NodeSeq) : Box[Elem] = {
    if(xml.size == 1) {
      val node = Utility.trim(xml.head)
      node match {
        case e:Elem => Full(e)
        case _ => Failure("Given node is not an XML element: " + node.toString)
      }
    } else {
      Failure("Bad details format. We were expected an unique node <entry/>, and we get: " + xml.toString)
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
  override def getRuleAddDetails(xml:NodeSeq) : Box[AddRuleDiff] = {
    getRuleFromXML(xml, "add").map { rule =>
      AddRuleDiff(rule)
    }
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
  override def getRuleDeleteDetails(xml:NodeSeq) : Box[DeleteRuleDiff] = {
    getRuleFromXML(xml, "delete").map { rule =>
      DeleteRuleDiff(rule)
    }
  }
  
  /**
   * <rule changeType="modify">
     <id>012f3064-d392-43a3-bec9-b0f75950a7ea</id>
     <displayName>cr1</displayName>
     <name><from>cr1</from><to>cr1-x</to></name>
     <target><from>....</from><to>....</to></target>
     <directiveIds><from><id>...</id><id>...</id></from><to><id>...</id></to></directiveIds>
     <shortDescription><from>...</from><to>...</to></shortDescription>
     <longDescription><from>...</from><to>...</to></longDescription>
     </rule>
   */
  override def getRuleModifyDetails(xml:NodeSeq) : Box[ModifyRuleDiff] = {
    for {
      entry             <- getEntryContent(xml)
      rule                <- (entry \ "rule").headOption ?~! ("Entry type is not rule : " + entry)
      changeTypeAddOk   <- {
                             if(rule.attribute("changeType").map( _.text ) == Some("modify")) Full("OK")
                             else Failure("Rule attribute does not have changeType=modify: " + entry)
                           }
      fileFormatOk      <- TestFileFormat(rule)
      id                <- (rule \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type rule : " + entry)
      displayName       <- (rule \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type rule : " + entry)
      name              <- getFromToString((rule \ "name").headOption)
      serial            <- getFromTo[Int]((rule \ "serial").headOption, { x => tryo(x.text.toInt) } )
      target            <- getFromTo[Option[RuleTarget]]((rule \ "target").headOption, {s =>  
                              //check for <from><none></none></from> or the same with <to>, <none/>, etc
                              if( (s \ "none").isEmpty) Full(RuleTarget.unser(s.text))
                              else Full(None)
                            } )
      shortDescription  <- getFromToString((rule \ "shortDescription").headOption)
      longDescription   <- getFromToString((rule \ "longDescription").headOption)
      isEnabled       <- getFromTo[Boolean]((rule \ "isEnabled").headOption, { s => tryo { s.text.toBoolean } } ) 
      isSystem          <- getFromTo[Boolean]((rule \ "isSystem").headOption, { s => tryo { s.text.toBoolean } } )
      directiveIds <- getFromTo[Set[DirectiveId]]((rule \ "directiveIds").headOption, { x:NodeSeq => 
                            Full((x \ "id").toSet.map( (y:NodeSeq) => DirectiveId( y.text ) ))
                          } )
    } yield {
      ModifyRuleDiff(
          id = RuleId(id)
        , name = displayName
        , modName = name
        , modSerial = serial
        , modTarget = target
        , modDirectiveIds = directiveIds
        , modShortDescription = shortDescription
        , modLongDescription = longDescription
        , modIsActivatedStatus = isEnabled
        , modIsSystem = isSystem
      )
    }
  }
  
  /**
   * Map XML into a rule
   */
  private[this] def getRuleFromXML(xml:NodeSeq, changeType:String) : Box[Rule] = {  
    for {
      entry           <- getEntryContent(xml)
      crXml           <- (entry \ "rule").headOption ?~! ("Entry type is not a rule: " + entry)
      changeTypeAddOk <- {
                           if(crXml.attribute("changeType").map( _.text ) == Some(changeType)) Full("OK")
                           else Failure("Rule attribute does not have changeType=%s: ".format(changeType) + entry)
                         }
      rule              <- crUnserialiser.unserialise(crXml)
    } yield {
      rule
    }
  }
  
  
  ///// directives /////
  
  
  /**
   * Map XML into a directive
   */
  private[this] def getDirectiveFromXML(xml:NodeSeq, changeType:String) : Box[(TechniqueName, Directive, SectionVal)] = {  
    for {
      entry                 <- getEntryContent(xml)
      piXml                 <- (entry \ "directive").headOption ?~! ("Entry type is not a directive: " + entry)
      changeTypeAddOk       <- {
                                  if(piXml.attribute("changeType").map( _.text ) == Some(changeType)) Full("OK")
                                  else Failure("Directive attribute does not have changeType=%s: ".format(changeType) + entry)
                                }
      ptPiSectionVals       <- piUnserialiser.unserialise(piXml)
    } yield {
      ptPiSectionVals
    }
  }
  

  def getDirectiveAddDetails(xml:NodeSeq) : Box[(AddDirectiveDiff, SectionVal)] = {
    getDirectiveFromXML(xml, "add").map { case (ptName, directive,sectionVal) =>
      (AddDirectiveDiff(ptName, directive),sectionVal)
    }
  }
  
  def getDirectiveDeleteDetails(xml:NodeSeq) : Box[(DeleteDirectiveDiff, SectionVal)] = {
    getDirectiveFromXML(xml, "delete").map { case(ptName, directive,sectionVal) =>
      (DeleteDirectiveDiff(ptName, directive),sectionVal)
    }
  }
  
  def getDirectiveModifyDetails(xml:NodeSeq) : Box[ModifyDirectiveDiff] = {
    for {
      entry <- getEntryContent(xml)
      directive                    <- (entry \ "directive").headOption ?~! ("Entry type is not directive : " + entry)
      changeTypeAddOk       <- {
                                 if(directive.attribute("changeType").map( _.text ) == Some("modify")) Full("OK")
                                 else Failure("Directive attribute does not have changeType=modify: " + entry)
                               }
      fileFormatOk          <- TestFileFormat(directive)
      id                    <- (directive \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type directive : " + entry)
      ptName                <- (directive \ "techniqueName").headOption.map( _.text ) ?~! ("Missing attribute 'techniqueName' in entry type directive : " + entry)
      displayName           <- (directive \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type directive : " + entry)
      name                  <- getFromToString((directive \ "name").headOption)
      techniqueVersion <- getFromTo[TechniqueVersion]((directive \ "techniqueVersion").headOption, {v =>
                                  tryo(TechniqueVersion(v.text))
                                } )
      parameters            <- getFromTo[SectionVal]((directive \ "parameters").headOption, {parameter =>
                                piUnserialiser.parseSectionVal(parameter)
                              })
      shortDescription      <- getFromToString((directive \ "shortDescription").headOption)
      longDescription       <- getFromToString((directive \ "longDescription").headOption)
      priority              <- getFromTo[Int]((directive \ "priority").headOption, { x => tryo(x.text.toInt) } )
      isEnabled           <- getFromTo[Boolean]((directive \ "isEnabled").headOption, { s => tryo { s.text.toBoolean } } ) 
      isSystem              <- getFromTo[Boolean]((directive \ "isSystem").headOption, { s => tryo { s.text.toBoolean } } )
    } yield {
      ModifyDirectiveDiff(
          techniqueName = TechniqueName(ptName)
        , id = DirectiveId(id)
        , name = displayName
        , modName = name
        , modTechniqueVersion = techniqueVersion
        , modParameters = parameters
        , modShortDescription = shortDescription
        , modLongDescription = longDescription
        , modPriority = priority
        , modIsActivated = isEnabled
        , modIsSystem = isSystem
      )
    }
  }
  
  ///// node group /////
  
  override def getNodeGroupAddDetails(xml:NodeSeq) : Box[AddNodeGroupDiff] = {
    getNodeGroupFromXML(xml, "add").map { group =>
      AddNodeGroupDiff(group)
    }
  }
  
  override def getNodeGroupDeleteDetails(xml:NodeSeq) : Box[DeleteNodeGroupDiff] = {
    getNodeGroupFromXML(xml, "delete").map { group =>
      DeleteNodeGroupDiff(group)
    }
  }
  
  override def getNodeGroupModifyDetails(xml:NodeSeq) : Box[ModifyNodeGroupDiff] = {
    for {
      entry           <- getEntryContent(xml)
      group           <- (entry \ "nodeGroup").headOption ?~! ("Entry type is not nodeGroup : " + entry)
      changeTypeAddOk <- {
                            if(group.attribute("changeType").map( _.text ) == Some("modify")) Full("OK")
                            else Failure("NodeGroup attribute does not have changeType=modify: " + entry)
                          }
      fileFormatOk    <- TestFileFormat(group)
      id              <- (group \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type nodeGroup : " + entry)
      displayName     <- (group \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type nodeGroup : " + entry)
      name            <- getFromToString((group \ "name").headOption)
      description     <- getFromToString((group \ "description").headOption)
      query           <- getFromTo[Option[Query]]((group \ "query").headOption, {s => 
                          //check for <from><none></none></from> or the same with <to>, <none/>, etc
                          if( (s \ "none").isEmpty) cmdbQueryParser(s.text).map( Some(_) )
                          else Full(None)
                        } )
      isDynamic       <- getFromTo[Boolean]((group \ "isDynamic").headOption, { s => tryo { s.text.toBoolean } } ) 
      serverList      <- getFromTo[Set[NodeId]]((group \ "nodeIds").headOption, { x:NodeSeq => 
                            Full((x \ "id").toSet.map( (y:NodeSeq) => NodeId( y.text ) ))
                          } )
      isEnabled     <- getFromTo[Boolean]((group \ "isEnabled").headOption, { s => tryo { s.text.toBoolean } } ) 
      isSystem        <- getFromTo[Boolean]((group \ "isSystem").headOption, { s => tryo { s.text.toBoolean } } )
    } yield {
      ModifyNodeGroupDiff(
          id = NodeGroupId(id)
        , name = displayName
        , modName = name
        , modDescription = description
        , modQuery = query
        , modIsDynamic = isDynamic
        , modNodeList = serverList
        , modIsActivated = isEnabled
        , modIsSystem = isSystem
      )
    }
  }

  /**
   * Map XML into a node group
   */
  private[this] def getNodeGroupFromXML(xml:NodeSeq, changeType:String) : Box[NodeGroup] = {  
    for {
      entry           <- getEntryContent(xml)
      groupXml        <- (entry \ "nodeGroup").headOption ?~! ("Entry type is not a nodeGroup: " + entry)
      changeTypeAddOk <- {
                            if(groupXml.attribute("changeType").map( _.text ) == Some(changeType)) Full("OK")
                            else Failure("nodeGroup attribute does not have changeType=%s: ".format(changeType) + entry)
                          }
      group           <- groupUnserialiser.unserialise(groupXml)
    } yield {
      group
    }
  }
  
  
  def getAcceptNodeLogDetails(xml:NodeSeq) : Box[InventoryLogDetails] = {
    getInventoryLogDetails(xml, "accept")
  }
  
  def getRefuseNodeLogDetails(xml:NodeSeq) : Box[InventoryLogDetails] = {
    getInventoryLogDetails(xml, "refuse")
  }
  
  /**
   * Get inventory details
   */
  private[this] def getInventoryLogDetails(xml:NodeSeq, action:String) : Box[InventoryLogDetails] = {
    for {
      entry        <- getEntryContent(xml)
      details      <- (entry \ "node").headOption ?~! ("Entry type is not a node: " + entry)
      actionOk     <- {
                        if(details.attribute("action").map( _.text ) == Some(action)) Full("OK")
                        else Failure("node attribute does not have action=%s: ".format(action) + entry)
                      }
      fileFormatOk <- TestFileFormat(details)
      nodeId       <- (details \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type node: " + entry)
      version      <- (details \ "inventoryVersion").headOption.map( _.text ) ?~! ("Missing attribute 'inventoryVersion' in entry type node : " + entry)
      hostname     <- (details \ "hostname").headOption.map( _.text ) ?~! ("Missing attribute 'hostname' in entry type node : " + entry)
      os           <- (details \ "fullOsName").headOption.map( _.text ) ?~! ("Missing attribute 'fullOsName' in entry type node : " + entry)
      actorIp      <- (details \ "actorIp").headOption.map( _.text ) ?~! ("Missing attribute 'actorIp' in entry type node : " + entry)      
    } yield {
      InventoryLogDetails(
          nodeId           = NodeId(nodeId)
        , inventoryVersion = ISODateTimeFormat.dateTimeParser.parseDateTime(version)
        , hostname         = hostname
        , fullOsName       = os
        , actorIp          = actorIp
      )
    }
  }
  
  
  def getDeleteNodeLogDetails(xml:NodeSeq) : Box[NodeLogDetails] = {
    getNodeLogDetails(xml, "delete")
  }

 /**
  * Get node details
  */   
  private[this] def getNodeLogDetails(xml:NodeSeq, action:String) : Box[NodeLogDetails] = {
    for {
      entry          <- getEntryContent(xml)
      details        <- (entry \ "node").headOption ?~! ("Entry type is not a node: " + entry)
      actionOk       <- {
                          if(details.attribute("action").map( _.text ) == Some(action)) Full("OK")
                          else Failure("node attribute does not have action=%s: ".format(action) + entry)
                        }
      fileFormatOk   <- TestFileFormat(details)
      nodeId         <- (details \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type node: " + entry)
      name           <- (details \ "name").headOption.map( _.text ) ?~! ("Missing attribute 'name' in entry type node : " + entry)
      hostname       <- (details \ "hostname").headOption.map( _.text ) ?~! ("Missing attribute 'hostname' in entry type node : " + entry)
      description    <- (details \ "description").headOption.map( _.text ) ?~! ("Missing attribute 'description' in entry type node : " + entry)
      ips            <- (details \ "ips").headOption.map { case  x:NodeSeq =>
                          (x \ "ip").toSeq.map( (y:NodeSeq) => y.text  )
                        }?~! ("Missing attribute 'ips' in entry type node : " + entry)
      os             <- (details \ "os").headOption.map( _.text ) ?~! ("Missing attribute 'os' in entry type node : " + entry)
      boxedAgentsName<- (details \ "agentsName").headOption.map  {
                              case x:NodeSeq => 
                              (x \ "agentName").toSeq.map( (y:NodeSeq) => AgentType.fromValue(y.text) )
                            } ?~! ("Missing attribute 'agentsName' in entry type node : " + entry)
      inventoryDate  <- (details \ "inventoryDate").headOption.map( _.text ) ?~! ("Missing attribute 'inventoryDate' in entry type node : " + entry)
      publicKey      <- (details \ "publicKey").headOption.map( _.text ) ?~! ("Missing attribute 'publicKey' in entry type node : " + entry)
      policyServerId <- (details \ "policyServerId").headOption.map( _.text ) ?~! ("Missing attribute 'policyServerId' in entry type node : " + entry)
      localAdministratorAccountName  <- (details \ "localAdministratorAccountName").headOption.map( _.text ) ?~! ("Missing attribute 'localAdministratorAccountName' in entry type node : " + entry)
      creationDate   <- (details \ "creationDate").headOption.map( _.text ) ?~! ("Missing attribute 'creationDate' in entry type node : " + entry)
      isBroken       <- (details \ "isBroken").headOption.map(_.text.toBoolean ) 
      isSystem       <- (details \ "isSystem").headOption.map(_.text.toBoolean ) 
      isPolicyServer <- (details \ "isPolicyServer").headOption.map(_.text.toBoolean )
      
    } yield {
      val agentsNames =  com.normation.utils.Control.boxSequence[AgentType](boxedAgentsName)
      
      NodeLogDetails(node = NodeInfo(
          id            = NodeId(nodeId)
        , name          = name
        , description   = description
        , hostname      = hostname
        , os            = os
        , ips           = ips.toList
        , inventoryDate = ISODateTimeFormat.dateTimeParser.parseDateTime(inventoryDate)
        , publicKey     = publicKey
        , agentsName    = agentsNames.openOr(Seq())
        , policyServerId= NodeId(policyServerId)
        , localAdministratorAccountName= localAdministratorAccountName
        , creationDate  = ISODateTimeFormat.dateTimeParser.parseDateTime(creationDate)
        , isBroken      = isBroken
        , isSystem      =isSystem
        , isPolicyServer=isPolicyServer
      ))
    }
  }
  
  
  
  def getDeploymentStatusDetails(xml:NodeSeq) : Box[CurrentDeploymentStatus] = {
    for {
      entry        <- getEntryContent(xml)
      details      <- (entry \ "deploymentStatus").headOption ?~! ("Entry type is not a deploymentStatus: " + entry)
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
  def getUpdatePolicyServerDetails(xml:NodeSeq) : Box[AuthorizedNetworkModification] = {
    for {
      entry        <- getEntryContent(xml)
      details      <- (entry \ "changeAuthorizedNetworks").headOption ?~! ("Entry type is not a changeAuthorizedNetworks: " + entry)
      fileFormatOk <- TestFileFormat(details)
      oldsXml      <- (entry \ "oldAuthorizedNetworks").headOption ?~! ("Missing attribute 'oldAuthorizedNetworks' in entry: " + entry)
      newsXml      <- (entry \ "newAuthorizedNetworks").headOption ?~! ("Missing attribute 'newAuthorizedNetworks' in entry: " + entry)
    } yield {
      AuthorizedNetworkModification(
          oldNetworks = (oldsXml \ "net").map( _.text )
        , newNetworks = (newsXml \ "net").map( _.text )
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
  def getTechniqueLibraryReloadDetails(xml:NodeSeq) : Box[Seq[TechniqueId]] = {
    for {
      entry              <- getEntryContent(xml)
      details            <- (entry \ "reloadTechniqueLibrary").headOption ?~! ("Entry type is not a techniqueReloaded: " + entry)
      fileFormatOk       <- TestFileFormat(details)
      activeTechniqueIds <- sequence((details \ "modifiedTechnique")) { technique =>
                              for {
                                name    <- (technique \ "name").headOption.map( _.text ) ?~! ("Missing attribute 'name' in entry type techniqueReloaded : " + entry)
                                version <- (technique \ "version").headOption.map( _.text ) ?~! ("Missing attribute 'version' in entry type techniqueReloaded : " + entry)
                                v       <- tryo { TechniqueVersion(version) }
                              } yield {
                                TechniqueId(TechniqueName(name),v)
                              }
                            }
    } yield {
      activeTechniqueIds
    }
  }
  
  
  
  def getNewArchiveDetails[T <: ExportEventLog](xml:NodeSeq, archive:T) : Box[GitArchiveId] = {
    def getCommitInfo(xml:NodeSeq, tagName:String) = {
      for {
        entry        <- getEntryContent(xml)
        details      <- (entry \ tagName).headOption ?~! ("Entry type is not a '%s': %s".format(tagName, entry))
        fileFormatOk <- TestFileFormat(details)
        path         <- (details \ "path").headOption.map( _.text ) ?~! ("Missing attribute 'path' in entry: " + xml)
        commitId     <- (details \ "commit").headOption.map( _.text ) ?~! ("Missing attribute 'commit' in entry: " + xml)
        name         <- (details \ "commiterName").headOption.map( _.text ) ?~! ("Missing attribute 'commiterName' in entry: " + xml)
        email        <- (details \ "commiterEmail").headOption.map( _.text ) ?~! ("Missing attribute 'commiterEmail' in entry: " + xml)
      } yield {
        GitArchiveId(GitPath(path), GitCommitId(commitId), new PersonIdent(name, email))
      }
    }
    
    archive match {
      case x:ExportGroupsArchive => getCommitInfo(xml, ExportGroupsArchive.tagName)
      case x:ExportTechniqueLibraryArchive => getCommitInfo(xml, ExportTechniqueLibraryArchive.tagName)
      case x:ExportRulesArchive => getCommitInfo(xml, ExportRulesArchive.tagName)
      case x:ExportFullArchive => getCommitInfo(xml, ExportFullArchive.tagName)
    }
  }
  
  def getRestoreArchiveDetails[T <: ImportEventLog](xml:NodeSeq, archive:T) : Box[GitCommitId] = {
    def getCommitInfo(xml:NodeSeq, tagName:String) = {
      for {
        entry        <- getEntryContent(xml)
        details      <- (entry \ tagName).headOption ?~! ("Entry type is not a '%s': %s".format(tagName, entry))
        fileFormatOk <- TestFileFormat(details)
        commitId <- (details \ "commit").headOption.map( _.text ) ?~! ("Missing attribute 'commit' in entry: " + xml)
      } yield {
        GitCommitId(commitId)
      }
    }
    
    archive match {
      case x:ImportGroupsArchive => getCommitInfo(xml, ImportGroupsArchive.tagName)
      case x:ImportTechniqueLibraryArchive => getCommitInfo(xml, ImportTechniqueLibraryArchive.tagName)
      case x:ImportRulesArchive => getCommitInfo(xml, ImportRulesArchive.tagName)
      case x:ImportFullArchive => getCommitInfo(xml, ImportFullArchive.tagName)
    }
  }

}