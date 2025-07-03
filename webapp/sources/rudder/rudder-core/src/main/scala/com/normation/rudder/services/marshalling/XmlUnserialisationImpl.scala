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

import cats.implicits.*
import com.normation.GitVersion
import com.normation.GitVersion.ParseRev
import com.normation.box.*
import com.normation.cfclerk.domain.TechniqueId
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.domain.TechniqueVersion
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.errors.AccumulateErrors
import com.normation.errors.Inconsistency
import com.normation.errors.OptionToPureResult
import com.normation.errors.PureResult
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
import com.normation.rudder.facts.nodes.NodeSecurityContext
import com.normation.rudder.rule.category.RuleCategory
import com.normation.rudder.rule.category.RuleCategoryId
import com.normation.rudder.services.queries.CmdbQueryParser
import com.normation.utils.Control.traverse
import net.liftweb.common.*
import net.liftweb.common.Box.*
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.format.ISODateTimeFormat
import scala.util.Failure as Catch
import scala.util.Success
import scala.util.Try
import scala.xml.Node as XNode
import scala.xml.NodeSeq
import zio.json.*

final case class XmlUnserializerImpl(
    rule:        RuleUnserialisation,
    directive:   DirectiveUnserialisation,
    group:       NodeGroupUnserialisation,
    globalParam: GlobalParameterUnserialisation,
    ruleCat:     RuleCategoryUnserialisation
) extends XmlUnserializer

class DirectiveUnserialisationImpl extends DirectiveUnserialisation {

  override def parseSectionVal(xml: NodeSeq): PureResult[SectionVal] = {
    def recValParseSection(elt: XNode): PureResult[(String, SectionVal)] = {
      if (elt.label != "section") Left(Inconsistency(s"Bad XML, expected a <section> but got: ${elt}"))
      else {
        for {
          name     <- (elt \ "@name").headOption.notOptionalPure(s"Missing required attribute 'name' for <section>: ${elt}")
          // Seq( (var name , var value ) )
          vars     <- (elt \ "var").accumulatePure { xmlVar =>
                        for {
                          n <-
                            (xmlVar \ "@name").headOption.notOptionalPure(s"Missing required attribute 'name' for <var>: ${xmlVar}")
                        } yield {
                          (n.text, xmlVar.text)
                        }
                      }
          // Seq ( SectionVal )
          sections <- (elt \ "section").accumulatePure(sectionXml => recValParseSection(sectionXml))
        } yield {
          val s = sections.groupBy { case (n, s) => n }.map { case (n, seq) => (n, seq.map { case (_, section) => section }) }
          (name.text, SectionVal(s, vars.toMap))
        }
      }
    }

    for {
      root         <- ((xml \ "section").toList match {
                        case Nil         => Left(Inconsistency(s"Missing required tag <section> in: ${xml}"))
                        case node :: Nil => Right(node)
                        case x           => Left(Inconsistency(s"Found several <section> tag in XML, but only one root section is allowed: ${xml}"))
                      }): PureResult[XNode]
      parseSection <- recValParseSection(root)
    } yield {
      parseSection._2
    }
  }

  override def unserialise(xml: XNode): PureResult[(TechniqueName, Directive, SectionVal)] = {

    import XmlUtils.*
    implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_DIRECTIVE)

    (for {
      directive        <- checkEntry(xml)
      _                <- TestFileFormat.check(directive)
      sid              <- getAndTransformChild(directive, "id", _.text)
      id               <- DirectiveId.parse(sid).leftMap(errMsg => Inconsistency(errMsg))
      ptName           <- getAndTransformChild(directive, "techniqueName", _.text)
      name             <- getAndTransformChild(directive, "displayName", _.text.trim)
      techniqueVersion <- getChild(directive, "techniqueVersion")
                            .flatMap(x => TechniqueVersion.parse(x.text).leftMap(errMsg => Inconsistency(errMsg)))
      sectionVal       <- parseSectionVal(directive)
      shortDescription <- getAndTransformChild(directive, "shortDescription", _.text)
      longDescription  <- getAndTransformChild(directive, "longDescription", _.text)
      isEnabled        <- getAndParseChild(directive, "isEnabled", s => s.text.toBooleanOption)
      priority         <- getAndParseChild(directive, "priority", s => s.text.toIntOption)
      isSystem         <- getAndParseChild(directive, "isSystem", s => s.text.toBooleanOption)
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
    }).chainError(s"${entryType} unserialisation failed")
  }
}

