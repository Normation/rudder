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

package com.normation.rudder.batch

import cats.syntax.bifunctor.*
import com.normation.errors.*
import com.normation.eventlog.EventActor
import com.normation.eventlog.EventLog
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.eventlog.*
import com.normation.rudder.domain.logger.PolicyGenerationLogger
import com.normation.rudder.domain.logger.PolicyGenerationLoggerPure
import com.normation.rudder.domain.logger.StatusLoggerPure
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.properties.FailedNodePropertyHierarchy
import com.normation.rudder.domain.properties.ResolvedNodePropertyHierarchy
import com.normation.rudder.domain.properties.SuccessNodePropertyHierarchy
import com.normation.rudder.ncf.CompilationStatus
import com.normation.rudder.ncf.CompilationStatusAllSuccess
import com.normation.rudder.ncf.CompilationStatusErrors
import com.normation.rudder.services.eventlog.EventLogDeploymentService
import com.normation.rudder.services.marshalling.DeploymentStatusSerialisation
import com.normation.rudder.services.policies.PromiseGenerationService
import com.normation.utils.DateFormaterService.toJavaInstant
import com.normation.zio.*
import enumeratum.*
import enumeratum.EnumEntry.*
import net.liftweb.actor.*
import net.liftweb.common.*
import net.liftweb.http.ListenerManager
import org.joda.time.*
import scala.concurrent.duration.Duration
import scala.xml.NodeSeq
import zio.*
import zio.syntax.*

sealed trait StartDeploymentMessage

//ask for a new deployment - automatic deployment !
//actor: the actor who asked for the deployment
final case class AutomaticStartDeployment(modId: ModificationId, actor: EventActor) extends StartDeploymentMessage

//ask for a new deployment - manual deployment (human clicked on "regenerate now"
//actor: the actor who asked for the deployment
final case class ManualStartDeployment(modId: ModificationId, actor: EventActor, reason: String) extends StartDeploymentMessage

sealed trait PolicyGenerationTrigger extends EnumEntry with LowerCamelcase
object PolicyGenerationTrigger       extends Enum[PolicyGenerationTrigger] {
  case object All        extends PolicyGenerationTrigger
  case object OnlyManual extends PolicyGenerationTrigger
  case object None       extends PolicyGenerationTrigger

  override def values: IndexedSeq[PolicyGenerationTrigger] = findValues

  override def extraNamesToValuesMap: Map[String, PolicyGenerationTrigger] = Map(
    "allGeneration"        -> All,
    "noGeneration"         -> None,
    "manual"               -> OnlyManual,
    "onlyManualGeneration" -> OnlyManual
  )

  def parse(value: String): Either[String, PolicyGenerationTrigger] = {
    withNameInsensitiveEither(value).leftMap(err => s"'${value}' is not a valid generation policy: ${err.getMessage}")
  }
}

/**
 * State of the deployment agent.
 */

sealed trait DeployerState
//not currently doing anything
case object IdleDeployer                                 extends DeployerState
//a deployment is currently running
final case class Processing(id: Long, started: DateTime) extends DeployerState
//a deployment is currently running and an other is queued
final case class ProcessingAndPendingAuto(asked: DateTime, current: Processing, actor: EventActor, eventLogId: Int)
    extends DeployerState
//a deployment is currently running and a manual is queued
final case class ProcessingAndPendingManual(
    asked:      DateTime,
    current:    Processing,
    actor:      EventActor,
    eventLogId: Int,
    reason:     String
) extends DeployerState

/**
 * Status of the last deployment process
 */
sealed trait CurrentDeploymentStatus

//noting was done for now
case object NoStatus                                                                         extends CurrentDeploymentStatus
//last status - success or error
final case class SuccessStatus(id: Long, started: DateTime, ended: DateTime, configuration: Set[NodeId])
    extends CurrentDeploymentStatus
final case class ErrorStatus(id: Long, started: DateTime, ended: DateTime, failure: Failure) extends CurrentDeploymentStatus

final case class DeploymentStatus(
    current:    CurrentDeploymentStatus,
    processing: DeployerState
)

case class UpdateCompilationStatus(
    status: CompilationStatus
)

