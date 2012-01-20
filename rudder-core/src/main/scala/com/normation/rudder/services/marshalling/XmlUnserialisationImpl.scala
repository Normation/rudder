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

package com.normation.rudder.services.marshalling

import scala.xml.NodeSeq
import net.liftweb.common._
import net.liftweb.common.Box._
import com.normation.rudder.services.queries.CmdbQueryParser
import net.liftweb.common.Failure
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.rudder.domain.policies.SectionVal
import scala.xml.{Node => XNode}
import com.normation.utils.Control.sequence
import com.normation.cfclerk.domain.PolicyVersion
import net.liftweb.util.Helpers.tryo
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.PolicyInstanceTarget
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.rudder.domain.policies.UserPolicyTemplateCategory
import com.normation.rudder.domain.policies.UserPolicyTemplateCategoryId
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.rudder.domain.policies.UserPolicyTemplateId
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.batch.SuccessStatus
import com.normation.rudder.batch.ErrorStatus
import com.normation.rudder.batch.NoStatus
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId

class PolicyInstanceUnserialisationImpl extends PolicyInstanceUnserialisation {
  
  override def parseSectionVal(xml:NodeSeq) : Box[SectionVal] = {
    def recValParseSection(elt:XNode) : Box[(String, SectionVal)] = {
      if(elt.label != "section") Failure("Bad XML, awaiting a <section> and get: " + elt)
      else {
        for {
          name <- (elt \ "@name").headOption ?~! ("Missing required attribute 'name' for <section>: " + elt)
          // Seq( (var name , var value ) )
          vars <- sequence( elt \ "var" ) { xmlVar =>
            for { 
              n <- (xmlVar \ "@name").headOption ?~! ("Missing required attribute 'name' for <var>: " + xmlVar)
            } yield {
              (n.text , xmlVar.text)
            }
          }
          // Seq ( SectionVal )
          sections <- sequence( elt \ "section" ) { sectionXml =>
            recValParseSection(sectionXml)
          }
        } yield {
          val s = sections.groupBy { case(n,s) => n }.map { case(n,seq) => (n,seq.map { case (_,section) => section } ) }
          (name.text, SectionVal(s, vars.toMap))
        }
      }
    }
    
    for {
      root <- (xml \ "section").toList match {
        case Nil => Failure("Missing required tag <section> in: " + xml)
        case node :: Nil => Full(node)
        case x => Failure("Found several <section> tag in XML, but only one root section is allowed: " + xml)
      }
      (_ , sectionVal) <- recValParseSection(root)
    } yield {
      sectionVal
    }
  }
  