class NodeGroupCategoryUnserialisationImpl extends NodeGroupCategoryUnserialisation {

  import XmlUtils.*
  implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_NODE_GROUP_CATEGORY)

  def unserialise(entry: XNode): PureResult[NodeGroupCategory] = {

    (for {
      category    <- checkEntry(entry)
      _           <- TestFileFormat.check(category)
      id          <- getAndTransformChild(category, "id", _.text)
      name        <- getAndTransformChild(category, "dispayName", _.text.trim)
      description <- getAndTransformChild(category, "description", _.text)
      isSystem    <- getAndParseChild(category, "isSystem", _.text.toBooleanOption)
    } yield {
      NodeGroupCategory(
        id = NodeGroupCategoryId(id),
        name = name,
        description = description,
        items = Nil,
        children = Nil,
        isSystem = isSystem
      )
    }).chainError(s"${entryType} unserialisation failed")
  }
}

class NodeGroupUnserialisationImpl(
    cmdbQueryParser: CmdbQueryParser
) extends NodeGroupUnserialisation {

  import XmlUtils.*
  implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_NODE_GROUP)

  def unserialise(entry: XNode): PureResult[NodeGroup] = {
    (for {
      group       <- checkEntry(entry)
      _           <- TestFileFormat.check(group)
      sid         <- getAndTransformChild(group, "id", _.text)
      id          <- NodeGroupId.parse(sid).leftMap(errMsg => Inconsistency(errMsg))
      name        <- getAndTransformChild(group, "displayName", _.text.trim)
      description <- getAndTransformChild(group, "description", _.text)
      query       <- (group \ "query").headOption match {
                       case None    => Right(None)
                       case Some(s) =>
                         if (s.text.isEmpty) Right(None)
                         else cmdbQueryParser.applyPure(s.text).map(Some(_))
                     }
      isDynamic   <- getAndParseChild(group, "isDynamic", s => s.text.toBooleanOption)
      serverList   = if (isDynamic) {
                       Set[NodeId]()
                     } else {
                       (group \ "nodeIds" \ "id").map(n => NodeId(n.text)).toSet
                     }
      isEnabled   <- getAndParseChild(group, "isEnabled", s => s.text.toBooleanOption)
      isSystem    <- getAndParseChild(group, "isSystem", s => s.text.toBooleanOption)
      properties  <- (group \ "properties" \ "property").toList.accumulatePure { property =>
                       val name = (property \\ "name").text.trim
                       if (name.trim.isEmpty) {
                         Left(Inconsistency(s"Found unexpected xml under <properties> tag (name is blank): $property"))
                       } else {
                         GroupProperty
                           .parse(
                             name = (property \\ "name").text.trim,
                             rev = ParseRev((property \\ "revision").text.trim),
                             value = StringEscapeUtils.unescapeXml((property \\ "value").text.trim),
                             mode =
                               (property \\ "inheritMode").headOption.flatMap(p => InheritMode.parseString(p.text.trim).toOption),
                             provider = (property \\ "provider").headOption.map(p => PropertyProvider(p.text.trim))
                           )
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
    }).chainError(s"${entryType} unserialisation failed")
  }
}

class RuleUnserialisationImpl extends RuleUnserialisation {

  import XmlUtils.*
  implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_RULE)

  def unserialise(entry: XNode): PureResult[Rule] = {
    (for {
      rule             <- checkEntry(entry)
      _                <- TestFileFormat.check(rule)
      sid              <- getAndTransformChild(rule, "id", _.text)
      id               <- RuleId.parse(sid).leftMap(errMsg => Inconsistency(errMsg))
      category         <- getAndTransformChild(rule, "category", n => RuleCategoryId(n.text))
      name             <- getAndTransformChild(rule, "displayName", _.text.trim)
      shortDescription <- getAndTransformChild(rule, "shortDescription", _.text)
      longDescription  <- getAndTransformChild(rule, "longDescription", _.text)
      isEnabled        <- getAndParseChild(rule, "isEnabled", s => s.text.toBooleanOption)
      isSystem         <- getAndParseChild(rule, "isSystem", s => s.text.toBooleanOption)
      targets          <- (rule \ "targets" \ "target").accumulatePure { t =>
                            RuleTarget.unser(t.text).notOptionalPure(s"Invalid attribute in 'target' entry: ${entry}")
                          }
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
    }).chainError(s"${entryType} unserialisation failed")
  }
}