case class UpdatePropertiesStatus(
    nodes:  Map[NodeId, ResolvedNodePropertyHierarchy],
    groups: Map[NodeGroupId, ResolvedNodePropertyHierarchy]
) {
  private[batch] def display: String = {
    val displayResolved: ResolvedNodePropertyHierarchy => String = {
      case f: FailedNodePropertyHierarchy  => f.error.debugString + (if (f.noResolved) "" else s"${f.success.size} success")
      case s: SuccessNodePropertyHierarchy => s"${s.resolved.size} success"
    }
    val displayNodes = nodes.map { case (id, r) => s"${id.value}(${displayResolved(r)})" }.mkString("[", ",", "]")
    val displayGroups = nodes.map { case (id, r) => s"${id.value}(${displayResolved(r)})" }.mkString("[", ",", "]")

    s"UpdatePropertiesStatus(nodes = ${displayNodes}, groups = ${displayGroups})"
  }
}

final case class AsyncDeploymentActorCreateUpdate(
    deploymentStatus:  DeploymentStatus,
    compilationStatus: CompilationStatus,
    nodeProperties:    Map[NodeId, ResolvedNodePropertyHierarchy],
    groupProperties:   Map[NodeGroupId, ResolvedNodePropertyHierarchy]
)

/**
 * Async version of the deployment service.
 */

trait AsyncDeploymentAgent {

  def launchDeployment(msg: StartDeploymentMessage): Unit

}

