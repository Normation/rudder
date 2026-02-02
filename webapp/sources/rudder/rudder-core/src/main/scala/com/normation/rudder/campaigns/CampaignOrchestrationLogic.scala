/*
 *************************************************************************************
 * Copyright 2025 Normation SAS
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

package com.normation.rudder.campaigns

import com.normation.errors.*
import com.normation.rudder.campaigns.CampaignEventState.*
import com.normation.rudder.campaigns.CampaignEventStateType.*
import com.normation.rudder.hooks.HookReturnCode
import com.normation.utils.DateFormaterService
import com.normation.utils.StringUuidGenerator
import com.softwaremill.quicklens.*
import org.joda.time.{Duration as JTDuration, *}
import zio.*
import zio.syntax.*

/*
 * This file defines all the workflow logic for campaigns, and the organisation with
 * plugin specialized campaign handlers.
 *
 * Pure logic + IO ports.
 * The logic part (how to handle an event, what to do next, etc) is separated from the IO,
 * so that the resulting classes are more easily testable.
 *
 * `Effects` traits for IO ports
 * The `Effects` trait define the minimum set of IO needed for the orchestration.
 * It allows to have them all gathered in one place, and make it easier to evolve than in several repositories
 * and services while at the same time keeping a stable interface for the pure logic part.
 */

/*
 * Data class that defines what the main orchestration should do with that new even state
 */
sealed trait EventOrchestration
object EventOrchestration {
  // persist the new event state and continue workflow processing
  case class SaveAndQueue(event: CampaignEvent)                                          extends EventOrchestration
  // persist the new event state, then update its state before continuing workflow processing
  case class SaveThenUpdateAndQueue(event: CampaignEvent, nextState: CampaignEventState) extends EventOrchestration
  // do not persist, and continue workflow processing (for ex if event didn't change)
  case class Queue(id: CampaignEventId)                                                  extends EventOrchestration
  // persist the new event state, but stop processing
  case class SaveAndStop(event: CampaignEvent)                                           extends EventOrchestration
  // do nothing
  case object IgnoreAndStop                                                              extends EventOrchestration
}

/*
 * This is the trait that need to be implemented by any specialised campaign handler that is
 * able to deal with a subclass of `Campaign`
 */
trait CampaignHandler {

  // name of the handler
  def name: String

  /*
   * Handle the given event if it can.
   * Return an updated even if handling succeed and event need to be saved and re-queued, or None if
   * processing should be stoped.
   */
  def handle(event: CampaignEvent): PartialFunction[Campaign, IOResult[EventOrchestration]]

  /*
   * Manage cleaning corresponding to deletion of the given event
   */
  def delete(event: CampaignEvent): PartialFunction[Campaign, IOResult[Unit]]
}

/*
 * This class is in charge of the campaign orchestration logic, ie what happens for handling an event:
 * - it retrieve context information for that event
 * - it checks if date ranges are ok and fail or correct if not
 * -
 */
class CampaignOrchestrationLogic(effects: CampaignOrchestrationEffects) {

  /*
   * Handle an event:
   *   - get the context,
   *   - forward to orchestration to do all the checking and workflow changes
   *   - call the post logic effects (persistence, progress, etc)
   *   - manage errors
   *
   * For the error management, we assume that we manage/deal with/log any business error. If we get a ZIO underlying
   * error (fiber or whatever), it needs to be taken care by the workflow engine runtime based on what makes sens for
   * it. (in the queue case, just let it crash)
   */
  def handle(eventId: CampaignEventId, now: DateTime, startDelay: JTDuration, endDelay: JTDuration): UIO[Unit] = {

    effects
      .getEventInfo(eventId)
      .flatMap { (campaign, event) =>
        (
          for {
            _              <- CampaignLogger.debug(
                                s"Start handling campaign event '${event.id.value}' in state ${event.state.value.entryName}"
                              )
            optEventToSave <- orchestrateEventType(campaign, event, now, startDelay, endDelay)
            _              <- effects.saveAndQueueEvent(optEventToSave)
          } yield ()
        ).catchAll { err =>
          /*
           * When we have an error at that level, if we don't skip the event, it will be requeue and we are extremely
           * likely to get the same error, and to loop infinitely, filling the disk with logs at full speed.
           * See: https://issues.rudder.io/issues/22141
           */
          CampaignLogger.error(
            s"An error occurred while treating campaign event ${eventId.value}, skipping that event. Error details : ${err.fullMsg}"
          ) *>
          // no more processing
          effects.saveAndQueueEvents(
            List(
              EventOrchestration
                .SaveAndStop(event.copy(state = Failure(s"An error occurred when processing event", err.fullMsg)))
            )
          )
        }
      }
      // this log is when even the previous level didn't actually take care of the event, for example when the effects for
      // saving the failed state failed. In that case, just log (and stop the event).
      .catchAll { err =>
        CampaignLogger.error(
          s"An error occurred while treating campaign event ${eventId.value}, error details: ${err.fullMsg}"
        )
      }
  }

