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
    uuidGen              : StringUuidGenerator
  , roChangeRequestRepo  : RoChangeRequestRepository
  , woChangeRequestRepo  : WoChangeRequestRepository
  , roDirectiveRepo      : RoDirectiveRepository
  , woDirectiveRepo      : WoDirectiveRepository
  , roNodeGroupRepo      : RoNodeGroupRepository
  , woNodeGroupRepo      : WoNodeGroupRepository
  , roRuleRepository     : RoRuleRepository
  , woRuleRepository     : WoRuleRepository
  , roParameterRepository: RoParameterRepository
  , woParameterRepository: WoParameterRepository
  , asyncDeploymentAgent : AsyncDeploymentAgent
  , dependencyService    : DependencyAndDeletionService
  , workflowEnabled      : Boolean
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

    def checkDirective(directiveChanges:DirectiveChanges) : Box[String] = {
      directiveChanges.changes.initialState match {
        case None => //ok, we were creating a directive, can't diverge
          Full("OK")
        case Some((t, initialDirective, s)) =>
          for {
            currentDirective <- roDirectiveRepo.getDirective(initialDirective.id)
            check            <- if ( compareDirectives(initialDirective, currentDirective) ) {
                                  Full("OK")
                                } else {
                                  Failure(s"Directive ${initialDirective.name} (id: ${initialDirective.id.value}) has diverged since change request creation")
                                }
            } yield {
              check
            }
      }
    }

    def checkGroup(nodeGroupChanges:NodeGroupChanges) : Box[String] = {
      nodeGroupChanges.changes.initialState match {
        case None => //ok, we were creating a group, can't diverge
          Full("OK")
        case Some(initialGroup) =>
          for {

            (currentGroup,_) <- roNodeGroupRepo.getNodeGroup(initialGroup.id)

            check  <- {
                        if ( compareGroups(initialGroup,currentGroup) ) {
                          Full("OK")
                        } else {
                          Failure(s"Group ${initialGroup.name} (id: ${initialGroup.id.value}) has diverged since change request creation")
                      } }
            } yield {
              check
            }
      }
    }

    def checkRule(ruleChanges:RuleChanges) : Box[String] = {
      ruleChanges.changes.initialState match {
        case None => //ok, we were creating a rule, can't diverge
          Full("OK")
        case Some(initialRule) =>
          for {
            currentRule <- roRuleRepository.get(initialRule.id)
            check       <- if ( compareRules(initialRule,currentRule) ) {
                              //we clearly don't want to compare for serial!
                              Full("OK")
                            } else {
                              Failure(s"Rule ${initialRule.name} (id: ${initialRule.id.value}) has diverged since change request creation")
                            }
            } yield {
              check
            }
      }
    }

    def checkGlobalParameter(globalParamChanges:GlobalParameterChanges) : Box[String] = {
      globalParamChanges.changes.initialState match {
        case None => //ok, we were creating a Global Parameter, can't diverge
          Full("OK")
        case Some(initialParam) =>
          for {
            currentParam <- roParameterRepository.getGlobalParameter(initialParam.name)
            check        <- if ( initialParam == currentParam) {
                              Full("OK")
                            } else {
                              Failure(s"Global Parameter ${initialParam.name.value} has diverged since change request creation")
                            }
            } yield {
              check
            }
      }
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

      initialFixed == currentFixed
    }

    def compareDirectives(initial:Directive, current:Directive) : Boolean = {
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
                          checkDirective(changes)
                        }
        groupsOk     <- sequence(changeRequest.nodeGroups.values.toSeq) { changes =>
                          checkGroup(changes)
                        }
        rulesOk      <- sequence(changeRequest.rules.values.toSeq) { changes =>
                          checkRule(changes)
                        }
        globalParamOk<- sequence(changeRequest.globalParams.values.toSeq) { changes =>
                          checkGlobalParameter(changes)
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
