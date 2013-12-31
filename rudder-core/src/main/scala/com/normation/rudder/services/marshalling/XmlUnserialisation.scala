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
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import net.liftweb.common.Box
import com.normation.rudder.domain.workflows.ChangeRequest
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.workflows.DirectiveChange
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.workflows._
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.parameters._
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.rule.category.RuleCategory


trait XmlUnserializer {

  val rule        : RuleUnserialisation
  val directive   : DirectiveUnserialisation
  val group       : NodeGroupUnserialisation
  val globalParam : GlobalParameterUnserialisation
  val ruleCat     : RuleCategoryUnserialisation

}


trait DeploymentStatusUnserialisation {
  /**
   * Version 2:
   * <deploymentStatus fileFormat="2">
          <id>{d.id}</id>
          <started>{d.started}</started>
          <ended>{d.ended}</ended>
          <status>success</status>
     </deploymentStatus>

     <deploymentStatus fileFormat="2">
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
 * group category from an XML file.
 *
 * BE CAREFUL: groups and sub-categories will
 * always be empty here, as they are not serialized.
 *
 */
trait NodeGroupCategoryUnserialisation {
  /**
   * Version 2:
     <nodeGroupCategory fileFormat="2">
        <id>{cat.id.value}</id>
        <displayName>{cat.name}</displayName>
        <description>{cat.description}</serial>
        <isSystem>{cat.isSystem}</isSystem>
      </nodeGroupCategory>
   */
  def unserialise(xml:XNode): Box[NodeGroupCategory]
}

/**
 * That trait allow to unserialise
 * Node Group from an XML file.
 */
trait NodeGroupUnserialisation {
  /**
   * Version 2.0
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
  def unserialise(xml:XNode) : Box[NodeGroup]
}

/**
 * That trait allow to unserialise
 * rule from an XML file.
 */
trait RuleUnserialisation {
  /**
   * Version 5:
     <rule fileFormat="5">
        <id>{rule.id.value}</id>
        <name>{rule.name}</name>
        <serial>{rule.serial}</serial>
        <target>{ rule.target.map( _.target).getOrElse("") }</target>
        <directiveIds>{
          rule.directiveIds.map { id => <id>{id.value}</id> }
        }</directiveIds>
        <category>{rule.category.value}</category>
        <shortDescription>{rule.shortDescription}</shortDescription>
        <longDescription>{rule.longDescription}</longDescription>
        <isEnabled>{rule.isEnabledStatus}</isEnabled>
        <isSystem>{rule.isSystem}</isSystem>
      </rule>
   */
  def unserialise(xml:XNode) : Box[Rule]
}

/**
 * That trait allows to unserialise Rule categories from an XML file.
 */
trait RuleCategoryUnserialisation {
  /**
   * Version 5: (rc: nodeGroupCategory)
     <ruleCategory fileFormat="5">
        <id>{ngc.id.value}</id>
        <displayName>{ngc.name}</displayName>
        <description>{ngc.description}</serial>
        <isSystem>{ngc.isSystem}</isSystem>
      </ruleCategory>
   */
  def unserialise(xml:XNode): Box[RuleCategory]
}

/**
 * That trait allow to unserialise
 * active technique category from an XML file.
 *
 * BE CAREFUL: items and children categories will
 * always be empty here, as they are not serialized.
 *
 */
trait ActiveTechniqueCategoryUnserialisation {
  /**
   * Version 2:
     <activeTechniqueCategory fileFormat="2">
        <id>{uptc.id.value}</id>
        <displayName>{uptc.name}</displayName>
        <description>{uptc.description}</serial>
        <isSystem>{uptc.isSystem}</isSystem>
      </activeTechniqueCategory>
   */
  def unserialise(xml:XNode): Box[ActiveTechniqueCategory]
}

/**
 * That trait allow to unserialise
 * active technique from an XML file.
 *
 * BE CAREFUL: directive will be empty as they are not serialized.
 */
trait ActiveTechniqueUnserialisation {
  /**
   * Version 2:
     <activeTechnique fileFormat="2">
        <id>{activeTechnique.id.value}</id>
        <techniqueName>{activeTechnique.techniqueName}</techniqueName>
        <isEnabled>{activeTechnique.isSystem}</isEnabled>
        <isSystem>{activeTechnique.isSystem}</isSystem>
        <versions>
          <version name="1.0">{activeTechnique.acceptationDate("1.0") in iso-8601}</version>
          <version name="1.1">{activeTechnique.acceptationDate("1.1") in iso-8601}</version>
          <version name="2.0">{activeTechnique.acceptationDate("2.0") in iso-8601}</version>
        </versions>
      </activeTechnique>
   */
  def unserialise(xml:XNode): Box[ActiveTechnique]
}

/**
 * That trait allow to unserialise
 * directive from an XML file.
 */
trait DirectiveUnserialisation {
  /**
   * Version 2.0
     <directive fileFormat="2">
       <id>{directive.id.value}</id>
       <displayName>{directive.name}</displayName>
       <techniqueName>{PT name}</techniqueName>
       <techniqueVersion>{directive.techniqueVersion}</techniqueVersion>
       <shortDescription>{directive.shortDescription}</shortDescription>
       <longDescription>{directive.longDescription}</longDescription>
       <priority>{directive.priority}</priority>
       <isEnabled>{piisEnabled.}</isEnabled>
       <isSystem>{directive.isSystem}</isSystem>
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
     </directive>
   */
  def unserialise(xml:XNode) : Box[(TechniqueName, Directive, SectionVal)]


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
 * That trait allows to unserialize
 * Global Parameter from an XML
 */
trait GlobalParameterUnserialisation {
  /**
   * Version 3:
     <globalParameter fileFormat="3">
       <name>{param.name.value}</name>
       <value>{param.value}</value>
       <description>{param.description}</description>
       <overridable>{param.overridable}</overridable>
     </globalParameter>
   */
  def unserialise(xml:XNode) : Box[GlobalParameter]
}

/**
 * That trait allows to unserialize
 * API Account from an XML
 */
trait ApiAccountUnserialisation {
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
   def unserialise(xml:XNode) : Box[ApiAccount]
}

/**
 * That trait allow to unserialise change request changes from an XML file.
 *
 */
trait ChangeRequestChangesUnserialisation {
  /**
   * Version 2:
     <changeRequest fileFormat="4">
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
  def unserialise(xml:XNode): Box[(Box[Map[DirectiveId,DirectiveChanges]],Map[NodeGroupId,NodeGroupChanges],Map[RuleId,RuleChanges],Map[ParameterName,GlobalParameterChanges])]
}