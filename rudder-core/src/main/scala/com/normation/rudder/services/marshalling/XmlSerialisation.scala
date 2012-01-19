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

import scala.xml.Elem
import com.normation.rudder.domain.policies.ConfigurationRule
import com.normation.rudder.domain.policies.UserPolicyTemplateCategory
import com.normation.rudder.domain.policies.UserPolicyTemplate
import com.normation.cfclerk.domain.PolicyPackageName
import com.normation.rudder.domain.policies.PolicyInstance
import com.normation.cfclerk.domain.SectionSpec
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroup



/**
 * That trait allow to serialise 
 * Configuration Rule to an XML file. 
 */
trait ConfigurationRuleSerialisation {
  /**
   * Version 1:
     <configurationRule fileFormat="1.0">
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
  def serialise(cr:ConfigurationRule):  Elem
}

/**
 * That trait allows to serialise 
 * User policy templates categories to an XML file. 
 */
trait UserPolicyTemplateCategorySerialisation {
  /**
   * Version 1:
     <policyLibraryCategory fileFormat="1.0">
        <id>{uptc.id.value}</id>
        <displayName>{uptc.name}</displayName>
        <description>{uptc.description}</serial>
        <isSystem>{uptc.isSystem}</isSystem>
      </policyLibraryCategory>
   */
  def serialise(uptc:UserPolicyTemplateCategory):  Elem
}

/**
 * That trait allows to serialise 
 * User policy templates to an XML file. 
 */
trait UserPolicyTemplateSerialisation {
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
  def serialise(uptc:UserPolicyTemplate):  Elem
}

/**
 * That trait allows to serialise 
 * User policy templates to an XML file. 
 */
trait PolicyInstanceSerialisation {
  /**
   * Version 1:
     <policyInstance fileFormat="1.0">
      <id>{pi.id.value}</id>
      <displayName>{pi.name}</displayName>
      <policyTemplateName>{policy template name on with depend that pi}</policyTemplateName>
      <policyTemplateVersion>{pi.policyTemplateVersion.toString}</policyTemplateVersion>
      <shortDescription>{pi.shortDescription}</shortDescription>
      <longDescription>{pi.longDescription}</longDescription>
      <priority>{pi.priority}</priority>
      <isActivated>{pi.isActivated}</isActivated>
      <isSystem>{pi.isSystem}</isSystem>
      <!-- That section is generated by the serialization of pi section -->
      <section name="sections">
        <section name="section1">
          <var name="var1">XXX</var>
          <var name="var2">XXX</var>
        </section>
        <!-- a second instance of section 1 -->
        <section name="section1">
          <var name="var1">YYY</var>
          <var name="var2">YYY</var>
        </section>
      </section>
    </policyInstance>
   */
  def serialise(
      ptName             : PolicyPackageName
    , variableRootSection: SectionSpec
    , pi                 : PolicyInstance):  Elem
}


/**
 * That trait allows to serialise 
 * Node group categories to an XML file. 
 */
trait NodeGroupCategorySerialisation {
  /**
   * Version 1: (ngc: nodeGroupCategory)
     <groupLibraryCategory fileFormat="1.0">
        <id>{ngc.id.value}</id>
        <displayName>{ngc.name}</displayName>
        <description>{ngc.description}</serial>
        <isSystem>{ngc.isSystem}</isSystem>
      </groupLibraryCategory>
   */
  def serialise(ngc:NodeGroupCategory):  Elem
}

/**
 * That trait allows to serialise 
 * Node group to an XML file. 
 */
trait NodeGroupSerialisation {
  /**
   * Version 1: 
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
  def serialise(ng:NodeGroup):  Elem
}

/**
 * That trait allows to serialise deployment status to an XML data
 */
trait DeploymentStatusSerialisation {
  /**
   * Version 1:
     <deploymentStatus fileFormat="1.0">
      <id>{deploymentStatus.id.value}</id>
      <started>{deploymentStatus.started}</started>
      <ended>{deploymentStatus.end}</ended>
      <status>[success|failure]</status>
      <errorMessage>{errorStatus.failure]</errorMessage>
  	</deploymentStatus>
  */
  def serialise(
      deploymentStatus : CurrentDeploymentStatus) : Elem
  
}