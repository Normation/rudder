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

package com.normation.rudder.services.log

import scala.xml.{Node => XNode, _}
import net.liftweb.common._
import net.liftweb.common.Box._
import net.liftweb.util.Helpers.tryo
import com.normation.cfclerk.domain.PolicyVersion
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.utils.Control.sequence
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.nodes._
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.domain.queries.Query
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.log.InventoryLogDetails
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.services.marshalling.ConfigurationRuleUnserialisation
import com.normation.rudder.services.marshalling.NodeGroupUnserialisation
import com.normation.rudder.services.marshalling.PolicyInstanceUnserialisation
import com.normation.rudder.batch.SuccessStatus
import com.normation.rudder.batch.ErrorStatus
import com.normation.rudder.domain.servers.NodeConfiguration
import com.normation.rudder.services.marshalling.DeploymentStatusUnserialisation
import com.normation.rudder.batch.CurrentDeploymentStatus

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

  ///// configuration Rule /////
  
  def getConfigurationRuleAddDetails(xml:NodeSeq) : Box[AddConfigurationRuleDiff]
  
  def getConfigurationRuleDeleteDetails(xml:NodeSeq) : Box[DeleteConfigurationRuleDiff]
  
  def getConfigurationRuleModifyDetails(xml:NodeSeq) : Box[ModifyConfigurationRuleDiff]
  
  ///// policy Instance /////
  
  def getPolicyInstanceAddDetails(xml:NodeSeq) : Box[(AddPolicyInstanceDiff, SectionVal)]
  
  def getPolicyInstanceDeleteDetails(xml:NodeSeq) : Box[(DeletePolicyInstanceDiff, SectionVal)]
  
  def getPolicyInstanceModifyDetails(xml:NodeSeq) : Box[ModifyPolicyInstanceDiff]

  ///// node group /////
  
  def getNodeGroupAddDetails(xml:NodeSeq) : Box[AddNodeGroupDiff]
  
  def getNodeGroupDeleteDetails(xml:NodeSeq) : Box[DeleteNodeGroupDiff]
  
  def getNodeGroupModifyDetails(xml:NodeSeq) : Box[ModifyNodeGroupDiff]
  
  def getAcceptNodeLogDetails(xml:NodeSeq) : Box[InventoryLogDetails]
  
  def getRefuseNodeLogDetails(xml:NodeSeq) : Box[InventoryLogDetails]
  
  def getDeploymentStatusDetails(xml:NodeSeq) : Box[CurrentDeploymentStatus]
  
}


/**
 * Details should always be in the format: <entry>{more details here}</entry>
 */
