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
import com.normation.rudder.domain.parameters._
import scala.xml._
import com.normation.rudder.services.marshalling.XmlSerializer
import com.normation.rudder.services.marshalling.XmlUnserializer
import com.normation.cfclerk.domain.SectionSpec
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import java.io.ByteArrayInputStream
import com.normation.rudder.domain.logger.ChangeRequestLogger

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
    uuidGen               : StringUuidGenerator
  , roChangeRequestRepo   : RoChangeRequestRepository
  , woChangeRequestRepo   : WoChangeRequestRepository
  , roDirectiveRepo       : RoDirectiveRepository
  , woDirectiveRepo       : WoDirectiveRepository
  , roNodeGroupRepo       : RoNodeGroupRepository
  , woNodeGroupRepo       : WoNodeGroupRepository
  , roRuleRepository      : RoRuleRepository
  , woRuleRepository      : WoRuleRepository
  , roParameterRepository : RoParameterRepository
  , woParameterRepository : WoParameterRepository
  , asyncDeploymentAgent  : AsyncDeploymentAgent
  , dependencyService     : DependencyAndDeletionService
  , workflowEnabled       : () => Box[Boolean]
  , xmlSerializer         : XmlSerializer
  , xmlUnserializer       : XmlUnserializer
  , sectionSpecParser     : SectionSpecParser
) extends CommitAndDeployChangeRequestService {

  val logger = ChangeRequestLogger

  def save(changeRequestId:ChangeRequestId, actor:EventActor, reason: Option[String]) : Box[ModificationId] = {
    if (workflowEnabled) {
      logger.info(s"Saving and deploying change request ${changeRequestId}")
    }
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

    case object CheckGlobalParameter extends CheckChanges[GlobalParameter]  {
      def failureMessage(param : GlobalParameter)  = s"Parameter ${param.name}"
      def getCurrentValue(param : GlobalParameter) = roParameterRepository.getGlobalParameter(param.name)
      def compareMethod(initial:GlobalParameter, current:GlobalParameter) = compareGlobalParameter(initial,current)
      def xmlSerialize(param : GlobalParameter) = Full(xmlSerializer.globalParam.serialise(param))
      def xmlUnserialize(xml : Node) = xmlUnserializer.globalParam.unserialise(xml)
    }

    def debugLog(message : String) = {
      logger.debug(s"CR #${changeRequest.id}: ${message}")
    }
    /*
     * Comparison methods between Rules/directives/groups/global Param
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

      if (initialFixed == currentFixed) {
        // No conflict return
        true
      } else {
        // Write debug logs to understand what cause the conflict
        debugLog("Attempt to merge Change Request (CR) failed because initial state could not be rebased on current state.")
        if ( initialFixed.name != currentFixed.name) {
          debugLog(s"Rule ID ${initialFixed.id.value.toUpperCase} name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}")
        }

        if ( initialFixed.shortDescription != currentFixed.shortDescription) {
          debugLog(s"Rule ID ${initialFixed.id.value.toUpperCase} short description has changed: original state from CR: ${initialFixed.shortDescription}, current value: ${currentFixed.shortDescription}")
        }

        if ( initialFixed.longDescription != currentFixed.longDescription) {
          debugLog(s"Rule ID ${initialFixed.id.value.toUpperCase} long description has changed: original state from CR: ${initialFixed.longDescription}, current value: ${currentFixed.longDescription}")
        }

        def displayTarget(target : RuleTarget) = {
          target match {
            case GroupTarget(groupId) => s"group: ${groupId.value.toUpperCase}"
            case PolicyServerTarget(nodeId) => s"policyServer: ${nodeId.value.toUpperCase}"
            case _ => target.target
          }
        }
        if ( initialFixed.targets != currentFixed.targets) {
          debugLog(s"Rule ID ${initialFixed.id.value.toUpperCase} target Groups have changed: original state from CR: ${initialFixed.targets.map(displayTarget)mkString("[ ", ", ", " ]")}, current value: ${currentFixed.targets.map(displayTarget).mkString("[ ", ", ", " ]")}")
        }

        if ( initialFixed.isEnabledStatus != currentFixed.isEnabledStatus) {
          debugLog(s"Rule ID ${initialFixed.id.value.toUpperCase} enable status has changed: original state from CR: ${initialFixed.isEnabledStatus}, current value: ${currentFixed.isEnabledStatus}")
        }

        if ( initialFixed.directiveIds != currentFixed.directiveIds) {
          debugLog(s"Rule ID ${initialFixed.id.value.toUpperCase} attached Directives have changed: original state from CR: ${initialFixed.directiveIds.map(_.value.toUpperCase).mkString("[ ", ", ", " ]")}, current value: ${currentFixed.directiveIds.map(_.value.toUpperCase).mkString("[ ", ", ", " ]")}")
        }

        //return
        false
      }
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


      if (initialFixed == currentFixed) {
        // return
        true
      } else {
        // Write debug logs to understand what cause the conflict
        debugLog("Attempt to merge Change Request (CR) failed because initial state could not be rebased on current state.")

        if ( initialFixed.name != currentFixed.name) {
          debugLog(s"Directive ID ${initialFixed.id.value.toUpperCase} name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}")
        }

        if ( initialFixed.shortDescription != currentFixed.shortDescription) {
          debugLog(s"Directive ID ${initialFixed.id.value.toUpperCase} short description has changed: original state from CR: ${initialFixed.shortDescription}, current value: ${currentFixed.shortDescription}")
        }

        if ( initialFixed.longDescription != currentFixed.longDescription) {
          debugLog(s"Directive ID ${initialFixed.id.value.toUpperCase} long description has changed: original state from CR: ${initialFixed.longDescription}, current value: ${currentFixed.longDescription}")
        }

        if ( initialFixed.priority != currentFixed.priority) {
          debugLog(s"Directive ID ${initialFixed.id.value.toUpperCase} priority has changed: original state from CR: ${initialFixed.priority}, current value: ${currentFixed.priority}")
        }

        if ( initialFixed.isEnabled != currentFixed.isEnabled) {
          debugLog(s"Directive ID ${initialFixed.id.value.toUpperCase} enable status has changed: original state from CR: ${initialFixed.isEnabled}, current value: ${currentFixed.isEnabled}")
        }

        if ( initialFixed.techniqueVersion != currentFixed.techniqueVersion) {
          debugLog(s"Directive ID ${initialFixed.id.value.toUpperCase} Technique version has changed: original state from CR: ${initialFixed.techniqueVersion}, current value: ${currentFixed.techniqueVersion}")
        }

        for  {
          key <- (initialFixed.parameters.keys ++ currentFixed.parameters.keys).toSeq.distinct
          initVal = initialFixed.parameters.get(key)
          currVal = currentFixed.parameters.get(key)
        } yield {
          if ( currVal != initVal) {
            debugLog(s"Directive ID ${initialFixed.id.value.toUpperCase} parameter $key has changed : original state from CR: ${initVal.getOrElse("value is mising")}, current value: ${currVal.getOrElse("value is mising")}")
          }
        }

        //return
        false
      }
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

      if (initialFixed == currentFixed) {
        // No conflict return
        true
      } else {
        // Write debug logs to understand what cause the conflict
        debugLog("Attempt to merge Change Request (CR) failed because initial state could not be rebased on current state.")

        if ( initialFixed.name != currentFixed.name) {
          debugLog(s"Group ID ${initialFixed.id.value.toUpperCase} name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}")
        }

        if ( initialFixed.description != currentFixed.description) {
          debugLog(s"Group ID ${initialFixed.id.value.toUpperCase} description has changed: original state from CR: ${initialFixed.description}, current value: ${currentFixed.description}")
        }

        if ( initialFixed.query != currentFixed.query) {
          debugLog(s"Group ID ${initialFixed.id.value.toUpperCase} query has changed: original state from CR: ${initialFixed.query}, current value: ${currentFixed.query}")
        }

        if ( initialFixed.isDynamic != currentFixed.isDynamic) {
          debugLog(s"Group ID ${initialFixed.id.value.toUpperCase} dynamic status has changed: original state from CR: ${initialFixed.isDynamic}, current value: ${currentFixed.isDynamic}")
        }

        if ( initialFixed.isEnabled != currentFixed.isEnabled) {
          debugLog(s"Group ID ${initialFixed.id.value.toUpperCase} enable status has changed: original state from CR: ${initialFixed.isEnabled}, current value: ${currentFixed.isEnabled}")
        }

        if (initialFixed.serverList != currentFixed.serverList) {
          debugLog(s"Group ID ${initialFixed.id.value.toUpperCase} nodes list has changed: original state from CR: ${initialFixed.serverList.map(_.value.toUpperCase).mkString("[ ", ", ", " ]")}, current value: ${currentFixed.serverList.map(_.value.toUpperCase).mkString("[ ", ", ", " ]")}")
        }

        //return
        false
      }
    }

    def compareGlobalParameter(initial:GlobalParameter, current:GlobalParameter) : Boolean = {

      if (initial == current) {
        // No conflict return
        true
      } else {
        // Write debug logs to understand what cause the conflict
        debugLog("Attempt to merge Change Request (CR) failed because initial state could not be rebased on current state.")

        if ( initial.name != current.name) {
          debugLog(s"Global Parameter name has changed: original state from CR: ${initial.name}, current value: ${current.name}")
        }

        if ( initial.description != current.description) {
          debugLog(s"Global Parameter '${initial.name}' description has changed: original state from CR: ${initial.description}, current value: ${current.description}")
        }

        if ( initial.value != current.value) {
          debugLog(s"Global Parameter '${initial.name}' value has changed: original state from CR: ${initial.value}, current value: ${current.value}")
        }

        if ( initial.overridable != current.overridable) {
          debugLog(s"Global Parameter '${initial.name}' overridable status has changed: original state from CR: ${initial.overridable}, current value: ${current.overridable}")
        }

        //return
        false
      }
    }

    /*
     * check for all elem, stop on the first failing
     */
    (for {
      cond <- workflowEnabled()
    } yield {
      if (cond)
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
        globalParamOk<- sequence(changeRequest.globalParams.values.toSeq) { changes =>
                          CheckGlobalParameter.check(changes.changes.initialState)
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
    }) match {
      case Full(mergeable) => mergeable
      case eb : EmptyBox =>
        val fail = eb ?~ "An error occurred while checking the change request acceptance"
        logger.error(fail.messageChain)
        false
    }
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

    def doParamChange(change:GlobalParameterChanges, modId: ModificationId) : Box[ParameterName] = {
      for {
        change <- change.changes.change
        done   <- change.diff match {
                    case DeleteGlobalParameterDiff(param) =>
                      woParameterRepository.delete(param.name, modId, change.actor, change.reason).map( _ => param.name)
                    case AddGlobalParameterDiff(param) =>
                      woParameterRepository.saveParameter(param, modId, change.actor, change.reason).map( _ => param.name)
                    case ModifyToGlobalParameterDiff(param) =>
                      woParameterRepository.updateParameter(param, modId, change.actor, change.reason).map( _ => param.name)
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
      params     <- sequence(cr.globalParams.values.toSeq) { param =>
                      doParamChange(param, modId)
                    }
    } yield {
      modId
    }
  }

}
