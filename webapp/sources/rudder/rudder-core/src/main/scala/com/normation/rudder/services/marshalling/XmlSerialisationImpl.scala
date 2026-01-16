/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.services.marshalling

import com.normation.GitVersion
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.xmlwriters.SectionSpecWriter
import com.normation.rudder.api.ApiAccount
import com.normation.rudder.api.ApiAccountKind
import com.normation.rudder.api.ApiAuthorization
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.batch.ErrorStatus
import com.normation.rudder.batch.SuccessStatus
import com.normation.rudder.domain.Constants.*
import com.normation.rudder.domain.appconfig.RudderWebProperty
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.AddDirectiveDiff
import com.normation.rudder.domain.policies.AddRuleDiff
import com.normation.rudder.domain.policies.DeleteDirectiveDiff
import com.normation.rudder.domain.policies.DeleteRuleDiff
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.policies.ModifyToDirectiveDiff
import com.normation.rudder.domain.policies.ModifyToRuleDiff
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.domain.policies.Tag
import com.normation.rudder.domain.policies.TagName
import com.normation.rudder.domain.policies.Tags
import com.normation.rudder.domain.policies.TagValue
import com.normation.rudder.domain.properties.AddGlobalParameterDiff
import com.normation.rudder.domain.properties.DeleteGlobalParameterDiff
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.ModifyToGlobalParameterDiff
import com.normation.rudder.domain.secret.Secret
import com.normation.rudder.domain.workflows.*
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.services.marshalling.MarshallingUtil.createTrimedElem
import net.liftweb.common.*
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.format.ISODateTimeFormat
import scala.xml.{Node as XNode, *}
import zio.json.*

//serialize / deserialize tags
object TagsXml {
  def toXml(tags: Tags): Elem = {
    <tags>{tags.map(tag => <tag name={tag.name.value} value={tag.value.value} />)}</tags>
  }

  // tags is the <tags> elements which contains <tag> direct children
  def getTags(tags: NodeSeq): Tags = {
    def untag(tag: XNode): Option[Tag] = {
      ((tag \ "@name").text, (tag \ "@value").text) match {
        case ("", _) | (_, "") => None
        case (name, value)     => Some(Tag(TagName(name), TagValue(value)))
      }
    }
    Tags(((tags \ "tag").flatMap(untag)).toSet)
  }
}

final case class XmlSerializerImpl(
    rule:        RuleSerialisation,
    directive:   DirectiveSerialisation,
    group:       NodeGroupSerialisation,
    globalParam: GlobalParameterSerialisation,
    ruleCat:     RuleCategorySerialisation
) extends XmlSerializer

class RuleSerialisationImpl(xmlVersion: String) extends RuleSerialisation {
  def serialise(rule: Rule): Elem = {
    createTrimedElem(XML_TAG_RULE, xmlVersion)(
      <id>{rule.id.serialize}</id>
        <displayName>{rule.name}</displayName>
        <category>{rule.categoryId.value}</category>
        <targets>{
        rule.targets.toList.sortBy(_.target).map(target => <target>{target.target}</target>)
      }</targets>
        <directiveIds>{
        rule.directiveIds.toList.sortBy(_.uid.value).map {
          case DirectiveId(uid, rev) =>
            rev match {
              case GitVersion.DEFAULT_REV =>
                <id>{uid.value}</id>
              case r                      =>
                <id revision={r.value}>{uid.value}</id>
            }
        }
      }</directiveIds>
        <shortDescription>{rule.shortDescription}</shortDescription>
        <longDescription>{rule.longDescription}</longDescription>
        <isEnabled>{rule.isEnabledStatus}</isEnabled>
        <isSystem>{rule.isSystem}</isSystem> ++ {
        TagsXml.toXml(rule.tags)
      }
    )
  }
}

/**
 * That trait allows to serialise
 * Node group categories to an XML file.
 */
class RuleCategorySerialisationImpl(xmlVersion: String) extends RuleCategorySerialisation {

