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

import scala.xml.NodeSeq
import net.liftweb.common._
import net.liftweb.common.Box._
import com.normation.rudder.services.queries.CmdbQueryParser
import net.liftweb.common.Failure
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.policies.Directive
import com.normation.cfclerk.domain.TechniqueName
import com.normation.rudder.domain.policies.SectionVal
import scala.xml.{Node => XNode}
import com.normation.utils.Control.sequence
import com.normation.cfclerk.domain.TechniqueVersion
import net.liftweb.util.Helpers.tryo
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleTarget
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.ActiveTechniqueCategory
import com.normation.rudder.domain.policies.ActiveTechniqueCategoryId
import com.normation.rudder.domain.policies.ActiveTechnique
import com.normation.rudder.domain.policies.ActiveTechniqueId
import org.joda.time.format.ISODateTimeFormat
import com.normation.rudder.batch.SuccessStatus
import com.normation.rudder.batch.ErrorStatus
import com.normation.rudder.batch.NoStatus
import com.normation.rudder.batch.CurrentDeploymentStatus
import com.normation.rudder.domain.nodes.NodeGroupCategory
import com.normation.rudder.domain.nodes.NodeGroupCategoryId
import scala.xml.Node
import com.normation.rudder.domain.Constants._
import com.normation.rudder.domain.workflows.DirectiveChange
import com.normation.rudder.domain.workflows.NodeGroupChange
import com.normation.rudder.domain.workflows.DirectiveChanges
import com.normation.rudder.domain.workflows.NodeGroupChanges
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.workflows.NodeGroupChanges
import com.normation.rudder.domain.nodes._
import com.normation.rudder.domain.workflows.NodeGroupChange
import com.normation.rudder.domain.workflows.NodeGroupChangeItem
import com.normation.eventlog.EventActor
import com.normation.rudder.domain.policies.DirectiveId
import com.normation.rudder.domain.workflows.DirectiveChanges
import com.normation.rudder.domain.policies._
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.services.TechniqueRepository
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.domain.workflows.DirectiveChangeItem
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.cfclerk.domain.TechniqueId
import com.normation.rudder.domain.workflows.RuleChange
import com.normation.rudder.domain.workflows.RuleChangeItem
import com.normation.rudder.domain.workflows.RuleChanges
import scala.util.Try
import scala.util.Success
import scala.util.{Failure => Catch}
import com.normation.rudder.domain.logger.ApplicationLogger


class DirectiveUnserialisationImpl extends DirectiveUnserialisation {

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

