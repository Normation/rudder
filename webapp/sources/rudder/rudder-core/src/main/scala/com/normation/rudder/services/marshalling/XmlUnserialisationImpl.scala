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
import com.normation.GitVersion.ParseRev
import com.normation.box.*
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.eventlog.EventActor
import com.normation.inventory.domain.NodeId
import com.normation.rudder.api.*
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.batch.ErrorStatus
import com.normation.rudder.batch.NoStatus
import com.normation.rudder.batch.SuccessStatus
import com.normation.rudder.domain.Constants.*
import com.normation.rudder.domain.nodes.*
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechniqueId
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.policies.DirectiveUid
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.policies.SectionVal
import com.normation.rudder.domain.properties.AddGlobalParameterDiff
import com.normation.rudder.domain.properties.DeleteGlobalParameterDiff
import com.normation.rudder.domain.properties.GlobalParameter
import com.normation.rudder.domain.properties.GroupProperty
import com.normation.rudder.domain.properties.InheritMode
import com.normation.rudder.domain.properties.ModifyToGlobalParameterDiff
import com.normation.rudder.domain.properties.PropertyProvider
import com.normation.rudder.domain.secret.Secret
import com.normation.rudder.domain.workflows.*
import com.normation.rudder.domain.workflows.DirectiveChangeItem
import com.normation.rudder.domain.workflows.DirectiveChanges
import com.normation.rudder.domain.workflows.NodeGroupChanges
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.utils.Control.traverse
import net.liftweb.common.*
import net.liftweb.common.Box.*
import net.liftweb.common.Failure
import net.liftweb.util.Helpers.tryo
import org.apache.commons.lang3.StringEscapeUtils
import org.joda.time.format.ISODateTimeFormat
import scala.annotation.nowarn
import scala.util.Failure as Catch
import scala.util.Success
import scala.util.Try
import scala.xml.Node as XNode
import scala.xml.NodeSeq
import scala.xml.Text
import zio.json.*

final case class XmlUnserializerImpl(
    rule:        RuleUnserialisation,
    directive:   DirectiveUnserialisation,
    group:       NodeGroupUnserialisation,
    globalParam: GlobalParameterUnserialisation,
    ruleCat:     RuleCategoryUnserialisation
) extends XmlUnserializer

class DirectiveUnserialisationImpl extends DirectiveUnserialisation {

  override def parseSectionVal(xml: NodeSeq): Box[SectionVal] = {
    def recValParseSection(elt: XNode): Box[(String, SectionVal)] = {
      if (elt.label != "section") Failure("Bad XML, awaiting a <section> and get: " + elt)
      else {
        for {
          name     <- (elt \ "@name").headOption ?~! ("Missing required attribute 'name' for <section>: " + elt)
          // Seq( (var name , var value ) )
          vars     <- traverse(elt \ "var") { xmlVar =>
                        for {
                          n <- (xmlVar \ "@name").headOption ?~! ("Missing required attribute 'name' for <var>: " + xmlVar)
                        } yield {
                          (n.text, xmlVar.text)
                        }
                      }
          // Seq ( SectionVal )
          sections <- traverse(elt \ "section")(sectionXml => recValParseSection(sectionXml))
        } yield {
          val s = sections.groupBy { case (n, s) => n }.map { case (n, seq) => (n, seq.map { case (_, section) => section }) }
          (name.text, SectionVal(s, vars.toMap))
        }
      }
    }

    for {
      root            <- (xml \ "section").toList match {
                           case Nil         => Failure("Missing required tag <section> in: " + xml)
                           case node :: Nil => Full(node)
                           case x           => Failure("Found several <section> tag in XML, but only one root section is allowed: " + xml)
                         }
      (_, sectionVal) <- recValParseSection(root)
    } yield {
      sectionVal
    }
  }