class RuleCategoryUnserialisationImpl extends RuleCategoryUnserialisation {

  import XmlUtils.*
  implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_RULE_CATEGORY)

  def unserialise(entry: XNode): PureResult[RuleCategory] = {
    (for {
      category    <- checkEntry(entry)
      _           <- TestFileFormat.check(category)
      id          <- getAndTransformChild(category, "id", _.text)
      name        <- getAndTransformChild(category, "displayName", _.text.trim)
      description <- getAndTransformChild(category, "description", _.text)
      isSystem    <- getAndParseChild(category, "isSystem", s => s.text.toBooleanOption)
    } yield {
      RuleCategory(
        id = RuleCategoryId(id),
        name = name,
        description = description,
        childs = Nil,
        isSystem = isSystem
      )
    }).chainError(s"${entryType} unserialisation failed")
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

    import XmlUtils.*
    val changeRequestEntryType = XmlEntryType(XML_TAG_CHANGE_REQUEST)

    def unserialiseNodeGroupChange(changeRequest: XNode): PureResult[Map[NodeGroupId, NodeGroupChanges]] = {

      for {
        groupsNode <- getChild(changeRequest, "groups")(changeRequestEntryType)
      } yield {

        implicit val entryType: XmlEntryType = XmlEntryType("changeRequest group changes")

        (groupsNode \ "group").accumulatePure { group =>
          (for {

            sid          <- getAttribute(group, "id")
            nodeGroupId  <- NodeGroupId.parse(sid).leftMap(errMsg => Inconsistency(errMsg))
            initialNode  <- getChild(group, "initialState")
            initialState <- (initialNode \ "nodeGroup").headOption match {
                              case Some(initialState) =>
                                nodeGroupUnserialiser.unserialise(initialState) match {
                                  case Right(group) => Right(Some(group))
                                  case Left(_)      => Left(Inconsistency("could not unserialize group"))
                                }
                              case None               => Right(None)
                            }
            changeNode   <- getGrandchild(group, "firstChange", "change")
            actor        <- getAndTransformChildRec(changeNode, "actor", actor => EventActor(actor.text))
            date         <- getAndTransformChildRec(
                              changeNode,
                              "date",
                              date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text)
                            )
            reason        = (changeNode \\ "reason").headOption.map(_.text)
            diff         <- getAndParseChildRec(changeNode, "diff", _.attribute("action").headOption.map(_.text))
            diffGroup    <- getChildRec(changeNode, "nodeGroup")
            changeGroup  <- nodeGroupUnserialiser.unserialise(diffGroup)
            change       <- diff match {
                              case "add"      => Right(AddNodeGroupDiff(changeGroup))
                              case "delete"   => Right(DeleteNodeGroupDiff(changeGroup))
                              case "modifyTo" => Right(ModifyToNodeGroupDiff(changeGroup))
                              case _          => Left(Inconsistency("should not happen"))
                            }

          } yield {
            val groupChange = NodeGroupChange(initialState, NodeGroupChangeItem(actor, date, reason, change), Seq())

            (nodeGroupId -> NodeGroupChanges(groupChange, Seq()))
          }).chainError(s"${entryType} unserialisation failed")

        } match {
          case Left(err)  =>
            logger.error(err.fullMsg)
            Map.empty
          case Right(res) => res.toMap
        }
      }
    }

    def unserialiseDirectiveChange(changeRequest: XNode): PureResult[Map[DirectiveId, DirectiveChanges]] = {

      for {
        directivesNode <- getChild(changeRequest, "directives")(changeRequestEntryType)
      } yield {

        implicit val entryType: XmlEntryType = XmlEntryType("changeRequest directive changes")

        (directivesNode \ "directive").iterator.flatMap { directive =>
          val res = (for {

            directiveId                        <- getAttribute(directive, "id")
                                                    .flatMap(id => DirectiveId.parse(id).leftMap(errMsg => Inconsistency(errMsg)))
            initialNode                        <- getChild(directive, "initialState")
            initialState                       <- (initialNode \\ "directive").headOption match {
                                                    case Some(initialState) =>
                                                      directiveUnserialiser.unserialise(initialState) match {
                                                        case Right((techniqueName, directive, _)) => Right(Some((techniqueName, directive)))
                                                        case Left(_)                              => Left(Inconsistency("could not unserialize directive"))
                                                      }
                                                    case None               => Right(None)
                                                  }
            changeNode                         <- getGrandchild(directive, "firstChange", "change")
            actor                              <- getAndTransformChildRec(changeNode, "actor", actor => EventActor(actor.text))
            date                               <- getAndTransformChildRec(
                                                    changeNode,
                                                    "date",
                                                    date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text)
                                                  )
            reason                              = (changeNode \\ "reason").headOption.map(_.text)
            diff                               <- getAndParseChildRec(changeNode, "diff", _.attribute("action").headOption.map(_.text))
            diffDirective                      <- getChildRec(changeNode, "directive")
            directive                          <- directiveUnserialiser.unserialise(diffDirective)
            (techniqueName, changeDirective, _) = directive
            change                             <- {
              diff match {
                case "add"      => Right(AddDirectiveDiff(techniqueName, changeDirective))
                case "delete"   => Right(DeleteDirectiveDiff(techniqueName, changeDirective))
                case "modifyTo" =>
                  getChildRec(changeNode, "rootSection")
                    .flatMap(rsXml => {
                      val techId = TechniqueId(techniqueName, changeDirective.techniqueVersion)
                      sectionSpecUnserialiser
                        .parseSectionsInPolicy(rsXml, techId, techniqueName.value)
                        .map(rootSection => ModifyToDirectiveDiff(techniqueName, changeDirective, Some(rootSection)))
                    })
                case _          => Left(Inconsistency("should not happen"))
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
          }).chainError(s"${entryType} unserialisation failed")

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

    def unserialiseRuleChange(changeRequest: XNode): PureResult[Map[RuleId, RuleChanges]] = {

      for {
        rulesNode <- getChild(changeRequest, "rules")(changeRequestEntryType)
      } yield {

        implicit val entryType: XmlEntryType = XmlEntryType("changeRequest rule changes")

        (rulesNode \ "rule").iterator.flatMap { rule =>
          val res = (for {
            ruleId       <- getAttribute(rule, "id")
                              .flatMap(id => RuleId.parse(id).leftMap(errMsg => Inconsistency(errMsg)))
            initialRule  <- getChild(rule, "initialState")
            initialState <- (initialRule \ "rule").headOption match {
                              case Some(initialState) =>
                                ruleUnserialiser.unserialise(initialState) match {
                                  case Right(rule) => Right(Some(rule))
                                  case Left(_)     => Left(Inconsistency("could not unserialize rule"))
                                }
                              case None               => Right(None)
                            }
            changeRule   <- getGrandchild(rule, "firstChange", "change")
            actor        <- getAndTransformChildRec(changeRule, "actor", actor => EventActor(actor.text))
            date         <- getAndTransformChildRec(
                              changeRule,
                              "date",
                              date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text)
                            )
            reason        = (changeRule \\ "reason").headOption.map(_.text)
            diff         <- getAndParseChildRec(changeRule, "diff", _.attribute("action").headOption.map(_.text))
            diffRule     <- getChildRec(changeRule, "rule")
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
          }).chainError(s"${entryType} unserialisation failed")

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

    def unserialiseGlobalParameterChange(changeRequest: XNode): PureResult[Map[String, GlobalParameterChanges]] = {

      for {
        paramsNode <- getChild(changeRequest, "globalParameters")(changeRequestEntryType)
      } yield {

        implicit val entryType: XmlEntryType = XmlEntryType("globalParameters Global Parameter changes")

        (paramsNode \ "globalParameter").iterator.flatMap { param =>
          val res = (for {
            paramName    <- getAttribute(param, "name")
            initialParam <- getChild(param, "initialState")
            initialState <- (initialParam \ "globalParameter").headOption match {
                              case Some(initialState) =>
                                globalParamUnserialiser.unserialise(initialState) match {
                                  case Right(param) => Right(Some(param))
                                  case Left(_)      => Left(Inconsistency("could not unserialize global parameter"))
                                }
                              case None               => Right(None)
                            }
            changeParam  <- getGrandchild(param, "firstChange", "change")
            actor        <- getAndTransformChildRec(changeParam, "actor", actor => EventActor(actor.text))
            date         <- getAndTransformChildRec(
                              changeParam,
                              "date",
                              date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text)
                            )
            reason        = (changeParam \\ "reason").headOption.map(_.text)
            diff         <- getAndParseChildRec(changeParam, "diff", _.attribute("action").headOption.map(_.text))
            diffParam    <- getChildRec(changeParam, "globalParameter")
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
          }).chainError(s"${entryType} unserialisation failed")

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

    (for {
      changeRequest <- checkEntry(xml)(changeRequestEntryType)
      _             <- TestFileFormat.check(changeRequest)
      groups        <- unserialiseNodeGroupChange(changeRequest)
      directives    <- {
        Try {
          unserialiseDirectiveChange(changeRequest)
        } match {
          case Success(change) => change
          case Catch(e)        =>
            Left(Inconsistency(s"Could not deserialize directives changes cause ${e.getMessage()}"))
        }
      }
      rules         <- unserialiseRuleChange(changeRequest)
      params        <- unserialiseGlobalParameterChange(changeRequest)
    } yield {
      (directives, groups, rules, params)
    }).chainError(s"${changeRequestEntryType} unserialisation failed")
  }

}

class GlobalParameterUnserialisationImpl extends GlobalParameterUnserialisation {

  import XmlUtils.*
  implicit val entryType: XmlEntryType = XmlEntryType(XML_TAG_GLOBAL_PARAMETER)

  def unserialise(entry: XNode): PureResult[GlobalParameter] = {
    (for {
      globalParam <- checkEntry(entry)
      _           <- TestFileFormat.check(globalParam)
      name        <- getAndTransformChild(globalParam, "name", _.text)
      value       <- getAndTransformChild(globalParam, "value", _.text)
      description <- getAndTransformChild(globalParam, "description", _.text)
      provider     = (globalParam \ "provider").headOption.map(x => PropertyProvider(x.text))
      mode         = (globalParam \ "inheritMode").headOption.flatMap(x => InheritMode.parseString(x.text).toOption)
      visibility   = (globalParam \ "visibility").headOption
                       .flatMap(x => Visibility.withNameInsensitiveOption(x.text))
                       .getOrElse(Visibility.default)
      // TODO: no version in param for now
      g           <- GlobalParameter.parse(name, GitVersion.DEFAULT_REV, value, mode, description, provider, visibility)
    } yield {
      g // TODO: no version in param for now
    }).chainError(s"${entryType} unserialisation failed")
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

    def parse(s: String): Box[List[HttpAction]] = {
      (s.split(",").map(_.trim).toList.filter(_.nonEmpty).traverse(HttpAction.parse)).toBox
    }

    val authzs = traverse(entry \\ "authz") { e =>
      for {
        pathStr <- e \@ "path" match {
                     case "" => Failure("Missing required attribute 'path' for element 'authz'")
                     case s  => Full(s)
                   }
        path    <- AclPath.parse(pathStr).fold(Failure(_), Full(_))
        actions <- e \@ "actions" match {
                     case "" => // check for old "action" attribute
                       e \@ "action" match {
                         case "" =>
                           Failure("Missing required attribute 'actions' for element 'authz'")
                         case s  => parse(s)
                       }
                     case s  => parse(s)
                   }
      } yield {
        ApiAclElement(path, actions.toSet)
      }
    }

    authzs.map(acl => {
      ApiAuthorization.ACL(
        acl
          .groupMapReduce(_.path)(identity) { case (acl1, acl2) => ApiAclElement(acl1.path, acl1.actions.union(acl2.actions)) }
          .values
          .toList
          .sortBy(_.path.value)
      )
    })
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
                            // we are most likely in a case where API ACL weren't implemented,
                            // because the event was saved < Rudder 4.3. Use a "nil" ACL
                            Full(ApiAuthorization.None)

                          case Some(x) if x.text == ApiAuthorizationKind.RO.name =>
                            Full(ApiAuthorization.RO)
                          case Some(x) if x.text == ApiAuthorizationKind.RW.name =>
                            Full(ApiAuthorization.RW)
                          case Some(node) if (node \ "acl").nonEmpty             =>
                            unserAcl((node \ "acl").head)
                          case _                                                 =>
                            Full(ApiAuthorization.None)
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

/**
 * Util class that offers methods to look up a child node or attribute in a given node's direct children or full tree
 * of descendants, and handles the related errors.
 */
object XmlUtils {

  /* The entry type (i.e. XML node label) of the node(s) that are passed as argument.
     It is used in case of an error when a child node or label is missing from the given node. */
  case class XmlEntryType(value: String) extends AnyVal

  private def missingChildErrMsg(label: String, entryType: XmlEntryType, entry: XNode): String = {
    s"Missing child '${label}' in entry type ${entryType} : ${entry}"
  }

  private def missingChildErrMsgRec(label: String, entryType: XmlEntryType, entry: XNode): String = {
    s"Missing child '${label}' in sequence and sub-sequences of entry type ${entryType} : ${entry}"
  }

  private def missingOrBadChildErrMsg(label: String, entryType: XmlEntryType, entry: XNode): String = {
    s"Missing or bad child '${label}' in entry type ${entryType} : ${entry}"
  }

  private def missingOrBadChildErrMsgRec(label: String, entryType: XmlEntryType, entry: XNode): String = {
    s"Missing or bad child '${label}' in sequence and sub-sequences of entry type ${entryType} : ${entry}"
  }

  private def missingAttributeErrMsg(attributeName: String, entryType: XmlEntryType, entry: XNode): String = {
    s"Missing attribute '${attributeName}' in entry type ${entryType} : ${entry}"
  }

  def checkEntry(entry: XNode)(implicit entryType: XmlEntryType): PureResult[XNode] = {
    if (entry.label == entryType.value) Right(entry)
    else Left(Inconsistency(s"Entry type is not a ${entryType.value}: ${entry}"))
  }

  def getAttribute(entry: XNode, attributeName: String)(implicit entryType: XmlEntryType): PureResult[String] = {
    entry.attribute(attributeName).map(id => id.text).notOptionalPure(missingAttributeErrMsg(attributeName, entryType, entry))
  }

  def getGrandchild(entry: XNode, label1: String, label2: String)(implicit entryType: XmlEntryType): PureResult[XNode] = {
    (entry \ label1 \ label2).headOption
      .notOptionalPure(s"Missing child '${label2}' in child '${label1}' in entry type ${entryType} : ${entry}")
  }

  /**
   * Get the first child node of the given 'entry' node that has the given label.
   */
  def getChild(entry: XNode, label: String)(implicit entryType: XmlEntryType): PureResult[XNode] = {
    (entry \ label).headOption
      .notOptionalPure(missingChildErrMsg(label, entryType, entry))
  }

  /**
   * Get the first child node of the given 'entry' node that has the given label.
   * If it exists, apply the given transformation function f on the child node.
   */
  def getAndTransformChild[A](
      entry: XNode,
      label: String,
      f:     XNode => A
  )(implicit entryType: XmlEntryType): PureResult[A] = {
    (entry \ label).headOption
      .notOptionalPure(missingChildErrMsg(label, entryType, entry))
      .map(f)
  }

  /**
   * Get the first child node of the given 'entry' node that has the given label.
   * If it exists, apply the given parsing function f on the child node.
   * The parsing may fail, in which case this function will return a Left(RudderError) value.
   */
  def getAndParseChild[A](
      entry: XNode,
      label: String,
      f:     XNode => Option[A]
  )(implicit entryType: XmlEntryType): PureResult[A] = {
    (entry \ label).headOption
      .flatMap(f)
      .notOptionalPure(missingOrBadChildErrMsg(label, entryType, entry))
  }

  /**
   * Get the first descendant of the given 'entry' node that has the given label.
   */
  def getChildRec(
      entry: XNode,
      label: String
  )(implicit entryType: XmlEntryType): PureResult[XNode] = {
    (entry \\ label).headOption
      .notOptionalPure(missingChildErrMsgRec(label, entryType, entry))
  }

  /**
   * Get the first descendant of the given 'entry' node that has the given label.
   * If it exists, apply the given transformation function f on the node.
   */
  def getAndTransformChildRec[A](
      entry: XNode,
      label: String,
      f:     XNode => A
  )(implicit entryType: XmlEntryType): PureResult[A] = {
    (entry \\ label).headOption
      .map(f)
      .notOptionalPure(missingChildErrMsgRec(label, entryType, entry))
  }

  /**
   * Get the first descendant of the given 'entry' node that has the given label.
   * If it exists, apply the given parsing function f on the node.
   * The parsing may fail, in which case this function will return a Left(RudderError) value.
   */
  def getAndParseChildRec[A](
      entry: XNode,
      label: String,
      f:     XNode => Option[A]
  )(implicit entryType: XmlEntryType): PureResult[A] = {
    (entry \\ label).headOption
      .flatMap(f)
      .notOptionalPure(missingOrBadChildErrMsgRec(label, entryType, entry))
  }

}
