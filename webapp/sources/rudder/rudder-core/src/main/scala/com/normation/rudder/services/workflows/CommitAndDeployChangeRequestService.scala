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

import com.normation.box.*
import com.normation.cfclerk.domain.TechniqueName
import com.normation.cfclerk.xmlparsers.SectionSpecParser
import com.normation.errors.*
import com.normation.rudder.batch.AsyncDeploymentActor
import com.normation.rudder.batch.AutomaticStartDeployment
import com.normation.rudder.domain.eventlog.RudderEventActor
import com.normation.rudder.domain.logger.ChangeRequestLoggerPure
import com.normation.rudder.domain.nodes.AddNodeGroupDiff
import com.normation.rudder.domain.nodes.DeleteNodeGroupDiff
import com.normation.rudder.domain.nodes.ModifyToNodeGroupDiff
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.policies.*
import com.normation.rudder.domain.properties.*
import com.normation.rudder.domain.workflows.*
import com.normation.rudder.repository.*
import com.normation.rudder.services.marshalling.XmlSerializer
import com.normation.rudder.services.marshalling.XmlUnserializer
import com.normation.rudder.services.policies.DependencyAndDeletionService
import com.normation.rudder.services.queries.DynGroupUpdaterService
import com.normation.rudder.tenants.ChangeContext
import com.normation.rudder.tenants.QueryContext
import com.normation.utils.Control.*
import com.normation.utils.StringUuidGenerator
import com.normation.utils.XmlSafe
import com.normation.zio.UnsafeRun
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import net.liftweb.common.*
import scala.xml.*
import zio.syntax.ToZio

/**
 * A service responsible to actually commit a change request,
 * and deploy
 */
trait CommitAndDeployChangeRequestService {

  /**
   * Save and deploy a change request.
   * That method must ensure that the change request
   * is only committed if there is no conflict between
   * the changes it contains and the actual current
   * state of configuration.
   */
  def save(changeRequest: ChangeRequest)(implicit cc: ChangeContext): IOResult[ChangeRequest]

  /**
   * Check if a changeRequest can be merged as it is.
   * TODO: in the future, that method should be able to
   * return either it is ok to merge (and the corresponding
   * merge) or the conflict.
   */
  def isMergeable(changeRequest: ChangeRequest)(implicit qc: QueryContext): Boolean
}

/**
 * A service that will do whatever is needed to commit
 * modification about a change request in LDAP and
 * deploy them
 */