  override def unserialise(xml: XNode): Box[(TechniqueName, Directive, SectionVal)] = {
    for {
      directive        <- {
        if (xml.label == XML_TAG_DIRECTIVE) Full(xml)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_DIRECTIVE, xml))
      }
      fileFormatOk     <- TestFileFormat(directive)
      sid              <- (directive \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type directive : " + xml)
      id               <- DirectiveId.parse(sid).toBox
      ptName           <- (directive \ "techniqueName").headOption.map(
                            _.text
                          ) ?~! ("Missing attribute 'techniqueName' in entry type directive : " + xml)
      name             <- (directive \ "displayName").headOption.map(
                            _.text.trim
                          ) ?~! ("Missing attribute 'displayName' in entry type directive : " + xml)
      techniqueVersion <- (directive \ "techniqueVersion").headOption.flatMap(x =>
                            TechniqueVersion.parse(x.text).toBox
                          ) ?~! ("Missing attribute 'techniqueVersion' in entry type directive : " + xml)
      sectionVal       <- parseSectionVal(directive)
      shortDescription <- (directive \ "shortDescription").headOption.map(
                            _.text
                          ) ?~! ("Missing attribute 'shortDescription' in entry type directive : " + xml)
      longDescription  <- (directive \ "longDescription").headOption.map(
                            _.text
                          ) ?~! ("Missing attribute 'longDescription' in entry type directive : " + xml)
      isEnabled        <- (directive \ "isEnabled").headOption.flatMap(s =>
                            tryo(s.text.toBoolean)
                          ) ?~! ("Missing attribute 'isEnabled' in entry type directive : " + xml)
      priority         <- (directive \ "priority").headOption.flatMap(s =>
                            tryo(s.text.toInt)
                          ) ?~! ("Missing or bad attribute 'priority' in entry type directive : " + xml)
      isSystem         <- (directive \ "isSystem").headOption.flatMap(s =>
                            tryo(s.text.toBoolean)
                          ) ?~! ("Missing attribute 'isSystem' in entry type directive : " + xml)
      policyMode        = (directive \ "policyMode").headOption.flatMap(s => PolicyMode.parse(s.text).toBox)
      tags              = TagsXml.getTags(directive \ "tags")
    } yield {
      (
        TechniqueName(ptName),
        Directive(
          id = id,
          name = name,
          techniqueVersion = techniqueVersion,
          parameters = SectionVal.toMapVariables(sectionVal),
          shortDescription = shortDescription,
          policyMode = policyMode,
          longDescription = longDescription,
          priority = priority,
          _isEnabled = isEnabled,
          isSystem = isSystem,
          tags = tags
        ),
        sectionVal
      )
    }
  }
}

class NodeGroupCategoryUnserialisationImpl extends NodeGroupCategoryUnserialisation {

  def unserialise(entry: XNode): Box[NodeGroupCategory] = {
    for {
      category     <- {
        if (entry.label == XML_TAG_NODE_GROUP_CATEGORY) Full(entry)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_NODE_GROUP_CATEGORY, entry))
      }
      fileFormatOk <- TestFileFormat(category)
      id           <- (category \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type groupLibraryCategory : " + entry)
      name         <- (category \ "displayName").headOption.map(
                        _.text.trim
                      ) ?~! ("Missing attribute 'displayName' in entry type groupLibraryCategory : " + entry)
      description  <- (category \ "description").headOption.map(
                        _.text
                      ) ?~! ("Missing attribute 'description' in entry type groupLibraryCategory : " + entry)
      isSystem     <- (category \ "isSystem").headOption.flatMap(s =>
                        tryo(s.text.toBoolean)
                      ) ?~! ("Missing attribute 'isSystem' in entry type groupLibraryCategory : " + entry)
    } yield {
      NodeGroupCategory(
        id = NodeGroupCategoryId(id),
        name = name,
        description = description,
        items = Nil,
        children = Nil,
        isSystem = isSystem
      )
    }
  }
}

class NodeGroupUnserialisationImpl(
    cmdbQueryParser: CmdbQueryParser
) extends NodeGroupUnserialisation {
  def unserialise(entry: XNode): Box[NodeGroup] = {
    for {
      group        <- {
        if (entry.label == XML_TAG_NODE_GROUP) Full(entry)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_NODE_GROUP, entry))
      }
      fileFormatOk <- TestFileFormat(group)
      sid          <- (group \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type nodeGroup : " + entry)
      id           <- NodeGroupId.parse(sid).toBox
      name         <- (group \ "displayName").headOption.map(
                        _.text.trim
                      ) ?~! ("Missing attribute 'displayName' in entry type nodeGroup : " + entry)
      description  <-
        (group \ "description").headOption.map(_.text) ?~! ("Missing attribute 'description' in entry type nodeGroup : " + entry)
      query        <- (group \ "query").headOption match {
                        case None    => Full(None)
                        case Some(s) =>
                          if (s.text.isEmpty) Full(None)
                          else cmdbQueryParser(s.text).map(Some(_))
                      }
      isDynamic    <- (group \ "isDynamic").headOption.flatMap(s =>
                        tryo(s.text.toBoolean)
                      ) ?~! ("Missing attribute 'isDynamic' in entry type nodeGroup : " + entry)
      serverList    = if (isDynamic) {
                        Set[NodeId]()
                      } else {
                        (group \ "nodeIds" \ "id").map(n => NodeId(n.text)).toSet
                      }
      isEnabled    <- (group \ "isEnabled").headOption.flatMap(s =>
                        tryo(s.text.toBoolean)
                      ) ?~! ("Missing attribute 'isEnabled' in entry type nodeGroup : " + entry)
      isSystem     <- (group \ "isSystem").headOption.flatMap(s =>
                        tryo(s.text.toBoolean)
                      ) ?~! ("Missing attribute 'isSystem' in entry type nodeGroup : " + entry)
      properties: Seq[GroupProperty] <- traverse(group \ "properties" \ "property") { p =>
                                          val name = (p \ "name").text.trim
                                          if (name.isEmpty) {
                                            Failure(s"Found unexpected xml under <properties> tag (name is blank): ${p}")
                                          } else {
                                            GroupProperty
                                              .parse(
                                                name,
                                                ParseRev((p \ "revision").text.trim),
                                                StringEscapeUtils.unescapeXml((p \ "value").text.trim): @nowarn(
                                                  "msg=class StringEscapeUtils in package lang3 is deprecated"
                                                ),
                                                (p \ "inheritMode").headOption.flatMap(p =>
                                                  InheritMode.parseString(p.text.trim).toOption
                                                ),
                                                (p \ "provider").headOption.map(p => PropertyProvider(p.text.trim))
                                              )
                                              .toBox
                                          }
                                        }
    } yield {
      NodeGroup(
        id = id,
        name = name,
        description = description,
        properties = properties.toList,
        query = query,
        isDynamic = isDynamic,
        serverList = serverList,
        _isEnabled = isEnabled,
        isSystem = isSystem
      )
    }
  }
}

