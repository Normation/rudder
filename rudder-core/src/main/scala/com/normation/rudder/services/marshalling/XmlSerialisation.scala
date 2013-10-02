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
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.domain.policies.Directive
import com.normation.cfclerk.domain.SectionSpec
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.workflows.ChangeRequest
import com.normation.rudder.domain.workflows.ConfigurationChangeRequest
import com.normation.rudder.domain.parameters.GlobalParameter
import com.normation.rudder.api.ApiAccount

trait XmlSerializer {

  val rule        : RuleSerialisation
  val directive   : DirectiveSerialisation
  val group       : NodeGroupSerialisation
  val globalParam : GlobalParameterSerialisation


}

/**
 * That trait allow to serialise
 * rule to an XML file.
 */
trait RuleSerialisation {
  /**
   * Version 2:
     <rule fileFormat="2">
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
  def serialise(rule:Rule):  Elem
}

/**
 * That trait allows to serialise
 * active techniques categories to an XML file.
 */
trait ActiveTechniqueCategorySerialisation {
  /**
   * Version 2:
     <policyLibraryCategory fileFormat="2">
        <id>{uptc.id.value}</id>
        <displayName>{uptc.name}</displayName>
        <description>{uptc.description}</serial>
        <isSystem>{uptc.isSystem}</isSystem>
      </policyLibraryCategory>
   */
  def serialise(uptc:ActiveTechniqueCategory):  Elem
}

/**
 * That trait allows to serialise
 * active techniques to an XML file.
 */
trait ActiveTechniqueSerialisation {
  /**
   * Version 2:
     <policyLibraryTemplate fileFormat="2">
        <id>{activeTechnique.id.value}</id>
        <techniqueName>{activeTechnique.techniqueName}</techniqueName>
        <isEnabled>{activeTechnique.isSystem}</isEnabled>
        <isSystem>{activeTechnique.isSystem}</isSystem>
        <versions>
          <version name="1.0">{activeTechnique.acceptationDate("1.0") in iso-8601}</version>
          <version name="1.1">{activeTechnique.acceptationDate("1.1") in iso-8601}</version>
          <version name="2.0">{activeTechnique.acceptationDate("2.0") in iso-8601}</version>
        </versions>
      </policyLibraryTemplate>
   */
  def serialise(uptc:ActiveTechnique):  Elem
}

/**
 * That trait allows to serialise
 * active techniques to an XML file.
 */
trait DirectiveSerialisation {
  /**
   * Version 2:
     <directive fileFormat="2">
      <id>{directive.id.value}</id>
      <displayName>{directive.name}</displayName>
      <techniqueName>{technique name on with depend that directive}</techniqueName>
      <techniqueVersion>{directive.techniqueVersion.toString}</techniqueVersion>
      <shortDescription>{directive.shortDescription}</shortDescription>
      <longDescription>{directive.longDescription}</longDescription>
      <priority>{directive.priority}</priority>
      <isEnabled>{directive.isEnabled}</isEnabled>
      <isSystem>{directive.isSystem}</isSystem>
      <!-- That section is generated by the serialization of directive section -->
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
    </directive>
   */
  def serialise(
      ptName             : TechniqueName
    , variableRootSection: SectionSpec
    , directive          : Directive
  ) : Elem
}


/**
 * That trait allows to serialise
 * Node group categories to an XML file.
 */
trait NodeGroupCategorySerialisation {
  /**
   * Version 2: (ngc: nodeGroupCategory)
     <groupLibraryCategory fileFormat="2">
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
   * Version 2:
     <nodeGroup fileFormat="2">
       <id>{group.id.value}</id>
       <displayName>{group.id.name}</displayName>
       <description>{group.id.description}</description>
       <isDynamic>{group.isDynamic}</isDynamic>
       <isEnabled>{group.isEnabled}</isEnabled>
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
   * Version 2:
     <deploymentStatus fileFormat="2">
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


/**
 * That trait allow to unserialise change request changes from an XML file.
 *
 */
// TODO : do we need to change the fileFormat ?
trait ChangeRequestChangesSerialisation {
  /**
   * Version 2:
     <changeRequest fileFormat="2">
        <groups></groups>
          <group id="id1">*
            <initialState>
              NodeGroupSerialization*
            </initialState>
            <firstChange>
              NodeGroupSerialization+
            </firstChange>
            <nextChanges>
              <change>*
                NodeGroupSerilization
              </change>
            </nextChanges>
          </group>
        <directives>
          <directive id="id2">
            <initialState>
              <techniqueName>
                techniqueName
              </techniqueName>
              DirectiveSerialization
              <sectionSpec>
                sectionSpec
              </sectionSpec>
            </initialState>
            <firstChange>
              <techniqueName>
                techniqueName
              </techniqueName>
              DirectiveSerialization
              <sectionSpec>
                sectionSpec
              </sectionSpec>
            </firstChange>
            <nextChanges>
              <change>*
                <techniqueName>
                  techniqueName
                </techniqueName>
                DirectiveSerialization
                <sectionSpec>
                  sectionSpec
                </sectionSpec>
              </change>
            </nextChanges>
          </directive>
        </directives>
        <rules>
          <rule id="id3">*
            <initialState>
              RuleSerialization*
            </initialState>
            <firstChange>
              RuleSerialization+
            </firstChange>
            <nextChanges>
              <change>*
                RuleSerialization
              </change>
            </nextChanges>
          </group>
        </rules>
         <globalParameters>
          <globalParameter name="id3">*
            <initialState>
              GlobalParameterSerialization*
            </initialState>
            <firstChange>
              GlobalParameterSerialization+
            </firstChange>
            <nextChanges>
              <change>*
                GlobalParameterSerialization
              </change>
            </nextChanges>
          </globalParameter>
        </globalParameters>
      </changeRequest>
   */
  def serialise(changeRequest:ChangeRequest): Elem
}

/**
 * That trait allows to serialise
 * Global Parameter to an XML
 */
trait GlobalParameterSerialisation {
  /**
   * Version 3:
     <globalParameter fileFormat="3">
       <name>{param.name.value}</name>
       <value>{param.value}</value>
       <description>{param.description}</description>
       <overridable>{param.overridable}</overridable>
     </globalParameter>
   */
  def serialise(param:GlobalParameter):  Elem
}

/**
 * That trait allows to serialise
 * API Account to an XML
 */
trait APIAccountSerialisation {
  /**
   * Version 4:
     <apiAccount fileFormat="4">
       <id>{account.id.value}</id>
       <name>{account.name.value}</name>
       <token>{account.token.value}</token>
       <description>{account.description}</description>
       <isEnabled>{account.isEnabled}</isEnabled>
       <creationDate>{account.creationDate.toString(ISODateTimeFormat.dateTime)}</creationDate>
       <tokenGenerationDate>{account.tokenGenerationDate.toString(ISODateTimeFormat.dateTime)}</tokenGenerationDate>
     </apiAccount>
   */
  def serialise(account:ApiAccount):  Elem
}
