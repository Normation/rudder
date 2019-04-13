/*
*************************************************************************************
* Copyright 2011-2013 Normation SAS
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

package com.normation.rudder.services.workflows

import com.normation.cfclerk.domain.TechniqueName
import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.policies._
import com.normation.rudder.domain.workflows._
import com.normation.rudder.services.policies.DependencyAndDeletionService
import com.normation.utils.Control._
import com.normation.utils.StringUuidGenerator
import net.liftweb.common._
import com.normation.rudder.repository._
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyToNodeGroupDiff
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.parameters._

import scala.xml._
import com.normation.rudder.services.marshalling.XmlSerializer
import com.normation.rudder.services.marshalling.XmlUnserializer
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import java.io.ByteArrayInputStream

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.logger.ChangeRequestLogger
import com.normation.rudder.services.queries.DynGroupUpdaterService

import com.normation.box._
import com.normation.errors._

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
  def save(changeRequest: ChangeRequest, actor:EventActor, reason: Option[String]) : Box[ChangeRequest]


  /**
   * Check if a changeRequest can be merged as it is.
   * TODO: in the future, that method should be able to
   * return either it is ok to merge (and the corresponding
   * merge) or the conflict.
   */
  def isMergeable(changeRequest: ChangeRequest) : Boolean
}


/**
 * A service that will do whatever is needed to commit
 * modification about a change request in LDAP and
 * deploy them
 */