class RuleUnserialisationImpl extends RuleUnserialisation {
  def unserialise(entry: XNode): Box[Rule] = {
    for {
      rule             <- {
        if (entry.label == XML_TAG_RULE) Full(entry)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_RULE, entry))
      }
      fileFormatOk     <- TestFileFormat(rule)
      sid              <- (rule \ "id").headOption.map(_.text) ?~!
                          ("Missing attribute 'id' in entry type rule: " + entry)
      id               <- RuleId.parse(sid).toBox
      category         <- (rule \ "category").headOption.map(n => RuleCategoryId(n.text)) ?~!
                          ("Missing attribute 'category' in entry type rule: " + entry)
      name             <- (rule \ "displayName").headOption.map(_.text.trim) ?~!
                          ("Missing attribute 'displayName' in entry type rule: " + entry)
      revision          = ParseRev((rule \ "revision").headOption.map(_.text.trim))
      shortDescription <- (rule \ "shortDescription").headOption.map(_.text) ?~!
                          ("Missing attribute 'shortDescription' in entry type rule: " + entry)
      longDescription  <- (rule \ "longDescription").headOption.map(_.text) ?~!
                          ("Missing attribute 'longDescription' in entry type rule: " + entry)
      isEnabled        <- (rule \ "isEnabled").headOption.flatMap(s => tryo(s.text.toBoolean)) ?~!
                          ("Missing attribute 'isEnabled' in entry type rule: " + entry)
      isSystem         <- (rule \ "isSystem").headOption.flatMap(s => tryo(s.text.toBoolean)) ?~!
                          ("Missing attribute 'isSystem' in entry type rule: " + entry)
      targets          <- traverse((rule \ "targets" \ "target")) { t =>
                            RuleTarget.unser(t.text)
                          } ?~!
                          ("Invalid attribute in 'target' entry: " + entry)
      directiveIds      = (rule \ "directiveIds" \ "id").map { n =>
                            DirectiveId(DirectiveUid(n.text), ParseRev((n \ "@revision").text))
                          }.toSet
      tags              = TagsXml.getTags(rule \ "tags")

    } yield {
      Rule(
        id,
        name,
        category,
        targets.toSet,
        directiveIds,
        shortDescription,
        longDescription,
        isEnabled,
        isSystem,
        tags
      )
    }
  }
}

class RuleCategoryUnserialisationImpl extends RuleCategoryUnserialisation {

  def unserialise(entry: XNode): Box[RuleCategory] = {
    for {
      category     <- {
        if (entry.label == XML_TAG_RULE_CATEGORY) Full(entry)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_RULE_CATEGORY, entry))
      }
      fileFormatOk <- TestFileFormat(category)
      id           <- (category \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type groupLibraryCategory : " + entry)
      name         <- (category \ "displayName").headOption.map(
                        _.text.trim
                      ) ?~! ("Missing attribute 'displayName' in entry type groupLibraryCategory : " + entry)
      description  <- (category \ "description").headOption.map(
                        _.text
                      ) ?~! ("Missing attribute 'description' in entry type groupLibraryCategory : " + entry)
      isSystem     <- (category \ "isSystem").headOption.flatMap(s =>
                        tryo(s.text.toBoolean)
                      ) ?~! ("Missing attribute 'isSystem' in entry type groupLibraryCategory : " + entry)
    } yield {
      RuleCategory(
        id = RuleCategoryId(id),
        name = name,
        description = description,
        childs = Nil,
        isSystem = isSystem
      )
    }
  }
}

