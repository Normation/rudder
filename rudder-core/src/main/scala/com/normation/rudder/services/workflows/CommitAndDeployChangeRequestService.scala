/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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

package com.normation.rudder.services.workflows

import scala.collection.mutable.Buffer
import scala.collection.mutable.{ Map => MutMap }
import org.joda.time.DateTime
import com.normation.cfclerk.domain.TechniqueName
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AsyncDeploymentAgent
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.workflows._
import com.normation.rudder.services.policies.DependencyAndDeletionService
import com.normation.utils.Control._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import com.normation.rudder.repository._
import com.normation.rudder.repository.inmemory.InMemoryChangeRequestRepository
import com.normation.rudder.services.eventlog.WorkflowEventLogService
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyToNodeGroupDiff
import com.normation.rudder.domain.nodes.NodeGroup
import scala.xml._
import com.normation.rudder.services.marshalling.XmlSerializer
import com.normation.rudder.services.marshalling.XmlUnserializer
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import java.io.ByteArrayInputStream


/**
 * A service responsible to actually commit a change request,
 * and deploy
 */
trait CommitAndDeployChangeRequestService {

  /**
   * Save and deploy a change request.
   * That method must ensure that the change request
   * is only commited if there is no conflict between
   * the changes it contains and the actual current
   * state of configuration.
   */
  def save(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[ModificationId]


  /**
   * Check if a changeRequest can be merged as it is.
   * TODO: in the future, that method should be able to
   * return either it is ok to merge (and the corresponding
   * merge) or the conflict.
   */
  def isMergeable(changeRequestId:ChangeRequestId) : Boolean
}


/**
 * A service that will do whatever is needed to commit
 * modification about a change request in LDAP and
 * deploy them
 */
class CommitAndDeployChangeRequestServiceImpl(
    uuidGen             : StringUuidGenerator
  , roChangeRequestRepo : RoChangeRequestRepository
  , woChangeRequestRepo : WoChangeRequestRepository
  , roDirectiveRepo     : RoDirectiveRepository
  , woDirectiveRepo     : WoDirectiveRepository
  , roNodeGroupRepo     : RoNodeGroupRepository
  , woNodeGroupRepo     : WoNodeGroupRepository
  , roRuleRepository    : RoRuleRepository
  , woRuleRepository    : WoRuleRepository
  , asyncDeploymentAgent: AsyncDeploymentAgent
  , dependencyService   : DependencyAndDeletionService
  , workflowEnabled     : Boolean
  , xmlSerializer       : XmlSerializer
  , xmlUnserializer     : XmlUnserializer
  , sectionSpecParser   : SectionSpecParser
) extends CommitAndDeployChangeRequestService with Loggable {

  def save(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[ModificationId] = {
    logger.info(s"Saving and deploying change request ${changeRequestId}")
    for {
      changeRequest <- roChangeRequestRepo.get(changeRequestId)
      modId         <- changeRequest match {
                         case Some(config:ConfigurationChangeRequest) =>
                           if(isMergeableConfigurationChangeRequest(config)) {
                             for{
                               modId <-saveConfigurationChangeRequest(config)
                               updatedCr  <- woChangeRequestRepo.updateChangeRequest(ChangeRequest.setModId(config, modId),actor,reason)
                             } yield {
                               modId
                             }
                           } else {
                             Failure("The change request can not be merge because current item state diverged since its creation")
                           }
                         case x => Failure("We don't know how to deploy change request like this one: " + x)
                       }
    } yield {
      asyncDeploymentAgent ! AutomaticStartDeployment(modId, RudderEventActor)
      modId
    }
  }

  def isMergeable(changeRequestId:ChangeRequestId) : Boolean = {
    roChangeRequestRepo.get(changeRequestId) match {
      case Full(Some(cr:ConfigurationChangeRequest)) => isMergeableConfigurationChangeRequest(cr)
      case _ => false
    }
  }

  /**
   * Look if the configuration change request is mergeable
   */
  private[this] def isMergeableConfigurationChangeRequest(changeRequest:ConfigurationChangeRequest) : Boolean = {


    trait CheckChanges[T] {
      // Logging function
      def failureMessage(elem : T)   : String
      // Find Current value from initial value
      def getCurrentValue (initial : T) : Box[T]
      // Function that validates that the change request is still valid
      def compareMethod (initial : T, current : T) : Boolean
      // How to serialize the data into XML
      def xmlSerialize(elem : T)   : Box[Node]
      // How to unserialize XML into the datatype
      def xmlUnserialize(xml : Node)  : Box[T]
      // Transform date from LDAP to XML then transform it back to data using the XML parser
      def normalizeData (elem : T) : Box[T] = {
        try {
          for {
            entry <- xmlSerialize(elem)
            is    =  new ByteArrayInputStream(entry.toString.getBytes)
            xml   = XML.load(is)
            elem  <- xmlUnserialize(xml)
          } yield {
            elem
          }
        } catch {
          case e : Exception => Failure(s"could not verify xml cause is : ${e.getCause}")
        }
      }

      // Check if the change request is mergeable
      def check(initialState : Option[T]) : Box [String] = {
        initialState match {
          case None => // No initial state means creation of new elements, can't diverge
            Full("OK")
          case Some(initial) =>
            for {
              current     <- getCurrentValue(initial)
              normalized  <- normalizeData(current)
              check       <- if ( compareMethod(initial,normalized) ) {
                               Full("OK")
                             } else {
                               Failure(s"${failureMessage(initial)} has diverged since change request creation")
                             }
            } yield {
              check
            }
        }
      }
    }

    case object CheckRule extends CheckChanges[Rule]  {
      def failureMessage(rule : Rule)  = s"Rule ${rule.name} (id: ${rule.id.value})"
      def getCurrentValue(rule : Rule) = roRuleRepository.get(rule.id)
      def compareMethod(initial:Rule, current:Rule) = compareRules(initial,current)
      def xmlSerialize(rule : Rule) = Full(xmlSerializer.rule.serialise(rule))
      def xmlUnserialize(xml:Node)  = xmlUnserializer.rule.unserialise(xml)
    }


    // For now we only check the Directive, not the SectionSpec and the TechniqueName.
    // The SectionSpec could be a problem (ie : A mono valued param was chanegd to multi valued without changing the technique version).
    case class CheckDirective(changes : DirectiveChanges) extends CheckChanges[Directive]  {
      // used in serialisation
      val directiveContext = {
        // Option is None, if this is a Directive creation, but serialisation won't be used in this case (see check method)
        changes.changes.initialState match {
          case Some((techniqueName,_,rootSection)) => Full((techniqueName,rootSection))
          case None => Failure("could not find directive context from initial state")
        }
      }
      def failureMessage(directive : Directive)  = s"Rule ${directive.name} (id: ${directive.id.value})"
      def getCurrentValue(directive : Directive) = roDirectiveRepo.getDirective(directive.id)
      def compareMethod(initial:Directive, current:Directive) = compareDirectives(initial,current)
      def xmlSerialize(directive : Directive) = {
        directiveContext.map{
          case (techniqueName,rootSection) =>
            xmlSerializer.directive.serialise(techniqueName,rootSection,directive)}
      }
      def xmlUnserialize(xml : Node)          = xmlUnserializer.directive.unserialise(xml).map(_._2)
    }

    case object CheckGroup extends CheckChanges[NodeGroup]  {
      def failureMessage(group : NodeGroup)  = s"Group ${group.name} (id: ${group.id.value})"
      def getCurrentValue(group : NodeGroup) = roNodeGroupRepo.getNodeGroup(group.id).map(_._1)
      def compareMethod(initial:NodeGroup, current:NodeGroup) = compareGroups(initial,current)
      def xmlSerialize(group : NodeGroup) = Full(xmlSerializer.group.serialise(group))
      def xmlUnserialize(xml : Node)      = xmlUnserializer.group.unserialise(xml)
    }

    /*
     * Comparison methods between Rules/directives/groups
     * They are used to check if they are mergeable.
     */

    def compareRules(initial:Rule, current:Rule) : Boolean = {
      val initialFixed = initial.copy(
          name = initial.name.trim
        , shortDescription = initial.shortDescription.trim
        , longDescription = initial.longDescription.trim
        , serial = current.serial
      )

      val currentFixed = current.copy(
          name = current.name.trim
        , shortDescription = current.shortDescription.trim
        , longDescription = current.longDescription.trim
      )

      initialFixed == currentFixed
    }

    def compareDirectives(initial:Directive, current:Directive) : Boolean = {
      val initialFixed = initial.copy(
          name             = initial.name.trim
        , shortDescription = initial.shortDescription.trim
        , longDescription  = initial.longDescription.trim
        , parameters       = initial.parameters.mapValues(_.map(_.trim))
      )

      val currentFixed = current.copy(
          name             = current.name.trim
        , shortDescription = current.shortDescription.trim
        , longDescription  = current.longDescription.trim
        , parameters       = initial.parameters.mapValues(_.map(_.trim))
      )

      initialFixed == currentFixed
    }

    def compareGroups(initial:NodeGroup, current:NodeGroup) : Boolean = {

      val initialFixed = initial.copy(
          name = initial.name.trim
        , description = initial.description.trim
      )

      val currentFixed = current.copy(
          name = current.name.trim
        , description = current.description.trim
        /*
        * We need to remove nodes from dynamic groups, it has no sense to compare them.
        * In a static group, the node list is important and can be very different,
        * and depends on when the change request was made.
        * Maybe a future upgrade will be to check the parameters first and then check the nodelist.
        */
        , serverList = ( if (current.isDynamic) { initial } else { current }).serverList
      )

      initialFixed == currentFixed
    }

    /*
     * check for all elem, stop on the first failing
     */
    if (workflowEnabled)
      (for {
        directivesOk <- sequence(changeRequest.directives.values.toSeq) { changes =>
                          // Only check the directive for now
                          CheckDirective(changes).check(changes.changes.initialState.map(_._2))
                        }
        groupsOk     <- sequence(changeRequest.nodeGroups.values.toSeq) { changes =>
                          CheckGroup.check(changes.changes.initialState)
                        }
        rulesOk      <- sequence(changeRequest.rules.values.toSeq) { changes =>
                          CheckRule.check(changes.changes.initialState)
                        }
      } yield {
        directivesOk
      }) match {
        case Full(_) => true
        case eb:EmptyBox =>
          val e = eb ?~! "Can not merge change request"
          logger.debug(e.messageChain)
          e.rootExceptionCause.foreach { ex =>
            logger.debug("Root exception was:", ex)
          }
          false
      }
    else
      //If workflow are disabled, a change is always mergeable.
      true
  }

  /*
   * So, what to do ?
   */
  private[this] def saveConfigurationChangeRequest(cr:ConfigurationChangeRequest) : Box[ModificationId] = {
    import com.normation.utils.Control.sequence

    def doDirectiveChange(directiveChanges:DirectiveChanges, modId: ModificationId) : Box[DirectiveId] = {
      def save(tn:TechniqueName, d:Directive, change: DirectiveChangeItem) = {
        for {
          activeTechnique <- roDirectiveRepo.getActiveTechnique(tn)
          saved           <- woDirectiveRepo.saveDirective(activeTechnique.id, d, modId, change.actor, change.reason)
        } yield {
          saved
        }
      }

      for {
        change <- directiveChanges.changes.change
        done   <- change.diff match {
                    case DeleteDirectiveDiff(tn,d) =>
                      dependencyService.cascadeDeleteDirective(d.id, modId, change.actor, change.reason).map( _ => d.id)
                    case ModifyToDirectiveDiff(tn,d,rs) => save(tn,d, change).map( _ => d.id )
                    case AddDirectiveDiff(tn,d) => save(tn,d, change).map( _ => d.id )
                  }
      } yield {
        done
      }
    }

    def doNodeGroupChange(change:NodeGroupChanges, modId: ModificationId) : Box[NodeGroupId] = {
      for {
        change <- change.changes.change
        done   <- change.diff match {
                    case DeleteNodeGroupDiff(n) =>
                      dependencyService.cascadeDeleteTarget(GroupTarget(n.id), modId, change.actor, change.reason).map(_ => n.id )
                    case AddNodeGroupDiff(n) =>
                     Failure("You should not be able to create a group with a change request")
                    case ModifyToNodeGroupDiff(n) =>
                      woNodeGroupRepo.update(n, modId, change.actor, change.reason).map( _ => n.id)
                  }
      } yield {
        done
      }
    }

    def doRuleChange(change:RuleChanges, modId: ModificationId) : Box[RuleId] = {
      for {
        change <- change.changes.change
        done   <- change.diff match {
                    case DeleteRuleDiff(r) =>
                      woRuleRepository.delete(r.id, modId, change.actor, change.reason).map( _ => r.id)
                    case AddRuleDiff(r) =>
                      woRuleRepository.create(r, modId, change.actor, change.reason).map( _ => r.id)
                    case ModifyToRuleDiff(r) =>
                      woRuleRepository.update(r, modId, change.actor, change.reason).map( _ => r.id)
                  }
      } yield {
        done
      }
    }

    val modId = ModificationId(uuidGen.newUuid)

    /*
     * TODO: we should NOT commit into git each modification,
     * but wait until the last line and then commit.
     */

    for {

      directives <- sequence(cr.directives.values.toSeq) { directiveChange =>
                      doDirectiveChange(directiveChange, modId)
                    }
      groups     <- sequence(cr.nodeGroups.values.toSeq) { nodeGroupChange =>
                      doNodeGroupChange(nodeGroupChange, modId)
                    }
      rules      <- sequence(cr.rules.values.toSeq) { rule =>
                      doRuleChange(rule, modId)
                    }
    } yield {
      modId
    }
  }

}