final class AsyncDeploymentActor(
    deploymentService:             PromiseGenerationService,
    eventLogger:                   EventLogDeploymentService,
    deploymentStatusSerialisation: DeploymentStatusSerialisation,
    getGenerationDelay:            () => IOResult[Duration],
    deploymentPolicy:              () => IOResult[PolicyGenerationTrigger],
    bootGuard:                     Promise[Nothing, Unit]
) extends LiftActor with ListenerManager with AsyncDeploymentAgent {

  deploymentManager =>

  val timeFormat = "yyyy-MM-dd HH:mm:ss"

  // message from the deployment agent to the manager
  sealed private case class DeploymentResult(
      id:         Long,
      modId:      ModificationId,
      start:      DateTime,
      end:        DateTime,
      results:    Box[Set[NodeId]],
      actor:      EventActor,
      eventLogId: Int
  )

  // message from manager to deployment agent
  private case class NewDeployment(id: Long, modId: ModificationId, started: DateTime, actor: EventActor, eventLogId: Int)

  private var lastFinishedDeployement: CurrentDeploymentStatus                         = getLastFinishedDeployment
  private var compilationStatus:       CompilationStatus                               = CompilationStatusAllSuccess
  private var nodeProperties:          Map[NodeId, ResolvedNodePropertyHierarchy]      = Map.empty
  private var groupProperties:         Map[NodeGroupId, ResolvedNodePropertyHierarchy] = Map.empty
  private var currentDeployerState:    DeployerState                                   = IdleDeployer
  private var currentDeploymentId = lastFinishedDeployement match {
    case NoStatus => 0L
    case a: SuccessStatus => a.id
    case a: ErrorStatus   => a.id
  }

  def getStatus:       CurrentDeploymentStatus = lastFinishedDeployement
  def getCurrentState: DeployerState           = currentDeployerState

  private def getLastFinishedDeployment: CurrentDeploymentStatus = {
    eventLogger.getLastDeployement() match {
      case Empty =>
        PolicyGenerationLogger.manager.debug("Could not find a last policy update")
        NoStatus
      case m: Failure =>
        PolicyGenerationLogger.manager.debug(s"Error when fetching the last policy update, reason: ${m.messageChain}")
        NoStatus
      case Full(status) => status
    }
  }

  // this method is here for testing purpose.

  override def launchDeployment(msg: StartDeploymentMessage): Unit = this ! msg

  /**
   * Manage what we send on other listener actors
   */
  override def createUpdate: AsyncDeploymentActorCreateUpdate = {
    AsyncDeploymentActorCreateUpdate(
      DeploymentStatus(lastFinishedDeployement, currentDeployerState),
      compilationStatus,
      nodeProperties,
      groupProperties
    )
  }

  private def WithDetails(xml: NodeSeq)(implicit actor: EventActor, reason: Option[String] = None) = {
    EventLogDetails(
      modificationId = None,
      principal = actor,
      reason = reason,
      details = EventLog.withContent(xml)
    )
  }

  // deprecated flag file to trigger policy generation at start
  val triggerPolicyUpdateFlagPath = "/opt/rudder/etc/trigger-policy-generation"

  private def logTriggerError(v: PureResult[PolicyGenerationTrigger]) = {
    v match {
      case Left(err) =>
        val msg =
          "Policy generation: An error occurred while getting generation policy, Starting policy generation even if not authorized"
        PolicyGenerationLogger.error(msg + ": " + err.fullMsg)
      case _         => ()
    }
  }

  override protected def lowPriority: PartialFunction[Any, Unit] = {

    //
    // Start a new deployment. Some triggers can be inhibited with
    // the `rudder_generation_trigger` (all, none, onlymanual) setting.
    //
    case AutomaticStartDeployment(modId, actor) => {
      implicit val a = actor

      PolicyGenerationLogger.manager.trace("Policy updater: receive new automatic policy update request message")

      deploymentPolicy().either.runNow match {
        case Right(PolicyGenerationTrigger.None | PolicyGenerationTrigger.OnlyManual) =>
          PolicyGenerationLogger.manager.info(
            "Policy generation: Due to policy generation policy, no automatic policy generation was triggered "
          )

        case v @ (Right(PolicyGenerationTrigger.All) | Left(_)) =>
          logTriggerError(v)
          currentDeployerState match {
            case IdleDeployer => // ok, start a new deployment
              currentDeploymentId += 1
              val newState = Processing(currentDeploymentId, DateTime.now(DateTimeZone.UTC))
              currentDeployerState = newState
              PolicyGenerationLogger.manager.debug("Automatic policy generation request: start policy generation (none running)")
              val event    = eventLogger.repository
                .saveEventLog(
                  modId,
                  AutomaticStartDeployement(WithDetails(NodeSeq.Empty))
                )
                .either
                .runNow
                .toOption
              DeployerAgent ! NewDeployment(newState.id, modId, newState.started, actor, event.flatMap(_.id).getOrElse(0))

            // we don't generate eventlogs when queuing/dropping an automatic start, see https://issues.rudder.io/issues/22879

            case p @ Processing(id, startTime) => // ok, add a pending deployment
              PolicyGenerationLogger.manager.debug(
                "Automatic policy generation request: queued - one policy " +
                "generation already running"
              )
              currentDeployerState = ProcessingAndPendingAuto(DateTime.now(DateTimeZone.UTC), p, actor, 0)

            case p: ProcessingAndPendingAuto => // drop message, one is already pending
              PolicyGenerationLogger.manager.debug(
                "Automatic policy generation request: dropped - one policy generation already running"
              )

            case p: ProcessingAndPendingManual => // drop message, one is already pending
              PolicyGenerationLogger.manager.debug(
                "Automatic policy generation request: dropped - one policy generation already running"
              )
          }
      }
      // update listeners
      updateListeners()
    }

    case ManualStartDeployment(modId, actor, reason)                                       => {
      implicit val a = actor
      implicit val r = Some(reason)

      PolicyGenerationLogger.manager.trace("Policy updater: receive new manual policy update request message")

      deploymentPolicy().either.runNow match {
        case Right(PolicyGenerationTrigger.None) =>
          PolicyGenerationLogger.manager.debug(
            "Policy generation: Due to policy generation policy, no manual policy generation was triggered "
          )

        case v @ (Right(_) | Left(_)) =>
          logTriggerError(v)
          currentDeployerState match {
            case IdleDeployer => // ok, start a new deployment
              currentDeploymentId += 1
              val newState = Processing(currentDeploymentId, DateTime.now)
              currentDeployerState = newState
              PolicyGenerationLogger.manager.debug("Manual policy generation request: start policy generation (none running)")
              val event    = eventLogger.repository
                .saveEventLog(
                  modId,
                  ManualStartDeployement(WithDetails(NodeSeq.Empty))
                )
                .either
                .runNow
                .toOption
              DeployerAgent ! NewDeployment(newState.id, modId, newState.started, actor, event.flatMap(_.id).getOrElse(0))

            case p @ Processing(id, startTime) => // ok, add a pending deployment
              PolicyGenerationLogger.manager.debug(
                "Manual policy generation request: queued - one policy generation already running"
              )
              val event = eventLogger.repository
                .saveEventLog(
                  modId,
                  ManualStartDeployement(WithDetails(<addPending alreadyPending="false"/>))
                )
                .either
                .runNow
                .toOption
              currentDeployerState =
                ProcessingAndPendingManual(DateTime.now(DateTimeZone.UTC), p, actor, event.flatMap(_.id).getOrElse(0), reason)

            case p: ProcessingAndPendingManual => // drop message, one is already pending
              eventLogger.repository
                .saveEventLog(
                  modId,
                  ManualStartDeployement(WithDetails(<addPending alreadyPending="true"/>))
                )
                .runNowLogError(err =>
                  PolicyGenerationLogger.error(s"Error when saving start generation event log: ${err.fullMsg}")
                )
              PolicyGenerationLogger.manager.debug(
                "Manual policy generation request: dropped - one policy generation already running"
              )

            case p: ProcessingAndPendingAuto => // replace with manual
              val event = eventLogger.repository
                .saveEventLog(
                  modId,
                  ManualStartDeployement(WithDetails(<addPending alreadyPending="true"/>))
                )
                .either
                .runNow
                .toOption
              currentDeployerState = ProcessingAndPendingManual(
                DateTime.now(DateTimeZone.UTC),
                p.current,
                actor,
                event.flatMap(_.id).getOrElse(0),
                reason
              )
              PolicyGenerationLogger.manager.debug(
                "Manual policy generation request: dropped - one policy generation already running"
              )
          }
      }
      // update listeners
      updateListeners()
    }
    //
    // response from the deployer
    //
    case DeploymentResult(id, modId, startTime, endTime, result, actor, deploymentEventId) => {
      // process the result
      result match {
        case e: EmptyBox =>
          val m = s"Policy update error for process '${id}' at ${endTime.toString(timeFormat)}"
          PolicyGenerationLogger.manager.error(m, e)
          lastFinishedDeployement = ErrorStatus(id, startTime, endTime, e ?~! m)
          eventLogger.repository
            .saveEventLog(
              modId,
              FailedDeployment(
                EventLogDetails(
                  modificationId = None,
                  principal = actor,
                  details = EventLog.withContent(deploymentStatusSerialisation.serialise(lastFinishedDeployement)),
                  cause = Some(deploymentEventId),
                  creationDate = startTime.toJavaInstant,
                  reason = None
                )
              )
            )
            .runNowLogError(err =>
              PolicyGenerationLogger.manager.error(s"Error when saving generation event log result: ${err.fullMsg}")
            )

        case Full(nodeIds) =>
          PolicyGenerationLogger.manager.info(
            s"Successful policy update '${id}' [started ${startTime.toString(timeFormat)} - ended ${endTime.toString(timeFormat)}]"
          )
          lastFinishedDeployement = SuccessStatus(id, startTime, endTime, nodeIds)
          eventLogger.repository
            .saveEventLog(
              modId,
              SuccessfulDeployment(
                EventLogDetails(
                  modificationId = None,
                  principal = actor,
                  details = EventLog.withContent(deploymentStatusSerialisation.serialise(lastFinishedDeployement)),
                  cause = Some(deploymentEventId),
                  creationDate = startTime.toJavaInstant,
                  reason = None
                )
              )
            )
            .runNowLogError(err =>
              PolicyGenerationLogger.manager.error(s"Error when saving generation event log result: ${err.fullMsg}")
            )
      }

      // look if there is another process to start and update current deployer status
      currentDeployerState match {
        case IdleDeployer => // should never happen
          PolicyGenerationLogger.manager.debug(
            "Found an IdleDeployer state for policy updater agent but it just gave me a result. What happened ?"
          )

        case p: Processing => // ok, come back to IdleDeployer
          currentDeployerState = IdleDeployer

        case p: ProcessingAndPendingAuto => // come back to IdleDeployer but immediately ask for another deployment
          currentDeployerState = IdleDeployer
          this ! AutomaticStartDeployment(modId, RudderEventActor)

        case p: ProcessingAndPendingManual => // come back to IdleDeployer but immediately ask for another deployment
          currentDeployerState = IdleDeployer
          this ! ManualStartDeployment(modId, RudderEventActor, p.reason)

      }
      // update listeners
      updateListeners()
    }

    //
    // Updating state from technique compilation output result
    //
    case UpdateCompilationStatus(status)                                                   => {
      StatusLoggerPure.Techniques.logEffect.trace(
        s"Compilation status has a new update command with payload : ${status}"
      )
      if (compilationStatus != status) {
        compilationStatus = status
        status match {
          case CompilationStatusErrors(techniquesInError) =>
            StatusLoggerPure.Techniques.logEffect.warn(s"Status of technique compilation has ${techniquesInError.size} errors")
          case CompilationStatusAllSuccess                =>
            StatusLoggerPure.Techniques.logEffect.debug(
              s"Status of technique compilation has changed to success from previous : ${compilationStatus}"
            )
        }
        updateListeners()
      }
    }

    //
    // Updating state from properties status change
    //
    case s @ UpdatePropertiesStatus(nodes, groups)                                         => {
      StatusLoggerPure.Properties.logEffect.trace(
        s"Properties status actor has a new update command with payload : ${s.display}"
      )
      // detecting change on status could be done more accurately
      // than just strict equality check : ordering may change
      // it only has impact on logs so it is sufficient
      if (nodeProperties != nodes || groupProperties != groups) {
        nodeProperties = nodes
        groupProperties = groups
        val (nodesErrors, _)  = nodes.partitionMap {
          case (nid, _: FailedNodePropertyHierarchy)  => Left(nid.value)
          case (nid, _: SuccessNodePropertyHierarchy) => Right(nid.value)
        }
        val (groupsErrors, _) = groups.partitionMap {
          case (gid, _: FailedNodePropertyHierarchy)  => Left(gid.serialize)
          case (gid, _: SuccessNodePropertyHierarchy) => Right(gid.serialize)
        }
        if (nodesErrors.nonEmpty) {
          StatusLoggerPure.Properties.logEffect.warn(s"Status of properties has errors for nodes ${nodesErrors.mkString(",")}")
        }
        if (groupsErrors.nonEmpty) {
          StatusLoggerPure.Properties.logEffect.warn(s"Status of properties has errors for groups ${groupsErrors.mkString(",")}")
        }
        if (nodesErrors.isEmpty && groupsErrors.isEmpty) {
          StatusLoggerPure.Properties.logEffect.debug(
            s"Status of properties has changed to success from previous : ${s.display}"
          )
        }
        updateListeners()
      }
    }

    //
    // Unexpected messages
    //
    case x                                                                                 => PolicyGenerationLogger.manager.debug("Policy updater does not know how to process message: '%s'".format(x))
  }

  /**
   * The internal agent that will actually do the deployment
   * Long time running process, I/O consuming.
   */
  private object DeployerAgent extends LiftActor {

    def doDeployement(bootSemaphore: Promise[Nothing, Unit], delay: IOResult[Duration], nd: NewDeployment): UIO[Unit] = {
      val prog = for {
        _ <- PolicyGenerationLoggerPure.manager.trace("Policy updater Agent: start to update policies")
        d <- delay
        _ <- ZIO.when(d.toMillis > 0) {
               PolicyGenerationLoggerPure.manager.info(s"Policy generation will start in ${d.toString}")
             }
        _ <- (for {
               _   <- PolicyGenerationLoggerPure.manager.debug(s"Policy generation starts now!")
               res <- deploymentService
                        .deploy()
                        .toIO
                        .foldZIO(
                          err =>
                            PolicyGenerationLoggerPure.manager.error(s"Error when updating policy, reason was: ${err.fullMsg}") *>
                            Failure(err.fullMsg).succeed,
                          ok => Full(ok).succeed
                        )
               _   <-
                 IOResult.attempt(
                   deploymentManager ! DeploymentResult(
                     nd.id,
                     nd.modId,
                     nd.started,
                     DateTime.now(DateTimeZone.UTC),
                     res,
                     nd.actor,
                     nd.eventLogId
                   )
                 )
             } yield ()).delay(zio.Duration.fromScala(d))
      } yield ()

      val managedErr = prog.catchAll { err =>
        val failure = Failure(s"Exception caught during policy update process: ${err.fullMsg}")
        IOResult
          .attempt(
            deploymentManager ! DeploymentResult(
              nd.id,
              nd.modId,
              nd.started,
              DateTime.now(DateTimeZone.UTC),
              failure,
              nd.actor,
              nd.eventLogId
            )
          )
          .catchAll(fatal => {
            PolicyGenerationLoggerPure.manager.error(
              s"Fatal error when trying to send previous error to policy generation manager. First error was: ${err.fullMsg}. Fatal error is: ${fatal.fullMsg}"
            )
          })
      }

      bootSemaphore.await *> managedErr
    }

    override protected def messageHandler: PartialFunction[Any, Unit] = {
      //
      // Start a new deployment. Wait for the guard to be released (in `Boot.boot`).
      // Each generation can be delayed by a given duration with the `rudder_generation_delay` setting.
      //
      case nd: NewDeployment =>
        doDeployement(bootGuard, getGenerationDelay(), nd).runNow

      //
      // Unexpected messages
      //
      case x =>
        val msg = s"Policy updater agent does not know how to process message: '${x}'"
        PolicyGenerationLogger.manager.error(msg)
        deploymentManager ! DeploymentResult(
          -1,
          ModificationId.dummy,
          DateTime.now(DateTimeZone.UTC),
          DateTime.now(DateTimeZone.UTC),
          Failure(msg),
          RudderEventActor,
          0
        )
    }
  }
}