class ActiveTechniqueCategoryUnserialisationImpl extends ActiveTechniqueCategoryUnserialisation {

  def unserialise(entry: XNode): Box[ActiveTechniqueCategory] = {
    for {
      uptc         <- {
        if (entry.label == XML_TAG_ACTIVE_TECHNIQUE_CATEGORY) Full(entry)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_ACTIVE_TECHNIQUE_CATEGORY, entry))
      }
      fileFormatOk <- TestFileFormat(uptc)
      id           <- (uptc \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type policyLibraryCategory : " + entry)
      name         <- (uptc \ "displayName").headOption.map(
                        _.text
                      ) ?~! ("Missing attribute 'displayName' in entry type policyLibraryCategory : " + entry)
      description  <- (uptc \ "description").headOption.map(
                        _.text
                      ) ?~! ("Missing attribute 'description' in entry type policyLibraryCategory : " + entry)
      isSystem     <- (uptc \ "isSystem").headOption.flatMap(s =>
                        tryo(s.text.toBoolean)
                      ) ?~! ("Missing attribute 'isSystem' in entry type policyLibraryCategory : " + entry)
    } yield {
      ActiveTechniqueCategory(
        id = ActiveTechniqueCategoryId(id),
        name = name,
        description = description,
        items = Nil,
        children = Nil,
        isSystem = isSystem
      )
    }
  }
}

class ActiveTechniqueUnserialisationImpl extends ActiveTechniqueUnserialisation {

  // we expect acceptation date to be in ISO-8601 format
  private val dateFormatter = ISODateTimeFormat.dateTime

  def unserialise(entry: XNode): Box[ActiveTechnique] = {
    for {
      activeTechnique  <- {
        if (entry.label == XML_TAG_ACTIVE_TECHNIQUE) Full(entry)
        else Failure("Entry type is not a <%s>: ".format(XML_TAG_ACTIVE_TECHNIQUE, entry))
      }
      fileFormatOk     <- TestFileFormat(activeTechnique)
      id               <- (activeTechnique \ "id").headOption.map(
                            _.text
                          ) ?~! ("Missing attribute 'id' in entry type policyLibraryTemplate : " + entry)
      ptName           <- (activeTechnique \ "techniqueName").headOption.map(
                            _.text
                          ) ?~! ("Missing attribute 'displayName' in entry type policyLibraryTemplate : " + entry)
      policyTypes      <- (activeTechnique \ "policyTypes").headOption.flatMap(s =>
                            s.text.fromJson[PolicyTypes].toBox
                          ) ?~! ("Missing attribute 'isSystem' in entry type policyLibraryTemplate : " + entry)
      isEnabled        <- (activeTechnique \ "isEnabled").headOption.flatMap(s =>
                            tryo(s.text.toBoolean)
                          ) ?~! ("Missing attribute 'isEnabled' in entry type policyLibraryTemplate : " + entry)
      acceptationDates <- traverse(activeTechnique \ "versions" \ "version") { version =>
                            for {
                              ptVersionName   <-
                                version
                                  .attribute("name")
                                  .map(_.text) ?~! "Missing attribute 'name' for acceptation date in PT '%s' (%s): '%s'".format(
                                  ptName,
                                  id,
                                  version
                                )
                              ptVersion       <- TechniqueVersion
                                                   .parse(ptVersionName)
                                                   .toBox ?~! s"Error when trying to parse '${ptVersionName}' as a technique version."
                              acceptationDate <- tryo(dateFormatter.parseDateTime(version.text))
                            } yield {
                              (ptVersion, acceptationDate)
                            }
                          }
      acceptationMap   <- {
        val map = acceptationDates.toMap
        if (map.size != acceptationDates.size) {
          Failure(
            "There exists a duplicate polity template version in the acceptation date map: " + acceptationDates.mkString("; ")
          )
        } else Full(map)
      }
    } yield {
      ActiveTechnique(
        id = ActiveTechniqueId(id),
        techniqueName = TechniqueName(ptName),
        acceptationDatetimes = acceptationMap,
        directives = Nil,
        _isEnabled = isEnabled,
        policyTypes = policyTypes
      )
    }
  }
}

class DeploymentStatusUnserialisationImpl extends DeploymentStatusUnserialisation {
  def unserialise(entry: XNode): Box[CurrentDeploymentStatus] = {
    for {
      depStatus    <- {
        if (entry.label == XML_TAG_DEPLOYMENT_STATUS) Full(entry)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_DEPLOYMENT_STATUS, entry))
      }
      fileFormatOk <- TestFileFormat(depStatus)
      id           <- (depStatus \ "id").headOption.flatMap(s =>
                        tryo(s.text.toLong)
                      ) ?~! ("Missing attribute 'id' in entry type deploymentStatus : " + entry)
      status       <-
        (depStatus \ "status").headOption.map(_.text) ?~! ("Missing attribute 'status' in entry type deploymentStatus : " + entry)