class EventLogDetailsServiceImpl(
    cmdbQueryParser  : CmdbQueryParser
  , piUnserialiser   : PolicyInstanceUnserialisation
  , groupUnserialiser: NodeGroupUnserialisation
  , crUnserialiser   : ConfigurationRuleUnserialisation
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
   * Version 1:
     <configurationRule changeType="add" fileFormat="1.0">
        <id>{cr.id.value}</id>
        <name>{cr.name}</name>
        <serial>{cr.serial}</serial>
        <target>{ cr.target.map( _.target).getOrElse("") }</target>
        <policyInstanceIds>{
          cr.policyInstanceIds.map { id => <id>{id.value}</id> } 
        }</policyInstanceIds>
        <shortDescription>{cr.shortDescription}</shortDescription>
        <longDescription>{cr.longDescription}</longDescription>
        <isActivated>{cr.isActivatedStatus}</isActivated>
        <isSystem>{cr.isSystem}</isSystem>
      </configurationRule>
   */
  override def getConfigurationRuleAddDetails(xml:NodeSeq) : Box[AddConfigurationRuleDiff] = {
    getConfigurationRuleFromXML(xml, "add").map { cr =>
      AddConfigurationRuleDiff(cr)
    }
  }
  
  /**
   * Version 1:
     <configurationRule changeType="delete" fileFormat="1.0">
        <id>{cr.id.value}</id>
        <name>{cr.name}</name>
        <serial>{cr.serial}</serial>
        <target>{ cr.target.map( _.target).getOrElse("") }</target>
        <policyInstanceIds>{
          cr.policyInstanceIds.map { id => <id>{id.value}</id> } 
        }</policyInstanceIds>
        <shortDescription>{cr.shortDescription}</shortDescription>
        <longDescription>{cr.longDescription}</longDescription>
        <isActivated>{cr.isActivatedStatus}</isActivated>
        <isSystem>{cr.isSystem}</isSystem>
      </configurationRule>
   */
  override def getConfigurationRuleDeleteDetails(xml:NodeSeq) : Box[DeleteConfigurationRuleDiff] = {
    getConfigurationRuleFromXML(xml, "delete").map { cr =>
      DeleteConfigurationRuleDiff(cr)
    }
  }
  
  /**
   * <configurationRule changeType="modify">
     <id>012f3064-d392-43a3-bec9-b0f75950a7ea</id>
     <displayName>cr1</displayName>
     <name><from>cr1</from><to>cr1-x</to></name>
     <target><from>....</from><to>....</to></target>
     <policyInstanceIds><from><id>...</id><id>...</id></from><to><id>...</id></to></policyInstanceIds>
     <shortDescription><from>...</from><to>...</to></shortDescription>
     <longDescription><from>...</from><to>...</to></longDescription>
     </configurationRule>
   */
  override def getConfigurationRuleModifyDetails(xml:NodeSeq) : Box[ModifyConfigurationRuleDiff] = {
    for {
      entry             <- getEntryContent(xml)
      cr                <- (entry \ "configurationRule").headOption ?~! ("Entry type is not configurationRule : " + entry)
      changeTypeAddOk   <- {
                             if(cr.attribute("changeType").map( _.text ) == Some("modify")) Full("OK")
                             else Failure("ConfigurationRule attribute does not have changeType=modify: " + entry)
                           }
      fileFormatOk      <- {
                             if(cr.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                             else Failure("Bad fileFormat (expecting 1.0): " + entry)
                           }
      id                <- (cr \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type configurationRule : " + entry)
      displayName       <- (cr \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type configurationRule : " + entry)
      name              <- getFromToString((cr \ "name").headOption)
      serial            <- getFromTo[Int]((cr \ "serial").headOption, { x => tryo(x.text.toInt) } )
      target            <- getFromTo[Option[PolicyInstanceTarget]]((cr \ "target").headOption, {s =>  
                              //check for <from><none></none></from> or the same with <to>, <none/>, etc
                              if( (s \ "none").isEmpty) Full(PolicyInstanceTarget.unser(s.text))
                              else Full(None)
                            } )
      shortDescription  <- getFromToString((cr \ "shortDescription").headOption)
      longDescription   <- getFromToString((cr \ "longDescription").headOption)
      isActivated       <- getFromTo[Boolean]((cr \ "isActivated").headOption, { s => tryo { s.text.toBoolean } } ) 
      isSystem          <- getFromTo[Boolean]((cr \ "isSystem").headOption, { s => tryo { s.text.toBoolean } } )
      policyInstanceIds <- getFromTo[Set[PolicyInstanceId]]((cr \ "policyInstanceIds").headOption, { x:NodeSeq => 
                            Full((x \ "id").toSet.map( (y:NodeSeq) => PolicyInstanceId( y.text ) ))
                          } )
    } yield {
      ModifyConfigurationRuleDiff(
          id = ConfigurationRuleId(id)
        , name = displayName
        , modName = name
        , modSerial = serial
        , modTarget = target
        , modPolicyInstanceIds = policyInstanceIds
        , modShortDescription = shortDescription
        , modLongDescription = longDescription
        , modIsActivatedStatus = isActivated
        , modIsSystem = isSystem
      )
    }
  }
  
  /**
   * Map XML into a configuration rule
   */
  private[this] def getConfigurationRuleFromXML(xml:NodeSeq, changeType:String) : Box[ConfigurationRule] = {  
    for {
      entry           <- getEntryContent(xml)
      crXml           <- (entry \ "configurationRule").headOption ?~! ("Entry type is not a configurationRule: " + entry)
      changeTypeAddOk <- {
                           if(crXml.attribute("changeType").map( _.text ) == Some(changeType)) Full("OK")
                           else Failure("ConfigurationRule attribute does not have changeType=%s: ".format(changeType) + entry)
                         }
      cr              <- crUnserialiser.unserialise(crXml)
    } yield {
      cr
    }
  }
  
  
  ///// policy instances /////
  
  
  /**
   * Map XML into a policy instance
   */
  private[this] def getPolicyInstanceFromXML(xml:NodeSeq, changeType:String) : Box[(PolicyPackageName, PolicyInstance, SectionVal)] = {  
    for {
      entry                 <- getEntryContent(xml)
      piXml                 <- (entry \ "policyInstance").headOption ?~! ("Entry type is not a policyInstance: " + entry)
      changeTypeAddOk       <- {
                                  if(piXml.attribute("changeType").map( _.text ) == Some(changeType)) Full("OK")
                                  else Failure("PolicyInstance attribute does not have changeType=%s: ".format(changeType) + entry)
                                }
      ptPiSectionVals       <- piUnserialiser.unserialise(piXml)
    } yield {
      ptPiSectionVals
    }
  }
  

  def getPolicyInstanceAddDetails(xml:NodeSeq) : Box[(AddPolicyInstanceDiff, SectionVal)] = {
    getPolicyInstanceFromXML(xml, "add").map { case (ptName, pi,sectionVal) =>
      (AddPolicyInstanceDiff(ptName, pi),sectionVal)
    }
  }
  
  def getPolicyInstanceDeleteDetails(xml:NodeSeq) : Box[(DeletePolicyInstanceDiff, SectionVal)] = {
    getPolicyInstanceFromXML(xml, "delete").map { case(ptName, pi,sectionVal) =>
      (DeletePolicyInstanceDiff(ptName, pi),sectionVal)
    }
  }
  
  def getPolicyInstanceModifyDetails(xml:NodeSeq) : Box[ModifyPolicyInstanceDiff] = {
    for {
      entry <- getEntryContent(xml)
      pi                    <- (entry \ "policyInstance").headOption ?~! ("Entry type is not policyInstance : " + entry)
      changeTypeAddOk       <- {
                                 if(pi.attribute("changeType").map( _.text ) == Some("modify")) Full("OK")
                                 else Failure("PolicyInstance attribute does not have changeType=modify: " + entry)
                               }
      fileFormatOk          <- {
                                 if(pi.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                                 else Failure("Bad fileFormat (expecting 1.0): " + entry)
                               }
      id                    <- (pi \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type policyInstance : " + entry)
      ptName                <- (pi \ "policyTemplateName").headOption.map( _.text ) ?~! ("Missing attribute 'policyTemplateName' in entry type policyInstance : " + entry)
      displayName           <- (pi \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type policyInstance : " + entry)
      name                  <- getFromToString((pi \ "name").headOption)
      policyTemplateVersion <- getFromTo[PolicyVersion]((pi \ "policyTemplateVersion").headOption, {v =>
                                  tryo(PolicyVersion(v.text))
                                } )
      parameters            <- getFromTo[SectionVal]((pi \ "parameters").headOption, {parameter =>
                                piUnserialiser.parseSectionVal(parameter)
                              })
      shortDescription      <- getFromToString((pi \ "shortDescription").headOption)
      longDescription       <- getFromToString((pi \ "longDescription").headOption)
      priority              <- getFromTo[Int]((pi \ "priority").headOption, { x => tryo(x.text.toInt) } )
      isActivated           <- getFromTo[Boolean]((pi \ "isActivated").headOption, { s => tryo { s.text.toBoolean } } ) 
      isSystem              <- getFromTo[Boolean]((pi \ "isSystem").headOption, { s => tryo { s.text.toBoolean } } )
    } yield {
      ModifyPolicyInstanceDiff(
          policyTemplateName = PolicyPackageName(ptName)
        , id = PolicyInstanceId(id)
        , name = displayName
        , modName = name
        , modPolicyTemplateVersion = policyTemplateVersion
        , modParameters = parameters
        , modShortDescription = shortDescription
        , modLongDescription = longDescription
        , modPriority = priority
        , modIsActivated = isActivated
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
      fileFormatOk    <- {
                           if(group.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                           else Failure("Bad fileFormat (expecting 1.0): " + entry)
                         }
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
      isActivated     <- getFromTo[Boolean]((group \ "isActivated").headOption, { s => tryo { s.text.toBoolean } } ) 
      isSystem        <- getFromTo[Boolean]((group \ "isSystem").headOption, { s => tryo { s.text.toBoolean } } )
    } yield {
      ModifyNodeGroupDiff(
          id = NodeGroupId(id)
        , name = displayName
        , modName = name
        , modDescription = description
        , modQuery = query
        , modIsDynamic = isDynamic
        , modServerList = serverList
        , modIsActivated = isActivated
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
      fileFormatOk <- {
                        if(details.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                        else Failure("Bad fileFormat (expecting 1.0): " + entry)
                      }
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
  
  
  def getDeploymentStatusDetails(xml:NodeSeq) : Box[CurrentDeploymentStatus] = {
    for {
      entry        <- getEntryContent(xml)
      details      <- (entry \ "deploymentStatus").headOption ?~! ("Entry type is not a deploymentStatus: " + entry)
      deploymentStatus <- deploymentStatusUnserialisation.unserialise(details)
	} yield {
	      deploymentStatus
	}
  }
  
}