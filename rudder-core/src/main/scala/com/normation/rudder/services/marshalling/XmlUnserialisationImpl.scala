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
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.Directive
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.domain.policies.SectionVal
import scala.xml.{Node => XNode}
import com.normation.utils.Control.sequence
import com.normation.cfclerk.domain.TechniqueVersion
import net.liftweb.util.Helpers.tryo
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueId
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.batch.SuccessStatus
import com.normation.rudder.batch.ErrorStatus
import com.normation.rudder.batch.NoStatus
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId

class DirectiveUnserialisationImpl extends DirectiveUnserialisation {
  
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
  
  override def unserialise(xml:XNode) : Box[(TechniqueName, Directive, SectionVal)] = {
    for {
      directive                    <- {
                                 if(xml.label == "directive") Full(xml)
                                 else Failure("Entry type is not a directive: " + xml)
                               }
      fileFormatOk          <- {
                                 if(directive.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                                 else Failure("Bad fileFormat (expecting 1.0): " + xml)
                               }
      id                    <- (directive \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type directive : " + xml)
      ptName                <- (directive \ "techniqueName").headOption.map( _.text ) ?~! ("Missing attribute 'techniqueName' in entry type directive : " + xml)
      name                  <- (directive \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type directive : " + xml)
      techniqueVersion <- (directive \ "techniqueVersion").headOption.map( x => TechniqueVersion(x.text) ) ?~! ("Missing attribute 'techniqueVersion' in entry type directive : " + xml)
      sectionVal            <- parseSectionVal(directive)
      shortDescription      <- (directive \ "shortDescription").headOption.map( _.text ) ?~! ("Missing attribute 'shortDescription' in entry type directive : " + xml)
      longDescription       <- (directive \ "longDescription").headOption.map( _.text ) ?~! ("Missing attribute 'longDescription' in entry type directive : " + xml)
      isEnabled           <- (directive \ "isEnabled").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isEnabled' in entry type directive : " + xml)
      priority              <- (directive \ "priority").headOption.flatMap(s => tryo { s.text.toInt } ) ?~! ("Missing or bad attribute 'priority' in entry type directive : " + xml)
      isSystem              <- (directive \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type directive : " + xml)
      directiveIds     =  (directive \ "directiveIds" \ "id" ).map( n => DirectiveId( n.text ) ).toSet
    } yield {
      (
          TechniqueName(ptName)
        , Directive(
              id = DirectiveId(id)
            , name = name
            , techniqueVersion = techniqueVersion
            , parameters = SectionVal.toMapVariables(sectionVal)
            , shortDescription = shortDescription
            , longDescription = longDescription
            , priority = priority
            , isEnabled = isEnabled
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
      isEnabled     <- (group \ "isEnabled").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isEnabled' in entry type nodeGroup : " + entry)
      isSystem        <- (group \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type nodeGroup : " + entry)
    } yield {
      NodeGroup(
          id = NodeGroupId(id)
        , name = name
        , description = description
        , query = query
        , isDynamic = isDynamic
        , serverList = serverList
        , isEnabled = isEnabled
        , isSystem = isSystem
      )
    }    
  }
}


class RuleUnserialisationImpl extends RuleUnserialisation {
  def unserialise(entry:XNode) : Box[Rule] = {
    for {
      rule               <- {
                            if(entry.label ==  "rule") Full(entry)
                            else Failure("Entry type is not a rule: " + entry)
                          }
      fileFormatOk     <- {
                            if(rule.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                            else Failure("Bad fileFormat (expecting 1.0): " + entry)
                          }
      id               <- (rule \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type rule : " + entry)
      name             <- (rule \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type rule : " + entry)
      serial           <- (rule \ "serial").headOption.flatMap(s => tryo { s.text.toInt } ) ?~! ("Missing or bad attribute 'serial' in entry type rule : " + entry)
      target           <- (rule \ "target").headOption.map(s =>  RuleTarget.unser(s.text) )  ?~! ("Missing attribute 'target' in entry type rule : " + entry)
      shortDescription <- (rule \ "shortDescription").headOption.map( _.text ) ?~! ("Missing attribute 'shortDescription' in entry type rule : " + entry)
      longDescription  <- (rule \ "longDescription").headOption.map( _.text ) ?~! ("Missing attribute 'longDescription' in entry type rule : " + entry)
      isEnabled      <- (rule \ "isEnabled").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isEnabled' in entry type rule : " + entry)
      isSystem         <- (rule \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type rule : " + entry)
      directiveIds = (rule \ "directiveIds" \ "id" ).map( n => DirectiveId( n.text ) ).toSet
    } yield {
      Rule(
          id = RuleId(id)
        , name = name
        , serial = serial
        , target = target
        , directiveIds = directiveIds
        , shortDescription = shortDescription
        , longDescription = longDescription
        , isEnabledStatus = isEnabled
        , isSystem = isSystem
      )
    }    
  }
}

class ActiveTechniqueCategoryUnserialisationImpl extends ActiveTechniqueCategoryUnserialisation {
  
  def unserialise(entry:XNode): Box[ActiveTechniqueCategory] = {
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
      ActiveTechniqueCategory(
          id = ActiveTechniqueCategoryId(id)
        , name = name
        , description = description
        , items = Nil
        , children = Nil
        , isSystem = isSystem
      )
    }    
  }
}

class ActiveTechniqueUnserialisationImpl extends ActiveTechniqueUnserialisation {
  
  //we expect acceptation date to be in ISO-8601 format
  private[this] val dateFormatter = ISODateTimeFormat.dateTime
  
  def unserialise(entry:XNode): Box[ActiveTechnique] = {
    for {
      activeTechnique              <- {
                            if(entry.label ==  "policyLibraryTemplate") Full(entry)
                            else Failure("Entry type is not a policyLibraryCategory: " + entry)
                          }
      fileFormatOk     <- {
                            if(activeTechnique.attribute("fileFormat").map( _.text ) == Some("1.0")) Full("OK")
                            else Failure("Bad fileFormat (expecting 1.0): " + entry)
                          }
      id               <- (activeTechnique \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type policyLibraryTemplate : " + entry)
      ptName           <- (activeTechnique \ "techniqueName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type policyLibraryTemplate : " + entry)
      isSystem         <- (activeTechnique \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type policyLibraryTemplate : " + entry)
      isEnabled      <- (activeTechnique \ "isEnabled").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isEnabled' in entry type policyLibraryTemplate : " + entry)
      acceptationDates <- sequence(activeTechnique \ "versions" \ "version" ) { version => 
                            for {
                              ptVersionName   <- version.attribute("name").map( _.text) ?~! "Missing attribute 'name' for acceptation date in PT '%s' (%s): '%s'".format(ptName, id, version)
                              ptVersion       <- tryo { TechniqueVersion(ptVersionName) }
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
      ActiveTechnique(
          id = ActiveTechniqueId(id)
        , techniqueName = TechniqueName(ptName)
        , acceptationDatetimes = acceptationMap
        , directives = Nil
        , isEnabled = isEnabled
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