      started      <- (depStatus \ "started").headOption.flatMap(s =>
                        tryo(ISODateTimeFormat.dateTimeParser.parseDateTime(s.text))
                      ) ?~! ("Missing or bad attribute 'started' in entry type deploymentStatus : " + entry)
      ended        <- (depStatus \ "ended").headOption.flatMap(s =>
                        tryo(ISODateTimeFormat.dateTimeParser.parseDateTime(s.text))
                      ) ?~! ("Missing or bad attribute 'ended' in entry type deploymentStatus : " + entry)
      errorMessage <- (depStatus \ "errorMessage").headOption match {
                        case None    => Full(None)
                        case Some(s) =>
                          if (s.text.isEmpty) Full(None)
                          else Full(Some(s.text))
                      }
    } yield {
      status match {
        case "success" => SuccessStatus(id, started, ended, Set())
        case "failure" => ErrorStatus(id, started, ended, errorMessage.map(x => Failure(x)).getOrElse(Failure("")))
        case s         => NoStatus
      }
    }
  }
}

/**
 * That trait allow to unserialise change request changes from an XML file.
 *
 */
class ChangeRequestChangesUnserialisationImpl(
    nodeGroupUnserialiser:   NodeGroupUnserialisation,
    directiveUnserialiser:   DirectiveUnserialisation,
    ruleUnserialiser:        RuleUnserialisation,
    globalParamUnserialiser: GlobalParameterUnserialisation,
    techRepo:                TechniqueRepository,
    sectionSpecUnserialiser: SectionSpecParser
) extends ChangeRequestChangesUnserialisation with Loggable {
  def unserialise(xml: XNode): Box[
    (
        Box[Map[DirectiveId, DirectiveChanges]],
        Map[NodeGroupId, NodeGroupChanges],
        Map[RuleId, RuleChanges],
        Map[String, GlobalParameterChanges]
    )
  ] = {
    def unserialiseNodeGroupChange(changeRequest: XNode): Box[Map[NodeGroupId, NodeGroupChanges]] = {
      (for {
        groupsNode <- (changeRequest \ "groups").headOption ?~! s"Missing child 'groups' in entry type changeRequest : ${xml}"
      } yield {
        (groupsNode \ "group").iterator.flatMap { group =>
          for {
            sid          <- group.attribute("id").map(id => id.text) ?~!
                            s"Missing attribute 'id' in entry type changeRequest group changes  : ${group}"
            nodeGroupId  <- NodeGroupId.parse(sid).toBox
            initialNode  <- (group \ "initialState").headOption
            initialState <- (initialNode \ "nodeGroup").headOption match {
                              case Some(initialState) =>
                                nodeGroupUnserialiser.unserialise(initialState) match {
                                  case Full(group) => Full(Some(group))
                                  case eb: EmptyBox => eb ?~! "could not unserialize group"
                                }
                              case None               => Full(None)
                            }

            changeNode  <- (group \ "firstChange" \ "change").headOption
            actor       <- (changeNode \\ "actor").headOption.map(actor => EventActor(actor.text))
            date        <- (changeNode \\ "date").headOption.map(date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text))
            reason       = (changeNode \\ "reason").headOption.map(_.text)
            diff        <- (changeNode \\ "diff").headOption.flatMap(_.attribute("action").headOption.map(_.text))
            diffGroup   <- (changeNode \\ "nodeGroup").headOption
            changeGroup <- nodeGroupUnserialiser.unserialise(diffGroup)
            change      <- diff match {
                             case "add"      => Full(AddNodeGroupDiff(changeGroup))
                             case "delete"   => Full(DeleteNodeGroupDiff(changeGroup))
                             case "modifyTo" => Full(ModifyToNodeGroupDiff(changeGroup))
                             case _          => Failure("should not happen")
                           }
          } yield {
            val groupChange = NodeGroupChange(initialState, NodeGroupChangeItem(actor, date, reason, change), Seq())

            (nodeGroupId -> NodeGroupChanges(groupChange, Seq()))
          }
        }.toMap
      })
    }

    def unserialiseDirectiveChange(changeRequest: XNode): Box[Map[DirectiveId, DirectiveChanges]] = {
      (for {
        directivesNode <-
          (changeRequest \ "directives").headOption ?~! s"Missing child 'directives' in entry type changeRequest : ${xml}"
      } yield {
        (directivesNode \ "directive").iterator.flatMap { directive =>
          for {
            directiveId  <- directive.attribute("id").flatMap(id => DirectiveId.parse(id.text).toBox) ?~!
                            s"Missing attribute 'id' in entry type changeRequest directive changes  : ${directive}"
            initialNode  <- (directive \ "initialState").headOption
            initialState <- (initialNode \\ "directive").headOption match {
                              case Some(initialState) =>
                                directiveUnserialiser.unserialise(initialState) match {
                                  case Full((techName, directive, _)) => Full(Some((techName, directive)))
                                  case eb: EmptyBox => eb ?~! "could not unserialize directive"
                                }
                              case None               => Full(None)
                            }

            changeNode    <- (directive \ "firstChange" \ "change").headOption
            actor         <- (changeNode \\ "actor").headOption.map(actor => EventActor(actor.text))
            date          <- (changeNode \\ "date").headOption.map(date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text))
            reason         = (changeNode \\ "reason").headOption.map(_.text)
            diff          <- (changeNode \\ "diff").headOption.flatMap(_.attribute("action").headOption.map(_.text))
            diffDirective <- (changeNode \\ "directive").headOption

            (techniqueName, changeDirective, _) <- directiveUnserialiser.unserialise(diffDirective)
            change                              <- {
              diff match {
                case "add"      => Full(AddDirectiveDiff(techniqueName, changeDirective))
                case "delete"   => Full(DeleteDirectiveDiff(techniqueName, changeDirective))
                case "modifyTo" =>
                  (changeNode \\ "rootSection").headOption match {
                    case Some(rsXml) =>
                      val techId = TechniqueId(techniqueName, changeDirective.techniqueVersion)
                      sectionSpecUnserialiser
                        .parseSectionsInPolicy(rsXml, techId, techniqueName.value)
                        .map(rootSection => ModifyToDirectiveDiff(techniqueName, changeDirective, Some(rootSection)))
                        .toBox
                    case None        => Failure(s"Could not find rootSection node in ${changeNode}")

                  }

                case _ => Failure("should not happen")
              }

            }
          } yield {
            val directiveChange = DirectiveChange(
              initialState.map {
                case (techName, directive) =>
                  val rootSection = techRepo.get(TechniqueId(techName, directive.techniqueVersion)).map(_.rootSection)
                  (techName, directive, rootSection)
              },
              DirectiveChangeItem(actor, date, reason, change),
              Seq()
            )

            (directiveId -> DirectiveChanges(directiveChange, Seq()))
          }
        }.toMap
      })
    }

    def unserialiseRuleChange(changeRequest: XNode): Box[Map[RuleId, RuleChanges]] = {
      (for {
        rulesNode <- (changeRequest \ "rules").headOption ?~! s"Missing child 'rules' in entry type changeRequest : ${xml}"
      } yield {

        (rulesNode \ "rule").iterator.flatMap { rule =>
          for {
            ruleId       <- rule.attribute("id").flatMap(id => RuleId.parse(id.text).toBox) ?~!
                            s"Missing attribute 'id' in entry type changeRequest rule changes  : ${rule}"
            initialRule  <- (rule \ "initialState").headOption
            initialState <- (initialRule \ "rule").headOption match {
                              case Some(initialState) =>
                                ruleUnserialiser.unserialise(initialState) match {
                                  case Full(rule) => Full(Some(rule))
                                  case eb: EmptyBox => eb ?~! "could not unserialize rule"
                                }
                              case None               => Full(None)
                            }

            changeRule <- (rule \ "firstChange" \ "change").headOption
            actor      <- (changeRule \\ "actor").headOption.map(actor => EventActor(actor.text))
            date       <- (changeRule \\ "date").headOption.map(date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text))
            reason      = (changeRule \\ "reason").headOption.map(_.text)
            diff       <- (changeRule \\ "diff").headOption.flatMap(_.attribute("action").headOption.map(_.text))
            diffRule   <- (changeRule \\ "rule").headOption
            changeRule <- ruleUnserialiser.unserialise(diffRule)
            change     <- diff match {
                            case "add"      => Full(AddRuleDiff(changeRule))
                            case "delete"   => Full(DeleteRuleDiff(changeRule))
                            case "modifyTo" => Full(ModifyToRuleDiff(changeRule))
                            case _          => Failure("should not happen")
                          }
          } yield {
            val ruleChange = RuleChange(initialState, RuleChangeItem(actor, date, reason, change), Seq())

            (ruleId -> RuleChanges(ruleChange, Seq()))
          }
        }.toMap
      })
    }

    def unserialiseGlobalParameterChange(changeRequest: XNode): Box[Map[String, GlobalParameterChanges]] = {
      (for {
        paramsNode <-
          (changeRequest \ "globalParameters").headOption ?~! s"Missing child 'globalParameters' in entry type changeRequest : ${xml}"
      } yield {
        (paramsNode \ "globalParameter").iterator.flatMap { param =>
          for {
            paramName    <- param.attribute("name").map(_.text) ?~!
                            s"Missing attribute 'name' in entry type globalParameters Global Parameter changes  : ${param}"
            initialParam <- (param \ "initialState").headOption
            initialState <- (initialParam \ "globalParameter").headOption match {
                              case Some(initialState) =>
                                globalParamUnserialiser.unserialise(initialState) match {
                                  case Full(rule) => Full(Some(rule))
                                  case eb: EmptyBox => eb ?~! "could not unserialize global parameter"
                                }
                              case None               => Full(None)
                            }

            changeParam <- (param \ "firstChange" \ "change").headOption
            actor       <- (changeParam \\ "actor").headOption.map(actor => EventActor(actor.text))
            date        <- (changeParam \\ "date").headOption.map(date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text))
            reason       = (changeParam \\ "reason").headOption.map(_.text)
            diff        <- (changeParam \\ "diff").headOption.flatMap(_.attribute("action").headOption.map(_.text))
            diffParam   <- (changeParam \\ "globalParameter").headOption
            changeParam <- globalParamUnserialiser.unserialise(diffParam)
            change      <- diff match {
                             case "add"      => Full(AddGlobalParameterDiff(changeParam))
                             case "delete"   => Full(DeleteGlobalParameterDiff(changeParam))
                             case "modifyTo" => Full(ModifyToGlobalParameterDiff(changeParam))
                             case _          => Failure("should not happen")
                           }
          } yield {
            val paramChange = GlobalParameterChange(initialState, GlobalParameterChangeItem(actor, date, reason, change), Seq())

            (paramName -> GlobalParameterChanges(paramChange, Seq()))
          }
        }.toMap
      })
    }

    for {
      changeRequest <- {
        if (xml.label == XML_TAG_CHANGE_REQUEST) Full(xml)
        else Failure("Entry type is not a <%s>: ".format(XML_TAG_CHANGE_REQUEST, xml))
      }
      fileFormatOk  <- TestFileFormat(changeRequest)
      groups        <- unserialiseNodeGroupChange(changeRequest)
      directives     = {
        Try {
          unserialiseDirectiveChange(changeRequest)
        } match {
          case Success(change) => change
          case Catch(e)        =>
            Failure(s"Could not deserialize directives changes cause ${e.getMessage()}")
        }
      }
      rules         <- unserialiseRuleChange(changeRequest)
      params        <- unserialiseGlobalParameterChange(changeRequest)
    } yield {
      (directives, groups, rules, params)
    }
  }

}

