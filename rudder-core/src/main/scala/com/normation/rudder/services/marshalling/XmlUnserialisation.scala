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

import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.rudder.domain.policies.SectionVal
import net.liftweb.common._
import scala.xml.NodeSeq
import scala.xml.{Node => XNode, _}
import net.liftweb.common.Box._
import com.normation.cfclerk.domain.PolicyVersion
import net.liftweb.util.Helpers.tryo
import com.normation.utils.Control.sequence
import com.normation.rudder.domain.policies.PolicyInstanceId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.domain.policies.PolicyInstanceTarget
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.domain.nodes.NodeGroupId

/**
 * That trait allow to unserialise 
 * policy instance from an XML file. 
 */
trait PolicyInstanceUnserialisation {
  /**
   * Version 1.0
     <policyInstance fileFormat="1.0">
       <id>{pi.id.value}</id>
       <displayName>{pi.name}</displayName>
       <policyTemplateName>{PT name}</policyTemplateName>
       <policyTemplateVersion>{pi.policyTemplateVersion}</policyTemplateVersion>
       <shortDescription>{pi.shortDescription}</shortDescription>
       <longDescription>{pi.longDescription}</longDescription>
       <priority>{pi.priority}</priority>
       <isActivated>{piisActivated.}</isActivated>
       <isSystem>{pi.isSystem}</isSystem>
       <section name="sections">
         <section name="ptTest1-section1">
           <var name="ptTest1-section1-var1">value0</var>
           <section name="ptTest1-section1.1">
             <var name="ptTest1-section1.1-var1">value1</var>
           </section>
           <section name="ptTest1-section1.1">
             <var name="ptTest1-section1.1-var1">value2</var>
           </section>
         </section>
       </section>
     </policyInstance>
   */
  def unserialise(xml:XNode) : Box[(PolicyPackageName, PolicyInstance, SectionVal)]
  
  
  /**
   * A section val look like:
   * <section name="root">
       <var name="vA">valueVA<var>
       <var name="vB">valueVB</var>
       <section name="multi">
         <var name="vC">vc1</var>
         <section name="s2>
           <var name="vD">vd1</var>
         </section>
       </section>
       <section name="multi">
         <var name="vC">vc2</var>
         <section name="s2>
           <var name="vD">vd2</var>
         </section>
       </section>
   * </section>
   * 
   * We await for a node that contains an unique <section>
   */
  def parseSectionVal(xml:NodeSeq) : Box[SectionVal]
}


/**
 * That trait allow to unserialise 
 * Node Group from an XML file. 
 */
trait NodeGroupUnserialisation {
  /**
   * Version 1.0
     <nodeGroup fileFormat="1.0">
       <id>{group.id.value}</id>
       <displayName>{group.id.name}</displayName>
       <description>{group.id.description}</description>
       <isDynamic>{group.isDynamic}</isDynamic>
       <isActivated>{group.isActivated}</isActivated>
       <isSystem>{group.isSystem}</isSystem>
       <query>{group.query}</query>
       <nodeIds>
         <id>{nodeId_1}</id>
         <id>{nodeId_2}</id>
       </nodeIds>
     </nodeGroup>
   */
  def unserialise(xml:XNode) : Box[NodeGroup]
}

/**
 * That trait allow to unserialise 
 * Configuration Rule from an XML file. 
 */
trait ConfigurationRuleUnserialisation {
  /**
   * Version 1:
     <configurationRule fileName="1.0">
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
  def unserialise(xml:XNode) : Box[ConfigurationRule]
}


////////////////////// implementations //////////////////////

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

