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
import com.normation.errors.BoxToEither
import com.normation.errors.Inconsistency
import com.normation.errors.OptionToPureResult
import com.normation.errors.PureResult
import com.normation.errors.Unexpected
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
import com.normation.rudder.domain.properties.Visibility
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
import net.liftweb.common.Box
import net.liftweb.common.Box.*
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.util.Helpers.tryo
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.format.ISODateTimeFormat
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
      if (elt.label != "section") Failure(s"Bad XML, expected a <section> but got: ${elt}")
      else {
        for {
          name     <- (elt \ "@name").headOption ?~! s"Missing required attribute 'name' for <section>: ${elt}"
          // Seq( (var name , var value ) )
          vars     <- traverse(elt \ "var") { xmlVar =>
                        for {
                          n <- (xmlVar \ "@name").headOption ?~! s"Missing required attribute 'name' for <var>: ${xmlVar}"
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
                           case Nil         => Failure(s"Missing required tag <section> in: ${xml}")
                           case node :: Nil => Full(node)
                           case x           => Failure(s"Found several <section> tag in XML, but only one root section is allowed: ${xml}")
                         }
      (_, sectionVal) <- recValParseSection(root)
    } yield {
      sectionVal
    }
  }

  override def unserialise(xml: XNode): PureResult[(TechniqueName, Directive, SectionVal)] = {

    import XmlUtils.*
    implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_DIRECTIVE)

    for {
      directive        <- {
        if (xml.label == XML_TAG_DIRECTIVE) Right(xml)
        else Left(Unexpected(s"Entry type is not a ${XML_TAG_DIRECTIVE}: ${xml}"))
      }
      _                <- TestFileFormat.check(directive)
      sid              <- mapAttributePure(directive, "id", _.text)
      id               <- DirectiveId.parse(sid) match {
                            case Left(err)  => Left(Unexpected(err))
                            case Right(res) => Right(res)
                          }
      ptName           <- mapAttributePure(directive, "techniqueName", _.text)
      name             <- mapAttributePure(directive, "displayName", _.text.trim)
      techniqueVersion <- idAttributePure(directive, "techniqueVersion").flatMap(x => {
                            TechniqueVersion.parse(x.text) match {
                              case Right(res) => Right(res)
                              case Left(err)  => Left(Unexpected(err))
                            }
                          })
      sectionVal       <- parseSectionVal(directive).toPureResult
      shortDescription <- mapAttributePure(directive, "shortDescription", _.text)
      longDescription  <- mapAttributePure(directive, "longDescription", _.text)
      isEnabled        <- flatMapAttributePure(directive, "isEnabled", s => s.text.toBooleanOption)
      priority         <- (directive \ "priority").headOption
                            .flatMap(s => s.text.toIntOption)
                            .notOptionalPure(s"Missing or bad attribute 'priority' in entry type directive : ${xml}")
      isSystem         <- flatMapAttributePure(directive, "isSystem", s => s.text.toBooleanOption)
      policyMode        = (directive \ "policyMode").headOption.flatMap(s => PolicyMode.parse(s.text).toOption)
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

  import XmlUtils.*
  implicit val entryType: XmlEntryType = XmlEntryType("groupLibraryCategory")

  def unserialise(entry: XNode): PureResult[NodeGroupCategory] = {

    for {
      category    <- {
        if (entry.label == XML_TAG_NODE_GROUP_CATEGORY) Right(entry)
        else Left(Unexpected(s"Entry type is not a ${XML_TAG_NODE_GROUP_CATEGORY}: ${entry}"))
      }
      _           <- TestFileFormat.check(category)
      id          <- mapAttributePure(category, "id", _.text)
      name        <- mapAttributePure(category, "dispayName", _.text.trim)
      description <- mapAttributePure(category, "description", _.text)
      isSystem    <- flatMapAttributePure(category, "isSystem", _.text.toBooleanOption)
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

  import XmlUtils.*
  implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_NODE_GROUP)

  def unserialise(entry: XNode): PureResult[NodeGroup] = {
    for {
      group       <- {
        if (entry.label == XML_TAG_NODE_GROUP) Right(entry)
        else Left(Unexpected(s"Entry type is not a ${XML_TAG_NODE_GROUP}: ${entry}"))
      }
      _           <- TestFileFormat.check(group)
      sid         <- mapAttributePure(group, "id", _.text)
      id          <- NodeGroupId.parse(sid) match {
                       case Right(res) => Right(res)
                       case Left(err)  => Left(Inconsistency(err))
                     }
      name        <- mapAttributePure(group, "displayName", _.text.trim)
      description <- mapAttributePure(group, "description", _.text)
      query       <- (group \ "query").headOption match {
                       case None    => Right(None)
                       case Some(s) =>
                         if (s.text.isEmpty) Right(None)
                         else cmdbQueryParser(s.text).map(Some(_)).toPureResult
                     }
      isDynamic   <- flatMapAttributePure(group, "isDynamic", s => s.text.toBooleanOption)
      serverList   = if (isDynamic) {
                       Set[NodeId]()
                     } else {
                       (group \ "nodeIds" \ "id").map(n => NodeId(n.text)).toSet
                     }
      isEnabled   <- flatMapAttributePure(group, "isEnabled", s => s.text.toBooleanOption)
      isSystem    <- flatMapAttributePure(group, "isSystem", s => s.text.toBooleanOption)
      properties  <- traverse((group \ "properties" \ "property").toList) {
                        // format: off
                        case <property>{p @ _*}</property> =>
                        // format: on
                         val name = (p \\ "name").text.trim
                         if (name.trim.isEmpty) {
                           Failure(s"Found unexpected xml under <properties> tag (name is blank): ${p}")
                         } else {
                           GroupProperty
                             .parse(
                               (p \\ "name").text.trim,
                               ParseRev((p \\ "revision").text.trim),
                               StringEscapeUtils.unescapeXml((p \\ "value").text.trim),
                               (p \\ "inheritMode").headOption.flatMap(p => InheritMode.parseString(p.text.trim).toOption),
                               (p \\ "provider").headOption.map(p => PropertyProvider(p.text.trim))
                             )
                             .toBox
                         }
                       case xml                           => Failure(s"Found unexpected xml under <properties> tag: ${xml}")
                     }.toPureResult
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

  import XmlUtils.*
  implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_RULE)

  def unserialise(entry: XNode): PureResult[Rule] = {
    for {
      rule             <- {
        if (entry.label == XML_TAG_RULE) Right(entry)
        else Left(Unexpected(s"Entry type is not a ${XML_TAG_RULE}: ${entry}"))
      }
      _                <- TestFileFormat.check(rule)
      sid              <- mapAttributePure(rule, "id", _.text)
      id               <- RuleId.parse(sid) match {
                            case Right(res) => Right(res)
                            case Left(err)  => Left(Inconsistency(err))
                          }
      category         <- mapAttributePure(rule, "category", n => RuleCategoryId(n.text))
      name             <- mapAttributePure(rule, "displayName", _.text.trim)
      revision          = ParseRev((rule \ "revision").headOption.map(_.text.trim))
      shortDescription <- mapAttributePure(rule, "shortDescription", _.text)
      longDescription  <- mapAttributePure(rule, "longDescription", _.text)
      isEnabled        <- flatMapAttributePure(rule, "isEnabled", s => s.text.toBooleanOption)
      isSystem         <- flatMapAttributePure(rule, "isSystem", s => s.text.toBooleanOption)
      targets          <- (traverse((rule \ "targets" \ "target")) { t =>
                            RuleTarget.unser(t.text)
                          } ?~!
                          (s"Invalid attribute in 'target' entry: ${entry}")).toPureResult
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
      category    <- {
        if (entry.label == XML_TAG_RULE_CATEGORY) Full(entry)
        else Failure(s"Entry type is not a ${XML_TAG_RULE_CATEGORY}: ${entry}")
      }
      _           <- TestFileFormat(category)
      id          <- (category \ "id").headOption.map(_.text) ?~! s"Missing attribute 'id' in entry type groupLibraryCategory : ${entry}"
      name        <- (category \ "displayName").headOption.map(
                       _.text.trim
                     ) ?~! s"Missing attribute 'displayName' in entry type groupLibraryCategory : ${entry}"
      description <- (category \ "description").headOption.map(
                       _.text
                     ) ?~! s"Missing attribute 'description' in entry type groupLibraryCategory : ${entry}"
      isSystem    <- (category \ "isSystem").headOption.flatMap(s =>
                       tryo(s.text.toBoolean)
                     ) ?~! s"Missing attribute 'isSystem' in entry type groupLibraryCategory : ${entry}"
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
      uptc        <- {
        if (entry.label == XML_TAG_ACTIVE_TECHNIQUE_CATEGORY) Full(entry)
        else Failure(s"Entry type is not a ${XML_TAG_ACTIVE_TECHNIQUE_CATEGORY}: ${entry}")
      }
      _           <- TestFileFormat(uptc)
      id          <- (uptc \ "id").headOption.map(_.text) ?~! s"Missing attribute 'id' in entry type policyLibraryCategory : ${entry}"
      name        <- (uptc \ "displayName").headOption.map(
                       _.text
                     ) ?~! s"Missing attribute 'displayName' in entry type policyLibraryCategory : ${entry}"
      description <- (uptc \ "description").headOption.map(
                       _.text
                     ) ?~! s"Missing attribute 'description' in entry type policyLibraryCategory : ${entry}"
      isSystem    <- (uptc \ "isSystem").headOption.flatMap(s =>
                       tryo(s.text.toBoolean)
                     ) ?~! s"Missing attribute 'isSystem' in entry type policyLibraryCategory : ${entry}"
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
        else Failure(s"Entry type is not a ${XML_TAG_ACTIVE_TECHNIQUE}: ${entry}")
      }
      _                <- TestFileFormat(activeTechnique)
      id               <- (activeTechnique \ "id").headOption.map(
                            _.text
                          ) ?~! (s"Missing attribute 'id' in entry type policyLibraryTemplate : ${entry}")
      ptName           <- (activeTechnique \ "techniqueName").headOption.map(
                            _.text
                          ) ?~! (s"Missing attribute 'displayName' in entry type policyLibraryTemplate : ${entry}")
      policyTypes       = (activeTechnique \ "policyTypes").headOption
                            .flatMap(s => s.text.fromJson[PolicyTypes].toBox)
                            .getOrElse(
                              // by default, for compat reason, if the type is missing, we assume rudder std technique
                              PolicyTypes.rudderBase
                            )
      isEnabled        <- (activeTechnique \ "isEnabled").headOption.flatMap(s =>
                            tryo(s.text.toBoolean)
                          ) ?~! (s"Missing attribute 'isEnabled' in entry type policyLibraryTemplate : ${entry}")
      acceptationDates <- traverse(activeTechnique \ "versions" \ "version") { version =>
                            for {
                              ptVersionName   <-
                                version
                                  .attribute("name")
                                  .map(
                                    _.text
                                  ) ?~! s"Missing attribute 'name' for acceptation date in PT '${ptName}' (${id}): '${version}'"
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
            s"There exists a duplicate polity template version in the acceptation date map: ${acceptationDates.mkString("; ")}"
          )
        } else Full(map)
      }
    } yield {
      ActiveTechnique(
        id = ActiveTechniqueId(id),
        techniqueName = TechniqueName(ptName),
        acceptationDatetimes = AcceptationDateTime(acceptationMap),
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
      depStatus <- {
        if (entry.label == XML_TAG_DEPLOYMENT_STATUS) Full(entry)
        else Failure(s"Entry type is not a ${XML_TAG_DEPLOYMENT_STATUS}: ${entry}")
      }
      _         <- TestFileFormat(depStatus)
      id        <- (depStatus \ "id").headOption.flatMap(s =>
                     tryo(s.text.toLong)
                   ) ?~! (s"Missing attribute 'id' in entry type deploymentStatus : ${entry}")
      status    <-
        (depStatus \ "status").headOption.map(
          _.text
        ) ?~! (s"Missing attribute 'status' in entry type deploymentStatus : ${entry}")

      started      <- (depStatus \ "started").headOption.flatMap(s =>
                        tryo(ISODateTimeFormat.dateTimeParser.parseDateTime(s.text))
                      ) ?~! (s"Missing or bad attribute 'started' in entry type deploymentStatus : ${entry}")
      ended        <- (depStatus \ "ended").headOption.flatMap(s =>
                        tryo(ISODateTimeFormat.dateTimeParser.parseDateTime(s.text))
                      ) ?~! (s"Missing or bad attribute 'ended' in entry type deploymentStatus : ${entry}")
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
 * That trait allows to unserialise change request changes from an XML file.
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

  def unserialise(xml: XNode): PureResult[
    (
        Map[DirectiveId, DirectiveChanges],
        Map[NodeGroupId, NodeGroupChanges],
        Map[RuleId, RuleChanges],
        Map[String, GlobalParameterChanges]
    )
  ] = {

    def unserialiseNodeGroupChange(changeRequest: XNode): PureResult[Map[NodeGroupId, NodeGroupChanges]] = {

      import XmlUtils.*
      implicit val entryType: XmlEntryType = XmlEntryType("changeRequest group changes")

      for {
        groupsNode <-
          (changeRequest \ "groups").headOption.notOptionalPure(s"Missing child 'groups' in entry type changeRequest : ${xml}")

      } yield {
        (groupsNode \ "group").iterator.flatMap { group =>
          val res = (for {

            sid          <- getPureAttribute(group.attribute("id").map(id => id.text), "id", group)
            nodeGroupId  <- NodeGroupId.parse(sid) match {
                              case Right(res) => Right(res)
                              case Left(_)    =>
                                Left(Inconsistency(missingErrMsg("id", entryType, group)))
                            }
            initialNode  <- idAttributePure(group, "initialState")
            initialState <- (initialNode \ "nodeGroup").headOption match {
                              case Some(initialState) =>
                                nodeGroupUnserialiser.unserialise(initialState) match {
                                  case Right(group) => Right(Some(group))
                                  case Left(_)      => Left(Inconsistency("could not unserialize group"))
                                }
                              case None               => Left(Inconsistency("TODO"))
                            }
            changeNode   <- getPureAttributeTwo(group, "firstChange", "change")
            actor        <- mapAttributePureRec(changeNode, "actor", group, actor => EventActor(actor.text))
            date         <- mapAttributePureRec(
                              changeNode,
                              "date",
                              group,
                              date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text)
                            )
            reason        = (changeNode \\ "reason").headOption.map(_.text)
            diff         <- flatMapAttributePureRec(changeNode, "diff", group, _.attribute("action").headOption.map(_.text))
            diffGroup    <- idAttributePureRec(changeNode, "nodeGroup", group)
            changeGroup  <- nodeGroupUnserialiser.unserialise(diffGroup)
            change       <- diff match {
                              case "add"      => Right(AddNodeGroupDiff(changeGroup))
                              case "delete"   => Right(DeleteNodeGroupDiff(changeGroup))
                              case "modifyTo" => Right(ModifyToNodeGroupDiff(changeGroup))
                              case _          => Left(Unexpected("should not happen"))
                            }

          } yield {
            val groupChange = NodeGroupChange(initialState, NodeGroupChangeItem(actor, date, reason, change), Seq())

            (nodeGroupId -> NodeGroupChanges(groupChange, Seq()))
          })

          // Compatibility : the errors are not included in the final result and will only be logged
          res match {
            case Left(err)  =>
              logger.error(err.fullMsg)
              None
            case Right(res) => Some(res)
          }

        }.toMap
      }
    }

    def unserialiseDirectiveChange(changeRequest: XNode): PureResult[Map[DirectiveId, DirectiveChanges]] = {

      import XmlUtils.*
      implicit val entryType: XmlEntryType = XmlEntryType("changeRequest directive changes")

      (for {
        directivesNode <- (changeRequest \ "directives").headOption.notOptionalPure(
                            s"Missing child 'directives' in entry type changeRequest : ${xml}"
                          )
      } yield {
        (directivesNode \ "directive").iterator.flatMap { directive =>
          val res = (for {

            directiveId    <-
              getPureAttribute(directive.attribute("id"), "id", directive).flatMap { id =>
                DirectiveId.parse(id.text) match {
                  case Right(res) => Right(res)
                  case Left(_)    =>
                    Left(Inconsistency(missingErrMsg("id", entryType, directive)))
                }
              }
            initialNode    <- idAttributePure(directive, "initialState")
            initialState   <- (initialNode \\ "directive").headOption match {
                                case Some(initialState) =>
                                  directiveUnserialiser.unserialise(initialState) match {
                                    case Right((techniqueName, directive, _)) => Right(Some((techniqueName, directive)))
                                    case Left(_)                              => Left(Inconsistency("could not unserialize directive"))
                                  }
                                case None               => Left(Inconsistency("TODO"))
                              }
            changeNode     <- getPureAttributeTwo(directive, "firstChange", "change")
            actor          <- mapAttributePureRec(changeNode, "actor", directive, actor => EventActor(actor.text))
            date           <- mapAttributePureRec(
                                changeNode,
                                "date",
                                directive,
                                date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text)
                              )
            reason          = (changeNode \\ "reason").headOption.map(_.text)
            diff           <-
              flatMapAttributePureRec(changeNode, "diff", directive, _.attribute("action").headOption.map(_.text))
            diffDirective  <- idAttributePureRec(changeNode, "directive", directive)
            directive      <- directiveUnserialiser.unserialise(diffDirective)
            techniqueName   = directive._1
            changeDirective = directive._2
            change         <- {
              diff match {
                case "add"      => Right(AddDirectiveDiff(techniqueName, changeDirective))
                case "delete"   => Right(DeleteDirectiveDiff(techniqueName, changeDirective))
                case "modifyTo" =>
                  (changeNode \\ "rootSection").headOption match {
                    case Some(rsXml) =>
                      val techId = TechniqueId(techniqueName, changeDirective.techniqueVersion)
                      sectionSpecUnserialiser
                        .parseSectionsInPolicy(rsXml, techId, techniqueName.value)
                        .map(rootSection => ModifyToDirectiveDiff(techniqueName, changeDirective, Some(rootSection)))
                    case None        => Left(Unexpected(s"Could not find rootSection node in ${changeNode}"))

                  }

                case _ => Left(Inconsistency("should not happen"))
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
          }): PureResult[(DirectiveId, DirectiveChanges)]

          // Compatibility : the errors are not included in the final result and will only be logged
          res match {
            case Left(err)  =>
              logger.error(err.fullMsg)
              None
            case Right(res) => Some(res)
          }

        }.toMap
      })
    }

    def unserialiseRuleChange(changeRequest: XNode): PureResult[Map[RuleId, RuleChanges]] = {

      import XmlUtils.*
      implicit val entryType: XmlEntryType = XmlEntryType("changeRequest rule changes")

      (for {
        rulesNode <-
          (changeRequest \ "rules").headOption.notOptionalPure(s"Missing child 'rules' in entry type changeRequest : ${xml}")

      } yield {

        (rulesNode \ "rule").iterator.flatMap { rule =>
          val res = (for {
            ruleId       <- getPureAttribute(rule.attribute("id"), "id", rule)
                              .flatMap(id => {
                                RuleId.parse(id.text) match {
                                  case Right(res) => Right(res)
                                  case Left(_)    =>
                                    Left(Inconsistency(missingErrMsg("id", entryType, rule)))
                                }
                              })
            initialRule  <- idAttributePure(rule, "initialState")
            initialState <- (initialRule \ "rule").headOption match {
                              case Some(initialState) =>
                                ruleUnserialiser.unserialise(initialState) match {
                                  case Right(rule) => Right(Some(rule))
                                  case Left(_)     => Left(Inconsistency("could not unserialize rule"))
                                }
                              case None               => Left(Inconsistency("TODO"))
                            }
            changeRule   <- getPureAttributeTwo(rule, "firstChange", "change")
            actor        <- mapAttributePureRec(changeRule, "actor", rule, actor => EventActor(actor.text))
            date         <- mapAttributePureRec(
                              changeRule,
                              "date",
                              rule,
                              date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text)
                            )
            reason        = (changeRule \\ "reason").headOption.map(_.text)
            diff         <- flatMapAttributePureRec(changeRule, "diff", rule, _.attribute("action").headOption.map(_.text))
            diffRule     <- idAttributePureRec(changeRule, "directive", rule)
            changeRule   <- ruleUnserialiser.unserialise(diffRule)
            change       <- diff match {
                              case "add"      => Right(AddRuleDiff(changeRule))
                              case "delete"   => Right(DeleteRuleDiff(changeRule))
                              case "modifyTo" => Right(ModifyToRuleDiff(changeRule))
                              case _          => Left(Inconsistency("should not happen"))
                            }
          } yield {
            val ruleChange = RuleChange(initialState, RuleChangeItem(actor, date, reason, change), Seq())

            (ruleId -> RuleChanges(ruleChange, Seq()))
          })

          // Compatibility : the errors are not included in the final result and will only be logged
          res match {
            case Left(err)  =>
              logger.error(err.fullMsg)
              None
            case Right(res) => Some(res)
          }

        }.toMap
      })
    }

    def unserialiseGlobalParameterChange(changeRequest: XNode): PureResult[Map[String, GlobalParameterChanges]] = {

      import XmlUtils.*
      implicit val entryType: XmlEntryType = XmlEntryType("globalParameters Global Parameter changes")

      (for {
        paramsNode <-
          (changeRequest \ "globalParameters").headOption.notOptionalPure(
            s"Missing child 'globalParameters' in entry type changeRequest : ${xml}"
          )
      } yield {
        (paramsNode \ "globalParameter").iterator.flatMap { param =>
          val res = (for {
            paramName    <-
              getPureAttribute(param.attribute("name").map(_.text), "name", param)
            initialParam <- idAttributePure(param, "initialState")
            initialState <- (initialParam \ "globalParameter").headOption match {
                              case Some(initialState) =>
                                globalParamUnserialiser.unserialise(initialState) match {
                                  case Right(param) => Right(Some(param))
                                  case Left(_)      => Left(Inconsistency("could not unserialize global parameter"))
                                }
                              case None               => Left(Inconsistency("TODO"))
                            }
            changeParam  <- getPureAttributeTwo(param, "firstChange", "change")
            actor        <- mapAttributePureRec(changeParam, "actor", param, actor => EventActor(actor.text))
            date         <- mapAttributePureRec(
                              changeParam,
                              "date",
                              param,
                              date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text)
                            )
            reason        = (changeParam \\ "reason").headOption.map(_.text)
            diff         <- flatMapAttributePureRec(changeParam, "diff", param, _.attribute("action").headOption.map(_.text))
            diffParam    <- idAttributePureRec(changeParam, "globalParameter", param)
            changeParam  <- globalParamUnserialiser.unserialise(diffParam)
            change       <- diff match {
                              case "add"      => Right(AddGlobalParameterDiff(changeParam))
                              case "delete"   => Right(DeleteGlobalParameterDiff(changeParam))
                              case "modifyTo" => Right(ModifyToGlobalParameterDiff(changeParam))
                              case _          => Left(Inconsistency("should not happen"))
                            }

          } yield {
            val paramChange = GlobalParameterChange(initialState, GlobalParameterChangeItem(actor, date, reason, change), Seq())

            (paramName -> GlobalParameterChanges(paramChange, Seq()))
          })

          // Compatibility : the errors are not included in the final result and will only be logged
          res match {
            case Left(err)  =>
              logger.error(err.fullMsg)
              None
            case Right(res) => Some(res)
          }

        }.toMap
      })
    }

    for {
      changeRequest <- {
        if (xml.label == XML_TAG_CHANGE_REQUEST) Right(xml)
        else Left(Unexpected(s"Entry type is not a ${XML_TAG_CHANGE_REQUEST}: ${xml}"))
      }
      _             <- TestFileFormat.check(changeRequest)
      groups        <- unserialiseNodeGroupChange(changeRequest)
      directives    <- {
        Try {
          unserialiseDirectiveChange(changeRequest)
        } match {
          case Success(change) => change
          case Catch(e)        =>
            Left(Unexpected(s"Could not deserialize directives changes cause ${e.getMessage()}"))
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
  def unserialise(entry: XNode): PureResult[GlobalParameter] = {
    (for {
      globalParam <- {
        if (entry.label == XML_TAG_GLOBAL_PARAMETER) Full(entry)
        else Failure(s"Entry type is not a ${XML_TAG_GLOBAL_PARAMETER}: ${entry}")
      }
      _           <- TestFileFormat(globalParam)

      name        <-
        (globalParam \ "name").headOption.map(_.text) ?~! s"Missing attribute 'name' in entry type globalParameter : ${entry}"
      value       <-
        (globalParam \ "value").headOption.map(_.text) ?~! s"Missing attribute 'value' in entry type globalParameter : ${entry}"
      description <- (globalParam \ "description").headOption.map(
                       _.text
                     ) ?~! s"Missing attribute 'description' in entry type globalParameter : ${entry}"
      provider     = (globalParam \ "provider").headOption.map(x => PropertyProvider(x.text))
      mode         = (globalParam \ "inheritMode").headOption.flatMap(x => InheritMode.parseString(x.text).toOption)
      visibility   = (globalParam \ "visibility").headOption
                       .flatMap(x => Visibility.withNameInsensitiveOption(x.text))
                       .getOrElse(Visibility.default)
      // TODO: no version in param for now
      g           <- GlobalParameter.parse(name, GitVersion.DEFAULT_REV, value, mode, description, provider, visibility).toBox
    } yield {
      g // TODO: no version in param for now

    }).toPureResult
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
    // at that point, any error is a grave error: we don't want to
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
        else Failure(s"Entry type is not a ${XML_TAG_API_ACCOUNT}: ${entry}")
      }
      _              <- TestFileFormat(apiAccount)
      id             <- (apiAccount \ "id").headOption.map(_.text) ?~! (s"Missing attribute 'id' in entry type API Account : ${entry}")
      name           <- (apiAccount \ "name").headOption.map(_.text) ?~! (s"Missing attribute 'name' in entry type API Account : ${entry}")
      token          <-
        (apiAccount \ "token").headOption.map(_.text) ?~! (s"Missing attribute 'token' in entry type API Account : ${entry}")
      description    <- (apiAccount \ "description").headOption.map(
                          _.text
                        ) ?~! (s"Missing attribute 'description' in entry type API Account : ${entry}")
      isEnabled      <- (apiAccount \ "isEnabled").headOption.flatMap(s =>
                          tryo(s.text.toBoolean)
                        ) ?~! (s"Missing attribute 'isEnabled' in entry type API Account : ${entry}")
      creationDate   <- (apiAccount \ "creationDate").headOption.flatMap(s =>
                          tryo(dateFormatter.parseDateTime(s.text))
                        ) ?~! (s"Missing attribute 'creationDate' in entry type API Account : ${entry}")
      tokenGenDate   <- (apiAccount \ "tokenGenerationDate").headOption.flatMap(s =>
                          tryo(dateFormatter.parseDateTime(s.text))
                        ) ?~! (s"Missing attribute 'tokenGenerationDate' in entry type API Account : ${entry}")
      expirationDate <- (apiAccount \ "expirationDate").headOption match {
                          case None    => Full(None)
                          case Some(s) =>
                            tryo {
                              Some(dateFormatter.parseDateTime(s.text))
                            } ?~! (s"Bad date format for field 'expirationDate' in entry type API Account : ${entry}")
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
                          // format: off
                          case Some(<acl>{xml @ _*}</acl>) if (xml.nonEmpty) =>
                          // format: on
                            unserAcl(xml.head)
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
        Some(ApiTokenHash.fromHashValue(token)),
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
      secret      <- {
        if (entry.label == XML_TAG_SECRET) Full(entry)
        else Failure(s"Entry type is not a ${XML_TAG_SECRET}: ${entry}")
      }
      _           <- TestFileFormat(secret)
      name        <- (secret \ "name").headOption.map(_.text) ?~! s"Missing attribute 'name' in entry type secret : ${entry}"
      description <-
        (secret \ "description").headOption.map(_.text) ?~! s"Missing attribute 'description' in entry type secret : ${entry}"
    } yield {
      Secret(name, "", description)
    }
  }
}

object XmlUtils {

  case class XmlEntryType(value: String) extends AnyVal

  def missingErrMsg(attributeName: String, entryType: XmlEntryType, entry: XNode): String = {
    s"Missing attribute '${attributeName}' in entry type ${entryType} : ${entry}"
  }

  def missingErrMsgRec(attributeName: String, entryType: XmlEntryType, entry: XNode): String = {
    s"Missing attribute '${attributeName}' in sequence and sub-sequences of entry type ${entryType} : ${entry}"
  }

  def getPureAttribute[A](attributeOpt: Option[A], attributeName: String, entry: XNode)(implicit
      entryType: XmlEntryType
  ): PureResult[A] = {
    attributeOpt.notOptionalPure(s"Missing attribute '${attributeName}' in entry type ${entryType} : ${entry}")
  }

  def getPureAttributeTwo(entry: XNode, attribute1: String, attribute2: String)(implicit
      entryType: XmlEntryType
  ): PureResult[XNode] = {
    (entry \ attribute1 \ attribute2).headOption
      .notOptionalPure(
        s"Missing children that have attribute '${attribute2}' in children with attribute" +
        s"'${attribute1}' in entry type ${entryType} : ${entry}"
      )
  }

  def idAttributePure(entry: XNode, attributeName: String)(implicit entryType: XmlEntryType): PureResult[XNode] = {
    (entry \ attributeName).headOption
      .notOptionalPure(missingErrMsg(attributeName, entryType, entry))
  }

  def mapAttributePure[A](
      entry:         XNode,
      attributeName: String,
      f:             XNode => A
  )(implicit entryType: XmlEntryType): PureResult[A] = {
    (entry \ attributeName).headOption
      .map(f)
      .notOptionalPure(missingErrMsg(attributeName, entryType, entry))
  }

  def flatMapAttributePure[A](
      entry:         XNode,
      attributeName: String,
      f:             XNode => Option[A]
  )(implicit entryType: XmlEntryType): PureResult[A] = {
    (entry \ attributeName).headOption
      .flatMap(f)
      .notOptionalPure(missingErrMsg(attributeName, entryType, entry))
  }

  def idAttributePureRec(
      node:          XNode,
      attributeName: String,
      entry:         XNode
  )(implicit entryType: XmlEntryType): PureResult[XNode] = {
    (node \\ attributeName).headOption
      .notOptionalPure(missingErrMsgRec(attributeName, entryType, entry))
  }

  def mapAttributePureRec[A](
      node:          XNode,
      attributeName: String,
      entry:         XNode,
      f:             XNode => A
  )(implicit entryType: XmlEntryType): PureResult[A] = {
    (node \\ attributeName).headOption
      .map(f)
      .notOptionalPure(missingErrMsgRec(attributeName, entryType, entry))
  }

  def flatMapAttributePureRec[A](
      node:          XNode,
      attributeName: String,
      entry:         XNode,
      f:             XNode => Option[A]
  )(implicit entryType: XmlEntryType): PureResult[A] = {
    (node \\ attributeName).headOption
      .flatMap(f)
      .notOptionalPure(missingErrMsgRec(attributeName, entryType, entry))
  }

}