  /*
   * The main orchestration logic based on the kind of event. It's basically a pattern matching on
   * all event kind, which does:
   *   - some generic checks (for ex. date consistency)
   *   - some generic processing (for ex. hooks)
   *   - forward to plugin services to do their stuff on that event.
   *
   *  Steps can ZIO.sleep: it's the standard way to deal with waiting for the correct time window to happen.
   *
   * WARNING: each case must AT MUST process one step. They MUST NOT loop or whatever. They are just a finite
   * step processor. It's the same for underlying service
   */
  def orchestrateEventType(
      campaign:   Campaign,
      event:      CampaignEvent,
      now:        DateTime,
      startDelay: JTDuration,
      endDelay:   JTDuration
  ): IOResult[EventOrchestration] = {
    event.state.value match {

      case FinishedType | SkippedType | DeletedType | FailureType =>
        serviceHandleEvent(campaign, event) *> effects.createNextScheduledCampaignEvent(campaign, event.end)

      case ScheduledType =>
        campaign.info.status match {
          case Disabled(reason) =>
            val reasonMessage = if (reason.isEmpty) "" else s"Reason is '${reason}''"
            // no more processing
            EventOrchestration
              .SaveAndStop(
                event.copy(state = Skipped(s"Event was cancelled because campaign is disabled. ${reasonMessage}"))
              )
              .succeed

          case Archived(reason, date) =>
            val reasonMessage = if (reason.isEmpty) "" else s"Reason is '${reason}''"
            // no more processing
            EventOrchestration
              .SaveAndStop(
                event.copy(state = Skipped(s"Event was cancelled because campaign is archived. ${reasonMessage}"))
              )
              .succeed

          case Enabled =>
            val effectiveStart = event.start.minus(startDelay)
            if (effectiveStart.isAfter(now)) {
              for {
                _ <-
                  CampaignLogger.debug(
                    s"Scheduled Campaign event ${event.id.value} put to sleep until it should start, on ${DateFormaterService
                        .serialize(effectiveStart)}, ${startDelay.getStandardHours} hour${if (startDelay.getStandardHours > 1) "s"
                      else ""} before official start date, to ensure policies are correctly dispatched, nothing will be applied on the node"
                  )
                _ <- ZIO.sleep(Duration.fromMillis(effectiveStart.getMillis - now.getMillis))
                // re-post processing because anything can happen during the sleep, it will need to
                // be managed as a new event handling
              } yield EventOrchestration.Queue(event.id)
            } else {
              CampaignLogger.debug(
                s"${event.id.value} should be treated by ${event.campaignType.value} handler for Scheduled state"
              ) *>
              serviceHandleEvent(campaign, event)
            }
        }

      case PreHooksType =>
        for {
          res <- effects.runPreHooks(campaign, event)
        } yield {
          // hooks are special: we don't call per-campaign type handler and we need to first save before change state/update
          val nextState = {
            res.results.collectFirst {
              case r if isError(r) =>
                // specify that the next step is error. User can access the "why" by looking at the history of states
                PostHooksInit.modify(_.nextState).setTo(FailureType)
            }.getOrElse(Running)
          }
          EventOrchestration.SaveThenUpdateAndQueue(event.copy(state = PreHooks(res)), nextState)
        }

      case RunningType =>
        val effectiveStart = event.start.minus(startDelay)
        val effectiveEnd   = event.end.plus(endDelay)
        if (effectiveStart.isAfter(now)) {
          for {
            // Campaign should be planned, not running
            _ <-
              CampaignLogger.warn(
                s"Campaign event ${event.id.value} was considered Running but we are before its start date, setting state to Schedule and wait for event to start, on ${DateFormaterService
                    .serialize(effectiveStart)}, ${startDelay.getStandardHours} hour${if (startDelay.getStandardHours > 1) "s"
                  else ""} before official start date, to ensure policies are correctly dispatched, nothing will be applied on the node"
              )
          } yield EventOrchestration.SaveAndQueue(event.copy(state = Scheduled))
        } else if (effectiveEnd.isAfter(now)) {
          for {
            _ <-
              CampaignLogger.debug(
                s"Running Campaign event ${event.id.value} put to sleep until it should end, on ${DateFormaterService
                    .serialize(effectiveEnd)}, ${endDelay.getStandardHours} hour${if (endDelay.getStandardHours > 1) "s" else ""} after official end date, so that we can gather results"
              )
            _ <- ZIO.sleep(Duration.fromMillis(effectiveEnd.getMillis - now.getMillis))
            // reprocess event as a new things, anything can happen during sleep
          } yield EventOrchestration.Queue(event.id)
        } else {
          CampaignLogger.debug(
            s"${event.id.value} should be treated by ${event.campaignType.value} handler for Running state"
          ) *> serviceHandleEvent(campaign, event)
        }

      case PostHooksType =>
        val state = event.state.asInstanceOf[PostHooks]
        for {
          res <- effects.runPostHooks(campaign, event)
        } yield {
          // hooks are special: we don't call per-campaign type handler and we need to first save before change state/update
          val nextState = {
            val optError = res.results.collectFirst { case r if isError(r) => Failure("post-hooks were in error", r.stderr) }
            (optError, state.nextState) match {
              case (None, FailureType)     => Failure("pre-hooks were in error and post-hooks completed successfully", "")
              case (None, s)               => getDefault(s)
              case (Some(f1), FailureType) => Failure("pre-hooks and post-hooks were in error. Last error:", f1.cause)
              case (Some(f1), _)           => f1
            }
          }
          EventOrchestration.SaveThenUpdateAndQueue(event.copy(state = state.modify(_.hookResults).setTo(res)), nextState)
        }
    }
  }

  private def isError(r: HookResult) = HookReturnCode.isError(r.code)

  /*
   * Once the main loop is checked, we find the service-handler and use it.
   * The service handler is the one actually making progress in events.
   * Based on that progress, we stop or re-queue the event for next processing.
   */
  def serviceHandleEvent(campaign: Campaign, event: CampaignEvent): IOResult[EventOrchestration] = {
    /*
     * In the base case, i.e. if there is no underlying able to handle the event, we
     * change the event to failed, else it could lead to infinite looping.
     */
    def base(event: CampaignEvent): PartialFunction[Campaign, IOResult[EventOrchestration]] = {
      case _ =>
        EventOrchestration
          .SaveAndQueue(
            event.copy(state = {
              Failure(
                "missing campaign handler",
                s"Error: no handler is present for campaign of type '${event.campaignType.value}', skipping event '${event.name}' [${event.id.value}]"
              )
            })
          )
          .succeed
    }

    val handle: UIO[PartialFunction[Campaign, IOResult[EventOrchestration]]] = {
      effects.campaignHandlers.get.map(handlers =>
        handlers.map(_.handle(event)).foldLeft(base(event)) { case (base, handler) => handler orElse base }
      )
    }

    for {
      h                <- handle
      postHandlerEvent <- h(campaign)
      _                <- postHandlerEvent match {
                            case EventOrchestration.SaveAndQueue(e)              =>
                              CampaignLogger.debug(
                                s"Campaign event '${e.id.value}' updated. Previous state: '${event.state.value.entryName}', new state: '${e.state.value.entryName}'"
                              )
                            case EventOrchestration.SaveThenUpdateAndQueue(e, s) =>
                              CampaignLogger.debug(
                                s"Campaign event '${e.id.value}' updated. Previous state: '${event.state.value.entryName}', future enqueued state: '${s.value.entryName}'"
                              )
                            case EventOrchestration.Queue(id)                    =>
                              CampaignLogger.debug(s"Campaign event '${id.value}' re-scheduled on queue")
                            case EventOrchestration.SaveAndStop(e)               =>
                              CampaignLogger.debug(
                                s"Campaign event '${e.id.value}' updated and will be saved, but further processing is aborted. Previous state: '${event.state.value.entryName}', new state: '${e.state.value.entryName}'"
                              )
                            case EventOrchestration.IgnoreAndStop                =>
                              CampaignLogger.debug(
                                s"Campaign event '${event.id.value}' processing is stopped: no further processing will be done"
                              )
                          }
    } yield postHandlerEvent
  }

}