  override def unserialise(xml:XNode) : Box[(TechniqueName, Directive, SectionVal)] = {
    for {
      directive             <- {
                                 if(xml.label == XML_TAG_DIRECTIVE) Full(xml)
                                 else Failure("Entry type is not a <%s>: %s".format(XML_TAG_DIRECTIVE, xml))
                               }
      fileFormatOk          <- TestFileFormat(directive)
      id                    <- (directive \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type directive : " + xml)
      ptName                <- (directive \ "techniqueName").headOption.map( _.text ) ?~! ("Missing attribute 'techniqueName' in entry type directive : " + xml)
      name                  <- (directive \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type directive : " + xml)
      techniqueVersion      <- (directive \ "techniqueVersion").headOption.map( x => TechniqueVersion(x.text) ) ?~! ("Missing attribute 'techniqueVersion' in entry type directive : " + xml)
      sectionVal            <- parseSectionVal(directive)
      shortDescription      <- (directive \ "shortDescription").headOption.map( _.text ) ?~! ("Missing attribute 'shortDescription' in entry type directive : " + xml)
      longDescription       <- (directive \ "longDescription").headOption.map( _.text ) ?~! ("Missing attribute 'longDescription' in entry type directive : " + xml)
      isEnabled             <- (directive \ "isEnabled").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isEnabled' in entry type directive : " + xml)
      priority              <- (directive \ "priority").headOption.flatMap(s => tryo { s.text.toInt } ) ?~! ("Missing or bad attribute 'priority' in entry type directive : " + xml)
      isSystem              <- (directive \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type directive : " + xml)
      directiveIds     =  (directive \ "directiveIds" \ "id" ).map( n => DirectiveId( n.text ) ).toSet
    } yield {
      (
          TechniqueName(ptName)
        , Directive(
              id = DirectiveId(id)
            , name = name
            , techniqueVersion = techniqueVersion
            , parameters = SectionVal.toMapVariables(sectionVal)
            , shortDescription = shortDescription
            , longDescription = longDescription
            , priority = priority
            , isEnabled = isEnabled
            , isSystem = isSystem
          )
        , sectionVal
      )
    }
  }
}


class NodeGroupCategoryUnserialisationImpl extends NodeGroupCategoryUnserialisation {

  def unserialise(entry:XNode): Box[NodeGroupCategory] = {
    for {
      category         <- {
                            if(entry.label ==  XML_TAG_NODE_GROUP_CATEGORY) Full(entry)
                            else Failure("Entry type is not a <%s>: %s".format(XML_TAG_NODE_GROUP_CATEGORY, entry))
                          }
      fileFormatOk     <- TestFileFormat(category)
      id               <- (category \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type groupLibraryCategory : " + entry)
      name             <- (category \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type groupLibraryCategory : " + entry)
      description      <- (category \ "description").headOption.map( _.text ) ?~! ("Missing attribute 'description' in entry type groupLibraryCategory : " + entry)
      isSystem         <- (category \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type groupLibraryCategory : " + entry)
    } yield {
      NodeGroupCategory(
          id = NodeGroupCategoryId(id)
        , name = name
        , description = description
        , items = Nil
        , children = Nil
        , isSystem = isSystem
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
                           if(entry.label == XML_TAG_NODE_GROUP) Full(entry)
                           else Failure("Entry type is not a <%s>: %s".format(XML_TAG_NODE_GROUP, entry))
                         }
      fileFormatOk    <- TestFileFormat(group)
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
      isEnabled       <- (group \ "isEnabled").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isEnabled' in entry type nodeGroup : " + entry)
      isSystem        <- (group \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type nodeGroup : " + entry)
    } yield {
      NodeGroup(
          id = NodeGroupId(id)
        , name = name
        , description = description
        , query = query
        , isDynamic = isDynamic
        , serverList = serverList
        , isEnabled = isEnabled
        , isSystem = isSystem
      )
    }
  }
}


class RuleUnserialisationImpl extends RuleUnserialisation {
  def unserialise(entry:XNode) : Box[Rule] = {
    for {
      rule             <- {
                            if(entry.label ==  XML_TAG_RULE) Full(entry)
                            else Failure("Entry type is not a <%s>: %s".format(XML_TAG_RULE, entry))
                          }
      fileFormatOk     <- TestFileFormat(rule)
      id               <- (rule \ "id").headOption.map( _.text ) ?~!
                          ("Missing attribute 'id' in entry type rule: " + entry)
      name             <- (rule \ "displayName").headOption.map( _.text ) ?~!
                          ("Missing attribute 'displayName' in entry type rule: " + entry)
      serial           <- (rule \ "serial").headOption.flatMap(s => tryo { s.text.toInt } ) ?~!
                          ("Missing or bad attribute 'serial' in entry type rule: " + entry)
      shortDescription <- (rule \ "shortDescription").headOption.map( _.text ) ?~!
                          ("Missing attribute 'shortDescription' in entry type rule: " + entry)
      longDescription  <- (rule \ "longDescription").headOption.map( _.text ) ?~!
                           ("Missing attribute 'longDescription' in entry type rule: " + entry)
      isEnabled        <- (rule \ "isEnabled").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~!
                          ("Missing attribute 'isEnabled' in entry type rule: " + entry)
      isSystem         <- (rule \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~!
                          ("Missing attribute 'isSystem' in entry type rule: " + entry)
      targets          <- sequence((rule \ "targets" \ "target")) { t => RuleTarget.unser(t.text) } ?~!
                          ("Invalid attribute in 'target' entry: " + entry)
      directiveIds     = (rule \ "directiveIds" \ "id" ).map( n => DirectiveId( n.text ) ).toSet
    } yield {
      Rule(
          id = RuleId(id)
        , name = name
        , serial = serial
        , targets = targets.toSet
        , directiveIds = directiveIds
        , shortDescription = shortDescription
        , longDescription = longDescription
        , isEnabledStatus = isEnabled
        , isSystem = isSystem
      )
    }
  }
}

class ActiveTechniqueCategoryUnserialisationImpl extends ActiveTechniqueCategoryUnserialisation {

  def unserialise(entry:XNode): Box[ActiveTechniqueCategory] = {
    for {
      uptc             <- {
                            if(entry.label ==  XML_TAG_ACTIVE_TECHNIQUE_CATEGORY) Full(entry)
                            else Failure("Entry type is not a <%s>: %s".format(XML_TAG_ACTIVE_TECHNIQUE_CATEGORY, entry))
                          }
      fileFormatOk     <- TestFileFormat(uptc)
      id               <- (uptc \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type policyLibraryCategory : " + entry)
      name             <- (uptc \ "displayName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type policyLibraryCategory : " + entry)
      description      <- (uptc \ "description").headOption.map( _.text ) ?~! ("Missing attribute 'description' in entry type policyLibraryCategory : " + entry)
      isSystem         <- (uptc \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type policyLibraryCategory : " + entry)
    } yield {
      ActiveTechniqueCategory(
          id = ActiveTechniqueCategoryId(id)
        , name = name
        , description = description
        , items = Nil
        , children = Nil
        , isSystem = isSystem
      )
    }
  }
}

class ActiveTechniqueUnserialisationImpl extends ActiveTechniqueUnserialisation {

  //we expect acceptation date to be in ISO-8601 format
  private[this] val dateFormatter = ISODateTimeFormat.dateTime

  def unserialise(entry:XNode): Box[ActiveTechnique] = {
    for {
      activeTechnique  <- {
                            if(entry.label ==  XML_TAG_ACTIVE_TECHNIQUE) Full(entry)
                            else Failure("Entry type is not a <%s>: ".format(XML_TAG_ACTIVE_TECHNIQUE, entry))
                          }
      fileFormatOk     <- TestFileFormat(activeTechnique)
      id               <- (activeTechnique \ "id").headOption.map( _.text ) ?~! ("Missing attribute 'id' in entry type policyLibraryTemplate : " + entry)
      ptName           <- (activeTechnique \ "techniqueName").headOption.map( _.text ) ?~! ("Missing attribute 'displayName' in entry type policyLibraryTemplate : " + entry)
      isSystem         <- (activeTechnique \ "isSystem").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isSystem' in entry type policyLibraryTemplate : " + entry)
      isEnabled        <- (activeTechnique \ "isEnabled").headOption.flatMap(s => tryo { s.text.toBoolean } ) ?~! ("Missing attribute 'isEnabled' in entry type policyLibraryTemplate : " + entry)
      acceptationDates <- sequence(activeTechnique \ "versions" \ "version" ) { version =>
                            for {
                              ptVersionName   <- version.attribute("name").map( _.text) ?~! "Missing attribute 'name' for acceptation date in PT '%s' (%s): '%s'".format(ptName, id, version)
                              ptVersion       <- tryo { TechniqueVersion(ptVersionName) }
                              acceptationDate <- tryo { dateFormatter.parseDateTime(version.text) }
                            } yield {
                              (ptVersion, acceptationDate)
                            }
                          }
      acceptationMap  <- {
                            val map = acceptationDates.toMap
                            if(map.size != acceptationDates.size) Failure("There exists a duplicate polity template version in the acceptation date map: " + acceptationDates.mkString("; "))
                            else Full(map)
                          }
    } yield {
      ActiveTechnique(
          id = ActiveTechniqueId(id)
        , techniqueName = TechniqueName(ptName)
        , acceptationDatetimes = acceptationMap
        , directives = Nil
        , isEnabled = isEnabled
        , isSystem = isSystem
      )
    }
  }
}

class DeploymentStatusUnserialisationImpl extends DeploymentStatusUnserialisation {
  def unserialise(entry:XNode) : Box[CurrentDeploymentStatus] = {
    for {
      depStatus        <- {
                            if(entry.label ==  XML_TAG_DEPLOYMENT_STATUS) Full(entry)
                            else Failure("Entry type is not a <%s>: %s".format(XML_TAG_DEPLOYMENT_STATUS, entry))
                          }
      fileFormatOk     <- TestFileFormat(depStatus)
      id               <- (depStatus \ "id").headOption.flatMap(s => tryo {s.text.toLong } ) ?~! ("Missing attribute 'id' in entry type deploymentStatus : " + entry)
      status           <- (depStatus \ "status").headOption.map( _.text ) ?~! ("Missing attribute 'status' in entry type deploymentStatus : " + entry)

      started          <- (depStatus \ "started").headOption.flatMap(s => tryo { ISODateTimeFormat.dateTimeParser.parseDateTime(s.text) } ) ?~! ("Missing or bad attribute 'started' in entry type deploymentStatus : " + entry)
      ended            <- (depStatus \ "ended").headOption.flatMap(s => tryo { ISODateTimeFormat.dateTimeParser.parseDateTime(s.text) } ) ?~! ("Missing or bad attribute 'ended' in entry type deploymentStatus : " + entry)
      errorMessage     <- (depStatus \ "errorMessage").headOption match {
                            case None => Full(None)
                            case Some(s) =>
                              if(s.text.size == 0) Full(None)
                              else Full(Some(s.text))
                  }
    } yield {
      status match {
        case "success" => SuccessStatus(id, started, ended, Map())
        case "failure" => ErrorStatus(id, started, ended, errorMessage.map(x => Failure(x)).getOrElse(Failure("")) )
        case s       => NoStatus
      }
    }
  }
}

/**
 * That trait allow to unserialise change request changes from an XML file.
 *
 */
class ChangeRequestChangesUnserialisationImpl (
    nodeGroupUnserialiser : NodeGroupUnserialisation
  , directiveUnserialiser : DirectiveUnserialisation
  , ruleUnserialiser      : RuleUnserialisation
  , techRepo : TechniqueRepository
  , sectionSpecUnserialiser : SectionSpecParser
) extends ChangeRequestChangesUnserialisation with Loggable {
  def unserialise(xml:XNode): Box[(Box[Map[DirectiveId,DirectiveChanges]],Map[NodeGroupId,NodeGroupChanges],Map[RuleId,RuleChanges])] = {
    def unserialiseNodeGroupChange(changeRequest:XNode): Box[Map[NodeGroupId,NodeGroupChanges]]= {
      (for {
          groupsNode  <- (changeRequest \ "groups").headOption ?~! s"Missing child 'groups' in entry type changeRequest : ${xml}"
      } yield {
        (groupsNode\"group").flatMap{ group =>
          for {
            nodeGroupId  <- group.attribute("id").map(id => NodeGroupId(id.text)) ?~!
                             s"Missing attribute 'id' in entry type changeRequest group changes  : ${group}"
            initialNode  <- (group \ "initialState").headOption
            initialState <- (initialNode \ "nodeGroup").headOption match {
              case Some(initialState) => nodeGroupUnserialiser.unserialise(initialState) match {
                case Full(group) => Full(Some(group))
                case eb : EmptyBox => eb ?~! "could not unserialize group"
              }
              case None => Full(None)
            }

            changeNode   <- (group \ "firstChange" \ "change").headOption
            actor        <- (changeNode \\ "actor").headOption.map(actor => EventActor(actor.text))
            date         <- (changeNode \\ "date").headOption.map(date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text))
            reason       =  (changeNode \\ "reason").headOption.map(_.text)
            diff         <- (changeNode \\ "diff").headOption.flatMap(_.attribute("action").headOption.map(_.text))
            diffGroup <- (changeNode \\ "nodeGroup").headOption
            changeGroup  <- nodeGroupUnserialiser.unserialise(diffGroup)
            change <- diff match {
              case "add" => Full(AddNodeGroupDiff(changeGroup))
              case "delete" => Full(DeleteNodeGroupDiff(changeGroup))
              case "modifyTo" => Full(ModifyToNodeGroupDiff(changeGroup))
              case  _ => Failure("should not happen")
            }
        } yield {
          val groupChange = NodeGroupChange(initialState,NodeGroupChangeItem(actor,date,reason,change),Seq())

          (nodeGroupId -> NodeGroupChanges(groupChange,Seq()))
        } }.toMap
      })
    }

    def unserialiseDirectiveChange(changeRequest:XNode): Box[Map[DirectiveId,DirectiveChanges]]= {
      (for {
          directivesNode  <- (changeRequest \ "directives").headOption ?~! s"Missing child 'directives' in entry type changeRequest : ${xml}"
      } yield {
        (directivesNode\"directive").flatMap{ directive =>
          for {
            directiveId  <- directive.attribute("id").map(id => DirectiveId(id.text)) ?~!
                             s"Missing attribute 'id' in entry type changeRequest directive changes  : ${directive}"
            initialNode  <- (directive \ "initialState").headOption
            initialState <- (initialNode \\ "directive").headOption match {
              case Some(initialState) => directiveUnserialiser.unserialise(initialState) match {
                case Full((techName,directive,_)) => Full(Some((techName,directive)))
                case eb : EmptyBox => eb ?~! "could not unserialize directive"
              }
              case None => Full(None)
            }

            changeNode   <- (directive \ "firstChange" \ "change").headOption
            actor        <- (changeNode \\ "actor").headOption.map(actor => EventActor(actor.text))
            date         <- (changeNode \\ "date").headOption.map(date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text))
            reason       =  (changeNode \\ "reason").headOption.map(_.text)
            diff         <- (changeNode \\ "diff").headOption.flatMap(_.attribute("action").headOption.map(_.text))
            diffDirective <- (changeNode \\ "directive").headOption

            (techniqueName,changeDirective,_)  <- directiveUnserialiser.unserialise(diffDirective)
            change <- { diff match {
              case "add" => Full(AddDirectiveDiff(techniqueName,changeDirective))
              case "delete" => Full(DeleteDirectiveDiff(techniqueName,changeDirective))
              case "modifyTo" => (changeNode \\ "rootSection").headOption match {
                case Some(rsXml) =>
                  val techId = TechniqueId(techniqueName,changeDirective.techniqueVersion)
                  val rootSection = sectionSpecUnserialiser.parseSectionsInPolicy(rsXml, techId, techniqueName.value)
                  Full(ModifyToDirectiveDiff(techniqueName,changeDirective,rootSection))
                case None => Failure(s"Could not find rootSection node in ${changeNode}")

              }

              case  _ => Failure("should not happen")
            }


            }
        } yield {

          val directiveChange = DirectiveChange(initialState.map{case (techName,directive) =>
                      val rootSection = techRepo.get(TechniqueId(techName,directive.techniqueVersion)).map(_.rootSection).get
                          (techName,directive,rootSection)},DirectiveChangeItem(actor,date,reason,change),Seq())

          (directiveId -> DirectiveChanges(directiveChange,Seq()))
        } }.toMap
      })
    }

    def unserialiseRuleChange(changeRequest:XNode): Box[Map[RuleId,RuleChanges]]= {
      (for {
          rulesNode  <- (changeRequest \ "rules").headOption ?~! s"Missing child 'rules' in entry type changeRequest : ${xml}"
      } yield {
        (rulesNode\"rule").flatMap{ rule =>
          for {
            ruleId       <- rule.attribute("id").map(id => RuleId(id.text)) ?~!
                             s"Missing attribute 'id' in entry type changeRequest rule changes  : ${rule}"
            initialRule  <- (rule \ "initialState").headOption
            initialState <- (initialRule \ "rule").headOption match {
              case Some(initialState) => ruleUnserialiser.unserialise(initialState) match {
                case Full(rule) => Full(Some(rule))
                case eb : EmptyBox => eb ?~! "could not unserialize rule"
              }
              case None => Full(None)
            }

            changeRule   <- (rule \ "firstChange" \ "change").headOption
            actor        <- (changeRule \\ "actor").headOption.map(actor => EventActor(actor.text))
            date         <- (changeRule \\ "date").headOption.map(date => ISODateTimeFormat.dateTimeParser.parseDateTime(date.text))
            reason       =  (changeRule \\ "reason").headOption.map(_.text)
            diff         <- (changeRule \\ "diff").headOption.flatMap(_.attribute("action").headOption.map(_.text))
            diffRule     <- (changeRule \\ "rule").headOption
            changeRule   <- ruleUnserialiser.unserialise(diffRule)
            change       <- diff match {
                              case "add" => Full(AddRuleDiff(changeRule))
                              case "delete" => Full(DeleteRuleDiff(changeRule))
                              case "modifyTo" => Full(ModifyToRuleDiff(changeRule))
                              case  _ => Failure("should not happen")
                            }
        } yield {
          val ruleChange = RuleChange(initialState,RuleChangeItem(actor,date,reason,change),Seq())

          (ruleId -> RuleChanges(ruleChange,Seq()))
        } }.toMap
      })
    }


    for {
      changeRequest  <- {
                            if(xml.label ==  XML_TAG_CHANGE_REQUEST) Full(xml)
                            else Failure("Entry type is not a <%s>: ".format(XML_TAG_CHANGE_REQUEST, xml))
                          }
      fileFormatOk    <- TestFileFormat(changeRequest)
      groups          <-  unserialiseNodeGroupChange(changeRequest)
      directives      =
        Try {
          unserialiseDirectiveChange(changeRequest)
        } match {
            case Success(change) => change
            case Catch(e) =>
             Failure(s"Could not deserialize directives changes cause ${e.getMessage()}")
        }
      rules           <-  unserialiseRuleChange(changeRequest)
    } yield {
      (directives,groups, rules)
    }
  }

}