class GlobalParameterUnserialisationImpl extends GlobalParameterUnserialisation {
  def unserialise(entry: XNode): Box[GlobalParameter] = {
    for {
      globalParam  <- {
        if (entry.label == XML_TAG_GLOBAL_PARAMETER) Full(entry)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_GLOBAL_PARAMETER, entry))
      }
      fileFormatOk <- TestFileFormat(globalParam)

      name        <-
        (globalParam \ "name").headOption.map(_.text) ?~! ("Missing attribute 'name' in entry type globalParameter : " + entry)
      value       <-
        (globalParam \ "value").headOption.map(_.text) ?~! ("Missing attribute 'value' in entry type globalParameter : " + entry)
      description <- (globalParam \ "description").headOption.map(
                       _.text
                     ) ?~! ("Missing attribute 'description' in entry type globalParameter : " + entry)
      provider     = (globalParam \ "provider").headOption.map(x => PropertyProvider(x.text))
      mode         = (globalParam \ "inheritMode").headOption.flatMap(x => InheritMode.parseString(x.text).toOption)
      // TODO: no version in param for now
      g           <- GlobalParameter.parse(name, GitVersion.DEFAULT_REV, value, mode, description, provider).toBox
    } yield {
      g // TODO: no version in param for now

    }
  }
}