object CampaignOrchestrationLogic {

  /*
   * Create a fresh event for a campaign at it initial starting point
   */
  def bootstrapEvent(
      campaign: Campaign,
      uuid:     String,
      start:    DateTime,
      end:      DateTime,
      index:    Int
  ): EventOrchestration.SaveAndQueue = {
    EventOrchestration.SaveAndQueue(
      CampaignEvent(
        CampaignEventId(uuid),
        campaign.info.id,
        s"${campaign.info.name} #${index}",
        Scheduled,
        start,
        end,
        campaign.campaignType
      )
    )
  }
}

/*
 * This trait defines all the effects/IO needed by the orchestrator to be able to do its job.
 */
trait CampaignOrchestrationEffects {

  /*
   * The list of specialized campaign handlers
   */
  def campaignHandlers: Ref[List[CampaignHandler]]

  /*
   * This is the bootstrap of the next iteration of a campaign. It will create the next scheduled and
   * clean existing things from the previous scheduled if any exists.
   * Return the number of scheduled events
   */
  def createNextScheduledCampaignEvent(campaign: Campaign, date: DateTime): IOResult[EventOrchestration]

  /*
   * Retrieve information about an event and its campaign
   */
  def getEventInfo(eventId: CampaignEventId): IOResult[(Campaign, CampaignEvent)]