class CommitAndDeployChangeRequestServiceImpl(
    uuidGen               : StringUuidGenerator
  , roDirectiveRepo       : RoDirectiveRepository
  , woDirectiveRepo       : WoDirectiveRepository
  , roNodeGroupRepo       : RoNodeGroupRepository
  , woNodeGroupRepo       : WoNodeGroupRepository
  , roRuleRepository      : RoRuleRepository
  , woRuleRepository      : WoRuleRepository
  , roParameterRepository : RoParameterRepository
  , woParameterRepository : WoParameterRepository
  , asyncDeploymentAgent  : AsyncDeploymentActor
  , dependencyService     : DependencyAndDeletionService
  , workflowEnabled       : () => Box[Boolean]
  , xmlSerializer         : XmlSerializer
  , xmlUnserializer       : XmlUnserializer
  , sectionSpecParser     : SectionSpecParser
  , updateDynamicGroups   : DynGroupUpdaterService
) extends CommitAndDeployChangeRequestService {

  val logger = ChangeRequestLogger

  def save(changeRequest: ChangeRequest, actor:EventActor, reason: Option[String]) : Box[ChangeRequest] = {
    workflowEnabled().foreach { if (_) {
      logger.info(s"Saving and deploying change request ${changeRequest.id.value}")
    } }
    for {
      (cr, modId, trigger) <- changeRequest match {
                         case config:ConfigurationChangeRequest =>
                           if(isMergeableConfigurationChangeRequest(config)) {
                             for{
                               (modId, triggerDeployment) <-saveConfigurationChangeRequest(config)
                               updatedCr = ChangeRequest.setModId(config, modId)
                             } yield {
                               (updatedCr, modId, triggerDeployment)
                             }
                           } else {
                             Failure("The change request can not be merge because current item state diverged since its creation")
                           }
                         case x => Failure("We don't know how to deploy change request like this one: " + x)
                       }
    } yield {
      if (trigger) {
        asyncDeploymentAgent ! AutomaticStartDeployment(modId, RudderEventActor)
      }
      cr
    }
  }

  def isMergeable(changeRequest: ChangeRequest) : Boolean = {
    changeRequest match {
      case cr:ConfigurationChangeRequest => isMergeableConfigurationChangeRequest(cr)
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
      def getCurrentValue(rule : Rule) = roRuleRepository.get(rule.id).toBox
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
      def getCurrentValue(directive : Directive) = roDirectiveRepo.getDirective(directive.id).toBox
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
      def getCurrentValue(group : NodeGroup) = roNodeGroupRepo.getNodeGroup(group.id).map(_._1).toBox
      def compareMethod(initial:NodeGroup, current:NodeGroup) = compareGroups(initial,current)
      def xmlSerialize(group : NodeGroup) = Full(xmlSerializer.group.serialise(group))
      def xmlUnserialize(xml : Node)      = xmlUnserializer.group.unserialise(xml)
    }

    case object CheckGlobalParameter extends CheckChanges[GlobalParameter]  {
      def failureMessage(param : GlobalParameter)  = s"Parameter ${param.name}"
      def getCurrentValue(param : GlobalParameter) = roParameterRepository.getGlobalParameter(param.name).notOptional(s"Parameter '${param.name}' was not found").toBox
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
          debugLog(s"Rule ID ${initialFixed.id.value} name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}")
        }

        if ( initialFixed.shortDescription != currentFixed.shortDescription) {
          debugLog(s"Rule ID ${initialFixed.id.value} short description has changed: original state from CR: ${initialFixed.shortDescription}, current value: ${currentFixed.shortDescription}")
        }

        if ( initialFixed.longDescription != currentFixed.longDescription) {
          debugLog(s"Rule ID ${initialFixed.id.value} long description has changed: original state from CR: ${initialFixed.longDescription}, current value: ${currentFixed.longDescription}")
        }

        def displayTarget(target : RuleTarget) = {
          target match {
            case GroupTarget(groupId) => s"group: ${groupId.value}"
            case PolicyServerTarget(nodeId) => s"policyServer: ${nodeId.value}"
            case _ => target.target
          }
        }
        if ( initialFixed.targets != currentFixed.targets) {
          debugLog(s"Rule ID ${initialFixed.id.value} target Groups have changed: original state from CR: ${initialFixed.targets.map(displayTarget)mkString("[ ", ", ", " ]")}, current value: ${currentFixed.targets.map(displayTarget).mkString("[ ", ", ", " ]")}")
        }

        if ( initialFixed.isEnabledStatus != currentFixed.isEnabledStatus) {
          debugLog(s"Rule ID ${initialFixed.id.value} enable status has changed: original state from CR: ${initialFixed.isEnabledStatus}, current value: ${currentFixed.isEnabledStatus}")
        }

        if ( initialFixed.directiveIds != currentFixed.directiveIds) {
          debugLog(s"Rule ID ${initialFixed.id.value} attached Directives have changed: original state from CR: ${initialFixed.directiveIds.map(_.value).mkString("[ ", ", ", " ]")}, current value: ${currentFixed.directiveIds.map(_.value).mkString("[ ", ", ", " ]")}")
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
          debugLog(s"Directive ID ${initialFixed.id.value} name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}")
        }

        if ( initialFixed.shortDescription != currentFixed.shortDescription) {
          debugLog(s"Directive ID ${initialFixed.id.value} short description has changed: original state from CR: ${initialFixed.shortDescription}, current value: ${currentFixed.shortDescription}")
        }

        if ( initialFixed.longDescription != currentFixed.longDescription) {
          debugLog(s"Directive ID ${initialFixed.id.value} long description has changed: original state from CR: ${initialFixed.longDescription}, current value: ${currentFixed.longDescription}")
        }

        if ( initialFixed.priority != currentFixed.priority) {
          debugLog(s"Directive ID ${initialFixed.id.value} priority has changed: original state from CR: ${initialFixed.priority}, current value: ${currentFixed.priority}")
        }

        if ( initialFixed.isEnabled != currentFixed.isEnabled) {
          debugLog(s"Directive ID ${initialFixed.id.value} enable status has changed: original state from CR: ${initialFixed.isEnabled}, current value: ${currentFixed.isEnabled}")
        }

        if ( initialFixed.techniqueVersion != currentFixed.techniqueVersion) {
          debugLog(s"Directive ID ${initialFixed.id.value} Technique version has changed: original state from CR: ${initialFixed.techniqueVersion}, current value: ${currentFixed.techniqueVersion}")
        }

        for  {
          key <- (initialFixed.parameters.keys ++ currentFixed.parameters.keys).toSeq.distinct
          initVal = initialFixed.parameters.get(key)
          currVal = currentFixed.parameters.get(key)
        } yield {
          if ( currVal != initVal) {
            debugLog(s"Directive ID ${initialFixed.id.value} parameter $key has changed : original state from CR: ${initVal.getOrElse("value is mising")}, current value: ${currVal.getOrElse("value is mising")}")
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
          debugLog(s"Group ID ${initialFixed.id.value} name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}")
        }

        if ( initialFixed.description != currentFixed.description) {
          debugLog(s"Group ID ${initialFixed.id.value} description has changed: original state from CR: ${initialFixed.description}, current value: ${currentFixed.description}")
        }

        if ( initialFixed.query != currentFixed.query) {
          debugLog(s"Group ID ${initialFixed.id.value} query has changed: original state from CR: ${initialFixed.query}, current value: ${currentFixed.query}")
        }

        if ( initialFixed.isDynamic != currentFixed.isDynamic) {
          debugLog(s"Group ID ${initialFixed.id.value} dynamic status has changed: original state from CR: ${initialFixed.isDynamic}, current value: ${currentFixed.isDynamic}")
        }

        if ( initialFixed.isEnabled != currentFixed.isEnabled) {
          debugLog(s"Group ID ${initialFixed.id.value} enable status has changed: original state from CR: ${initialFixed.isEnabled}, current value: ${currentFixed.isEnabled}")
        }

        if (initialFixed.serverList != currentFixed.serverList) {
          debugLog(s"Group ID ${initialFixed.id.value} nodes list has changed: original state from CR: ${initialFixed.serverList.map(_.value).mkString("[ ", ", ", " ]")}, current value: ${currentFixed.serverList.map(_.value).mkString("[ ", ", ", " ]")}")
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
   * Returns the modificationId, plus a boolean indicating if we need to trigger a deployment
   */
  private[this] def saveConfigurationChangeRequest(cr:ConfigurationChangeRequest) : Box[(ModificationId, Boolean)] = {

    def doDirectiveChange(directiveChanges:DirectiveChanges, modId: ModificationId) : Box[TriggerDeploymentDiff] = {
      def save(tn:TechniqueName, d:Directive, change: DirectiveChangeItem) : Box[Option[DirectiveSaveDiff]] = {
        for {
          activeTechnique <- roDirectiveRepo.getActiveTechnique(tn).notOptional(s"Missing active technique with name ${tn}")
          saved           <- woDirectiveRepo.saveDirective(activeTechnique.id, d, modId, change.actor, change.reason)
        } yield {
          saved
        }
      }.toBox

      for {
        change <- directiveChanges.changes.change
        diff   <- change.diff match {
                    case DeleteDirectiveDiff(tn,d) =>
                      dependencyService.cascadeDeleteDirective(d.id, modId, change.actor, change.reason).map( _ => DeleteDirectiveDiff(tn,d) )
                    case ModifyToDirectiveDiff(tn,d,rs) =>
                      // if the save returns None, then we return the original modification object
                      save(tn,d, change).map(_.getOrElse(ModifyToDirectiveDiff(tn,d,rs)))
                    case AddDirectiveDiff(tn,d) =>
                      // if the save returns None, then we return the original modification object
                      save(tn,d, change).map(_.getOrElse(AddDirectiveDiff(tn,d)))
                  }
      } yield {
        diff
      }
    }

    def doNodeGroupChange(change:NodeGroupChanges, modId: ModificationId) : Box[TriggerDeploymentDiff] = {
      for {
        change <- change.changes.change
        diff   <- change.diff match {
                    case DeleteNodeGroupDiff(n) =>
                      dependencyService.cascadeDeleteTarget(GroupTarget(n.id), modId, change.actor, change.reason).map(_ => DeleteNodeGroupDiff(n) )
                    case AddNodeGroupDiff(n) =>
                      Failure("You should not be able to create a group with a change request")
                    case ModifyToNodeGroupDiff(n) =>
                      // we first need to refresh the node list if it's a dynamic group
                      val group = if (n.isDynamic) updateDynamicGroups.computeDynGroup(n) else Full(n)

                      // If we could get a nodeList, then we apply the change, else we bubble up the error
                      group.flatMap { resultingGroup =>
                        // if the update returns None, then we return the original modification object
                        woNodeGroupRepo.update(resultingGroup, modId, change.actor, change.reason).map(_.getOrElse(ModifyToNodeGroupDiff(n)))
                      }



                  }
      } yield {
        diff
      }
    }

    def doRuleChange(change:RuleChanges, modId: ModificationId) : Box[TriggerDeploymentDiff] = {
      for {
        change <- change.changes.change
        diff   <- (change.diff match {
                    case DeleteRuleDiff(r) =>
                      woRuleRepository.delete(r.id, modId, change.actor, change.reason)
                    case AddRuleDiff(r) =>
                      woRuleRepository.create(r, modId, change.actor, change.reason)
                    case ModifyToRuleDiff(r) =>
                      // if the update returns None, then we return the original modification object
                      woRuleRepository.update(r, modId, change.actor, change.reason).map(_.getOrElse(ModifyToRuleDiff(r)))
                  }).toBox
      } yield {
        diff
      }
    }

    def doParamChange(change:GlobalParameterChanges, modId: ModificationId) : Box[TriggerDeploymentDiff] = {
      for {
        change <- change.changes.change
        diff   <- (change.diff match {
                    case DeleteGlobalParameterDiff(param) =>
                      woParameterRepository.delete(param.name, modId, change.actor, change.reason)
                    case AddGlobalParameterDiff(param) =>
                      woParameterRepository.saveParameter(param, modId, change.actor, change.reason)
                    case ModifyToGlobalParameterDiff(param) =>
                      // if the update returns None, then we return the original modification object
                      woParameterRepository.updateParameter(param, modId, change.actor, change.reason).map(_.getOrElse(ModifyToGlobalParameterDiff(param)))
                  }).toBox
      } yield {
        diff
      }
    }
    val modId = ModificationId(uuidGen.newUuid)

    /*
     * Logic to commit Change request with multiple changes:
     *
     * - such a change request is NOT atomic, each change is
     *   commited independently to other
     * - from previous point, it may happen that a erroneous commit
     *   in a beginner element leads to other errors
     * - the commit order is DEFINED and FIXE
     *   - we commit by element types,
     *   - for a given type, we do all its action before passing to
     *     an other type
     *
     * - Change type order:
     *   - 1/ GlobalParameter
     *   - 2/ Group
     *   - 3/ Directive
     *   - 4/ Rules
     *
     * - Action order:
     *   - 1/ delete
     *   - 2/ create
     *   - 3/ modify
     */

    val sortedParam = cr.globalParams.values.toSeq.sortWith { case (x,y) =>
      (for {
        c1 <- x.changes.change
        c2 <- y.changes.change
      } yield {
        (c1.diff, c2.diff) match {
          case (_:DeleteGlobalParameterDiff, _:DeleteGlobalParameterDiff) => true
          case (_, _:DeleteGlobalParameterDiff) => false
          case (_, _:AddGlobalParameterDiff) => false
          case _ => true
        }
      }).openOr(true)
    }

    val sortedGroups = cr.nodeGroups.values.toSeq.sortWith { case (x,y) =>
      (for {
        c1 <- x.changes.change
        c2 <- y.changes.change
      } yield {
        (c1.diff, c2.diff) match {
          case (_:DeleteNodeGroupDiff, _:DeleteNodeGroupDiff) => true
          case (_, _:DeleteNodeGroupDiff) => false
          case (_, _:AddNodeGroupDiff) => false
          case _ => true
        }
      }).openOr(true)
    }

    val sortedDirectives = cr.directives.values.toSeq.sortWith { case (x,y) =>
      (for {
        c1 <- x.changes.change
        c2 <- y.changes.change
      } yield {
        (c1.diff, c2.diff) match {
          case (_:DeleteDirectiveDiff, _:DeleteDirectiveDiff) => true
          case (_, _:DeleteDirectiveDiff) => false
          case (_, _:AddDirectiveDiff) => false
          case _ => true
        }
      }).openOr(true)
    }

    val sortedRules = cr.rules.values.toSeq.sortWith { case (x,y) =>
      (for {
        c1 <- x.changes.change
        c2 <- y.changes.change
      } yield {
        (c1.diff, c2.diff) match {
          case (_:DeleteRuleDiff, _:DeleteRuleDiff) => true
          case (_, _:DeleteRuleDiff) => false
          case (_, _:AddRuleDiff) => false
          case _ => true
        }
      }).openOr(true)
    }

    val params     = bestEffort(sortedParam) { param =>
                      doParamChange(param, modId)
                   }
    val groups     = bestEffort(sortedGroups) { nodeGroupChange =>
                      doNodeGroupChange(nodeGroupChange, modId)
                    }
    val directives = bestEffort(sortedDirectives) { directiveChange =>
                      doDirectiveChange(directiveChange, modId)
                    }
    val rules      = bestEffort(sortedRules) { rule =>
                      doRuleChange(rule, modId)
                    }
    //TODO: we will want to keep tracks of all the modification done, and in
    //particular of all the error
    //not fail on the first erroneous, but in case of error, get them all.
    // For each of the step, we need to detect if a deployment is required
    for {
      triggerDeployments <-  bestEffort(List(params, directives, groups, rules)) { _.map( x => x.exists(y => (y.needDeployment == true)) ) }
    } yield {
      // If one of the step require a deployment, then we need to trigger a deployment
      (modId, triggerDeployments.contains(true))
    }

  }

}