class ApiAccountUnserialisationImpl extends ApiAccountUnserialisation {
  // we expect acceptation date to be in ISO-8601 format
  private val dateFormatter = ISODateTimeFormat.dateTime

  // <acl>
  //   <authz path="/foo/bar/$baz", actions="get, post, patch" />
  //   <authz path="/foo/wiz/$waz", actions="get, post, patch"/>
  // </acl>
  private def unserAcl(entry: XNode): Box[ApiAuthorization.ACL] = {
    // at that point, any error is an grave error: we don't want to
    // mess up with authz
    import cats.implicits.*

    val authzs = traverse(entry \\ "authz") { e =>
      for {
        pathStr <- e \@ "path" match {
                     case "" => Failure("Missing required attribute 'path' for element 'authz'")
                     case s  => Full(s)
                   }
        path    <- AclPath.parse(pathStr).fold(Failure(_), Full(_))
        actions <- e \@ "actions" match {
                     case "" => Failure("Missing required attribute 'actions' for element 'authz'")
                     case s  =>
                       (s.split(",").map(_.trim).toList.filter(_.isEmpty).traverse(HttpAction.parse _)) match {
                         case Left(s)  => Failure(s)
                         case Right(x) => Full(x)
                       }
                   }
      } yield {
        ApiAclElement(path, actions.toSet)
      }
    }

    authzs.map(acl => ApiAuthorization.ACL(acl.toList))
  }