  def serialise(rc: RuleCategory): Elem = {
    createTrimedElem(XML_TAG_RULE_CATEGORY, xmlVersion)(
      <id>{rc.id.value}</id>
        <displayName>{rc.name}</displayName>
        <description>{rc.description}</description>
        <isSystem>{rc.isSystem}</isSystem>
    )
  }
}

/**
 * That trait allow to serialise
 * active techniques categories to an XML file.
 */
class ActiveTechniqueCategorySerialisationImpl(xmlVersion: String) extends ActiveTechniqueCategorySerialisation {

  def serialise(uptc: ActiveTechniqueCategory): Elem = {
    createTrimedElem(XML_TAG_ACTIVE_TECHNIQUE_CATEGORY, xmlVersion)(
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
class ActiveTechniqueSerialisationImpl(xmlVersion: String) extends ActiveTechniqueSerialisation {

  def serialise(activeTechnique: ActiveTechnique): Elem = {
    createTrimedElem(XML_TAG_ACTIVE_TECHNIQUE, xmlVersion)(
      <id>{activeTechnique.id.value}</id>
        <techniqueName>{activeTechnique.techniqueName.value}</techniqueName>
        <isEnabled>{activeTechnique.isEnabled}</isEnabled>
        <policyTypes>{activeTechnique.policyTypes.toJson}</policyTypes>
        <versions>{
        activeTechnique.acceptationDatetimes.versions.map {
          case (version, date) =>
            // we never serialize revision in xml
            <version name={version.version.toVersionString}>{date.toString(ISODateTimeFormat.dateTime)}</version>
        }
      }</versions>
    )
  }
}

/**
 * That trait allows to serialise
 * directives an XML file.
 */
class DirectiveSerialisationImpl(xmlVersion: String) extends DirectiveSerialisation {

  def serialise(
      ptName:              TechniqueName,
      variableRootSection: Option[SectionSpec],
      directive:           Directive
  ): Elem = {
    createTrimedElem(XML_TAG_DIRECTIVE, xmlVersion)(
      <id>{directive.id.serialize}</id>
      :: <displayName>{directive.name}</displayName>
      :: <techniqueName>{ptName.value}</techniqueName>
      :: <techniqueVersion>{directive.techniqueVersion.serialize}</techniqueVersion>
      :: { SectionVal.toOptionnalXml(variableRootSection.map(SectionVal.directiveValToSectionVal(_, directive.parameters))) }
      :: <shortDescription>{directive.shortDescription}</shortDescription>
      :: <longDescription>{directive.longDescription}</longDescription>
      :: <priority>{directive.priority}</priority>
      :: <isEnabled>{directive.isEnabled}</isEnabled>
      :: <isSystem>{directive.isSystem}</isSystem>
      :: <policyMode>{directive.policyMode.map(_.name).getOrElse("default")}</policyMode>
      :: TagsXml.toXml(directive.tags)
      :: Nil
    )
  }
}

/**
 * That trait allows to serialise
 * Node group categories to an XML file.
 */
class NodeGroupCategorySerialisationImpl(xmlVersion: String) extends NodeGroupCategorySerialisation {

  def serialise(ngc: NodeGroupCategory): Elem = {
    createTrimedElem(XML_TAG_NODE_GROUP_CATEGORY, xmlVersion)(
      <id>{ngc.id.value}</id>
        <displayName>{ngc.name}</displayName>
        <description>{ngc.description}</description>
        <isSystem>{ngc.isSystem}</isSystem>
    )
  }
}

class NodeGroupSerialisationImpl(xmlVersion: String) extends NodeGroupSerialisation {
  def serialise(group: NodeGroup): Elem = {
    createTrimedElem(XML_TAG_NODE_GROUP, xmlVersion)(
      <id>{group.id.withDefaultRev.serialize}</id>
        <displayName>{group.name}</displayName>
        <description>{group.description}</description>
        <query>{group.query.map(_.toJson).getOrElse("")}</query>
        <isDynamic>{group.isDynamic}</isDynamic>
        <nodeIds>{
        if (group.isDynamic) {
          NodeSeq.Empty
        } else {
          group.serverList.map(id => <id>{id.value}</id>)
        }
      }</nodeIds>
        <isEnabled>{group.isEnabled}</isEnabled>
        <isSystem>{group.isSystem}</isSystem>
        <properties>{
        group.properties.sortBy(_.name).map { p =>
          // value parsing of properties is a bit messy and semantically linked
          // to json, since value part can be a string or json object.
          // Parsing that back from xml would be tedious.
          <property><name>{p.name}</name><value>{StringEscapeUtils.escapeHtml4(p.valueAsString)}</value></property>
        }
      }</properties>
    )
  }
}

/**
 * That trait allows to serialise deployment status to an XML data
 */
class DeploymentStatusSerialisationImpl(xmlVersion: String) extends DeploymentStatusSerialisation {
  def serialise(deploymentStatus: CurrentDeploymentStatus): Elem = {
    createTrimedElem(XML_TAG_DEPLOYMENT_STATUS, xmlVersion)(deploymentStatus match {
      case d: SuccessStatus => (
        <id>{d.id}</id>
          <started>{d.started}</started>
          <ended>{d.ended}</ended>
          <status>success</status>
      )
      case d: ErrorStatus   => (
        <id>{d.id}</id>
          <started>{d.started}</started>
          <ended>{d.ended}</ended>
          <status>failure</status>
          <errorMessage>{d.failure.messageChain}</errorMessage>
      )
      case _ => throw new IllegalArgumentException("Bad CurrentDeploymentStatus type, expected a success or an error")
    })
  }
}

class GlobalParameterSerialisationImpl(xmlVersion: String) extends GlobalParameterSerialisation {
  def serialise(param: GlobalParameter): Elem = {
    createTrimedElem(XML_TAG_GLOBAL_PARAMETER, xmlVersion)(
      <name>{param.name}</name>
       <value>{param.valueAsString}</value>
      ++ { param.inheritMode.map(m => <inheritMode>{m.value}</inheritMode>).getOrElse(NodeSeq.Empty) } ++
      <description>{param.description}</description>
      ++ { param.provider.map(p => <provider>{p.value}</provider>).getOrElse(NodeSeq.Empty) }
    )
  }
}

/**
 * That class allow to serialise change request changes to an XML data.
 *
 */
class ChangeRequestChangesSerialisationImpl(
    xmlVersion:            String,
    nodeGroupSerializer:   NodeGroupSerialisation,
    directiveSerializer:   DirectiveSerialisation,
    ruleSerializer:        RuleSerialisation,
    globalParamSerializer: GlobalParameterSerialisation,
    techniqueRepo:         TechniqueRepository,
    sectionSerializer:     SectionSpecWriter
) extends ChangeRequestChangesSerialisation with Loggable {
  def serialise(changeRequest: ChangeRequest): Elem = {

    def serializeGroupChange(change: NodeGroupChangeItem): NodeSeq = {
      <change>
        <actor>{change.actor.name}</actor>
        <date>{change.creationDate}</date>
        <reason>{change.reason.getOrElse("")}</reason>
        {
        change.diff match {
          case AddNodeGroupDiff(group)      => <diff action="add">{nodeGroupSerializer.serialise(group)}</diff>
          case DeleteNodeGroupDiff(group)   => <diff action="delete">{nodeGroupSerializer.serialise(group)}</diff>
          case ModifyToNodeGroupDiff(group) => <diff action="modifyTo">{nodeGroupSerializer.serialise(group)}</diff>
        }
      }
      </change>
    }

    def serializeDirectiveChange(change: DirectiveChangeItem): NodeSeq = {
      <change>
        <actor>{change.actor.name}</actor>
        <date>{change.creationDate}</date>
        <reason>{change.reason.getOrElse("")}</reason>
        {
        change.diff match {
          case AddDirectiveDiff(techniqueName, directive)                   =>
            techniqueRepo.get(TechniqueId(techniqueName, directive.techniqueVersion)) match {
              case None            => (
                s"Error, could not retrieve technique ${techniqueName.value} version ${directive.techniqueVersion.debugString}"
              )
              case Some(technique) =>
                <diff action="add">{directiveSerializer.serialise(techniqueName, Some(technique.rootSection), directive)}</diff>
            }
          case DeleteDirectiveDiff(techniqueName, directive)                =>
            techniqueRepo.get(TechniqueId(techniqueName, directive.techniqueVersion)) match {
              case None            => (
                s"Error, could not retrieve technique ${techniqueName.value} version ${directive.techniqueVersion.debugString}"
              )
              case Some(technique) =>
                <diff action="delete">{
                  directiveSerializer.serialise(techniqueName, Some(technique.rootSection), directive)
                }</diff>
            }
          case ModifyToDirectiveDiff(techniqueName, directive, rootSection) =>
            val rootSectionXml = change.diff match {
              case ModifyToDirectiveDiff(_, _, rs) =>
                sectionSerializer.serialize(rs).getOrElse(NodeSeq.Empty)
              case _                               => NodeSeq.Empty
            }
            <diff action="modifyTo">{directiveSerializer.serialise(techniqueName, rootSection, directive)}</diff> ++
            <rootSection>{rootSectionXml}</rootSection>
        }
      }
      </change>
    }

    def serializeRuleChange(change: RuleChangeItem): NodeSeq = {
      <change>
        <actor>{change.actor.name}</actor>
        <date>{change.creationDate}</date>
        <reason>{change.reason.getOrElse("")}</reason>
        {
        change.diff match {
          case AddRuleDiff(rule)      => <diff action="add">{ruleSerializer.serialise(rule)}</diff>
          case DeleteRuleDiff(rule)   => <diff action="delete">{ruleSerializer.serialise(rule)}</diff>
          case ModifyToRuleDiff(rule) => <diff action="modifyTo">{ruleSerializer.serialise(rule)}</diff>
        }
      }
      </change>
    }

    def serializeGlobalParamChange(change: GlobalParameterChangeItem): NodeSeq = {
      <change>
        <actor>{change.actor.name}</actor>
        <date>{change.creationDate}</date>
        <reason>{change.reason.getOrElse("")}</reason>
        {
        change.diff match {
          case AddGlobalParameterDiff(param)      => <diff action="add">{globalParamSerializer.serialise(param)}</diff>
          case DeleteGlobalParameterDiff(param)   => <diff action="delete">{globalParamSerializer.serialise(param)}</diff>
          case ModifyToGlobalParameterDiff(param) => <diff action="modifyTo">{globalParamSerializer.serialise(param)}</diff>
        }
      }
      </change>
    }

    changeRequest match {

      case changeRequest: ConfigurationChangeRequest =>
        val groups = changeRequest.nodeGroups.map {
          case (nodeGroupId, group) =>
            <group id={nodeGroupId.withDefaultRev.serialize}>
            <initialState>
              {group.changes.initialState.map(nodeGroupSerializer.serialise(_)).getOrElse(NodeSeq.Empty)}
            </initialState>
              <firstChange>
                {serializeGroupChange(group.changes.firstChange)}
              </firstChange>
            <nextChanges>
              {group.changes.nextChanges.map(serializeGroupChange(_))}
            </nextChanges>
          </group>
        }

        val directives = changeRequest.directives.map {
          case (directiveId, directive) =>
            <directive id={directiveId.serialize}>
            <initialState>
              {
              directive.changes.initialState.map {
                case (techniqueName, directive, rootSection) =>
                  directiveSerializer.serialise(techniqueName, rootSection, directive)
              }.getOrElse(NodeSeq.Empty)
            }
            </initialState>
              <firstChange>
                {serializeDirectiveChange(directive.changes.firstChange)}
              </firstChange>
            <nextChanges>
              {directive.changes.nextChanges.map(serializeDirectiveChange(_))}
            </nextChanges>
          </directive>
        }

        val rules  = changeRequest.rules.map {
          case (ruleId, rule) =>
            <rule id={ruleId.serialize}>
            <initialState>
              {
              rule.changes.initialState.map {
                case (initialRule) =>
                  ruleSerializer.serialise(initialRule)
              }.getOrElse(NodeSeq.Empty)
            }
            </initialState>
            <firstChange>
              {serializeRuleChange(rule.changes.firstChange)}
            </firstChange>
            <nextChanges>
              {rule.changes.nextChanges.map(serializeRuleChange(_))}
            </nextChanges>
          </rule>
        }
        val params = changeRequest.globalParams.map {
          case (paramName, param) =>
            <globalParameter name={paramName}>
            <initialState>
              {
              param.changes.initialState.map {
                case (initialParam) =>
                  globalParamSerializer.serialise(initialParam)
              }.getOrElse(NodeSeq.Empty)
            }
            </initialState>
            <firstChange>
              {serializeGlobalParamChange(param.changes.firstChange)}
            </firstChange>
            <nextChanges>
              {param.changes.nextChanges.map(serializeGlobalParamChange(_))}
            </nextChanges>
          </globalParameter>
        }

        createTrimedElem(XML_TAG_CHANGE_REQUEST, xmlVersion)(
          <groups>
        {groups}
      </groups>
      <directives>
        {directives}
      </directives>
      <rules>
        {rules}
      </rules>
      <globalParameters>
        {params}
      </globalParameters>
        )

      case _ => <not_implemented_yet />
    }

  }
}

/**
 * That trait allows to serialise
 *  API Account to an XML file.
 */
class APIAccountSerialisationImpl(xmlVersion: String) extends APIAccountSerialisation {

  def serialise(account: ApiAccount): Elem = {
    val kind = account.kind match {
      case ApiAccountKind.User | ApiAccountKind.System =>
        <kind>{account.kind.kind.name}</kind>
      case ApiAccountKind.PublicApi(authz, policy)     =>
        NodeSeq.fromSeq(
          Seq(
            <kind>{account.kind.kind.name}</kind>,
            <authorization>{
              authz match {
                case ApiAuthorization.None     => Text("none")
                case ApiAuthorization.RO       => Text("ro")
                case ApiAuthorization.RW       => Text("rw")
                case ApiAuthorization.ACL(acl) =>
                  <acl>{
                    acl.map(authz => <authz path={authz.path.value} action={authz.actions.map(_.name).mkString(",")} />)
                  }</acl>
              }
            }</authorization>
          )
        ) ++ policy.expirationDate
          .map(d => <expirationDate>{d.toString}</expirationDate>)
          .getOrElse(NodeSeq.Empty)
    }

    createTrimedElem(XML_TAG_API_ACCOUNT, xmlVersion)(
      (
        <id>{account.id.value}</id>
       <name>{account.name.value}</name>
       <token>{account.accountToken.flatMap(_.hash).flatMap(_.exposeHash()).getOrElse("")}</token>
       <description>{account.description}</description>
       <isEnabled>{account.isEnabled}</isEnabled>
       <creationDate>{account.creationDate.toString}</creationDate>
       <tokenGenerationDate>{account.tokenGenerationDate.toString}</tokenGenerationDate>
       <tenants>{account.tenants.serialize}</tenants>
      ) ++ kind
    )
  }
}

/**
 * That trait allows to serialize a web property to an XML
 */
class GlobalPropertySerialisationImpl(xmlVersion: String) extends GlobalPropertySerialisation {

  /**
   * Version 6:
     <globalPropertyUpdate fileFormat="6">
       <name>{property.name.value}</name>
       <value>{property.value}</value>
     </globalPropertyUpdate>
   */
  def serializeChange(oldProperty: RudderWebProperty, newProperty: RudderWebProperty): Elem = {
    val diff = SimpleDiff(oldProperty.value, newProperty.value)
    <globalPropertyUpdate changetype="modify" fileFormat="6">
       <name>{oldProperty.name.value}</name>
       {SimpleDiff.stringToXml(<value/>, diff)}
     </globalPropertyUpdate>
  }

}

class SecretSerialisationImpl(xmlVersion: String) extends SecretSerialisation {
  def serialise(secret: Secret): Elem = {
    createTrimedElem(XML_TAG_SECRET, xmlVersion)(
      <name>{secret.name}</name>
        <description>{secret.description}</description>
    )
  }
}