  /*
   * Run hooks that are configured to run before the campaign time slot is reached.
   * Hooks results are collected to for trace.
   */
  def runPreHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults]

  /*
   * Run hooks that are configured to run before the campaign time slot is reached.
   * Hooks results are collected to for trace.
   */
  def runPostHooks(c: Campaign, e: CampaignEvent): IOResult[HookResults]

  /*
   * IO necessary to persist and advance in the campaign workflow
   */
  def saveAndQueueEvents(events: Seq[EventOrchestration]): IOResult[Unit]

  // utility methods for when there is only one event
  def saveAndQueueEvent(event: EventOrchestration): IOResult[Unit] = {
    saveAndQueueEvents(List(event))
  }

}

/*
 * Default implementation that uses the common bits for campaigns.
 */
class DefaultCampaignOrchestrationEffects(
    override val campaignHandlers: Ref[List[CampaignHandler]],
    val queue:                     Queue[CampaignEventId],
    eventRepository:               CampaignEventRepository,
    campaignRepository:            CampaignRepository,
    hooksService:                  CampaignHooksService,
    uuidGen:                       StringUuidGenerator
) extends CampaignOrchestrationEffects {

  export hooksService.runPostHooks
  // hooks
  export hooksService.runPreHooks

  /*
   * IO related to post-processing of an event
   */
  override def saveAndQueueEvents(events: Seq[EventOrchestration]): IOResult[Unit] = {
    events.accumulate {
      case EventOrchestration.SaveAndQueue(e)              => eventRepository.saveCampaignEvent(e) *> queue.offer(e.id).unit
      case EventOrchestration.SaveThenUpdateAndQueue(e, s) =>
        eventRepository.saveCampaignEvent(e) *>
        eventRepository.saveCampaignEvent(e.copy(state = s)) *>
        queue.offer(e.id).unit
      case EventOrchestration.Queue(id)                    => queue.offer(id).unit
      case EventOrchestration.SaveAndStop(e)               => eventRepository.saveCampaignEvent(e)
      case EventOrchestration.IgnoreAndStop                => ZIO.unit
    }.unit
  }

  /*
   * This is the bootstrap of the next iteration of a campaign. It will create the next scheduled and
   * clean existing things from the previous scheduled if any exists.
   * Return the number of scheduled events.
   * It looks like it should belong to campaign repository for main parts
   */
  override def createNextScheduledCampaignEvent(campaign: Campaign, date: DateTime): IOResult[EventOrchestration] = {

    campaign.info.status match {
      case Enabled =>
        for {
          nbOfEvents   <- eventRepository.numberOfEventsByCampaign(campaign.info.id)
          // check if an event is running which would end after specified date
          events       <- eventRepository.getWithCriteria(
                            RunningType :: Nil,
                            Nil,
                            Some(campaign.info.id),
                            None,
                            None,
                            None,
                            None,
                            None,
                            None
                          )
          lastEventDate = events match {
                            case Nil => date
                            case _   =>
                              val maxEventDate = events.maxBy(_.end).start
                              if (maxEventDate.isAfter(date)) maxEventDate else date

                          }
          dates        <- CampaignDateScheduler.nextCampaignDate(campaign.info.schedule, lastEventDate).toIO
        } yield {
          dates match {
            case Some((start, end)) =>
              CampaignOrchestrationLogic.bootstrapEvent(campaign, uuidGen.newUuid, start, end, nbOfEvents)
            case None               =>
              EventOrchestration.IgnoreAndStop
          }
        }

      case Disabled(_) | Archived(_, _) => EventOrchestration.IgnoreAndStop.succeed
    }
  }

  override def getEventInfo(eventId: CampaignEventId): IOResult[(Campaign, CampaignEvent)] = {
    eventRepository
      .get(eventId)
      .notOptional(
        s"An error occurred while treating campaign event ${eventId.value}, error details : Could not find campaign event details "
      )
      .flatMap { event =>
        campaignRepository
          .get(event.campaignId)
          .notOptional(s"Campaign with id ${event.campaignId.value} not found")
          .tap(_ => CampaignLogger.debug(s"Got Campaign ${event.campaignId.value} for event ${event.id.value}"))
          .map(campaign => (campaign, event))
      }
  }
}