  def unserialise(entry: XNode): Box[ApiAccount] = {
    for {
      apiAccount     <- {
        if (entry.label == XML_TAG_API_ACCOUNT) Full(entry)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_API_ACCOUNT, entry))
      }
      fileFormatOk   <- TestFileFormat(apiAccount)
      id             <- (apiAccount \ "id").headOption.map(_.text) ?~! ("Missing attribute 'id' in entry type API Account : " + entry)
      name           <- (apiAccount \ "name").headOption.map(_.text) ?~! ("Missing attribute 'name' in entry type API Account : " + entry)
      token          <-
        (apiAccount \ "token").headOption.map(_.text) ?~! ("Missing attribute 'token' in entry type API Account : " + entry)
      description    <- (apiAccount \ "description").headOption.map(
                          _.text
                        ) ?~! ("Missing attribute 'description' in entry type API Account : " + entry)
      isEnabled      <- (apiAccount \ "isEnabled").headOption.flatMap(s =>
                          tryo(s.text.toBoolean)
                        ) ?~! ("Missing attribute 'isEnabled' in entry type API Account : " + entry)
      creationDate   <- (apiAccount \ "creationDate").headOption.flatMap(s =>
                          tryo(dateFormatter.parseDateTime(s.text))
                        ) ?~! ("Missing attribute 'creationDate' in entry type API Account : " + entry)
      tokenGenDate   <- (apiAccount \ "tokenGenerationDate").headOption.flatMap(s =>
                          tryo(dateFormatter.parseDateTime(s.text))
                        ) ?~! ("Missing attribute 'tokenGenerationDate' in entry type API Account : " + entry)
      expirationDate <- (apiAccount \ "expirationDate").headOption match {
                          case None    => Full(None)
                          case Some(s) =>
                            tryo {
                              Some(dateFormatter.parseDateTime(s.text))
                            } ?~! ("Bad date format for field 'expirationDate' in entry type API Account : " + entry)
                        }
      authz          <- (apiAccount \ "authorization").headOption match {
                          case None =>
                            // we are most likelly in a case where API ACL weren't implemented,
                            // because the event was saved < Rudder 4.3. Use a "nil" ACL
                            Full(ApiAuthorization.None)

                          case Some(Text(text)) if text == ApiAuthorizationKind.RO.name =>
                            Full(ApiAuthorization.RO)
                          case Some(Text(text)) if text == ApiAuthorizationKind.RW.name =>
                            Full(ApiAuthorization.RW)
                          case Some(xml @ <acl>{_*}</acl>) if xml.child.nonEmpty        => unserAcl(xml.child.head)
                          // all other case: serialization pb => None
                          case _                                                        => Full(ApiAuthorization.None)
                        }
      accountType     = (apiAccount \ "kind").headOption.map(_.text) match {
                          case None    => ApiAccountType.PublicApi
                          case Some(s) => ApiAccountType.values.find(_.name == s).getOrElse(ApiAccountType.PublicApi)
                        }
      tenants        <- NodeSecurityContext.parse((apiAccount \ "tenants").headOption.map(_.text)).toBox
    } yield {
      val kind = accountType match {
        case ApiAccountType.System    => ApiAccountKind.System
        case ApiAccountType.User      => ApiAccountKind.User
        case ApiAccountType.PublicApi => ApiAccountKind.PublicApi(authz, expirationDate)
      }

      ApiAccount(
        ApiAccountId(id),
        kind,
        ApiAccountName(name),
        ApiToken(token),
        description,
        isEnabled,
        creationDate,
        tokenGenDate,
        tenants
      )
    }
  }
}

class SecretUnserialisationImpl extends SecretUnserialisation {
  def unserialise(entry: XNode): Box[Secret] = {
    for {
      secret       <- {
        if (entry.label == XML_TAG_SECRET) Full(entry)
        else Failure("Entry type is not a <%s>: %s".format(XML_TAG_SECRET, entry))
      }
      fileFormatOk <- TestFileFormat(secret)
      name         <- (secret \ "name").headOption.map(_.text) ?~! ("Missing attribute 'name' in entry type secret : " + entry)
      description  <-
        (secret \ "description").headOption.map(_.text) ?~! ("Missing attribute 'description' in entry type secret : " + entry)
    } yield {
      Secret(name, "", description)
    }
  }
}
