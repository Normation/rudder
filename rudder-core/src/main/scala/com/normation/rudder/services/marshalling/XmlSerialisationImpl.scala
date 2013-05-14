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

import com.normation.rudder.domain.policies.Directive
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.domain.policies.SectionVal
import net.liftweb.common._
import scala.xml.NodeSeq
import scala.xml.{Node => XNode, _}
import net.liftweb.common.Box._
import com.normation.cfclerk.domain.TechniqueVersion
import net.liftweb.util.Helpers.tryo
import com.normation.utils.Control.sequence
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.RuleId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.exceptions.TechnicalException
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechnique
import org.joda.time.format.ISODateTimeFormat
import com.normation.cfclerk.domain.SectionSpec
import com.normation.rudder.batch.{CurrentDeploymentStatus,SuccessStatus,ErrorStatus}
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.services.marshalling.MarshallingUtil.createTrimedElem
import com.normation.utils.XmlUtils
import com.normation.rudder.domain.Constants._


class RuleSerialisationImpl(xmlVersion:String) extends RuleSerialisation {
  def serialise(rule:Rule):  Elem = {
    createTrimedElem(XML_TAG_RULE, xmlVersion) {
        <id>{rule.id.value}</id>
        <displayName>{rule.name}</displayName>
        <targets>{
          rule.targets.map { target => <target>{target.target}</target> } 
        }</targets>
        <directiveIds>{
          rule.directiveIds.map { id => <id>{id.value}</id> } 
        }</directiveIds>
        <shortDescription>{rule.shortDescription}</shortDescription>
        <longDescription>{rule.longDescription}</longDescription>
        <isEnabled>{rule.isEnabledStatus}</isEnabled>
        <isSystem>{rule.isSystem}</isSystem>
    }
  }
}

/**
 * That trait allow to serialise 
 * active techniques categories to an XML file. 
 */
class ActiveTechniqueCategorySerialisationImpl(xmlVersion:String) extends ActiveTechniqueCategorySerialisation {

  def serialise(uptc:ActiveTechniqueCategory):  Elem = {
    createTrimedElem(XML_TAG_ACTIVE_TECHNIQUE_CATEGORY, xmlVersion) (
        <id>{uptc.id.value}</id>
        <displayName>{uptc.name}</displayName>
        <description>{uptc.description}</description>
        <isSystem>{uptc.isSystem}</isSystem>
    )
  }
}

/**
 * That trait allows to serialise 
 * active techniques to an XML file. 
 */
class ActiveTechniqueSerialisationImpl(xmlVersion:String) extends ActiveTechniqueSerialisation {

  def serialise(activeTechnique:ActiveTechnique):  Elem = {
    createTrimedElem(XML_TAG_ACTIVE_TECHNIQUE, xmlVersion) (
        <id>{activeTechnique.id.value}</id>
        <techniqueName>{activeTechnique.techniqueName}</techniqueName>
        <isEnabled>{activeTechnique.isEnabled}</isEnabled>
        <isSystem>{activeTechnique.isSystem}</isSystem>
        <versions>{ activeTechnique.acceptationDatetimes.map { case(version,date) =>
          <version name={version.toString}>{date.toString(ISODateTimeFormat.dateTime)}</version>
        } }</versions>
    )
  }
}

/**
 * That trait allows to serialise 
 * directives an XML file. 
 */
class DirectiveSerialisationImpl(xmlVersion:String) extends DirectiveSerialisation {
  
  def serialise(
      ptName             : TechniqueName
    , variableRootSection: SectionSpec
    , directive          : Directive
  ) = {
    createTrimedElem(XML_TAG_DIRECTIVE, xmlVersion) (
          <id>{directive.id.value}</id>
      ::  <displayName>{directive.name}</displayName>
      ::  <techniqueName>{ptName.value}</techniqueName>
      ::  <techniqueVersion>{directive.techniqueVersion}</techniqueVersion>
      ::  {SectionVal.toXml(SectionVal.directiveValToSectionVal(variableRootSection, directive.parameters))}
      ::  <shortDescription>{directive.shortDescription}</shortDescription>
      ::  <longDescription>{directive.longDescription}</longDescription>
      ::  <priority>{directive.priority}</priority>
      ::  <isEnabled>{directive.isEnabled}</isEnabled>
      ::  <isSystem>{directive.isSystem}</isSystem>
      ::  Nil
    )
  }
}

/**
 * That trait allows to serialise 
 * Node group categories to an XML file. 
 */
class NodeGroupCategorySerialisationImpl(xmlVersion:String) extends NodeGroupCategorySerialisation {

  def serialise(ngc:NodeGroupCategory):  Elem = {
    createTrimedElem(XML_TAG_NODE_GROUP_CATEGORY, xmlVersion) (
        <id>{ngc.id.value}</id>
        <displayName>{ngc.name}</displayName>
        <description>{ngc.description}</description>
        <isSystem>{ngc.isSystem}</isSystem>
    )
  }
}

class NodeGroupSerialisationImpl(xmlVersion:String) extends NodeGroupSerialisation {
  def serialise(group:NodeGroup):  Elem = {
    createTrimedElem(XML_TAG_NODE_GROUP, xmlVersion) (
        <id>{group.id.value}</id>
        <displayName>{group.name}</displayName>
        <description>{group.description}</description>
        <query>{ group.query.map( _.toJSONString ).getOrElse("") }</query>
        <isDynamic>{group.isDynamic}</isDynamic>
        <nodeIds>{
          if (group.isDynamic) {
            NodeSeq.Empty
          } else { 
          group.serverList.map { id => <id>{id.value}</id> } 
          }
        }</nodeIds>
        <isEnabled>{group.isEnabled}</isEnabled>
        <isSystem>{group.isSystem}</isSystem>
    )
  }
}

/**
 * That trait allows to serialise deployment status to an XML data
 */
class DeploymentStatusSerialisationImpl(xmlVersion:String) extends DeploymentStatusSerialisation {
  def serialise(
      deploymentStatus : CurrentDeploymentStatus) : Elem = {
      createTrimedElem(XML_TAG_DEPLOYMENT_STATUS, xmlVersion) ( deploymentStatus match {
      case d : SuccessStatus => (
          <id>{d.id}</id>
          <started>{d.started}</started>
          <ended>{d.ended}</ended>
          <status>success</status>
          )
      case d : ErrorStatus => (
          <id>{d.id}</id>
          <started>{d.started}</started>
          <ended>{d.ended}</ended>
          <status>failure</status>
          <errorMessage>{d.failure.messageChain}</errorMessage>
          )
      case _ => throw new TechnicalException("Bad CurrentDeploymentStatus type, expected a success or an error")
    }
  ) }
}