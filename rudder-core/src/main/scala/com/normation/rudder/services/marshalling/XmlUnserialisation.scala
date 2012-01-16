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

import scala.xml.{Node => XNode}
import scala.xml.NodeSeq

import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.domain.policies.PolicyInstanceTarget
import com.normation.rudder.domain.policies.ConfigurationRuleId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.rudder.domain.nodes.NodeGroupId
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.rudder.domain.policies.UserPolicyTemplateCategory
import com.normation.rudder.batch.CurrentDeploymentStatus

import net.liftweb.common.Box


trait DeploymentStatusUnserialisation {
  /**
   * version 1:
   * <deploymentStatus fileFormat="1.0">
          <id>{d.id}</id>
          <started>{d.started}</started>
          <ended>{d.ended}</ended>
          <status>success</status>
     </deploymentStatus>
     
     <deploymentStatus fileFormat="1.0">
          <id>{d.id}</id>
          <started>{d.started}</started>
          <ended>{d.ended}</ended>
          <status>failure</status>
          <errorMessage>{d.failure}</errorMessage>
     </deploymentStatus>
   */
  def unserialise(xml:XNode) : Box[CurrentDeploymentStatus]
  
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

/**
 * That trait allow to unserialise 
 * user policy template category from an XML file. 
 * 
 * BE CAREFUL: items and children categories will 
 * always be empty here, as they are not serialized. 
 * 
 */
trait UserPolicyTemplateCategoryUnserialisation {
  /**
   * Version 1:
     <policyLibraryCategory fileFormat="1.0">
        <id>{uptc.id.value}</id>
        <displayName>{uptc.name}</displayName>
        <description>{uptc.description}</serial>
        <isSystem>{uptc.isSystem}</isSystem>
      </policyLibraryCategory>
   */
  def unserialise(xml:XNode): Box[UserPolicyTemplateCategory]
}

/**
 * That trait allow to unserialise 
 * user policy template from an XML file. 
 * 
 * BE CAREFUL: policyInstance will be empty as they are not serialized. 
 */
trait UserPolicyTemplateUnserialisation {
  /**
   * Version 1:
     <policyLibraryTemplate fileFormat="1.0">
        <id>{upt.id.value}</id>
        <policyTemplateName>{upt.referencePolicyTemplateName}</policyTemplateName>
        <isActivated>{upt.isSystem}</isActivated>
        <isSystem>{upt.isSystem}</isSystem>
        <versions>
          <version name="1.0">{upt.acceptationDate("1.0") in iso-8601}</version>
          <version name="1.1">{upt.acceptationDate("1.1") in iso-8601}</version>
          <version name="2.0">{upt.acceptationDate("2.0") in iso-8601}</version>
        </versions>
      </policyLibraryTemplate>
   */
  def unserialise(xml:XNode): Box[UserPolicyTemplate]
}

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