  override def unserialise(xml:XNode) : Box[(PolicyPackageName, PolicyInstance, SectionVal)] = {
    for {
      pi                    <- {
                                 if(xml.label == "policyInstance") Full(xml)
                                 else Failure("Entry type is not a policyInstance: " + xml)
                               }
      fileFormatOk          <- {
                                 if(pi.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                                 else Failure("Bad fileFormat (expecting 1.0): " + xml)
                               }
      id                    <- (pi \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type policyInstance : " + xml)
      ptName                <- (pi \ "policyTemplateName").headOption.map( _.text ) ?~! ("Missing attribute 'policyTemplateName' in entry type policyInstance : " + xml)
      name                  <- (pi \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type policyInstance : " + xml)
      policyTemplateVersion <- (pi \ "policyTemplateVersion").headOption.map( x => PolicyVersion(x.text) ) ?~! ("Missing attribute 'policyTemplateVersion' in entry type policyInstance : " + xml)
      sectionVal            <- parseSectionVal(pi)
      shortDescription      <- (pi \ "shortDescription").headOption.map( _.text ) ?~! ("Missing attribute 'shortDescription' in entry type policyInstance : " + xml)
      longDescription       <- (pi \ "longDescription").headOption.map( _.text ) ?~! ("Missing attribute 'longDescription' in entry type policyInstance : " + xml)
      isActivated           <- (pi \ "isActivated").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isActivated' in entry type policyInstance : " + xml)
      priority              <- (pi \ "priority").headOption.flatMap(s => tryo { s.text.toInt } ) ?~! ("Missing or bad attribute 'priority' in entry type policyInstance : " + xml)
      isSystem              <- (pi \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type policyInstance : " + xml)
      policyInstanceIds     =  (pi \ "policyInstanceIds" \ "id" ).map( n => PolicyInstanceId( n.text ) ).toSet
    } yield {
      (
          PolicyPackageName(ptName)
        , PolicyInstance(
              id = PolicyInstanceId(id)
            , name = name
            , policyTemplateVersion = policyTemplateVersion
            , parameters = SectionVal.toMapVariables(sectionVal)
            , shortDescription = shortDescription
            , longDescription = longDescription
            , priority = priority
            , isActivated = isActivated
            , isSystem = isSystem
          )
        , sectionVal
      )
    }   
  }
}


class NodeGroupCategoryUnserialisationImpl extends NodeGroupCategoryUnserialisation {
  
  def unserialise(entry:XNode): Box[NodeGroupCategory] = {
    for {
      category        <- {
                            if(entry.label ==  "groupLibraryCategory") Full(entry)
                            else Failure("Entry type is not a groupLibraryCategory: " + entry)
                          }
      fileFormatOk     <- {
                            if(category.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                            else Failure("Bad fileFormat (expecting 1.0): " + entry)
                          }
      id               <- (category \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type groupLibraryCategory : " + entry)
      name             <- (category \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type groupLibraryCategory : " + entry)
      description      <- (category \ "description").headOption.map( _.text ) ?~! ("Missing attribute 'description' in entry type groupLibraryCategory : " + entry)
      isSystem         <- (category \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type groupLibraryCategory : " + entry)
    } yield {
      NodeGroupCategory(
          id = NodeGroupCategoryId(id)
        , name = name
        , description = description
        , items = Nil
        , children = Nil
        , isSystem = isSystem
      )
    }    
  }
}


class NodeGroupUnserialisationImpl(    
    cmdbQueryParser: CmdbQueryParser
) extends NodeGroupUnserialisation {
  def unserialise(entry:XNode) : Box[NodeGroup] = {
    for {
      group           <- {
                           if(entry.label == "nodeGroup") Full(entry)
                           else Failure("Entry type is not a nodeGroup: " + entry)
                         }
      fileFormatOk    <- {
                           if(group.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                           else Failure("Bad fileFormat (expecting 1.0): " + entry)
                         }
      id              <- (group \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type nodeGroup : " + entry)
      name            <- (group \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type nodeGroup : " + entry)
      description     <- (group \ "description").headOption.map( _.text ) ?~! ("Missing attribute 'description' in entry type nodeGroup : " + entry)
      query           <- (group \ "query").headOption match {
                            case None => Full(None)
                            case Some(s) => 
                              if(s.text.size == 0) Full(None)
                              else cmdbQueryParser(s.text).map( Some(_) )
                          }
      isDynamic       <- (group \ "isDynamic").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isDynamic' in entry type nodeGroup : " + entry)
      serverList      =  (group \ "nodeIds" \ "id" ).map( n => NodeId( n.text ) ).toSet
      isActivated     <- (group \ "isActivated").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isActivated' in entry type nodeGroup : " + entry)
      isSystem        <- (group \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type nodeGroup : " + entry)
    } yield {
      NodeGroup(
          id = NodeGroupId(id)
        , name = name
        , description = description
        , query = query
        , isDynamic = isDynamic
        , serverList = serverList
        , isActivated = isActivated
        , isSystem = isSystem
      )
    }    
  }
}


class ConfigurationRuleUnserialisationImpl extends ConfigurationRuleUnserialisation {
  def unserialise(entry:XNode) : Box[ConfigurationRule] = {
    for {
      cr               <- {
                            if(entry.label ==  "configurationRule") Full(entry)
                            else Failure("Entry type is not a configurationRule: " + entry)
                          }
      fileFormatOk     <- {
                            if(cr.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                            else Failure("Bad fileFormat (expecting 1.0): " + entry)
                          }
      id               <- (cr \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type configurationRule : " + entry)
      name             <- (cr \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type configurationRule : " + entry)
      serial           <- (cr \ "serial").headOption.flatMap(s => tryo { s.text.toInt } ) ?~! ("Missing or bad attribute 'serial' in entry type configurationRule : " + entry)
      target           <- (cr \ "target").headOption.map(s =>  PolicyInstanceTarget.unser(s.text) )  ?~! ("Missing attribute 'target' in entry type configurationRule : " + entry)
      shortDescription <- (cr \ "shortDescription").headOption.map( _.text ) ?~! ("Missing attribute 'shortDescription' in entry type configurationRule : " + entry)
      longDescription  <- (cr \ "longDescription").headOption.map( _.text ) ?~! ("Missing attribute 'longDescription' in entry type configurationRule : " + entry)
      isActivated      <- (cr \ "isActivated").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isActivated' in entry type configurationRule : " + entry)
      isSystem         <- (cr \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type configurationRule : " + entry)
      policyInstanceIds = (cr \ "policyInstanceIds" \ "id" ).map( n => PolicyInstanceId( n.text ) ).toSet
    } yield {
      ConfigurationRule(
          id = ConfigurationRuleId(id)
        , name = name
        , serial = serial
        , target = target
        , policyInstanceIds = policyInstanceIds
        , shortDescription = shortDescription
        , longDescription = longDescription
        , isActivatedStatus = isActivated
        , isSystem = isSystem
      )
    }    
  }
}

class UserPolicyTemplateCategoryUnserialisationImpl extends UserPolicyTemplateCategoryUnserialisation {
  
  def unserialise(entry:XNode): Box[UserPolicyTemplateCategory] = {
    for {
      uptc             <- {
                            if(entry.label ==  "policyLibraryCategory") Full(entry)
                            else Failure("Entry type is not a policyLibraryCategory: " + entry)
                          }
      fileFormatOk     <- {
                            if(uptc.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                            else Failure("Bad fileFormat (expecting 1.0): " + entry)
                          }
      id               <- (uptc \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type policyLibraryCategory : " + entry)
      name             <- (uptc \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type policyLibraryCategory : " + entry)
      description      <- (uptc \ "description").headOption.map( _.text ) ?~! ("Missing attribute 'description' in entry type policyLibraryCategory : " + entry)
      isSystem         <- (uptc \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type policyLibraryCategory : " + entry)
    } yield {
      UserPolicyTemplateCategory(
          id = UserPolicyTemplateCategoryId(id)
        , name = name
        , description = description
        , items = Nil
        , children = Nil
        , isSystem = isSystem
      )
    }    
  }
}

class UserPolicyTemplateUnserialisationImpl extends UserPolicyTemplateUnserialisation {
  
  //we expect acceptation date to be in ISO-8601 format
  private[this] val dateFormatter = ISODateTimeFormat.dateTime
  
  def unserialise(entry:XNode): Box[UserPolicyTemplate] = {
    for {
      upt              <- {
                            if(entry.label ==  "policyLibraryTemplate") Full(entry)
                            else Failure("Entry type is not a policyLibraryCategory: " + entry)
                          }
      fileFormatOk     <- {
                            if(upt.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                            else Failure("Bad fileFormat (expecting 1.0): " + entry)
                          }
      id               <- (upt \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type policyLibraryTemplate : " + entry)
      ptName           <- (upt \ "policyTemplateName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type policyLibraryTemplate : " + entry)
      isSystem         <- (upt \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type policyLibraryTemplate : " + entry)
      isActivated      <- (upt \ "isActivated").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isActivated' in entry type policyLibraryTemplate : " + entry)
      acceptationDates <- sequence(upt \ "versions" \ "version" ) { version => 
                            for {
                              ptVersionName   <- version.attribute("name").map( _.text) ?~! "Missing attribute 'name' for acceptation date in PT '%s' (%s): '%s'".format(ptName, id, version)
                              ptVersion       <- tryo { PolicyVersion(ptVersionName) }
                              acceptationDate <- tryo { dateFormatter.parseDateTime(version.text) }
                            } yield {
                              (ptVersion, acceptationDate)
                            }
                          }
      acceptationMap   <- {
                            val map = acceptationDates.toMap
                            if(map.size != acceptationDates.size) Failure("There exists a duplicate polity template version in the acceptation date map: " + acceptationDates.mkString("; "))
                            else Full(map)
                          }
    } yield {
      UserPolicyTemplate(
          id = UserPolicyTemplateId(id)
        , referencePolicyTemplateName = PolicyPackageName(ptName)
        , acceptationDatetimes = acceptationMap
        , policyInstances = Nil
        , isActivated = isActivated
        , isSystem = isSystem
      )
    }    
  }
}

class DeploymentStatusUnserialisationImpl extends DeploymentStatusUnserialisation {
  def unserialise(entry:XNode) : Box[CurrentDeploymentStatus] = {
    for {
      depStatus      <- {
                  if(entry.label ==  "deploymentStatus") Full(entry)
                            else Failure("Entry type is not a deploymentStatus: " + entry)
                          }
      fileFormatOk     <- {
                            if(depStatus.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                            else Failure("Bad fileFormat (expecting 1.0): " + entry)
                          }        
      id               <- (depStatus \ "id").headOption.flatMap(s => tryo {s.text.toInt } ) ?~! ("Missing attribute 'id' in entry type deploymentStatus : " + entry)
      status           <- (depStatus \ "status").headOption.map( _.text ) ?~! ("Missing attribute 'status' in entry type deploymentStatus : " + entry)

      started          <- (depStatus \ "started").headOption.flatMap(s => tryo { ISODateTimeFormat.dateTimeParser.parseDateTime(s.text) } ) ?~! ("Missing or bad attribute 'started' in entry type deploymentStatus : " + entry)
      ended            <- (depStatus \ "ended").headOption.flatMap(s => tryo { ISODateTimeFormat.dateTimeParser.parseDateTime(s.text) } ) ?~! ("Missing or bad attribute 'ended' in entry type deploymentStatus : " + entry)
      errorMessage     <- (depStatus \ "errorMessage").headOption match {
                  case None => Full(None)
                            case Some(s) => 
                              if(s.text.size == 0) Full(None)
                              else Full(Some(s.text))
                  }
    } yield {
      status match {
        case "success" => SuccessStatus(id, started, ended, Map())
        case "failure" => ErrorStatus(id, started, ended, errorMessage.map(x => Failure(x)).getOrElse(Failure("")) )
        case s       => NoStatus
      }
    }
  }
  
}