class CommitAndDeployChangeRequestServiceImpl(
    uuidGen:               StringUuidGenerator,
    roDirectiveRepo:       RoDirectiveRepository,
    woDirectiveRepo:       WoDirectiveRepository,
    roNodeGroupRepo:       RoNodeGroupRepository,
    woNodeGroupRepo:       WoNodeGroupRepository,
    roRuleRepository:      RoRuleRepository,
    woRuleRepository:      WoRuleRepository,
    roParameterRepository: RoParameterRepository,
    woParameterRepository: WoParameterRepository,
    asyncDeploymentAgent:  AsyncDeploymentActor,
    dependencyService:     DependencyAndDeletionService,
    workflowEnabled:       () => IOResult[Boolean],
    xmlSerializer:         XmlSerializer,
    xmlUnserializer:       XmlUnserializer,
    sectionSpecParser:     SectionSpecParser,
    updateDynamicGroups:   DynGroupUpdaterService
) extends CommitAndDeployChangeRequestService {

  private val logger = ChangeRequestLoggerPure

  def save(changeRequest: ChangeRequest)(implicit cc: ChangeContext): IOResult[ChangeRequest] = {
    implicit val qc: QueryContext = cc.toQC

    for {
      _                <- workflowEnabled()
                            .tap(workflowEnabled => {
                              if (workflowEnabled) logger.info(s"Saving and deploying change request ${changeRequest.id.value}")
                              else ().succeed
                            })
      res              <- changeRequest match {
                            case config: ConfigurationChangeRequest =>
                              if (!isMergeableConfigurationChangeRequest(config)) {
                                logger.info(
                                  s"Merging change request #${config.id.value} while configurations have diverged: ${config.info.name}: ${config.info.description}"
                                )
                              }
                              saveConfigurationChangeRequest(config).map(config -> _).toIO

                            case x =>
                              Inconsistency("We don't know how to deploy change request like this one: " + x).fail
                          }
      (config, trigger) = res
    } yield {
      if (trigger) {
        asyncDeploymentAgent ! AutomaticStartDeployment(cc.modId, RudderEventActor)
      }
      ChangeRequest.setModId(config, cc.modId)
    }
  }

  def isMergeable(changeRequest: ChangeRequest)(implicit qc: QueryContext): Boolean = {
    changeRequest match {
      case cr: ConfigurationChangeRequest => isMergeableConfigurationChangeRequest(cr)
      case _ => false
    }
  }

  /**
   * Look if the configuration change request is mergeable
   */
  private def isMergeableConfigurationChangeRequest(
      changeRequest: ConfigurationChangeRequest
  )(implicit qc: QueryContext): Boolean = {

    trait CheckChanges[T] {
      // Logging function
      def failureMessage(elem:     T): String
      // Find Current value from initial value
      def getCurrentValue(initial: T): IOResult[T]
      // Function that validates that the change request is still valid
      def compareMethod(initial:   T, current: T): Boolean
      // How to serialize the data into XML
      def xmlSerialize(elem:       T): PureResult[Node]
      // How to unserialize XML into the datatype
      def xmlUnserialize(xml:      Node): PureResult[T]
      // Transform date from LDAP to XML then transform it back to data using the XML parser
      def normalizeData(elem: T): PureResult[T] = {
        try {
          for {
            entry <- xmlSerialize(elem)
            is     = new ByteArrayInputStream(entry.toString.getBytes(StandardCharsets.UTF_8))
            xml    = XmlSafe.load(is)
            elem  <- xmlUnserialize(xml)
          } yield {
            elem
          }
        } catch {
          case e: Exception => Left(Inconsistency(s"could not verify xml cause is : ${e.getCause}"))
        }
      }

      // Check if the change request is mergeable
      def check(initialState: Option[T]): IOResult[String] = {
        initialState match {
          case None          => // No initial state means creation of new elements, can't diverge
            "OK".succeed
          case Some(initial) =>
            for {
              current    <- getCurrentValue(initial)
              normalized <- normalizeData(current).toIO
              check      <- if (compareMethod(initial, normalized)) {
                              "OK".succeed
                            } else {
                              Inconsistency(s"${failureMessage(initial)} has diverged since change request creation").fail
                            }
            } yield {
              check
            }
        }
      }
    }

    implicit val changeRequestId = changeRequest.id.value

    case object CheckRule extends CheckChanges[Rule] {
      def failureMessage(rule:   Rule) = s"Rule ${rule.name} (id: ${rule.id.serialize})"
      def getCurrentValue(rule:  Rule) = roRuleRepository.get(rule.id)
      def compareMethod(initial: Rule, current: Rule) = CheckDivergenceForMerge.compareRules(initial, current)
      def xmlSerialize(rule:     Rule) = Right(xmlSerializer.rule.serialise(rule))
      def xmlUnserialize(xml:    Node) = xmlUnserializer.rule.unserialise(xml)
    }

    // For now we only check the Directive, not the SectionSpec and the TechniqueName.
    // The SectionSpec could be a problem (ie : A mono valued param was changed to multi valued without changing the technique version).
    final case class CheckDirective(changes: DirectiveChanges) extends CheckChanges[Directive] {
      // used in serialisation
      val directiveContext                      = {
        // Option is None, if this is a Directive creation, but serialisation won't be used in this case (see check method)
        changes.changes.initialState match {
          case Some((techniqueName, _, rootSection)) => Right((techniqueName, rootSection))
          case None                                  => Left(Inconsistency("could not find directive context from initial state"))
        }
      }
      def failureMessage(directive: Directive) = s"Directive ${directive.name} (id: ${directive.id.serialize})"
      def getCurrentValue(directive: Directive) = roDirectiveRepo.getDirective(directive.id.uid).flatMap {
        case None            => Inconsistency("could not get current value of directive from roDirectiveRepo : directive is absent").fail
        case Some(directive) => directive.succeed
      }
      def compareMethod(initial: Directive, current: Directive) = CheckDivergenceForMerge.compareDirectives(initial, current)
      def xmlSerialize(directive: Directive)    = {
        directiveContext.map {
          case (techniqueName, rootSection) =>
            xmlSerializer.directive.serialise(techniqueName, rootSection, directive)
        }
      }
      def xmlUnserialize(xml: Node) = xmlUnserializer.directive.unserialise(xml).map(_._2)
    }

    case object CheckGroup extends CheckChanges[NodeGroup] {
      def failureMessage(group:  NodeGroup) = s"Group ${group.name} (id: ${group.id.serialize})"
      def getCurrentValue(group: NodeGroup) = roNodeGroupRepo.getNodeGroup(group.id).map(_._1)
      def compareMethod(initial: NodeGroup, current: NodeGroup) = CheckDivergenceForMerge.compareGroups(initial, current)
      def xmlSerialize(group:    NodeGroup): PureResult[Node] = Right(xmlSerializer.group.serialise(group))
      def xmlUnserialize(xml:    Node) = xmlUnserializer.group.unserialise(xml)
    }

    case object CheckGlobalParameter extends CheckChanges[GlobalParameter] {
      def failureMessage(param: GlobalParameter) = s"Parameter ${param.name}"
      def getCurrentValue(param: GlobalParameter)                           =
        roParameterRepository.getGlobalParameter(param.name).notOptional(s"Parameter '${param.name}' was not found")
      def compareMethod(initial: GlobalParameter, current: GlobalParameter) =
        CheckDivergenceForMerge.compareGlobalParameter(initial, current)
      def xmlSerialize(param: GlobalParameter): PureResult[Node] = Right(xmlSerializer.globalParam.serialise(param))
      def xmlUnserialize(xml: Node) = xmlUnserializer.globalParam.unserialise(xml)
    }

    /*
     * check for all elem, stop on the first failing
     */

    def isMergeable = {
      (for {
        directivesOk  <- changeRequest.directives.values.toSeq.accumulate { changes =>
                           // Only check the directive for now
                           CheckDirective(changes).check(changes.changes.initialState.map(_._2))
                         }
        groupsOk      <- changeRequest.nodeGroups.values.toSeq.accumulate(changes => CheckGroup.check(changes.changes.initialState))
        rulesOk       <- changeRequest.rules.values.toSeq.accumulate(changes => CheckRule.check(changes.changes.initialState))
        globalParamOk <- changeRequest.globalParams.values.toSeq.accumulate { changes =>
                           CheckGlobalParameter.check(changes.changes.initialState)
                         }
      } yield {
        directivesOk
      }).chainError("Can not merge change request")
        .tapError(err => logger.debug(err.fullMsg))
        .chainError("An error occurred while checking whether change request is mergeable")
        .fold(
          err => {
            logger.error(err.fullMsg)
            false
          },
          _ => true
        )
    }

    (for {
      cond        <- workflowEnabled()
      isMergeable <- if (cond) isMergeable else true.succeed
    } yield {
      isMergeable
    }).either.runNow match {
      case Right(mergeable) => mergeable
      case Left(_)          => false
    }
  }

  /*
   * So, what to do ?
   * Returns the modificationId, plus a boolean indicating if we need to trigger a deployment
   */
  private def saveConfigurationChangeRequest(cr: ConfigurationChangeRequest)(implicit cc: ChangeContext): Box[Boolean] = {
    import cc.modId

    def doDirectiveChange(directiveChanges: DirectiveChanges): Box[TriggerDeploymentDiff] = {
      def save(tn: TechniqueName, d: Directive, change: DirectiveChangeItem): Box[Option[DirectiveSaveDiff]] = {
        for {
          activeTechnique <- roDirectiveRepo.getActiveTechnique(tn).notOptional(s"Missing active technique with name ${tn}")
          saved           <- woDirectiveRepo.saveDirective(activeTechnique.id, d, modId, change.actor, change.reason)
        } yield {
          saved
        }
      }.toBox

      for {
        change <- directiveChanges.changes.change.toBox
        diff   <- change.diff match {
                    case DeleteDirectiveDiff(tn, d)       =>
                      dependencyService
                        .cascadeDeleteDirective(d.id.uid, modId, change.actor, change.reason)
                        .map(_ => DeleteDirectiveDiff(tn, d))
                    case ModifyToDirectiveDiff(tn, d, rs) =>
                      // if the save returns None, then we return the original modification object
                      save(tn, d, change).map(_.getOrElse(ModifyToDirectiveDiff(tn, d, rs)))
                    case AddDirectiveDiff(tn, d)          =>
                      // if the save returns None, then we return the original modification object
                      save(tn, d, change).map(_.getOrElse(AddDirectiveDiff(tn, d)))
                  }
      } yield {
        diff
      }
    }

    def doNodeGroupChange(change: NodeGroupChanges)(implicit qc: QueryContext): IOResult[TriggerDeploymentDiff] = {
      for {
        change <- change.changes.change.toIO
        diff   <- change.diff match {
                    case DeleteNodeGroupDiff(n)   =>
                      dependencyService
                        .cascadeDeleteTarget(GroupTarget(n.id))
                        .map(_ => DeleteNodeGroupDiff(n))
                    case AddNodeGroupDiff(n)      =>
                      Inconsistency("You should not be able to create a group with a change request").fail
                    case ModifyToNodeGroupDiff(n) =>
                      // we first need to refresh the node list if it's a dynamic group
                      val group = if (n.isDynamic) updateDynamicGroups.computeDynGroup(n)(using qc).toIO else n.succeed

                      // If we could get a nodeList, then we apply the change, else we bubble up the error
                      group.flatMap { resultingGroup =>
                        // if the update returns None, then we return the original modification object
                        woNodeGroupRepo
                          .update(resultingGroup)
                          .map(_.getOrElse(ModifyToNodeGroupDiff(n)))

                      }
                  }
      } yield {
        diff
      }
    }

    def doRuleChange(change: RuleChanges): Box[TriggerDeploymentDiff] = {
      for {
        change <- change.changes.change.toBox
        diff   <- (change.diff match {
                    case DeleteRuleDiff(r)   =>
                      woRuleRepository.delete(r.id, modId, change.actor, change.reason)
                    case AddRuleDiff(r)      =>
                      woRuleRepository.create(r, modId, change.actor, change.reason)
                    case ModifyToRuleDiff(r) =>
                      // if the update returns None, then we return the original modification object
                      woRuleRepository.update(r, modId, change.actor, change.reason).map(_.getOrElse(ModifyToRuleDiff(r)))
                  }).toBox
      } yield {
        diff
      }
    }

    def doParamChange(change: GlobalParameterChanges): Box[TriggerDeploymentDiff] = {
      for {
        change <- change.changes.change.toBox
        diff   <- (change.diff match {
                    case DeleteGlobalParameterDiff(param)   =>
                      woParameterRepository
                        .delete(param.name, Some(PropertyProvider.defaultPropertyProvider), modId, change.actor, change.reason)
                        .map {
                          case None       => new TriggerDeploymentDiff { override def needDeployment: Boolean = false }
                          case Some(diff) => diff
                        }
                    case AddGlobalParameterDiff(param)      =>
                      woParameterRepository.saveParameter(param, modId, change.actor, change.reason)
                    case ModifyToGlobalParameterDiff(param) =>
                      // if the update returns None, then we return the original modification object
                      woParameterRepository
                        .updateParameter(param, modId, change.actor, change.reason)
                        .map(_.getOrElse(ModifyToGlobalParameterDiff(param)))
                  }).toBox
      } yield {
        diff
      }
    }

    /*
     * Logic to commit Change request with multiple changes:
     *
     * - such a change request is NOT atomic, each change is
     *   committed independently to other
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

    val sortedParam = cr.globalParams.values.toSeq.sortWith {
      case (x, y) =>
        (for {
          c1 <- x.changes.change
          c2 <- y.changes.change
        } yield {
          (c1.diff, c2.diff) match {
            case (_: DeleteGlobalParameterDiff, _: DeleteGlobalParameterDiff) => true
            case (_, _: DeleteGlobalParameterDiff)                            => false
            case (_, _: AddGlobalParameterDiff)                               => false
            case _                                                            => true
          }
        }).getOrElse(true)
    }

    val sortedGroups = cr.nodeGroups.values.toSeq.sortWith {
      case (x, y) =>
        (for {
          c1 <- x.changes.change
          c2 <- y.changes.change
        } yield {
          (c1.diff, c2.diff) match {
            case (_: DeleteNodeGroupDiff, _: DeleteNodeGroupDiff) => true
            case (_, _: DeleteNodeGroupDiff)                      => false
            case (_, _: AddNodeGroupDiff)                         => false
            case _                                                => true
          }
        }).getOrElse(true)
    }

    val sortedDirectives = cr.directives.values.toSeq.sortWith {
      case (x, y) =>
        (for {
          c1 <- x.changes.change
          c2 <- y.changes.change
        } yield {
          (c1.diff, c2.diff) match {
            case (_: DeleteDirectiveDiff, _: DeleteDirectiveDiff) => true
            case (_, _: DeleteDirectiveDiff)                      => false
            case (_, _: AddDirectiveDiff)                         => false
            case _                                                => true
          }
        }).getOrElse(true)
    }

    val sortedRules = cr.rules.values.toSeq.sortWith {
      case (x, y) =>
        (for {
          c1 <- x.changes.change
          c2 <- y.changes.change
        } yield {
          (c1.diff, c2.diff) match {
            case (_: DeleteRuleDiff, _: DeleteRuleDiff) => true
            case (_, _: DeleteRuleDiff)                 => false
            case (_, _: AddRuleDiff)                    => false
            case _                                      => true
          }
        }).getOrElse(true)
    }

    val params     = bestEffort(sortedParam)(param => doParamChange(param))
    val groups     = bestEffort(sortedGroups)(nodeGroupChange => doNodeGroupChange(nodeGroupChange)(using cc.toQC).toBox)
    val directives = bestEffort(sortedDirectives)(directiveChange => doDirectiveChange(directiveChange))
    val rules      = bestEffort(sortedRules)(rule => doRuleChange(rule))
    // TODO: we will want to keep tracks of all the modification done, and in
    // particular of all the error
    // not fail on the first erroneous, but in case of error, get them all.
    // For each of the step, we need to detect if a deployment is required
    for {
      triggerDeployments <- bestEffort(List(params, directives, groups, rules)) {
                              _.map(x => x.exists(y => (y.needDeployment == true)))
                            }
    } yield {
      // If one of the step require a deployment, then we need to trigger a deployment
      triggerDeployments.contains(true)
    }

  }

}

object CheckDivergenceForMerge {

  /*
   * In a string, we don't want to take care of a difference in the number of spaces
   * in names / description.
   * We also remove space in the end.
   * Do not use it in a place where space may be significant, like a template.
   */
  def normalizeString(text: String): String = {
    text.replaceAll("""\s+""", " ").trim
  }

  def debugLog(message: String)(implicit changeRequestId: Int): Unit = {
    ChangeRequestLoggerPure.debug(s"CR #${changeRequestId}: ${message}")
  }
  /*
   * Comparison methods between Rules/directives/groups/global Param
   * They are used to check if they are mergeable.
   */

  def compareRules(initial: Rule, current: Rule)(implicit changeRequestId: Int): Boolean = {
    def normalizeRule(r: Rule) = r.copy(
      name = normalizeString(r.name),
      shortDescription = normalizeString(r.shortDescription),
      longDescription = normalizeString(r.longDescription)
    )

    val initialFixed = normalizeRule(initial)
    val currentFixed = normalizeRule(current)

    if (initialFixed == currentFixed) {
      // No conflict return
      true
    } else {
      // Write debug logs to understand what cause the conflict
      debugLog("Merge Change Request (CR) warning: initial state diverged from current state.")
      if (initialFixed.name != currentFixed.name) {
        debugLog(
          s"Rule ID ${initialFixed.id.serialize} name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}"
        )
      }

      if (initialFixed.shortDescription != currentFixed.shortDescription) {
        debugLog(
          s"Rule ID ${initialFixed.id.serialize} short description has changed: original state from CR: ${initialFixed.shortDescription}, current value: ${currentFixed.shortDescription}"
        )
      }

      if (initialFixed.longDescription != currentFixed.longDescription) {
        debugLog(
          s"Rule ID ${initialFixed.id.serialize} long description has changed: original state from CR: '${initialFixed.longDescription}', current value: '${currentFixed.longDescription}''"
        )
      }

      def displayTarget(target: RuleTarget) = {
        target match {
          case GroupTarget(groupId)       => s"group: ${groupId.serialize}"
          case PolicyServerTarget(nodeId) => s"policyServer: ${nodeId.value}"
          case _                          => target.target
        }
      }

      if (initialFixed.targets != currentFixed.targets) {
        debugLog(
          s"Rule ID ${initialFixed.id.serialize} target Groups have changed: original state from CR: ${initialFixed.targets
              .map(displayTarget)
              .mkString("[ ", ", ", " ]")}, current value: ${currentFixed.targets.map(displayTarget).mkString("[ ", ", ", " ]")}"
        )
      }

      if (initialFixed.isEnabledStatus != currentFixed.isEnabledStatus) {
        debugLog(
          s"Rule ID ${initialFixed.id.serialize} enable status has changed: original state from CR: ${initialFixed.isEnabledStatus}, current value: ${currentFixed.isEnabledStatus}"
        )
      }

      if (initialFixed.directiveIds != currentFixed.directiveIds) {
        debugLog(
          s"Rule ID ${initialFixed.id.serialize} attached Directives have changed: original state from CR: ${initialFixed.directiveIds
              .map(_.debugString)
              .mkString("[ ", ", ", " ]")}, current value: ${currentFixed.directiveIds.map(_.debugString).mkString("[ ", ", ", " ]")}"
        )
      }

      // return
      false
    }
  }

  def compareDirectives(initial: Directive, current: Directive)(implicit changeRequestId: Int): Boolean = {
    def normalizeDirective(d: Directive) = d.copy(
      name = normalizeString(d.name),
      shortDescription = normalizeString(d.shortDescription),
      longDescription = normalizeString(d.longDescription),
      parameters = d.parameters.view.mapValues(v => v.map(vv => normalizeString(vv))).toMap
    )

    val initialFixed = normalizeDirective(initial)
    val currentFixed = normalizeDirective(current)

    if (initialFixed == currentFixed) {
      // return
      true
    } else {
      // Write debug logs to understand what cause the conflict
      debugLog(
        "Attempt to merge Change Request (CR) failed because initial state could not be rebased on current state."
      )

      if (initialFixed.name != currentFixed.name) {
        debugLog(
          s"Directive ID ${initialFixed.id.uid.value} name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}"
        )
      }

      if (initialFixed.shortDescription != currentFixed.shortDescription) {
        debugLog(
          s"Directive ID ${initialFixed.id.uid.value} short description has changed: original state from CR: ${initialFixed.shortDescription}, current value: ${currentFixed.shortDescription}"
        )
      }

      if (initialFixed.longDescription != currentFixed.longDescription) {
        debugLog(
          s"Directive ID ${initialFixed.id.uid.value} long description has changed: original state from CR: ${initialFixed.longDescription}, current value: ${currentFixed.longDescription}"
        )
      }

      if (initialFixed.priority != currentFixed.priority) {
        debugLog(
          s"Directive ID ${initialFixed.id.uid.value} priority has changed: original state from CR: ${initialFixed.priority}, current value: ${currentFixed.priority}"
        )
      }

      if (initialFixed.isEnabled != currentFixed.isEnabled) {
        debugLog(
          s"Directive ID ${initialFixed.id.uid.value} enable status has changed: original state from CR: ${initialFixed.isEnabled}, current value: ${currentFixed.isEnabled}"
        )
      }

      if (initialFixed.techniqueVersion != currentFixed.techniqueVersion) {
        debugLog(
          s"Directive ID ${initialFixed.id.uid.value} Technique version has changed: original state from CR: ${initialFixed.techniqueVersion.debugString}, current value: ${currentFixed.techniqueVersion.debugString}"
        )
      }

      for {
        key    <- (initialFixed.parameters.keys ++ currentFixed.parameters.keys).toSeq.distinct
        initVal = initialFixed.parameters.get(key)
        currVal = currentFixed.parameters.get(key)
      } yield {
        if (currVal != initVal) {
          debugLog(s"Directive ID ${initialFixed.id.uid.value} parameter $key has changed : original state from CR: ${initVal
              .getOrElse("value is missing")}, current value: ${currVal.getOrElse("value is missing")}")
        }
      }

      // return
      false
    }
  }

  def compareGroups(initial: NodeGroup, current: NodeGroup)(implicit changeRequestId: Int): Boolean = {
    def normalizeGroup(g: NodeGroup) = g.copy(
      name = normalizeString(initial.name),
      description = normalizeString(initial.description),
      properties = initial.properties.map(c => c.copy(config = c.config.withoutPath(GenericProperty.PROVIDER))).sortBy(_.name)
    )

    val initialFixed = normalizeGroup(initial)
    val currentFixed = normalizeGroup(current).copy(
      /*
       * We need to remove nodes from dynamic groups, it has no sense to compare them.
       * In a static group, the node list is important and can be very different,
       * and depends on when the change request was made.
       * Maybe a future upgrade will be to check the parameters first and then check the nodelist.
       */
      serverList = (
        if (current.isDynamic) {
          initial
        } else {
          current
        }
      ).serverList
    )

    if (initialFixed == currentFixed) {
      // No conflict return
      true
    } else {
      // Write debug logs to understand what cause the conflict
      debugLog(
        "Attempt to merge Change Request (CR) failed because initial state could not be rebased on current state."
      )

      if (initialFixed.name != currentFixed.name) {
        debugLog(
          s"Group ID ${initialFixed.id.serialize} name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}"
        )
      }

      if (initialFixed.description != currentFixed.description) {
        debugLog(
          s"Group ID ${initialFixed.id.serialize} description has changed: original state from CR: ${initialFixed.description}, current value: ${currentFixed.description}"
        )
      }

      if (initialFixed.query != currentFixed.query) {
        debugLog(
          s"Group ID ${initialFixed.id.serialize} query has changed: original state from CR: ${initialFixed.query}, current value: ${currentFixed.query}"
        )
      }

      if (initialFixed.isDynamic != currentFixed.isDynamic) {
        debugLog(
          s"Group ID ${initialFixed.id.serialize} dynamic status has changed: original state from CR: ${initialFixed.isDynamic}, current value: ${currentFixed.isDynamic}"
        )
      }

      if (initialFixed.isEnabled != currentFixed.isEnabled) {
        debugLog(
          s"Group ID ${initialFixed.id.serialize} enable status has changed: original state from CR: ${initialFixed.isEnabled}, current value: ${currentFixed.isEnabled}"
        )
      }

      // we compare nodes only for static group, not dynamic ones
      if (!(initialFixed.isDynamic && currentFixed.isDynamic) && initialFixed.serverList != currentFixed.serverList) {
        debugLog(
          s"Group ID ${initialFixed.id.serialize} nodes list has changed: original state from CR: ${initialFixed.serverList
              .map(_.value)
              .mkString("[ ", ", ", " ]")}, current value: ${currentFixed.serverList.map(_.value).mkString("[ ", ", ", " ]")}"
        )
      }

      if (initialFixed.properties != currentFixed.properties) {
        debugLog(s"Group ID ${initialFixed.id.serialize} properties changed: original state from CR: ${initialFixed.properties
            .map(_.toData)
            .mkString("[ ", ", ", " ]")}, current value: ${currentFixed.properties.map(_.toData).mkString("[ ", ", ", " ]")}")
      }

      // return
      false
    }
  }

  def compareGlobalParameter(initial: GlobalParameter, current: GlobalParameter)(implicit changeRequestId: Int): Boolean = {
    def normalizeParam(p: GlobalParameter) = p.withDescription(
      normalizeString(p.description)
    )

    val initialFixed = normalizeParam(initial)
    val currentFixed = normalizeParam(current)

    if (initialFixed == currentFixed) {
      // No conflict return
      true
    } else {
      // Write debug logs to understand what cause the conflict
      debugLog(
        "Attempt to merge Change Request (CR) failed because initial state could not be rebased on current state."
      )

      if (initialFixed.name != currentFixed.name) {
        debugLog(
          s"Global Parameter name has changed: original state from CR: ${initialFixed.name}, current value: ${currentFixed.name}"
        )
      }

      if (initialFixed.description != currentFixed.description) {
        debugLog(
          s"Global Parameter '${initialFixed.name}' description has changed: original state from CR: ${initialFixed.description}, current value: ${currentFixed.description}"
        )
      }

      if (initialFixed.value != currentFixed.value) {
        debugLog(
          s"Global Parameter '${initialFixed.name}' value has changed: original state from CR: ${initialFixed.value}, current value: ${currentFixed.value}"
        )
      }

      // return
      false
    }
  }
}
